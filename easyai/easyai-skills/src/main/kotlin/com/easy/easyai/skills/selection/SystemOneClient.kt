package com.easy.easyai.skills.selection

import com.easy.easyai.common.util.SharedObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.netty.http.client.HttpClient
import tools.jackson.module.kotlin.readValue
import java.net.URI
import java.time.Duration

/**
 * One System One decision call: endpoint material comes from the user's `SKILL_SELECTION` aux
 * model row, the question is a single `choice` over [criteria] (skill name -> description).
 */
data class SystemOneSelectionRequest(
    val baseUrl: String,
    val apiKey: String,
    val modelId: String,
    val query: String,
    val recent: String? = null,
    val instructions: String,
    val criteria: Map<String, String>,
    val timeoutMs: Long
)

/** The chosen label and its confidence; a missing confidence degrades to 0 (below any threshold). */
data class SystemOneDecision(
    val choice: String?,
    val confidence: Double?
)

/**
 * Structured-decision client for skill routing. Deliberately not a Spring AI `ChatModel`:
 * the decision endpoint speaks `state`/`questions` JSON, not messages. Every transport or
 * parse failure degrades to null so the caller keeps its baseline skill visibility.
 */
interface SkillSelectionClient {
    suspend fun decide(request: SystemOneSelectionRequest): SystemOneDecision?

    companion object {
        /** The HTTP-backed decision client; kept as a factory so the implementation stays internal. */
        @JvmStatic
        fun http(): SkillSelectionClient = HttpSkillSelectionClient()
    }
}

/**
 * WebClient implementation against `POST https://{host}/compatible-mode/v1/systemone`.
 *
 * The endpoint is derived from the configured row's baseUrl by keeping only scheme+host+port:
 * stored baseUrls vary by protocol (`.../apps/anthropic`, `.../compatible-mode`, bare host)
 * while System One is always served under the compatible-mode path on the same gateway host.
 */
internal class HttpSkillSelectionClient : SkillSelectionClient {

    private val logger = LoggerFactory.getLogger(HttpSkillSelectionClient::class.java)
    private val mapper = SharedObjectMapper.instance

    /** Shared WebClient instance — avoids creating a new connection pool per decision call. */
    private val client: WebClient = WebClient.builder()
        .clientConnector(ReactorClientHttpConnector(HttpClient.create()))
        .codecs { it.defaultCodecs().maxInMemorySize(MAX_BODY_SIZE) }
        .build()

    override suspend fun decide(request: SystemOneSelectionRequest): SystemOneDecision? {
        val state = buildMap<String, Any?> {
            put("query", request.query)
            request.recent?.let { put("recent", it) }
        }
        val body = mapOf(
            "model" to request.modelId,
            "state" to state,
            "questions" to mapOf(
                QUESTION_KEY to mapOf(
                    "type" to "choice",
                    "instructions" to request.instructions,
                    "criteria" to request.criteria
                )
            )
        )
        val uri = systemOneEndpoint(request.baseUrl) ?: run {
            logger.warn("Skill selection skipped: unusable baseUrl {}", request.baseUrl)
            return null
        }
        val raw = try {
            client.post()
                .uri(uri)
                .headers { headers -> headers.setBearerAuth(request.apiKey) }
                .bodyValue(body)
                .retrieve()
                .bodyToMono<String>()
                .timeout(Duration.ofMillis(request.timeoutMs))
                .awaitSingleOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never quote the response or the request here: the body carries no secret but the
            // underlying client exception messages can echo the Authorization header.
            logger.warn("Skill selection call failed: {}: {}", e.javaClass.simpleName, e.message?.take(MAX_ERROR_MESSAGE))
            return null
        }
        if (raw.isNullOrBlank()) return null
        return try {
            val parsed = mapper.readValue<Map<String, Any?>>(raw)
            val answer = (parsed["answers"] as? Map<*, *>)?.get(QUESTION_KEY) as? Map<*, *>
            if (answer == null) {
                logger.warn("Skill selection response has no answer for '{}'", QUESTION_KEY)
                return null
            }
            val choice = (answer["choice"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            SystemOneDecision(choice, (answer["confidence"] as? Number)?.toDouble())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Skill selection response unparsable: {}", e.message?.take(MAX_ERROR_MESSAGE))
            null
        }
    }

    companion object {
        private const val QUESTION_KEY = "skill"
        private const val SYSTEM_ONE_PATH = "/compatible-mode/v1/systemone"
        private const val MAX_BODY_SIZE = 1 * 1024 * 1024
        private const val MAX_ERROR_MESSAGE = 200

        /**
         * Reduce any configured gateway URL to its origin and append the System One path;
         * returns null when the string is not an http(s) URL with a host.
         */
        @JvmStatic
        fun systemOneEndpoint(baseUrl: String): String? {
            val uri = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host ?: return null
            val port = if (uri.port > 0) ":${uri.port}" else ""
            return "$scheme://$host$port$SYSTEM_ONE_PATH"
        }
    }
}
