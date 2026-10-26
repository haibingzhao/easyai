package com.easy.easyai.web.service

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.llm.*
import com.easy.easyai.core.knowledge.KnowledgeAccessContext
import com.easy.easyai.core.knowledge.KnowledgeAccessPolicy
import com.easy.easyai.core.knowledge.KnowledgeArticle
import com.easy.easyai.core.knowledge.KnowledgeArticleStore
import com.easy.easyai.core.knowledge.KnowledgeOwnership
import com.easy.easyai.core.knowledge.KnowledgeStore
import com.easy.easyai.core.knowledge.KnowledgeUploadItem
import com.easy.easyai.core.knowledge.UploadResult
import com.easy.easyai.core.storage.ObjectStorageResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.sql.SQLException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** An article plus the caller-specific actions rendered by the console. */
data class AccessibleKnowledgeArticle(
    val article: KnowledgeArticle,
    val canWrite: Boolean,
    val canManage: Boolean,
)

@Service
class KnowledgeArticleService(
    @param:Autowired(required = false) private val objectStorageResolver: ObjectStorageResolver?,
    @param:Autowired(required = false) private val knowledgeStore: KnowledgeStore?,
    private val articleStore: KnowledgeArticleStore,
    private val accessPolicy: KnowledgeAccessPolicy,
    private val configStore: ModelProviderConfigStore,
    private val modelFactories: List<ChatModelFactory>,
    @param:Value("\${easyai.knowledge.shared-within-group:false}") private val sharedWithinGroup: Boolean = false
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val chatModelCache = ConcurrentHashMap<com.easy.easyai.api.model.ModelProviderConfig, ChatModel>()

    // ── Access helpers ────────────────────────────────────────────────

    suspend fun canView(context: KnowledgeAccessContext): Boolean = accessPolicy.canView(context)

    suspend fun canCreate(context: KnowledgeAccessContext): Boolean = accessPolicy.canCreate(context)

    suspend fun canWrite(context: KnowledgeAccessContext, article: KnowledgeArticle): Boolean =
        accessPolicy.canWrite(context, article)

    suspend fun canManage(context: KnowledgeAccessContext, article: KnowledgeArticle): Boolean =
        accessPolicy.canManage(context, article)

    // ── CRUD ──────────────────────────────────────────────────────────

    suspend fun saveArticle(
        context: KnowledgeAccessContext,
        title: String,
        content: String,
        source: String,
        relativePath: String? = null,
        category: String,
        origin: String,
        originSessionId: String? = null,
        originMessageId: String? = null,
        composedFrom: String? = null,
    ): KnowledgeArticle {
        val ownerId = writeOwner(context)
        KnowledgePathValidator.validateSource(source)
        val path = relativePath?.takeIf { it.isNotBlank() } ?: generateRelativePath(title)
        KnowledgePathValidator.validateRelativePath(path)
        val ext = path.substringAfterLast('.', "md").lowercase()
        val knowledgeKey = "$source/$path"
        val ossKey = buildOssKey(ownerId, source, path)
        val now = System.currentTimeMillis()

        val existing = articleStore.findByKey(ownerId, knowledgeKey)
        if (existing != null) {
            if (!accessPolicy.canWrite(context, existing)) {
                throw ResponseStatusException(HttpStatus.FORBIDDEN, "No write permission on article: $knowledgeKey")
            }
            if (origin != "upload") {
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "An article with the same title already exists: $knowledgeKey"
                )
            }
        }

        // Re-uploading keeps the original creator stamp; legacy rows stay owner-less (treated as
        // bucket rows by the policy) instead of being silently claimed by the uploader.
        val article = if (existing != null) {
            existing.copy(
                ossKey = existing.ossKey.ifBlank { ossKey },
                title = title,
                description = content.take(200),
                category = category,
                ext = ext,
                contentHash = sha256(content),
                origin = origin,
                originSessionId = originSessionId,
                originMessageId = originMessageId,
                composedFrom = composedFrom,
                updatedAt = now
            )
        } else {
            if (!accessPolicy.canCreate(context)) {
                throw ResponseStatusException(HttpStatus.FORBIDDEN, "No permission to create knowledge articles")
            }
            KnowledgeArticle(
                id = UUID.randomUUID().toString(),
                userId = ownerId,
                createdBy = context.userId,
                knowledgeKey = knowledgeKey,
                ossKey = ossKey,
                title = title,
                description = content.take(200),
                category = category,
                source = source,
                relativePath = path,
                ext = ext,
                contentHash = sha256(content),
                origin = origin,
                originSessionId = originSessionId,
                originMessageId = originMessageId,
                composedFrom = composedFrom,
                createdAt = now,
                updatedAt = now
            )
        }

        val created = existing == null
        try {
            articleStore.save(article)
        } catch (e: Exception) {
            if (created) {
                withContext(NonCancellable) {
                    runCatching { articleStore.delete(article.id) }
                }
                // check-then-insert is racy; the unique index is the source of truth.
                if (isUniqueViolation(e)) {
                    throw ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "An article with the same title already exists: $knowledgeKey"
                    )
                }
            }
            throw e
        }

        try {
            writeToOss(article.userId, article.ossKey, content.toByteArray(Charsets.UTF_8), "text/markdown")
        } catch (e: Exception) {
            if (created) {
                withContext(NonCancellable) {
                    runCatching { articleStore.delete(article.id) }
                }
            }
            throw e
        }

        knowledgeStore?.update(article.userId, knowledgeKey, content, title, category)

        logger.info("Saved knowledge article: key={}, createdBy={}, origin={}", knowledgeKey, article.createdBy, origin)
        return article
    }

    suspend fun listAccessible(
        context: KnowledgeAccessContext,
        source: String? = null,
        category: String? = null,
    ): List<AccessibleKnowledgeArticle> {
        if (!accessPolicy.canView(context)) return emptyList()
        val seen = mutableSetOf<String>()
        val result = mutableListOf<AccessibleKnowledgeArticle>()
        // Self first, then the group bucket: a personal row shadows a group row with the same key.
        for (bucket in visibleBuckets(context)) {
            val articles = articleStore.findAllInBuckets(listOf(bucket), source, category).toList()
            for (article in articles) {
                if (!seen.add(article.knowledgeKey)) continue
                if (!accessPolicy.canRead(context, article)) continue
                result += AccessibleKnowledgeArticle(
                    article = article,
                    canWrite = accessPolicy.canWrite(context, article),
                    canManage = accessPolicy.canManage(context, article),
                )
            }
        }
        return result.sortedByDescending { it.article.updatedAt }
    }

    suspend fun readContent(context: KnowledgeAccessContext, key: String): String? {
        val article = resolveArticle(context, key) ?: return null
        if (!accessPolicy.canRead(context, article)) return null
        return readArticleContent(article)
    }

    suspend fun updateArticle(
        context: KnowledgeAccessContext,
        key: String,
        content: String,
        title: String? = null,
        category: String? = null,
    ): KnowledgeArticle {
        val existing = resolveArticle(context, key)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Article not found: $key")
        if (!accessPolicy.canWrite(context, existing)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "No write permission on article: $key")
        }

        // Legacy rows may carry an empty oss_key (column default ''); rebuild it like the create
        // path does instead of writing the body to an empty object key.
        val ossKey = existing.ossKey.ifBlank {
            buildOssKey(existing.userId, existing.source, existing.relativePath)
        }
        // Content first: a storage failure must leave the metadata (contentHash) untouched.
        writeToOss(existing.userId, ossKey, content.toByteArray(Charsets.UTF_8), "text/markdown")

        val updated = existing.copy(
            title = title ?: existing.title,
            category = category ?: existing.category,
            contentHash = sha256(content),
            ossKey = ossKey,
            updatedAt = System.currentTimeMillis()
        )
        articleStore.save(updated)
        knowledgeStore?.update(existing.userId, existing.knowledgeKey, content, updated.title, updated.category)

        logger.info("Updated knowledge article: key={}", key)
        return updated
    }

    suspend fun deleteArticle(context: KnowledgeAccessContext, key: String): Boolean {
        val article = resolveArticle(context, key) ?: return false
        if (!accessPolicy.canManage(context, article)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "No permission to delete article: $key")
        }

        objectStorageResolver?.resolve(article.userId)?.delete(article.ossKey)
        articleStore.delete(article.id)
        knowledgeStore?.delete(article.userId, article.knowledgeKey)

        logger.info("Deleted knowledge article: key={}", key)
        return true
    }

    /**
     * Authorize, index and save a batch of uploaded files.
     *
     * Every file is authorized before the RAG write (an overwrite is checked against the existing
     * article), denied files are reported per item, and a failed metadata write rolls back the RAG
     * entry only when the file did not exist before.
     */
    suspend fun uploadItems(
        context: KnowledgeAccessContext,
        source: String,
        category: String?,
        items: List<KnowledgeUploadItem>,
    ): List<UploadResult> {
        val store = knowledgeStore ?: throw ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Knowledge system is not enabled"
        )
        KnowledgePathValidator.validateSource(source)
        items.forEach { KnowledgePathValidator.validateRelativePath(it.relativePath) }
        if (items.isEmpty()) return emptyList()

        val ownerId = writeOwner(context)
        val keyOf = { relativePath: String -> "$source/$relativePath" }
        val existingByKey = articleStore.findByKeys(ownerId, items.map { keyOf(it.relativePath) })
            .associateBy { it.knowledgeKey }
        val canCreate = items.any { keyOf(it.relativePath) !in existingByKey } &&
            accessPolicy.canCreate(context)

        val denied = mutableMapOf<String, String>()
        val allowed = mutableListOf<KnowledgeUploadItem>()
        for (item in items) {
            val existing = existingByKey[keyOf(item.relativePath)]
            val permitted = if (existing != null) {
                accessPolicy.canWrite(context, existing)
            } else {
                canCreate
            }
            if (permitted) {
                allowed += item
            } else {
                denied[item.relativePath] = UPLOAD_DENIED_REASON
            }
        }
        if (allowed.isEmpty()) {
            return items.map { UploadResult(it.relativePath, false, null, denied[it.relativePath]) }
        }

        val results = store.uploadBatch(ownerId, source, allowed, category)
        val failedMetadata = mutableSetOf<String>()
        for ((item, result) in allowed.zip(results)) {
            if (!result.success) continue
            try {
                saveArticle(
                    context = context,
                    title = titleFor(item),
                    content = item.content,
                    source = source,
                    relativePath = item.relativePath,
                    category = category ?: "other",
                    origin = "upload"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Failed to save article metadata for {}: {}", item.relativePath, e.message)
                failedMetadata += item.relativePath
                // Only roll back a file that did not exist before; overwriting an existing article
                // must not delete its already-indexed content.
                if (keyOf(item.relativePath) !in existingByKey) {
                    withContext(NonCancellable) {
                        runCatching { store.delete(ownerId, keyOf(item.relativePath)) }
                    }
                }
            }
        }

        val byPath = results.associateBy { it.relativePath }
        return items.map { item ->
            val denial = denied[item.relativePath]
            if (denial != null) return@map UploadResult(item.relativePath, false, null, denial)
            val result = byPath[item.relativePath]
                ?: return@map UploadResult(item.relativePath, false, null, UPLOAD_NOT_ATTEMPTED_REASON)
            if (item.relativePath in failedMetadata) {
                UploadResult(item.relativePath, false, result.key, UPLOAD_METADATA_FAILED_REASON)
            } else {
                result
            }
        }
    }

    // ── AI Operations ─────────────────────────────────────────────────

    suspend fun aiRewrite(
        context: KnowledgeAccessContext,
        key: String,
        content: String? = null,
        instruction: String,
        modelConfigId: String,
        owners: Collection<String>
    ): String {
        val article = resolveArticle(context, key)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Article content not found: $key")
        if (!accessPolicy.canWrite(context, article)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "No write permission on article: $key")
        }
        val articleContent = content?.takeIf { it.isNotBlank() } ?: readArticleContent(article)

        val systemPrompt = """
You are a professional writing assistant. The user will provide an article and an instruction.
Rewrite the entire article according to the instruction.
Return ONLY the rewritten article in Markdown format, without any explanation or preamble.
Preserve the original structure (headings, lists, code blocks) unless the instruction explicitly asks to change it.
""".trimIndent()

        val userPrompt = """
## Original Article

$articleContent

## Instruction

$instruction
""".trimIndent()

        return callLlm(modelConfigId, systemPrompt, userPrompt, owners)
    }

    suspend fun aiModifySelection(
        context: KnowledgeAccessContext,
        key: String,
        content: String? = null,
        selectedText: String,
        instruction: String,
        modelConfigId: String,
        owners: Collection<String>
    ): String {
        val article = resolveArticle(context, key)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Article content not found: $key")
        if (!accessPolicy.canWrite(context, article)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "No write permission on article: $key")
        }
        val articleContent = content?.takeIf { it.isNotBlank() } ?: readArticleContent(article)

        val systemPrompt = """
You are a professional writing assistant. The user will provide an article, a selected portion of it, and an instruction.
Modify ONLY the selected portion according to the instruction.
Return ONLY the modified portion text in Markdown format, without any explanation or preamble.
The output will replace the selected portion in the original article.
""".trimIndent()

        val userPrompt = """
## Original Article

$articleContent

## Selected Text

$selectedText

## Instruction

$instruction
""".trimIndent()

        return callLlm(modelConfigId, systemPrompt, userPrompt, owners)
    }

    suspend fun aiCompose(
        context: KnowledgeAccessContext,
        sourceKeys: List<String>,
        guidance: String,
        style: String,
        modelConfigId: String,
        owners: Collection<String>
    ): String {
        val materials = sourceKeys.mapNotNull { key ->
            val article = resolveArticle(context, key) ?: return@mapNotNull null
            if (!accessPolicy.canRead(context, article)) return@mapNotNull null
            "### Material: ${article.title}\n\n${readArticleContent(article)}"
        }

        if (materials.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No readable materials found")
        }

        val systemPrompt = """
You are a professional writer. The user will provide several source materials and guidance on how to compose them into a coherent article.
Write a well-structured article in Markdown format that organizes and synthesizes the source materials.
Follow the user's guidance and style preferences.
Return ONLY the composed article, without any explanation or preamble.
""".trimIndent()

        val styleHint = if (style.isNotBlank()) "\nStyle: $style" else ""
        val userPrompt = """
## Source Materials

${materials.joinToString("\n\n---\n\n")}

## Guidance

$guidance$styleHint
""".trimIndent()

        return callLlm(modelConfigId, systemPrompt, userPrompt, owners)
    }

    // ── Internal helpers ──────────────────────────────────────────────

    private fun writeOwner(context: KnowledgeAccessContext): String =
        KnowledgeOwnership.ownerId(context.userId, context.groupUserId, sharedWithinGroup)

    private fun visibleBuckets(context: KnowledgeAccessContext): List<String> =
        listOfNotNull(context.userId.takeIf { it.isNotBlank() }, context.groupUserId?.takeIf { it.isNotBlank() })
            .distinct()

    /** Self bucket first, then the group bucket; a caller-owned row shadows a same-key group row. */
    private suspend fun resolveArticle(context: KnowledgeAccessContext, key: String): KnowledgeArticle? {
        KnowledgePathValidator.validateRelativePath(key)
        for (bucket in visibleBuckets(context)) {
            articleStore.findByKey(bucket, key)?.let { return it }
        }
        return null
    }

    private fun generateRelativePath(title: String): String {
        val slug = title.lowercase()
            .replace(Regex("[^a-z0-9\\u4e00-\\u9fff]+"), "-")
            .trim('-')
            .ifBlank { "article" }
        return "$slug-${UUID.randomUUID().toString().take(8)}.md"
    }

    private fun titleFor(item: KnowledgeUploadItem): String =
        item.content.lines().firstOrNull { it.startsWith("#") }
            ?.trimStart('#')?.trim()
            ?: item.relativePath.substringAfterLast('/').substringBeforeLast('.')

    /** R2DBC drivers wrap the vendor error differently, so walk the whole cause chain. */
    private fun isUniqueViolation(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is SQLException && current.sqlState == UNIQUE_VIOLATION_SQLSTATE) return true
            val message = current.message.orEmpty().lowercase()
            if (message.contains(UNIQUE_VIOLATION_SQLSTATE) ||
                message.contains("unique index") ||
                message.contains("unique constraint") ||
                message.contains("duplicate key")
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private suspend fun callLlm(
        modelConfigId: String,
        systemPrompt: String,
        userPrompt: String,
        owners: Collection<String>
    ): String {
        // The caller passes its complete read-visibility set (self → group → system); the single-id
        // lookup would hide a shared model from every group member.
        val config = configStore.getConfig(modelConfigId, owners)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Model config not found: $modelConfigId")

        val factory = modelFactories.firstOrNull { it.supports(config.protocol) }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No ChatModelFactory for protocol: ${config.protocol}")

        val chatModel = chatModelCache.computeIfAbsent(config) { factory.create(it) }

        val messages = listOf(
            SystemMessage(systemPrompt) as Message,
            UserMessage(userPrompt) as Message
        )

        return withContext(Dispatchers.IO) {
            val response = chatModel.call(Prompt(messages))
            response.result?.output?.text ?: ""
        }
    }

    private fun buildOssKey(ownerId: String, source: String, relativePath: String): String {
        val encoded = encodeUserId(ownerId)
        return "knowledge-articles/$encoded/$source/$relativePath"
    }

    private fun encodeUserId(userId: String): String {
        val bytes = userId.toByteArray(Charsets.UTF_8)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Content always lives in the article owner's storage, never in the caller's. */
    private suspend fun readArticleContent(article: KnowledgeArticle): String {
        val storage = objectStorageResolver?.resolve(article.userId)
            ?: throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Object storage is not configured; article content is unavailable"
            )
        val obj = storage.get(article.ossKey)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Article content is missing from storage: ${article.knowledgeKey}"
            )
        return String(obj.bytes, Charsets.UTF_8)
    }

    private suspend fun writeToOss(ownerId: String, key: String, bytes: ByteArray, contentType: String) {
        val storage = objectStorageResolver?.resolve(ownerId)
            ?: throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Object storage is not configured; article content cannot be saved"
            )
        try {
            storage.put(key, bytes, contentType)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to write article to OSS: key={}, error={}", key, e.message)
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Failed to write article content to storage: ${e.message}"
            )
        }
    }

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(content.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        /** Postgres/H2 unique-violation SQLState. */
        const val UNIQUE_VIOLATION_SQLSTATE = "23505"
        const val UPLOAD_DENIED_REASON = "无权限覆盖或创建该文档"
        const val UPLOAD_METADATA_FAILED_REASON = "元数据保存失败"
        const val UPLOAD_NOT_ATTEMPTED_REASON = "未上传"
    }
}
