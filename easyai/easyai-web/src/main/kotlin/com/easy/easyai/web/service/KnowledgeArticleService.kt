package com.easy.easyai.web.service

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.llm.ChatModel
import com.easy.easyai.api.llm.Message
import com.easy.easyai.api.llm.Prompt
import com.easy.easyai.api.llm.SystemMessage
import com.easy.easyai.api.llm.UserMessage
import com.easy.easyai.core.knowledge.ArticleStatus
import com.easy.easyai.core.knowledge.KnowledgeAccessContext
import com.easy.easyai.core.knowledge.KnowledgeAccessPolicy
import com.easy.easyai.core.knowledge.KnowledgeArticle
import com.easy.easyai.core.knowledge.KnowledgeArticleStore
import com.easy.easyai.core.knowledge.KnowledgeFolder
import com.easy.easyai.core.knowledge.KnowledgeOwnership
import com.easy.easyai.core.knowledge.KnowledgeStore
import com.easy.easyai.core.knowledge.KnowledgeTree
import com.easy.easyai.core.storage.ObjectStorageResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** An article plus the caller-specific actions and display path rendered by the console. */
data class AccessibleKnowledgeArticle(
    val article: KnowledgeArticle,
    val folderPath: String,
    val canWrite: Boolean,
    val canManage: Boolean,
)

/**
 * Result of a content write.
 *
 * [indexPending] is only meaningful for published, rag-enabled articles: the body was saved but
 * the index write failed, so the article still shows old content to retrieval until re-indexed.
 */
data class ArticleWriteResult(
    val article: KnowledgeArticle,
    val indexPending: Boolean = false,
)

/** One uploaded text file, relative to the destination folder. */
data class KnowledgeUploadItem(
    val relativePath: String,
    val content: String,
)

/** Per-file upload outcome; [id] is set once a row exists. */
data class UploadOutcome(
    val relativePath: String,
    val success: Boolean,
    val id: String? = null,
    val reason: String? = null,
    val indexPending: Boolean = false,
)

/**
 * Knowledge article operations.
 *
 * The article UUID is the only identity: it addresses the database row, the object-storage object
 * (`knowledge-articles/{id}`) and the RAG document. Folders are database-only metadata, so moving
 * an article or renaming a folder never touches storage or the index.
 */
