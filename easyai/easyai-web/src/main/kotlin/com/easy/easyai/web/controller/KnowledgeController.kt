package com.easy.easyai.web.controller

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.knowledge.ArticleStatus
import com.easy.easyai.core.knowledge.KnowledgeAccessContext
import com.easy.easyai.core.knowledge.KnowledgeArticle
import com.easy.easyai.web.model.AiComposeRequestDto
import com.easy.easyai.web.model.AiModifySelectionRequestDto
import com.easy.easyai.web.model.AiResultDto
import com.easy.easyai.web.model.AiRewriteRequestDto
import com.easy.easyai.web.model.CreateFolderRequestDto
import com.easy.easyai.web.model.IndexStatusDto
import com.easy.easyai.web.model.KnowledgeArticleDto
import com.easy.easyai.web.model.KnowledgeFolderDto
import com.easy.easyai.web.model.MoveArticleRequestDto
import com.easy.easyai.web.model.PublishArticleRequestDto
import com.easy.easyai.web.model.RenameFolderRequestDto
import com.easy.easyai.web.model.SaveArticleRequestDto
import com.easy.easyai.web.model.SetArticleRagRequestDto
import com.easy.easyai.web.model.UpdateArticleRequestDto
import com.easy.easyai.web.model.UploadResponseDto
import com.easy.easyai.web.model.UploadResultDto
import com.easy.easyai.web.security.currentGroupUserId
import com.easy.easyai.web.security.currentOwners
import com.easy.easyai.web.security.getCurrentUserId
import com.easy.easyai.web.service.AccessibleKnowledgeArticle
import com.easy.easyai.web.service.AccessibleKnowledgeFolder
import com.easy.easyai.web.service.KnowledgeArticleService
import com.easy.easyai.web.service.KnowledgeFolderService
import com.easy.easyai.web.service.KnowledgePathValidator
import com.easy.easyai.web.service.KnowledgeUploadItem
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.multipart.FilePart
import org.springframework.http.codec.multipart.Part
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * REST controller for the knowledge base.
 *
 * Endpoints:
 * - POST /api/knowledge/upload — Upload files into a folder (multipart: files, paths, folderId?, category?)
 * - GET /api/knowledge/articles — List visible articles (optional category/status filter)
 * - POST /api/knowledge/save-text — Save a DRAFT article from text/chat
 * - PUT /api/knowledge/article — Update article content (published + ragEnabled re-index)
 * - GET /api/knowledge/article/content?id= — Read the stored body
 * - POST /api/knowledge/article/publish — Publish a draft / rebuild its index
 * - POST /api/knowledge/article/unpublish?id= — Demote to draft and remove from the index
 * - POST /api/knowledge/article/rag — Toggle retrieval exposure of a published article
 * - POST /api/knowledge/article/move — Move an article between folders (database-only)
 * - DELETE /api/knowledge/article?id= — Delete article (index + storage + DB)
 * - GET /api/knowledge/index-status?ids=a&ids=b — Chunk counts for uploaded ids
 * - GET/POST/PATCH/DELETE /api/knowledge/folders — Folder tree CRUD
 * - POST /api/knowledge/ai-rewrite | ai-modify-selection | ai-compose
 *
 * All content access goes through [KnowledgeArticleService] / [KnowledgeFolderService], which
 * delegate to the host-provided KnowledgeAccessPolicy. Listing endpoints answer an empty
 * collection when the policy denies view; single-entry lookups answer 404 instead of leaking
 * existence, including writes aimed at another user's draft, while other denied writes answer 403.
 */
