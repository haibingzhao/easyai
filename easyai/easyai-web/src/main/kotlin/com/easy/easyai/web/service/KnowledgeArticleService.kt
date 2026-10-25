package com.easy.easyai.web.service

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.llm.*
import com.easy.easyai.core.knowledge.KnowledgeAccess
import com.easy.easyai.core.knowledge.KnowledgeAccessStore
import com.easy.easyai.core.knowledge.KnowledgeArticle
import com.easy.easyai.core.knowledge.KnowledgeArticleStore
import com.easy.easyai.core.knowledge.KnowledgeOwnership
import com.easy.easyai.core.knowledge.KnowledgeStore
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

@Service
class KnowledgeArticleService(
    @param:Autowired(required = false) private val objectStorageResolver: ObjectStorageResolver?,
    @param:Autowired(required = false) private val knowledgeStore: KnowledgeStore?,
    private val articleStore: KnowledgeArticleStore,
    private val accessStore: KnowledgeAccessStore,
    private val configStore: ModelProviderConfigStore,
    private val modelFactories: List<ChatModelFactory>,
    @param:Value("\${easyai.knowledge.shared-within-group:false}") private val sharedWithinGroup: Boolean = false
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val chatModelCache = ConcurrentHashMap<com.easy.easyai.api.model.ModelProviderConfig, ChatModel>()

    // ── CRUD ──────────────────────────────────────────────────────────

    suspend fun saveArticle(
        userId: String,
        groupUserId: String?,
        title: String,
        content: String,
        source: String,
        relativePath: String,
        category: String,
        visibility: String,
        origin: String,
        originSessionId: String? = null,
        originMessageId: String? = null,
        composedFrom: String? = null
    ): KnowledgeArticle {
        val ownerId = KnowledgeOwnership.ownerId(userId, groupUserId, sharedWithinGroup)
        // `group` visibility only widens reads inside a shared group bucket; without one it would
        // persist a label no read path honors (group-less deployments such as trading).
        val groupSharing = sharedWithinGroup && !groupUserId.isNullOrBlank()
        val effectiveVisibility = if (visibility == "group" && !groupSharing) "private" else visibility
        val ext = relativePath.substringAfterLast('.', "md").lowercase()
        val knowledgeKey = "$source/$relativePath"
        val ossKey = buildOssKey(ownerId, source, relativePath)
        val now = System.currentTimeMillis()

        val existing = articleStore.findByKey(ownerId, knowledgeKey)
        if (existing != null && origin != "upload") {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "An article with the same title already exists: $knowledgeKey"
            )
        }

        // Re-uploading the same source path updates the existing row instead of tripping the
        // (user_id, knowledge_key) unique index.
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
            KnowledgeArticle(
                id = UUID.randomUUID().toString(),
                userId = ownerId,
                knowledgeKey = knowledgeKey,
                ossKey = ossKey,
                title = title,
                description = content.take(200),
                category = category,
                source = source,
                relativePath = relativePath,
                ext = ext,
                contentHash = sha256(content),
                visibility = effectiveVisibility,
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
        } catch (e: SQLException) {
            // check-then-insert is racy; the unique index is the source of truth.
            if (created && e.sqlState == UNIQUE_VIOLATION_SQLSTATE) {
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "An article with the same title already exists: $knowledgeKey"
                )
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

        logger.info("Saved knowledge article: key={}, visibility={}, origin={}", knowledgeKey, article.visibility, origin)
        return article
    }

    suspend fun readContent(userId: String, groupUserId: String?, key: String): String? {
        val article = resolveVisibleArticle(userId, groupUserId, key) ?: return null
        if (!canRead(article, userId, groupUserId)) return null
        return readArticleContent(article)
    }

    suspend fun updateArticle(
        userId: String,
        groupUserId: String?,
        key: String,
        content: String,
        title: String? = null,
        category: String? = null
    ): KnowledgeArticle {
        val existing = resolveVisibleArticle(userId, groupUserId, key)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Article not found: $key")

        if (!canWrite(existing, userId, groupUserId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "No write permission on article: $key")
        }

        val ossKey = existing.ossKey.ifBlank {
            buildOssKey(existing.userId, existing.source, existing.relativePath)
        }
        writeToOss(existing.userId, ossKey, content.toByteArray(Charsets.UTF_8), "text/markdown")

        val updated = existing.copy(
            title = title ?: existing.title,
            category = category ?: existing.category,
            contentHash = sha256(content),
            ossKey = ossKey,
            updatedAt = System.currentTimeMillis()
        )
        articleStore.save(updated)

        knowledgeStore?.update(existing.userId, existing.knowledgeKey, content, title, category)

        logger.info("Updated knowledge article: key={}", key)
        return updated
    }

    suspend fun deleteArticle(userId: String, groupUserId: String?, key: String): Boolean {
        val article = resolveVisibleArticle(userId, groupUserId, key) ?: return false

        if (!isOwner(article, userId, groupUserId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only the article owner can delete")
        }

        objectStorageResolver?.resolve(article.userId)?.delete(article.ossKey)
        accessStore.deleteByArticle(article.id)
        articleStore.delete(article.id)
        knowledgeStore?.delete(article.userId, article.knowledgeKey)

        logger.info("Deleted knowledge article: key={}", key)
        return true
    }

    suspend fun listVisible(
        userId: String,
        groupUserId: String?,
        source: String? = null,
        category: String? = null
    ): List<KnowledgeArticle> {
        val groupUserIds = if (sharedWithinGroup && !groupUserId.isNullOrBlank()) {
            listOf(groupUserId)
        } else {
            emptyList()
        }
        // In shared mode the group bucket is co-owned: every row is visible, including
        // rows saved as `private` when the sharing flag was off.
        val sharedOwnerIds = if (sharedWithinGroup && !groupUserId.isNullOrBlank()) {
            listOf(groupUserId)
        } else {
            emptyList()
        }

        val articles = articleStore.findAllVisible(userId, groupUserIds, sharedOwnerIds, source, category)
        return articles.toList()
    }

    // ── Permissions ───────────────────────────────────────────────────

    suspend fun canRead(article: KnowledgeArticle, userId: String, groupUserId: String?): Boolean {
        if (isOwner(article, userId, groupUserId)) return true
        if (article.visibility == "group" && sharedWithinGroup &&
            !groupUserId.isNullOrBlank() && article.userId == groupUserId) return true
        return accessStore.hasPermission(article.id, userId, "read") ||
            accessStore.hasPermission(article.id, userId, "write")
    }

    suspend fun canWrite(article: KnowledgeArticle, userId: String, groupUserId: String?): Boolean {
        if (isOwner(article, userId, groupUserId)) return true
        return accessStore.hasPermission(article.id, userId, "write")
    }

    /**
     * Whether the caller owns the article's bucket. With shared knowledge every group member
     * co-owns the group bucket, which is what makes the shared slice writable for members.
     */
    fun canManage(article: KnowledgeArticle, userId: String, groupUserId: String?): Boolean =
        isOwner(article, userId, groupUserId)

    private fun isOwner(article: KnowledgeArticle, userId: String, groupUserId: String?): Boolean =
        article.userId == KnowledgeOwnership.ownerId(userId, groupUserId, sharedWithinGroup)

    suspend fun grantAccess(
        articleId: String,
        granteeId: String,
        permission: String,
        grantedBy: String,
        groupUserId: String?
    ) {
        val article = articleStore.findById(articleId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Article not found")
        if (!isOwner(article, grantedBy, groupUserId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only the article owner can grant access")
        }
        if (permission != "read" && permission != "write") {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid permission: $permission")
        }
        accessStore.grant(
            KnowledgeAccess(
                id = UUID.randomUUID().toString(),
                articleId = articleId,
                granteeId = granteeId,
                permission = permission,
                grantedBy = grantedBy,
                grantedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun revokeAccess(articleId: String, granteeId: String, userId: String, groupUserId: String?) {
        val article = articleStore.findById(articleId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Article not found")
        if (!isOwner(article, userId, groupUserId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only the article owner can revoke access")
        }
        accessStore.revoke(articleId, granteeId)
    }

    suspend fun listAccess(articleId: String, userId: String, groupUserId: String?): List<KnowledgeAccess> {
        val article = articleStore.findById(articleId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Article not found")
        if (!isOwner(article, userId, groupUserId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only the article owner can view access list")
        }
        return accessStore.listByArticle(articleId)
    }

    suspend fun updateVisibility(articleId: String, visibility: String, userId: String, groupUserId: String?) {
        val article = articleStore.findById(articleId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Article not found")
        if (!isOwner(article, userId, groupUserId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only the article owner can change visibility")
        }
        if (visibility != "private" && visibility != "group") {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid visibility: $visibility")
        }
        articleStore.save(article.copy(visibility = visibility, updatedAt = System.currentTimeMillis()))
    }

    // ── AI Operations ─────────────────────────────────────────────────

    suspend fun aiRewrite(
        userId: String,
        groupUserId: String?,
        key: String,
        content: String? = null,
        instruction: String,
        modelConfigId: String,
        owners: Collection<String>
    ): String {
        val articleContent = content?.takeIf { it.isNotBlank() }
            ?: readContent(userId, groupUserId, key)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Article content not found: $key")

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
        userId: String,
        groupUserId: String?,
        key: String,
        content: String? = null,
        selectedText: String,
        instruction: String,
        modelConfigId: String,
        owners: Collection<String>
    ): String {
        val articleContent = content?.takeIf { it.isNotBlank() }
            ?: readContent(userId, groupUserId, key)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Article content not found: $key")

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
        userId: String,
        groupUserId: String?,
        sourceKeys: List<String>,
        guidance: String,
        style: String,
        modelConfigId: String,
        owners: Collection<String>
    ): String {
        val materials = sourceKeys.mapNotNull { key ->
            val article = resolveVisibleArticle(userId, groupUserId, key) ?: return@mapNotNull null
            if (!canRead(article, userId, groupUserId)) return@mapNotNull null
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

    /**
     * Resolve the article a key refers to for this caller: own/shared bucket first, then an
     * explicit grant. Keys are only unique per owner, so an own row shadows a granted one.
     */
    private suspend fun resolveVisibleArticle(
        userId: String,
        groupUserId: String?,
        key: String
    ): KnowledgeArticle? {
        val ownerId = KnowledgeOwnership.ownerId(userId, groupUserId, sharedWithinGroup)
        articleStore.findByKey(ownerId, key)?.let { return it }
        return articleStore.findGrantedByKey(userId, key)
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
    }
}
