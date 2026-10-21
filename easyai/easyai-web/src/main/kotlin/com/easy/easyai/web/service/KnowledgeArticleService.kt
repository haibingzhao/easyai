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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
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
        val ext = relativePath.substringAfterLast('.', "md").lowercase()
        val knowledgeKey = "$source/$relativePath"
        val ossKey = buildOssKey(ownerId, source, relativePath)
        val now = System.currentTimeMillis()

        writeToOss(ownerId, ossKey, content.toByteArray(Charsets.UTF_8), "text/markdown")

        val article = KnowledgeArticle(
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
            visibility = visibility,
            origin = origin,
            originSessionId = originSessionId,
            originMessageId = originMessageId,
            composedFrom = composedFrom,
            createdAt = now,
            updatedAt = now
        )

        articleStore.save(article)

        knowledgeStore?.update(ownerId, knowledgeKey, content, title, category)

        logger.info("Saved knowledge article: key={}, visibility={}, origin={}", knowledgeKey, visibility, origin)
        return article
    }

    suspend fun readContent(userId: String, groupUserId: String?, key: String): String? {
        val ownerId = KnowledgeOwnership.ownerId(userId, groupUserId, sharedWithinGroup)
        val article = articleStore.findByKey(ownerId, key) ?: return null

        val storage = objectStorageResolver?.resolve(ownerId) ?: return null
        val obj = storage.get(article.ossKey) ?: return null
        return String(obj.bytes, Charsets.UTF_8)
    }

    suspend fun updateArticle(
        userId: String,
        groupUserId: String?,
        key: String,
        content: String,
        title: String? = null,
        category: String? = null
    ): KnowledgeArticle {
        val ownerId = KnowledgeOwnership.ownerId(userId, groupUserId, sharedWithinGroup)
        val existing = articleStore.findByKey(ownerId, key)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Article not found: $key")

        if (!canWrite(existing, userId, groupUserId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "No write permission on article: $key")
        }

        val ossKey = existing.ossKey.ifBlank { buildOssKey(ownerId, existing.source, existing.relativePath) }
        writeToOss(ownerId, ossKey, content.toByteArray(Charsets.UTF_8), "text/markdown")

        val updated = existing.copy(
            title = title ?: existing.title,
            category = category ?: existing.category,
            contentHash = sha256(content),
            ossKey = ossKey,
            updatedAt = System.currentTimeMillis()
        )
        articleStore.save(updated)

        knowledgeStore?.update(ownerId, key, content, title, category)

        logger.info("Updated knowledge article: key={}", key)
        return updated
    }

    suspend fun deleteArticle(userId: String, groupUserId: String?, key: String): Boolean {
        val ownerId = KnowledgeOwnership.ownerId(userId, groupUserId, sharedWithinGroup)
        val article = articleStore.findByKey(ownerId, key) ?: return false

        if (article.userId != userId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only the article owner can delete")
        }

        objectStorageResolver?.resolve(ownerId)?.delete(article.ossKey)
        accessStore.deleteByArticle(article.id)
        articleStore.delete(article.id)
        knowledgeStore?.delete(ownerId, key)

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

        val articles = articleStore.findAllVisible(userId, groupUserIds, source, category)
        return articles.toList()
    }

    suspend fun findByKey(userId: String, groupUserId: String?, key: String): KnowledgeArticle? {
        val ownerId = KnowledgeOwnership.ownerId(userId, groupUserId, sharedWithinGroup)
        val article = articleStore.findByKey(ownerId, key) ?: return null
        if (!canRead(article, userId, groupUserId)) return null
        return article
    }

    // ── Permissions ───────────────────────────────────────────────────

    suspend fun canRead(article: KnowledgeArticle, userId: String, groupUserId: String?): Boolean {
        if (article.userId == userId) return true
        if (article.visibility == "group" && sharedWithinGroup &&
            !groupUserId.isNullOrBlank() && article.userId == groupUserId) return true
        return accessStore.hasPermission(article.id, userId, "read") ||
            accessStore.hasPermission(article.id, userId, "write")
    }

    suspend fun canWrite(article: KnowledgeArticle, userId: String, groupUserId: String?): Boolean {
        if (article.userId == userId) return true
        return accessStore.hasPermission(article.id, userId, "write")
    }

    suspend fun grantAccess(
        articleId: String,
        granteeId: String,
        permission: String,
        grantedBy: String
    ) {
        val article = articleStore.findById(articleId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Article not found")
        if (article.userId != grantedBy) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only the article owner can grant access")
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

    suspend fun revokeAccess(articleId: String, granteeId: String, userId: String) {
        val article = articleStore.findById(articleId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Article not found")
        if (article.userId != userId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only the article owner can revoke access")
        }
        accessStore.revoke(articleId, granteeId)
    }

    suspend fun listAccess(articleId: String, userId: String): List<KnowledgeAccess> {
        val article = articleStore.findById(articleId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Article not found")
        if (article.userId != userId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only the article owner can view access list")
        }
        return accessStore.listByArticle(articleId)
    }

    suspend fun updateVisibility(articleId: String, visibility: String, userId: String) {
        val article = articleStore.findById(articleId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Article not found")
        if (article.userId != userId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only the article owner can change visibility")
        }
        articleStore.save(article.copy(visibility = visibility, updatedAt = System.currentTimeMillis()))
    }

    // ── AI Operations ─────────────────────────────────────────────────

    suspend fun aiRewrite(
        userId: String,
        groupUserId: String?,
        key: String,
        instruction: String,
        modelConfigId: String
    ): String {
        val content = readContent(userId, groupUserId, key)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Article content not found: $key")

        val systemPrompt = """
You are a professional writing assistant. The user will provide an article and an instruction.
Rewrite the entire article according to the instruction.
Return ONLY the rewritten article in Markdown format, without any explanation or preamble.
Preserve the original structure (headings, lists, code blocks) unless the instruction explicitly asks to change it.
""".trimIndent()

        val userPrompt = """
## Original Article

$content

## Instruction

$instruction
""".trimIndent()

        return callLlm(userId, modelConfigId, systemPrompt, userPrompt)
    }

    suspend fun aiModifySelection(
        userId: String,
        groupUserId: String?,
        key: String,
        selectedText: String,
        instruction: String,
        modelConfigId: String
    ): String {
        val content = readContent(userId, groupUserId, key)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Article content not found: $key")

        val systemPrompt = """
You are a professional writing assistant. The user will provide an article, a selected portion of it, and an instruction.
Modify ONLY the selected portion according to the instruction.
Return ONLY the modified portion text in Markdown format, without any explanation or preamble.
The output will replace the selected portion in the original article.
""".trimIndent()

        val userPrompt = """
## Selected Text

$selectedText

## Instruction

$instruction
""".trimIndent()

        return callLlm(userId, modelConfigId, systemPrompt, userPrompt)
    }

    suspend fun aiCompose(
        userId: String,
        groupUserId: String?,
        sourceKeys: List<String>,
        guidance: String,
        style: String,
        modelConfigId: String
    ): String {
        val materials = sourceKeys.mapNotNull { key ->
            val content = readContent(userId, groupUserId, key) ?: return@mapNotNull null
            val article = findByKey(userId, groupUserId, key)
            val title = article?.title ?: key
            "### Material: $title\n\n$content"
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

        return callLlm(userId, modelConfigId, systemPrompt, userPrompt)
    }

    // ── Internal helpers ──────────────────────────────────────────────

    private suspend fun callLlm(
        userId: String,
        modelConfigId: String,
        systemPrompt: String,
        userPrompt: String
    ): String {
        val config = configStore.getConfig(modelConfigId, userId)
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

    private suspend fun writeToOss(ownerId: String, key: String, bytes: ByteArray, contentType: String) {
        val storage = objectStorageResolver?.resolve(ownerId) ?: return
        try {
            storage.put(key, bytes, contentType)
        } catch (e: Exception) {
            logger.warn("Failed to write article to OSS: key={}, error={}", key, e.message)
        }
    }

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(content.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