@RestController
@RequestMapping("/api/knowledge")
class KnowledgeController(
    @param:Autowired(required = false) private val articleService: KnowledgeArticleService?,
    @param:Autowired(required = false) private val folderService: KnowledgeFolderService?,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private suspend fun accessContext(): KnowledgeAccessContext = KnowledgeAccessContext(
        userId = getCurrentUserId(),
        groupUserId = currentGroupUserId(),
        owners = currentOwners(),
    )

    // ── Upload ────────────────────────────────────────────────────────

    @PostMapping("/upload", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadFiles(
        exchange: ServerWebExchange,
        @RequestParam(value = "folderId", required = false) folderId: String?,
        @RequestParam(value = "category", required = false) category: String?,
    ): Mono<UploadResponseDto> {
        return exchange.multipartData.flatMap { multipartData ->
            mono {
                val service = articleService ?: throw knowledgeNotEnabled()
                val fileParts = multipartData["files"]?.filterIsInstance<FilePart>() ?: emptyList()
                val pathsPart = multipartData["paths"]?.firstOrNull()
                val pathsJson = if (pathsPart != null) readPartContent(pathsPart) else "[]"
                val paths = parsePathsJson(pathsJson)

                if (paths.size != fileParts.size) {
                    throw badRequest(
                        "paths array size (${paths.size}) does not match files count (${fileParts.size})"
                    )
                }
                paths.forEach { validateRelativePath(it) }
                if (fileParts.isEmpty()) {
                    return@mono UploadResponseDto(emptyList(), 0, 0, 0)
                }
                folderId?.takeIf { it.isNotBlank() }?.let { validateId(it) }

                logger.info(
                    "Uploading {} file(s) into folder '{}' with category '{}' by user '{}'",
                    fileParts.size, folderId, category, getCurrentUserId()
                )

                val items = fileParts.zip(paths).map { (filePart, path) ->
                    KnowledgeUploadItem(relativePath = path, content = readPartContent(filePart))
                }
                val outcomes = service.uploadItems(accessContext(), folderId, category, items)
                UploadResponseDto(
                    results = outcomes.map {
                        UploadResultDto(it.relativePath, it.success, it.id, it.reason, it.indexPending)
                    },
                    totalFiles = outcomes.size,
                    successCount = outcomes.count { it.success },
                    failedCount = outcomes.count { !it.success },
                )
            }
        }
    }

    // ── Articles ──────────────────────────────────────────────────────

    @GetMapping("/articles")
    fun listArticles(
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) status: String?,
    ): Mono<List<KnowledgeArticleDto>> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            service.listAccessible(
                accessContext(), category, resolveStatusFilter(status)
            ).map { toArticleDto(it) }
        }
    }

    @PostMapping("/save-text")
    fun saveArticle(@RequestBody request: SaveArticleRequestDto): Mono<KnowledgeArticleDto> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            val context = accessContext()
            request.folderId?.takeIf { it.isNotBlank() }?.let { validateId(it) }
            val article = service.saveArticle(
                context = context,
                title = request.title,
                content = request.content,
                folderId = request.folderId,
                category = request.category,
                origin = request.origin,
                originSessionId = request.originSessionId,
                originMessageId = request.originMessageId,
                composedFrom = request.composedFrom,
            )
            toArticleDto(context, article, service)
        }
    }

    @PutMapping("/article")
    fun updateArticle(@RequestBody request: UpdateArticleRequestDto): Mono<KnowledgeArticleDto> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            validateId(request.id)
            val context = accessContext()
            val result = service.updateArticle(
                context = context,
                id = request.id,
                content = request.content,
                title = request.title,
                category = request.category,
            )
            toArticleDto(context, result.article, service, result.indexPending)
        }
    }

    @GetMapping("/article/content")
    fun getArticleContent(@RequestParam id: String): Mono<Map<String, String>> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            validateId(id)
            val content = service.readContent(accessContext(), id)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Article content not found: $id")
            mapOf("content" to content)
        }
    }

    @PostMapping("/article/publish")
    fun publishArticle(@RequestBody request: PublishArticleRequestDto): Mono<KnowledgeArticleDto> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            validateId(request.id)
            val context = accessContext()
            val article = service.publish(
                context = context,
                id = request.id,
                category = request.category,
                title = request.title,
            )
            toArticleDto(context, article, service)
        }
    }

    @PostMapping("/article/unpublish")
    fun unpublishArticle(@RequestParam id: String): Mono<KnowledgeArticleDto> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            validateId(id)
            val context = accessContext()
            val article = service.unpublish(context, id)
            toArticleDto(context, article, service)
        }
    }

    @PostMapping("/article/rag")
    fun setArticleRag(@RequestBody request: SetArticleRagRequestDto): Mono<KnowledgeArticleDto> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            validateId(request.id)
            val context = accessContext()
            val article = service.setRag(context, request.id, request.enabled)
            toArticleDto(context, article, service)
        }
    }

    @PostMapping("/article/move")
    fun moveArticle(@RequestBody request: MoveArticleRequestDto): Mono<KnowledgeArticleDto> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            validateId(request.id)
            validateId(request.folderId)
            val item = service.moveArticle(accessContext(), request.id, request.folderId)
            toArticleDto(item)
        }
    }

    @DeleteMapping("/article")
    fun deleteArticle(@RequestParam id: String): Mono<Map<String, Boolean>> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            validateId(id)
            val deleted = service.deleteArticle(accessContext(), id)
            mapOf("deleted" to deleted)
        }
    }

    @GetMapping("/index-status")
    fun indexStatus(@RequestParam ids: List<String>): Mono<IndexStatusDto> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            if (ids.size > MAX_INDEX_STATUS_IDS) {
                throw badRequest("Too many ids (max $MAX_INDEX_STATUS_IDS)")
            }
            ids.forEach { validateId(it) }
            IndexStatusDto(service.indexStatus(accessContext(), ids))
        }
    }

    // ── Folders ───────────────────────────────────────────────────────

    @GetMapping("/folders")
    fun listFolders(): Mono<List<KnowledgeFolderDto>> {
        return mono {
            val service = folderService ?: throw knowledgeNotEnabled()
            service.list(accessContext()).map { toFolderDto(it) }
        }
    }

    @PostMapping("/folders")
    fun createFolder(@RequestBody request: CreateFolderRequestDto): Mono<KnowledgeFolderDto> {
        return mono {
            val service = folderService ?: throw knowledgeNotEnabled()
            request.parentId?.takeIf { it.isNotBlank() }?.let { validateId(it) }
            val folder = service.create(accessContext(), request.parentId, request.name)
            // Creation only succeeds in the caller's write bucket, through the manage check.
            toFolderDto(AccessibleKnowledgeFolder(folder, canManage = true, writable = true))
        }
    }

    @PatchMapping("/folders/{id}")
    fun renameFolder(
        @PathVariable id: String,
        @RequestBody request: RenameFolderRequestDto,
    ): Mono<KnowledgeFolderDto> {
        return mono {
            val service = folderService ?: throw knowledgeNotEnabled()
            validateId(id)
            val folder = service.rename(accessContext(), id, request.name)
            toFolderDto(AccessibleKnowledgeFolder(folder, canManage = true, writable = true))
        }
    }

    @DeleteMapping("/folders/{id}")
    fun deleteFolder(@PathVariable id: String): Mono<Map<String, Boolean>> {
        return mono {
            val service = folderService ?: throw knowledgeNotEnabled()
            validateId(id)
            service.delete(accessContext(), id)
            mapOf("deleted" to true)
        }
    }

    // ── AI ────────────────────────────────────────────────────────────

    @PostMapping("/ai-rewrite")
    fun aiRewrite(@RequestBody request: AiRewriteRequestDto): Mono<AiResultDto> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            validateId(request.id)
            val result = service.aiRewrite(
                context = accessContext(),
                id = request.id,
                content = request.content,
                instruction = request.instruction,
                modelConfigId = request.modelConfigId,
                owners = currentOwners(),
            )
            AiResultDto(content = result)
        }
    }

    @PostMapping("/ai-modify-selection")
    fun aiModifySelection(@RequestBody request: AiModifySelectionRequestDto): Mono<AiResultDto> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            validateId(request.id)
            val result = service.aiModifySelection(
                context = accessContext(),
                id = request.id,
                content = request.content,
                selectedText = request.selectedText,
                instruction = request.instruction,
                modelConfigId = request.modelConfigId,
                owners = currentOwners(),
            )
            AiResultDto(content = result)
        }
    }

    @PostMapping("/ai-compose")
    fun aiCompose(@RequestBody request: AiComposeRequestDto): Mono<AiResultDto> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            request.sourceIds.forEach { validateId(it) }
            val result = service.aiCompose(
                context = accessContext(),
                sourceIds = request.sourceIds,
                guidance = request.guidance,
                style = request.style,
                modelConfigId = request.modelConfigId,
                owners = currentOwners(),
            )
            AiResultDto(content = result)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun knowledgeNotEnabled(): ResponseStatusException =
        ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Knowledge system is not enabled")

    private fun validateId(id: String) = KnowledgePathValidator.validateArticleId(id)

    private fun validateRelativePath(path: String) = KnowledgePathValidator.validateRelativePath(path)

    private fun badRequest(message: String): ResponseStatusException =
        ResponseStatusException(HttpStatus.BAD_REQUEST, message)

    /** Blank means "no filter"; a typo must fail instead of quietly listing everything. */
    private fun resolveStatusFilter(status: String?): ArticleStatus? {
        val value = status?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return ArticleStatus.fromFilter(value)
            ?: throw badRequest("Invalid status filter: $value (expected draft or published)")
    }

    private fun toArticleDto(
        item: AccessibleKnowledgeArticle,
        indexPending: Boolean = false,
    ): KnowledgeArticleDto = KnowledgeArticleDto(
        id = item.article.id,
        folderId = item.article.folderId,
        folderPath = item.folderPath,
        ownerId = item.article.userId,
        fileName = item.article.fileName,
        title = item.article.title,
        description = item.article.description,
        category = item.article.category,
        ext = item.article.ext,
        origin = item.article.origin,
        status = ArticleStatus.toWire(item.article.status),
        ragEnabled = item.article.ragEnabled,
        indexPending = indexPending,
        canWrite = item.canWrite,
        canManage = item.canManage,
        createdAt = item.article.createdAt,
        updatedAt = item.article.updatedAt,
    )

    private suspend fun toArticleDto(
        context: KnowledgeAccessContext,
        article: KnowledgeArticle,
        service: KnowledgeArticleService,
        indexPending: Boolean = false,
    ): KnowledgeArticleDto = toArticleDto(
        AccessibleKnowledgeArticle(
            article = article,
            folderPath = service.folderPathOf(article),
            canWrite = service.canWrite(context, article),
            canManage = service.canManage(context, article),
        ),
        indexPending,
    )

    private fun toFolderDto(item: AccessibleKnowledgeFolder): KnowledgeFolderDto = KnowledgeFolderDto(
        id = item.folder.id,
        parentId = item.folder.parentId,
        name = item.folder.name,
        ownerId = item.folder.userId,
        isDefault = item.folder.isDefault,
        writable = item.writable,
        createdAt = item.folder.createdAt,
        updatedAt = item.folder.updatedAt,
    )

    /**
     * Read a Part's content into a String without blocking the event loop.
     * Aggregation respects `spring.codec.max-in-memory-size`.
     */
    private suspend fun readPartContent(part: Part): String {
        val joined = DataBufferUtils.join(part.content()).awaitSingleOrNull() ?: return ""
        return try {
            val bytes = ByteArray(joined.readableByteCount())
            joined.read(bytes)
            String(bytes, Charsets.UTF_8)
        } finally {
            DataBufferUtils.release(joined)
        }
    }

    /** Parse a JSON array of strings: `["path1","path2"]`. */
    private fun parsePathsJson(json: String): List<String> = try {
        SharedObjectMapper.instance.readValue(json, Array<String>::class.java).toList()
    } catch (e: Exception) {
        logger.warn("Invalid paths JSON in knowledge upload: {}", e.message)
        throw badRequest("Invalid paths JSON: ${e.message}")
    }

    private companion object {
        const val MAX_INDEX_STATUS_IDS = 200
    }
}
