package com.easy.easyai.rag

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.rag.EasyRagClient.Companion.AUTH_FAILURE_COOLDOWN_MS
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.netty.http.client.HttpClient
import tools.jackson.module.kotlin.readValue
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

/**
 * Default [RagClient] implementation over the EasyRAG REST API using a shared WebClient.
 *
 * Behavior notes:
 * - [RagConfig] is reloaded per request so runtime changes apply without restart.
 * - Disabled integration: reads degrade to empty/null/false; writes raise [RagException]
 *   so callers surface the failure instead of silently losing data.
 * - `upsert` triggers synchronous indexing (write-then-read); HTTP 409 from the index
 *   endpoint means the document is already processed/processing and is treated as success.
 * - JWT login is performed lazily when the server reports `auth_required`, re-login once on 401.
 */
internal class EasyRagClient(
    private val configPath: Path = RagConfig.defaultConfigPath()
) : RagClient {

    private val logger = LoggerFactory.getLogger(EasyRagClient::class.java)
    private val mapper = SharedObjectMapper.instance

    /** Shared WebClient instance — avoids creating a new connection pool per request. */
    private val client: WebClient = WebClient.builder()
        .clientConnector(ReactorClientHttpConnector(HttpClient.create().followRedirect(true)))
        .codecs { it.defaultCodecs().maxInMemorySize(MAX_BODY_SIZE) }
        .build()

    private val cachedToken = AtomicReference<String?>(null)
    private val authInitialized = AtomicBoolean(false)
    /** Epoch-ms of the last auth failure; used to throttle re-login after 429 / network errors. */
    private val lastAuthFailureMs = AtomicLong(0L)
    /** Serializes forced re-logins so concurrent 401s trigger a single login request. */
    private val loginMutex = Mutex()

    override suspend fun isEnabled(): Boolean = RagConfig.load(configPath).enabled

    override suspend fun healthCheck(): Boolean {
        val config = RagConfig.load(configPath)
        // Disabled integration degrades reads to false, matching readByExternalId/search
        // short-circuits — do not probe the server while the integration is off.
        if (!config.enabled) return false
        return try {
            exchange(config, HttpMethod.GET, "/api/health", timeoutMs = config.readTimeoutMs)
            true
        } catch (e: Exception) {
            logger.debug("RAG health check failed for {}: {}", config.baseUrl, e.message)
            false
        }
    }

    override suspend fun upsert(doc: RagDocument, bizId: String?): RagUpsertResult {
        val config = loadEnabledConfig("upsert")
        val body = mapOfNonNull(
            "text" to doc.content,
            "filePath" to doc.filePath,
            "externalId" to doc.externalId,
            "createTime" to doc.createTime,
            "metadata" to doc.metadata.ifEmpty { null },
            "options" to mapOfNonNull(
                "chunkMethod" to doc.options.chunkMethod,
                "chunkTokenSize" to doc.options.chunkTokenSize,
                "chunkOverlap" to doc.options.chunkOverlap,
                "skipKg" to doc.options.skipKg,
                "buildStructure" to doc.options.buildStructure
            ),
            "workspace" to config.workspace,
            "bizId" to bizId
        )
        // Retry on 409: server-side upsert may hit a busy global pipeline (checkPipelineBusy)
        val insertResponse = withConflictRetry("upsert ${doc.externalId}") {
            exchange(config, HttpMethod.POST, "/api/documents/text", body = body, timeoutMs = config.indexTimeoutMs)
        }
        val docId = insertResponse["docId"] as? String
            ?: throw RagException("EasyRAG upsert response missing docId")
        val unchanged = (insertResponse["message"] as? String).orEmpty().contains("unchanged", ignoreCase = true)
        if (unchanged) {
            logger.debug("RAG upsert skipped (content unchanged): {}", doc.externalId)
            return RagUpsertResult(docId = docId, indexed = true, unchanged = true)
        }

        // Synchronous indexing so the document is searchable immediately after write
        val indexResponse = try {
            exchange(
                config, HttpMethod.POST, "/api/documents/$docId/index",
                params = mapOf("workspace" to config.workspace, "bizId" to bizId),
                timeoutMs = config.indexTimeoutMs
            )
        } catch (e: RagException) {
            if (e.statusCode == CONFLICT) {
                // Already processed or being processed by the global pipeline — fine
                logger.debug("RAG index 409 for {}, treating as already indexed", docId)
                null
            } else {
                throw e
            }
        }
        val chunksCount = (indexResponse?.get("chunks_count") as? Number)?.toInt()
        logger.debug("RAG upsert completed: externalId={}, docId={}, chunks={}", doc.externalId, docId, chunksCount)
        return RagUpsertResult(docId = docId, indexed = true, chunksCount = chunksCount)
    }

    override suspend fun delete(externalId: String, bizId: String?) {
        val config = loadEnabledConfig("delete")
        val detail = readByExternalId(config, externalId, bizId) ?: run {
            logger.debug("RAG delete: no document for externalId={}, nothing to do", externalId)
            return
        }
        withConflictRetry("delete $externalId") {
            exchange(
                config, HttpMethod.DELETE, "/api/documents/${detail.docId}",
                params = mapOf("workspace" to config.workspace, "bizId" to bizId),
                timeoutMs = config.indexTimeoutMs
            )
        }
        logger.debug("RAG deleted document: externalId={}, docId={}", externalId, detail.docId)
    }

    override suspend fun batchDelete(docIds: List<String>, bizId: String?): Int {
        if (docIds.isEmpty()) return 0
        val config = loadEnabledConfig("batchDelete")
        val body = mapOfNonNull("docIds" to docIds, "workspace" to config.workspace, "bizId" to bizId)
        val response = withConflictRetry("batchDelete") {
            exchange(config, HttpMethod.POST, "/api/documents/batch_delete", body = body, timeoutMs = config.indexTimeoutMs)
        }
        val deleted = (response["deleted"] as? Number)?.toInt() ?: 0
        val errors = response["errors"] as? List<*>
        if (!errors.isNullOrEmpty()) {
            logger.warn("RAG batchDelete reported {} errors: {}", errors.size, errors)
        }
        return deleted
    }

    override suspend fun readByExternalId(externalId: String, bizId: String?): RagDocumentDetail? {
        val config = RagConfig.load(configPath)
        if (!config.enabled) return null
        return readByExternalId(config, externalId, bizId)
    }

    override suspend fun list(category: RagCategory, pathPrefix: String, bizId: String?): List<RagDocInfo> {
        val config = RagConfig.load(configPath)
        if (!config.enabled) return emptyList()
        val result = mutableListOf<RagDocInfo>()
        var page = 1
        while (true) {
            val response = exchange(
                config, HttpMethod.GET, "/api/documents/list",
                params = mapOf(
                    "page" to page.toString(),
                    "pageSize" to LIST_PAGE_SIZE.toString(),
                    "filePathPrefix" to pathPrefix,
                    "workspace" to config.workspace,
                    "bizId" to bizId
                ),
                timeoutMs = config.readTimeoutMs
            )
            val documents = (response["documents"] as? List<*>)
                ?.filterIsInstance<Map<String, Any?>>()
                ?: emptyList()
            if (documents.isEmpty()) break
            for (item in documents) {
                result.add(parseDocInfo(item))
            }
            val total = (response["total"] as? Number)?.toInt() ?: result.size
            if (result.size >= total || documents.size < LIST_PAGE_SIZE) break
            page++
        }
        return result
    }

    override suspend fun search(
        query: String,
        category: RagCategory,
        filters: Map<String, String>,
        topK: Int,
        timeRangeStart: Long?,
        timeRangeEnd: Long?,
        bizId: String?
    ): List<RagChunk> {
        val config = RagConfig.load(configPath)
        if (!config.enabled) return emptyList()
        val metadataFilters = filters + ("category" to category.code)
        val body = mapOfNonNull(
            "query" to query,
            "mode" to "naive",
            "chunkTopK" to topK,
            "timeRangeStart" to timeRangeStart,
            "timeRangeEnd" to timeRangeEnd,
            "metadataFilters" to metadataFilters,
            "workspace" to config.workspace,
            "bizId" to bizId
        )
        val response = exchange(config, HttpMethod.POST, "/api/query/data", body = body, timeoutMs = config.readTimeoutMs)
        val chunks = (response["chunks"] as? List<*>)
            ?.filterIsInstance<Map<String, Any?>>()
            ?: return emptyList()
        return chunks.map { chunk ->
            RagChunk(
                content = chunk["content"] as? String ?: "",
                filePath = chunk["file_path"] as? String,
                score = (chunk["score"] as? Number)?.toDouble(),
                createTime = (chunk["create_time"] as? Number)?.toLong(),
                metadata = (chunk["metadata"] as? Map<*, *>)
                    ?.entries?.associate { (k, v) -> k.toString() to v }
                    ?: emptyMap()
            )
        }
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private suspend fun loadEnabledConfig(operation: String): RagConfig {
        val config = RagConfig.load(configPath)
        if (!config.enabled) {
            throw RagException("RAG integration is disabled; cannot perform $operation")
        }
        return config
    }

    private suspend fun readByExternalId(config: RagConfig, externalId: String, bizId: String?): RagDocumentDetail? {
        val node = try {
            exchange(
                config, HttpMethod.GET, "/api/documents/by-external-id",
                params = mapOf(
                    "externalId" to externalId,
                    "workspace" to config.workspace,
                    "bizId" to bizId
                ),
                timeoutMs = config.readTimeoutMs
            )
        } catch (e: RagException) {
            if (e.statusCode == NOT_FOUND) return null else throw e
        }
        return RagDocumentDetail(
            docId = node["doc_id"] as? String ?: return null,
            externalId = node["external_id"] as? String,
            filePath = node["file_path"] as? String,
            content = node["content"] as? String,
            status = node["status"] as? String,
            createTime = (node["create_time"] as? Number)?.toLong(),
            chunksCount = (node["chunks_count"] as? Number)?.toInt()
        )
    }

    private fun parseDocInfo(node: Map<String, Any?>): RagDocInfo = RagDocInfo(
        docId = node["id"] as? String ?: "",
        filePath = node["filePath"] as? String ?: "",
        status = node["status"] as? String,
        externalId = node["externalId"] as? String,
        contentSummary = node["contentSummary"] as? String,
        contentLength = (node["contentLength"] as? Number)?.toInt(),
        chunksCount = (node["chunksCount"] as? Number)?.toInt(),
        createdAt = parseInstantToEpoch(node["createdAt"]),
        updatedAt = parseInstantToEpoch(node["updatedAt"])
    )

    private fun parseInstantToEpoch(value: Any?): Long? {
        if (value is Number) return value.toLong()
        val text = value as? String ?: return null
        return try {
            Instant.parse(text).epochSecond
        } catch (_: Exception) {
            null
        }
    }

    /** Retry a mutating call on HTTP 409 (busy global pipeline) with exponential backoff. */
    private suspend fun withConflictRetry(description: String, block: suspend () -> Map<String, Any?>): Map<String, Any?> {
        var attempt = 0
        var backoffMs = UPSERT_RETRY_BACKOFF_MS
        while (true) {
            try {
                return block()
            } catch (e: RagException) {
                if (e.statusCode != CONFLICT || attempt >= UPSERT_RETRY_MAX) throw e
                attempt++
                logger.warn("RAG {} hit 409 (pipeline busy), retry {} of {} after {}ms", description, attempt, UPSERT_RETRY_MAX, backoffMs)
                delay(backoffMs.milliseconds)
                backoffMs *= 2
            }
        }
    }

    private suspend fun exchange(
        config: RagConfig,
        method: HttpMethod,
        path: String,
        params: Map<String, String?> = emptyMap(),
        body: Map<String, Any?>? = null,
        timeoutMs: Long
    ): Map<String, Any?> {
        ensureAuthInitialized(config)
        return executeOnce(config, method, path, params, body, timeoutMs, retryOn401 = true)
    }

    private suspend fun executeOnce(
        config: RagConfig,
        method: HttpMethod,
        path: String,
        params: Map<String, String?>,
        body: Map<String, Any?>?,
        timeoutMs: Long,
        retryOn401: Boolean
    ): Map<String, Any?> {
        val uri = buildUri(config, path, params)
        val uriSpec = client.method(method).uri(uri)
        // Capture the token used for this request so the 401 handler can detect
        // whether another coroutine has already refreshed it.
        val usedToken = cachedToken.get()
        usedToken?.let { token -> uriSpec.headers { headers -> headers.setBearerAuth(token) } }
        val finalSpec: WebClient.RequestHeadersSpec<*> = if (body != null) uriSpec.bodyValue(body) else uriSpec
        val raw = try {
            finalSpec.retrieve()
                .bodyToMono<String>()
                .timeout(Duration.ofMillis(timeoutMs))
                .awaitSingleOrNull()
        } catch (e: WebClientResponseException) {
            if (e.statusCode.value() == UNAUTHORIZED && retryOn401) {
                val token = loginMutex.withLock {
                    val cached = cachedToken.get()
                    // If the cached token differs from the one that just failed,
                    // another coroutine already re-logged in — reuse it. Otherwise
                    // the cached value is the same stale token (or null), so force
                    // a fresh login.
                    if (cached != null && cached != usedToken) cached
                    else login(config, force = true)
                }
                if (token != null) {
                    logger.debug("RAG 401 on {} {}, re-logged in and retrying", method, path)
                    // Give the server time to recover from rate limiting before retrying;
                    // an immediate retry typically lands inside the same 429 window.
                    delay(RETRY_AFTER_401_DELAY_MS.milliseconds)
                    return executeOnce(config, method, path, params, body, timeoutMs, retryOn401 = false)
                }
            }
            throw RagException(
                "EasyRAG ${method.name()} $path failed: HTTP ${e.statusCode.value()} ${e.statusText}",
                statusCode = e.statusCode.value(),
                cause = e
            )
        } catch (e: Exception) {
            throw RagException("EasyRAG ${method.name()} $path failed: ${e.message}", cause = e)
        }
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            mapper.readValue<Map<String, Any?>>(raw)
        } catch (e: Exception) {
            throw RagException("EasyRAG ${method.name()} $path returned unparsable response", cause = e)
        }
    }

    private fun buildUri(config: RagConfig, path: String, params: Map<String, String?>): URI {
        val base = config.baseUrl.trimEnd('/')
        val query = params.entries
            .filter { it.value != null }
            .joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
            }
        return URI.create(if (query.isEmpty()) "$base$path" else "$base$path?$query")
    }

    /**
     * Lazily probe auth-status and login when the server requires authentication.
     *
     * Serializes the full probe + login under [loginMutex] so that [authInitialized]
     * is only set to `true` **after** login succeeds and the token is cached.
     * This prevents a race where concurrent callers see `authInitialized = true`
     * but `cachedToken` is still null, causing requests to go out without an
     * Authorization header.
     *
     * On failure the flag stays `false` so the next request can retry, but a
     * [AUTH_FAILURE_COOLDOWN_MS] cooldown prevents hammering the server.
     */
    private suspend fun ensureAuthInitialized(config: RagConfig) {
        if (authInitialized.get()) return
        // Throttle retries after a recent failure to avoid amplifying 429 storms.
        val now = System.currentTimeMillis()
        val lastFailure = lastAuthFailureMs.get()
        if (lastFailure > 0 && now - lastFailure < AUTH_FAILURE_COOLDOWN_MS) {
            // Cooldown: skip the full probe, but still attempt a direct (mutex-guarded)
            // login when no token is cached — otherwise every concurrent request
            // proceeds unauthenticated and cascades into 401s. A failure here is
            // non-fatal: the 401 retry path in executeOnce remains as fallback.
            if (cachedToken.get() == null) {
                loginMutex.withLock {
                    cachedToken.get() ?: login(config, force = true)
                }
            }
            return
        }
        // Serialize the full probe under loginMutex so concurrent callers either:
        // (a) wait on the mutex, then see authInitialized=true and return, or
        // (b) enter as the designated initializer and complete the setup.
        //
        // CRITICAL: authInitialized is set to true ONLY after login succeeds and
        // the token is cached — never before. Otherwise concurrent callers see
        // authInitialized=true but cachedToken=null and send requests without auth.
        loginMutex.withLock {
            if (authInitialized.get()) return
            try {
                val status = executeOnce(config, HttpMethod.GET, "/api/auth-status", emptyMap(), null, config.readTimeoutMs, retryOn401 = false)
                val authRequired = status["auth_required"] as? Boolean ?: false
                if (authRequired) {
                    val token = login(config)  // loginMutex already held
                    if (token != null) {
                        authInitialized.set(true)  // ← set AFTER login + token cached
                        return
                    }
                } else {
                    authInitialized.set(true)  // no auth needed
                    return
                }
            } catch (e: Exception) {
                logger.warn("RAG auth probe failed for {}: {}", config.baseUrl, e.message)
            }
            // Auth failed — leave authInitialized=false so next request retries
            lastAuthFailureMs.set(System.currentTimeMillis())
        }
    }

    /** Logs in against /api/auth/login and caches the bearer token; returns the token or null. */
    private suspend fun login(config: RagConfig, force: Boolean = false): String? {
        // Reuse a cached token unless the caller needs a fresh one (401 retry must
        // refresh an expired token instead of returning the stale cached value).
        if (!force) cachedToken.get()?.let { return it }
        val username = config.username
        val password = config.password
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            logger.debug("RAG auth required but no credentials configured for {}", config.baseUrl)
            return null
        }
        return try {
            val response = executeOnce(
                config, HttpMethod.POST, "/api/auth/login",
                params = emptyMap(),
                body = mapOf("username" to username, "password" to password),
                timeoutMs = config.readTimeoutMs,
                retryOn401 = false
            )
            val token = firstTextual(response)
            if (token == null) {
                logger.warn("RAG login response contained no token for {}", config.baseUrl)
            } else {
                cachedToken.set(token)
                logger.info("RAG login succeeded for user {} at {}", username, config.baseUrl)
            }
            token
        } catch (e: Exception) {
            logger.warn("RAG login failed for {} at {}: {}", username, config.baseUrl, e.message)
            null
        }
    }

    private fun firstTextual(node: Map<String, Any?>,
                             vararg fieldNames: String = arrayOf("token", "accessToken", "access_token")): String? {
        for (field in fieldNames) {
            val value = node[field]
            if (value is String) return value
        }
        return null
    }

    private fun mapOfNonNull(vararg pairs: Pair<String, Any?>): Map<String, Any> =
        pairs.filter { it.second != null }.associate { it.first to it.second!! }

    private companion object {
        const val MAX_BODY_SIZE = 16 * 1024 * 1024
        const val LIST_PAGE_SIZE = 100
        const val UPSERT_RETRY_MAX = 2
        const val UPSERT_RETRY_BACKOFF_MS = 500L
        const val NOT_FOUND = 404
        const val UNAUTHORIZED = 401
        const val CONFLICT = 409
        /** Delay (ms) before retrying a request after a 401-triggered re-login. */
        const val RETRY_AFTER_401_DELAY_MS = 500L
        /** Cooldown (ms) after an auth failure before retrying — avoids 429 amplification. */
        const val AUTH_FAILURE_COOLDOWN_MS = 5_000L
    }
}