@Service
class KnowledgeArticleService(
    @param:Autowired(required = false) private val objectStorageResolver: ObjectStorageResolver?,
    @param:Autowired(required = false) private val knowledgeStore: KnowledgeStore?,
    private val articleStore: KnowledgeArticleStore,
    private val folderService: KnowledgeFolderService,
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

    /**
     * Create an article row and write its body to object storage.
     *
     * Metadata alone never touches the search index. Upload reaches it through its own path (the
     * file is indexed right after the row exists); a caller that wants a retrievable article has to
     * [publish] it, which is why [ragEnabled] is refused here.
     */
    suspend fun saveArticle(
        context: KnowledgeAccessContext,
        title: String,
        content: String,
        folderId: String?,
        category: String,
        origin: String,
        fileName: String? = null,
        ext: String = "md",
        originSessionId: String? = null,
        originMessageId: String? = null,
        composedFrom: String? = null,
        status: ArticleStatus = ArticleStatus.DRAFT,
        ragEnabled: Boolean = false,
    ): KnowledgeArticle {
        if (!accessPolicy.canCreate(context)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "No permission to create knowledge articles")
        }
        if (ragEnabled) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Retrieval is enabled through publish or the retrieval toggle, not on save"
            )
        }
        val ownerId = writeOwner(context)
        val folder = folderService.requireFolder(ownerId, folderId, context.userId)
        val article = createArticle(
            ownerId = ownerId,
            createdBy = context.userId,
            folder = folder,
            fileName = fileName,
            ext = ext,
            title = title,
            content = content,
            category = category,
            origin = origin,
            originSessionId = originSessionId,
            originMessageId = originMessageId,
            composedFrom = composedFrom,
            status = status,
            ragEnabled = ragEnabled,
        )
        logger.info(
            "Saved knowledge article: id={}, folder={}, origin={}, status={}, ragEnabled={}",
            article.id, article.folderId, article.origin, article.status, article.ragEnabled
        )
        return article
    }

    /**
     * Save edited content.
     *
     * A published article with ragEnabled keeps its flags and has the index rebuilt (upsert, so a
     * missing entry is recreated); a draft or non-indexed article never touches the index. Index
     * failure is reported through [ArticleWriteResult.indexPending] instead of rolling back the
     * saved body — downgrading an article on a transient network error would remove it from
     * retrieval entirely.
     */
    suspend fun updateArticle(
        context: KnowledgeAccessContext,
        id: String,
        content: String,
        title: String? = null,
        category: String? = null,
    ): ArticleWriteResult {
        val existing = requireArticle(context, id)
        if (!accessPolicy.canWrite(context, existing)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "No write permission on article: $id")
        }

        // Content first: a storage failure must leave the metadata untouched.
        writeToOss(existing.userId, storageKey(id), content, contentType(existing.ext))

        val updated = existing.copy(
            title = title?.trim()?.takeIf { it.isNotBlank() } ?: existing.title,
            category = category?.trim()?.takeIf { it.isNotBlank() } ?: existing.category,
            description = content.take(DESCRIPTION_LIMIT),
            updatedAt = System.currentTimeMillis()
        )
        articleStore.save(updated)

        var indexPending = false
        if (updated.status == ArticleStatus.PUBLISHED && updated.ragEnabled) {
            indexPending = !indexArticle(updated, content, knowledgeStore)
            if (indexPending) {
                logger.warn("Article content saved but index write failed: id={}", id)
            }
        }

        logger.info("Updated knowledge article: id={}, status={}, ragEnabled={}", id, updated.status, updated.ragEnabled)
        return ArticleWriteResult(article = updated, indexPending = indexPending)
    }

    /** Move an article between folders (or roots). Database-only: storage and index keys are stable. */
    suspend fun moveArticle(
        context: KnowledgeAccessContext,
        id: String,
        targetFolderId: String,
    ): AccessibleKnowledgeArticle {
        val existing = requireArticle(context, id)
        if (!accessPolicy.canWrite(context, existing)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "No write permission on article: $id")
        }
        if (targetFolderId.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Target folder is required")
        }
        val target = folderService.requireFolder(existing.userId, targetFolderId, context.userId)
        if (target.id == existing.folderId) {
            return accessible(context, existing)
        }
        val moved = existing.copy(folderId = target.id, updatedAt = System.currentTimeMillis())
        try {
            articleStore.save(moved)
        } catch (e: Exception) {
            if (isUniqueViolation(e)) {
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The target folder already has a file named '${existing.fileName ?: existing.title}'"
                )
            }
            if (isForeignKeyViolation(e)) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "The target folder no longer exists")
            }
            throw e
        }
        logger.info("Moved knowledge article: id={}, folder={}", id, target.id)
        return accessible(context, moved)
    }

    suspend fun deleteArticle(context: KnowledgeAccessContext, id: String): Boolean {
        val article = requireArticle(context, id)
        if (!accessPolicy.canManage(context, article)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "No permission to delete article: $id")
        }

        // Index first: deleting metadata before the index can leave a retrievable orphan entry.
        if (article.ragEnabled) {
            val store = knowledgeStore
            if (store != null) {
                if (!removeFromIndex(store, article)) {
                    throw ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Failed to remove the article from the search index; it is still published"
                    )
                }
            } else {
                // Retrieval is switched off for this deployment; refusing the delete forever would
                // strand the row, so drop the metadata and say that the index side was skipped.
                logger.warn(
                    "Deleting article {} while retrieval is disabled; its index entry cannot be removed", id
                )
            }
        }
        objectStorageResolver?.resolve(article.userId)?.delete(storageKey(article.id))
        articleStore.delete(article.id)

        logger.info("Deleted knowledge article: id={}", id)
        return true
    }

    /**
     * Publish a draft, or rebuild the index of an already published article.
     *
     * A first publish flips the status before the index write so a crash can only leave "published
     * but not yet indexed"; a failed write removes the partial entry and reverts the row to its
     * previous metadata. Re-indexing an already published article never demotes it: on failure the
     * previous publication/rag state is restored.
     */
    suspend fun publish(
        context: KnowledgeAccessContext,
        id: String,
        category: String? = null,
        title: String? = null,
    ): KnowledgeArticle {
        val store = knowledgeStore ?: throw knowledgeNotEnabled()
        val existing = requireArticle(context, id)
        if (!accessPolicy.canWrite(context, existing)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "No write permission on article: $id")
        }

        val wasPublished = existing.status == ArticleStatus.PUBLISHED
        val content = readArticleContent(existing)
        val published = existing.copy(
            status = ArticleStatus.PUBLISHED,
            ragEnabled = true,
            title = title?.trim()?.takeIf { it.isNotBlank() } ?: existing.title,
            category = category?.trim()?.takeIf { it.isNotBlank() } ?: existing.category,
            updatedAt = System.currentTimeMillis()
        )
        articleStore.save(published)

        if (!indexArticle(published, content, store)) {
            if (wasPublished) {
                withContext(NonCancellable) {
                    runCatching { articleStore.save(existing) }
                }
                throw ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Failed to rebuild the search index; the article is still published"
                )
            }
            withContext(NonCancellable) {
                // The write may have been accepted before the error surfaced; do not leave a
                // searchable entry behind while the row goes back to draft.
                runCatching { store.delete(existing.userId, existing.id) }
                runCatching { articleStore.save(existing) }
            }
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Failed to index the article; it remains a draft"
            )
        }

        logger.info("Published knowledge article: id={}, category={}", id, published.category)
        return published
    }

    /** Take a published article out of the index and demote it to a draft. */
    suspend fun unpublish(context: KnowledgeAccessContext, id: String): KnowledgeArticle {
        val existing = requireArticle(context, id)
        if (!accessPolicy.canManage(context, existing)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "No permission to unpublish article: $id")
        }
        if (existing.status == ArticleStatus.DRAFT) return existing

        if (existing.ragEnabled) {
            val store = knowledgeStore
            if (store != null) {
                if (!removeFromIndex(store, existing)) {
                    throw ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Failed to remove the article from the search index; it is still published"
                    )
                }
            } else {
                logger.warn(
                    "Unpublishing article {} while retrieval is disabled; its index entry cannot be removed",
                    id
                )
            }
        }

        val draft = existing.copy(
            status = ArticleStatus.DRAFT,
            ragEnabled = false,
            updatedAt = System.currentTimeMillis()
        )
        articleStore.save(draft)

        logger.info("Unpublished knowledge article: id={}", id)
        return draft
    }

    /** Toggle retrieval exposure of a published article without changing its visibility. */
    suspend fun setRag(
        context: KnowledgeAccessContext,
        id: String,
        enabled: Boolean,
    ): KnowledgeArticle {
        val existing = requireArticle(context, id)
        if (!accessPolicy.canManage(context, existing)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "No permission to change article retrieval: $id")
        }
        if (existing.status == ArticleStatus.DRAFT) {
            if (enabled) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Publish the article before enabling retrieval")
            }
            return existing
        }
        if (existing.ragEnabled == enabled) return existing

        if (enabled) {
            val store = knowledgeStore ?: throw knowledgeNotEnabled()
            val content = readArticleContent(existing)
            val updated = existing.copy(ragEnabled = true, updatedAt = System.currentTimeMillis())
            // Mark first, like publish: a crash can only leave "retrievable but not yet indexed",
            // never an indexed entry the UI calls hidden while the agent can still read it.
            articleStore.save(updated)
            if (!indexArticle(updated, content, store)) {
                withContext(NonCancellable) {
                    // The write may have been accepted before the error surfaced; do not leave a
                    // searchable entry behind while the row goes back to hidden.
                    runCatching { store.delete(existing.userId, existing.id) }
                    runCatching { articleStore.save(existing) }
                }
                throw ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Failed to index the article; it is still not retrievable"
                )
            }
            logger.info("Enabled knowledge retrieval: id={}", id)
            return updated
        }

        val store = knowledgeStore
        if (store != null) {
            if (!removeFromIndex(store, existing)) {
                throw ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Failed to remove the article from the search index; it is still retrievable"
                )
            }
        } else {
            logger.warn(
                "Disabling retrieval for article {} while retrieval is disabled; no index entry to remove",
                id
            )
        }
        val updated = existing.copy(ragEnabled = false, updatedAt = System.currentTimeMillis())
        articleStore.save(updated)
        logger.info("Disabled knowledge retrieval: id={}", id)
        return updated
    }

    suspend fun listAccessible(
        context: KnowledgeAccessContext,
        category: String? = null,
        status: ArticleStatus? = null,
    ): List<AccessibleKnowledgeArticle> {
        if (!accessPolicy.canView(context)) return emptyList()
        val buckets = visibleBuckets(context)
        val folders = folderService.foldersIn(buckets).associateBy { it.id }
        val seen = mutableSetOf<String>()
        val result = mutableListOf<AccessibleKnowledgeArticle>()
        for (bucket in buckets) {
            val articles = articleStore.findAllInBuckets(
                userIds = listOf(bucket),
                category = category,
                status = status,
            ).toList()
            for (article in articles) {
                if (!seen.add(article.id)) continue
                if (!canSeeArticle(context, article)) continue
                if (!accessPolicy.canRead(context, article)) continue
                result += AccessibleKnowledgeArticle(
                    article = article,
                    folderPath = KnowledgeTree.pathOf(article.folderId, folders),
                    canWrite = accessPolicy.canWrite(context, article),
                    canManage = accessPolicy.canManage(context, article),
                )
            }
        }
        return result.sortedByDescending { it.article.updatedAt }
    }

    suspend fun readContent(context: KnowledgeAccessContext, id: String): String? {
        val article = requireArticle(context, id)
        if (!accessPolicy.canRead(context, article)) return null
        return readArticleContent(article)
    }

    suspend fun folderPathOf(article: KnowledgeArticle): String {
        val folders = folderService.foldersIn(listOf(article.userId)).associateBy { it.id }
        return KnowledgeTree.pathOf(article.folderId, folders)
    }

    /** Resolve a display path (`Family/Recipes`) in the caller's write bucket. */
    suspend fun resolveFolderIdByPath(context: KnowledgeAccessContext, path: String): String? =
        folderService.resolvePath(writeOwner(context), path)?.id

    // ── Upload ────────────────────────────────────────────────────────

    /**
     * Authorize, store and index a batch of uploaded files.
     *
     * Every file's directory segments become folders under the destination; files are published
     * and indexed immediately. Re-uploading the same `(folder, file name)` replaces the existing
     * article in place (never publishing a draft); index failures keep the article with
     * `ragEnabled = false` and surface through [UploadOutcome.indexPending].
     */
    suspend fun uploadItems(
        context: KnowledgeAccessContext,
        folderId: String?,
        category: String?,
        items: List<KnowledgeUploadItem>,
    ): List<UploadOutcome> {
        val store = knowledgeStore ?: throw knowledgeNotEnabled()
        if (!accessPolicy.canCreate(context)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "No permission to create knowledge articles")
        }
        items.forEach { KnowledgePathValidator.validateRelativePath(it.relativePath) }
        if (items.isEmpty()) return emptyList()

        val ownerId = writeOwner(context)
        val target = folderService.requireFolder(ownerId, folderId, context.userId)
        return items.chunked(UPLOAD_CONCURRENCY).flatMap { chunk ->
            coroutineScope {
                chunk.map { item ->
                    async { uploadOne(context, ownerId, target, category, item, store) }
                }.awaitAll()
            }
        }
    }

    private suspend fun uploadOne(
        context: KnowledgeAccessContext,
        ownerId: String,
        target: KnowledgeFolder,
        category: String?,
        item: KnowledgeUploadItem,
        store: KnowledgeStore,
    ): UploadOutcome {
        val relativePath = item.relativePath
        val fileName = relativePath.substringAfterLast('/')
        val folderSegments = relativePath.substringBeforeLast('/', "")
            .split('/')
            .filter { it.isNotBlank() }

        val folder = try {
            var current = target
            for (segment in folderSegments) {
                current = folderService.ensureChild(ownerId, current, segment, context.userId)
            }
            current
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return UploadOutcome(relativePath, false, reason = e.message ?: "Failed to create folder")
        }

        val ext = normalizeExt(fileName.substringAfterLast('.', ""))
        if (!store.supports(ext)) {
            return UploadOutcome(relativePath, false, reason = "Unsupported file type: .$ext")
        }
        val resolvedCategory = category?.takeIf { it.isNotBlank() } ?: "other"
        val title = titleFor(item.content, fileName)

        val existing = articleStore.findByFolderAndFileName(ownerId, folder.id, fileName)
        if (existing != null) {
            // Authorize before revealing anything: in a shared bucket the row may be another
            // member's draft, and even its existence and id must not leak.
            if (!accessPolicy.canWrite(context, existing)) {
                return UploadOutcome(relativePath, false, reason = UPLOAD_DENIED_REASON)
            }
            if (existing.status == ArticleStatus.DRAFT) {
                return UploadOutcome(relativePath, false, existing.id, UPLOAD_DRAFT_OVERWRITE_REASON)
            }
            return try {
                writeToOss(ownerId, storageKey(existing.id), item.content, contentType(ext))
                val updated = existing.copy(
                    title = title,
                    description = item.content.take(DESCRIPTION_LIMIT),
                    category = resolvedCategory,
                    ext = ext,
                    origin = "upload",
                    updatedAt = System.currentTimeMillis()
                )
                articleStore.save(updated)
                val indexPending = updated.ragEnabled && !indexArticle(updated, item.content, store)
                UploadOutcome(relativePath, true, existing.id, indexPending = indexPending)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Knowledge upload overwrite failed for {}: {}", relativePath, e.message)
                UploadOutcome(relativePath, false, existing.id, e.message)
            }
        }

        val article = try {
            createArticle(
                ownerId = ownerId,
                createdBy = context.userId,
                folder = folder,
                fileName = fileName,
                ext = ext,
                title = title,
                content = item.content,
                category = resolvedCategory,
                origin = "upload",
                status = ArticleStatus.PUBLISHED,
                ragEnabled = true,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Knowledge upload failed for {}: {}", relativePath, e.message)
            return UploadOutcome(relativePath, false, reason = e.message)
        }

        if (!indexArticle(article, item.content, store)) {
            val disabled = article.copy(ragEnabled = false, updatedAt = System.currentTimeMillis())
            withContext(NonCancellable) { runCatching { articleStore.save(disabled) } }
            return UploadOutcome(relativePath, true, article.id, UPLOAD_INDEX_PENDING_REASON, indexPending = true)
        }
        return UploadOutcome(relativePath, true, article.id)
    }

    suspend fun indexStatus(context: KnowledgeAccessContext, ids: Collection<String>): Map<String, Int> {
        if (ids.isEmpty() || !accessPolicy.canView(context)) return emptyMap()
        val store = knowledgeStore ?: return emptyMap()
        val result = mutableMapOf<String, Int>()
        for (bucket in visibleBuckets(context)) {
            val readable = articleStore.findByIds(bucket, ids)
                .filter { accessPolicy.canRead(context, it) }
                .map { it.id }
            if (readable.isEmpty()) continue
            result.putAll(store.chunkCounts(bucket, readable))
        }
        return result
    }

    // ── AI Operations ─────────────────────────────────────────────────

    suspend fun aiRewrite(
        context: KnowledgeAccessContext,
        id: String,
        content: String? = null,
        instruction: String,
        modelConfigId: String,
        owners: Collection<String>
    ): String {
        val article = requireWritableArticle(context, id)
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
        id: String,
        content: String? = null,
        selectedText: String,
        instruction: String,
        modelConfigId: String,
        owners: Collection<String>
    ): String {
        val article = requireWritableArticle(context, id)
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
        sourceIds: List<String>,
        guidance: String,
        style: String,
        modelConfigId: String,
        owners: Collection<String>
    ): String {
        val materials = sourceIds.mapNotNull { id ->
            val article = resolveArticle(context, id) ?: return@mapNotNull null
            if (!canSeeArticle(context, article)) return@mapNotNull null
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

    private suspend fun createArticle(
        ownerId: String,
        createdBy: String?,
        folder: KnowledgeFolder,
        fileName: String?,
        ext: String,
        title: String,
        content: String,
        category: String,
        origin: String,
        originSessionId: String? = null,
        originMessageId: String? = null,
        composedFrom: String? = null,
        status: ArticleStatus,
        ragEnabled: Boolean,
    ): KnowledgeArticle {
        val now = System.currentTimeMillis()
        val article = KnowledgeArticle(
            id = UUID.randomUUID().toString(),
            userId = ownerId,
            createdBy = createdBy,
            folderId = folder.id,
            fileName = fileName?.takeIf { it.isNotBlank() },
            title = title.trim().ifBlank { "Untitled" },
            description = content.take(DESCRIPTION_LIMIT),
            category = category.trim().ifBlank { "other" },
            ext = normalizeExt(ext),
            origin = origin,
            originSessionId = originSessionId,
            originMessageId = originMessageId,
            composedFrom = composedFrom,
            status = status,
            ragEnabled = ragEnabled,
            createdAt = now,
            updatedAt = now,
        )
        try {
            articleStore.save(article)
        } catch (e: Exception) {
            if (isUniqueViolation(e)) {
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "An article with the same file name already exists in this folder"
                )
            }
            if (isForeignKeyViolation(e)) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "The folder no longer exists")
            }
            throw e
        }
        try {
            writeToOss(article.userId, storageKey(article.id), content, contentType(article.ext))
        } catch (e: Exception) {
            withContext(NonCancellable) {
                runCatching { articleStore.delete(article.id) }
            }
            throw e
        }
        return article
    }

    private suspend fun requireWritableArticle(context: KnowledgeAccessContext, id: String): KnowledgeArticle {
        val article = requireArticle(context, id)
        if (!accessPolicy.canWrite(context, article)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "No write permission on article: $id")
        }
        return article
    }

    private fun writeOwner(context: KnowledgeAccessContext): String =
        KnowledgeOwnership.ownerId(context.userId, context.groupUserId, sharedWithinGroup)

    /**
     * Drafts are creator-only regardless of the host policy: publishing is what widens visibility,
     * so a policy that grants group-wide reads must not expose someone's unfinished notes.
     */
    private fun canSeeArticle(context: KnowledgeAccessContext, article: KnowledgeArticle): Boolean =
        article.status != ArticleStatus.DRAFT || article.createdBy == context.userId

    private fun visibleBuckets(context: KnowledgeAccessContext): List<String> =
        listOfNotNull(context.userId.takeIf { it.isNotBlank() }, context.groupUserId?.takeIf { it.isNotBlank() })
            .distinct()

    private suspend fun resolveArticle(context: KnowledgeAccessContext, id: String): KnowledgeArticle? {
        KnowledgePathValidator.validateArticleId(id)
        val article = articleStore.findById(id) ?: return null
        if (article.userId !in visibleBuckets(context)) return null
        return article
    }

    /** A hidden draft answers 404 like a missing id, so callers cannot probe that it exists. */
    private suspend fun requireArticle(context: KnowledgeAccessContext, id: String): KnowledgeArticle {
        val article = resolveArticle(context, id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Article not found: $id")
        if (!canSeeArticle(context, article)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Article not found: $id")
        }
        return article
    }

    private suspend fun accessible(
        context: KnowledgeAccessContext,
        article: KnowledgeArticle,
    ): AccessibleKnowledgeArticle = AccessibleKnowledgeArticle(
        article = article,
        folderPath = folderPathOf(article),
        canWrite = accessPolicy.canWrite(context, article),
        canManage = accessPolicy.canManage(context, article),
    )

    private suspend fun indexArticle(
        article: KnowledgeArticle,
        content: String,
        store: KnowledgeStore?,
    ): Boolean {
        val target = store ?: return false
        return try {
            target.index(
                userId = article.userId,
                id = article.id,
                content = content,
                title = article.title,
                kcategory = article.category,
                ext = article.ext,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Index write failed for article {}: {}", article.id, e.message)
            false
        }
    }

    /**
     * Delete the index entry, then verify it is gone. A false return means the entry may still be
     * retrievable, so callers must keep the article's previous metadata.
     */
    private suspend fun removeFromIndex(store: KnowledgeStore, article: KnowledgeArticle): Boolean {
        val removed = try {
            store.delete(article.userId, article.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to remove article {} from the index: {}", article.id, e.message)
            false
        }
        if (removed) return true
        // false also means "nothing was indexed", which is the desired end state.
        return try {
            store.read(article.userId, article.id) == null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private fun titleFor(content: String, fileName: String): String =
        content.lines().firstOrNull { it.startsWith("#") }
            ?.trimStart('#')?.trim()?.takeIf { it.isNotBlank() }
            ?: fileName.substringBeforeLast('.', fileName)

    private fun normalizeExt(ext: String): String =
        ext.trim().removePrefix(".").lowercase().ifBlank { "md" }

    private fun storageKey(articleId: String): String = "$STORAGE_PREFIX/$articleId"

    private fun contentType(ext: String): String =
        if (ext == "md") "text/markdown" else "text/plain"

    /** Content always lives in the article owner's storage, never in the caller's. */
    private suspend fun readArticleContent(article: KnowledgeArticle): String {
        val storage = objectStorageResolver?.resolve(article.userId)
            ?: throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Object storage is not configured; article content is unavailable"
            )
        val obj = storage.get(storageKey(article.id))
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Article content is missing from storage: ${article.id}"
            )
        return String(obj.bytes, Charsets.UTF_8)
    }

    private suspend fun writeToOss(ownerId: String, key: String, content: String, contentType: String) {
        val storage = objectStorageResolver?.resolve(ownerId)
            ?: throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Object storage is not configured; article content cannot be saved"
            )
        try {
            storage.put(key, content.toByteArray(Charsets.UTF_8), contentType)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to write article content to storage: key={}, error={}", key, e.message)
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Failed to write article content to storage: ${e.message}"
            )
        }
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

        // Cancellable: an abandoned generation request must not keep billing the model.
        val response = chatModel.callAsync(Prompt(messages))
        return response.result?.output?.text ?: ""
    }

    private fun knowledgeNotEnabled(): ResponseStatusException =
        ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Knowledge system is not enabled")

    private companion object {
        const val STORAGE_PREFIX = "knowledge-articles"
        const val DESCRIPTION_LIMIT = 200
        const val UPLOAD_CONCURRENCY = 4
        const val UPLOAD_DENIED_REASON = "无权限覆盖或创建该文档"
        const val UPLOAD_DRAFT_OVERWRITE_REASON = "草稿不支持上传覆盖，请先发布或删除"
        const val UPLOAD_INDEX_PENDING_REASON = "已保存，但检索索引构建失败，请稍后重试"
    }
}
