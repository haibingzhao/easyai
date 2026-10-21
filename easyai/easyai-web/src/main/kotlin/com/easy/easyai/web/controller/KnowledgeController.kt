package com.easy.easyai.web.controller

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.knowledge.KnowledgeEntry
import com.easy.easyai.core.knowledge.KnowledgeOwnership
import com.easy.easyai.core.knowledge.KnowledgeStore
import com.easy.easyai.core.knowledge.KnowledgeUploadItem
import com.easy.easyai.web.model.*
import com.easy.easyai.web.security.currentGroupUserId
import com.easy.easyai.web.security.getCurrentUserId
import com.easy.easyai.web.service.KnowledgeArticleService
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.multipart.FilePart
import org.springframework.http.codec.multipart.Part
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * REST controller for knowledge base operations.
 *
 * Endpoints:
 * - POST /api/knowledge/upload — Upload files to the knowledge base
 * - GET /api/knowledge — List knowledge entries (optional source/category/query filter)
 * - GET /api/knowledge/sources — List distinct sources
 * - GET /api/knowledge/detail — Get full detail with relationships
 * - DELETE /api/knowledge — Delete a single entry by key
 * - DELETE /api/knowledge/source/{source} — Delete all entries from a source
 *
 * Article editing endpoints (require KnowledgeArticleService):
 * - GET /api/knowledge/articles — List visible articles (DB-backed)
 * - POST /api/knowledge/save-text — Save article from text/chat
 * - PUT /api/knowledge/article — Update article content
 * - DELETE /api/knowledge/article — Delete article (DB + OSS + RAG)
 * - POST /api/knowledge/ai-rewrite — AI full article rewrite
 * - POST /api/knowledge/ai-modify-selection — AI modify selected text
 * - POST /api/knowledge/ai-compose — AI compose article from materials
 * - POST /api/knowledge/access — Grant access to article
 * - DELETE /api/knowledge/access — Revoke access
 * - GET /api/knowledge/access/{articleId} — List access grants
 * - PUT /api/knowledge/visibility — Update article visibility
 */
@RestController
@RequestMapping("/api/knowledge")
class KnowledgeController(
    @param:Autowired(required = false) private val knowledgeStore: KnowledgeStore?,
    @param:Autowired(required = false) private val articleService: KnowledgeArticleService?,
    @param:Value("\${easyai.knowledge.shared-within-group:false}") private val sharedWithinGroup: Boolean = false
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private suspend fun knowledgeOwnerId(): String =
        if (sharedWithinGroup) {
            KnowledgeOwnership.ownerId(getCurrentUserId(), currentGroupUserId(), true)
        } else {
            KnowledgeOwnership.ownerId(getCurrentUserId(), null, false)
        }

    // ── Original RAG-backed endpoints ─────────────────────────────────

    @PostMapping("/upload", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadFiles(
        exchange: ServerWebExchange,
        @RequestParam(value = "source", required = false) source: String?,
        @RequestParam(value = "category", required = false) category: String?,
        @RequestParam(value = "visibility", required = false, defaultValue = "private") visibility: String
    ): Mono<UploadResponseDto> {
        return exchange.multipartData.flatMap { multipartData ->
            mono {
                val store = knowledgeStore ?: throw knowledgeNotEnabled()
                val userId = getCurrentUserId()
                val ownerId = knowledgeOwnerId()

                val fileParts = multipartData["files"]?.filterIsInstance<FilePart>() ?: emptyList()
                val pathsPart = multipartData["paths"]?.firstOrNull()
                val pathsJson = if (pathsPart != null) readPartContent(pathsPart) else "[]"
                val paths = parsePathsJson(pathsJson)

                if (paths.size != fileParts.size) {
                    throw ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "paths array size (${paths.size}) does not match files count (${fileParts.size})"
                    )
                }

                paths.forEach { path -> validateRelativePath(path) }

                val resolvedSource = source?.takeIf { it.isNotBlank() } ?: "default"
                validateSource(resolvedSource)

                logger.info("Uploading {} file(s) for source '{}' with category '{}' by user '{}'", fileParts.size, resolvedSource, category, userId)

                val items = fileParts.zip(paths).map { (filePart, path) ->
                    val content = readPartContent(filePart)
                    KnowledgeUploadItem(relativePath = path, content = content)
                }

                val results = store.uploadBatch(ownerId, resolvedSource, items, category)

                // Also save to DB + OSS when article service is available
                if (articleService != null) {
                    for ((item, result) in items.zip(results)) {
                        if (result.success) {
                            try {
                                val title = item.content.lines().firstOrNull { it.startsWith("#") }
                                    ?.trimStart('#')?.trim()
                                    ?: item.relativePath.substringAfterLast('/').substringBeforeLast('.')
                                articleService.saveArticle(
                                    userId = userId,
                                    groupUserId = currentGroupUserId(),
                                    title = title,
                                    content = item.content,
                                    source = resolvedSource,
                                    relativePath = item.relativePath,
                                    category = category ?: "other",
                                    visibility = visibility,
                                    origin = "upload"
                                )
                            } catch (e: Exception) {
                                logger.warn("Failed to save article metadata for {}: {}", item.relativePath, e.message)
                            }
                        }
                    }
                }

                val dtoResults = results.map { r ->
                    UploadResultDto(
                        relativePath = r.relativePath,
                        success = r.success,
                        key = r.key,
                        reason = r.reason
                    )
                }
                UploadResponseDto(
                    results = dtoResults,
                    totalFiles = results.size,
                    successCount = results.count { it.success },
                    failedCount = results.count { !it.success }
                )
            }
        }
    }

    @GetMapping
    fun listEntries(
        @RequestParam(required = false) source: String?,
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) q: String?
    ): Mono<List<KnowledgeEntryDto>> {
        return mono {
            val store = knowledgeStore ?: return@mono emptyList()
            store.list(knowledgeOwnerId(), source, category, q).map { toEntryDto(it) }
        }
    }

    @GetMapping("/sources")
    fun listSources(): Mono<List<String>> {
        return mono {
            val store = knowledgeStore ?: return@mono emptyList()
            store.sources(knowledgeOwnerId())
        }
    }

    @GetMapping("/detail")
    fun getDetail(@RequestParam key: String): Mono<KnowledgeDetailDto> {
        return mono {
            val store = knowledgeStore
                ?: throw knowledgeNotEnabled()
            validateRelativePath(key)
            val detail = store.detail(knowledgeOwnerId(), key)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Knowledge entry not found: $key")
            KnowledgeDetailDto(
                entry = toEntryDto(detail.entry),
                fullContent = detail.fullContent,
                toc = detail.toc,
                parent = detail.parent,
                children = detail.children,
                related = detail.related
            )
        }
    }

    @DeleteMapping
    fun deleteEntry(@RequestParam key: String): Mono<Map<String, Boolean>> {
        return mono {
            val store = knowledgeStore
                ?: throw knowledgeNotEnabled()
            validateRelativePath(key)
            val deleted = store.delete(knowledgeOwnerId(), key)
            mapOf("deleted" to deleted)
        }
    }

    @DeleteMapping("/source/{source}")
    fun deleteSource(@PathVariable source: String): Mono<Map<String, Int>> {
        return mono {
            val store = knowledgeStore
                ?: throw knowledgeNotEnabled()
            validateSource(source)
            val count = store.deleteSource(knowledgeOwnerId(), source)
            mapOf("deleted" to count)
        }
    }

    // ── Article editing endpoints ─────────────────────────────────────

    @GetMapping("/articles")
    fun listArticles(
        @RequestParam(required = false) source: String?,
        @RequestParam(required = false) category: String?
    ): Mono<List<KnowledgeArticleDto>> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            val userId = getCurrentUserId()
            val groupUserId = currentGroupUserId()
            service.listVisible(userId, groupUserId, source, category).map { article ->
                KnowledgeArticleDto(
                    id = article.id,
                    knowledgeKey = article.knowledgeKey,
                    title = article.title,
                    description = article.description,
                    category = article.category,
                    source = article.source,
                    relativePath = article.relativePath,
                    ext = article.ext,
                    visibility = article.visibility,
                    origin = article.origin,
                    canWrite = service.canWrite(article, userId, groupUserId),
                    createdAt = article.createdAt,
                    updatedAt = article.updatedAt
                )
            }
        }
    }

    @PostMapping("/save-text")
    fun saveArticle(@RequestBody request: SaveArticleRequestDto): Mono<KnowledgeArticleDto> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            val userId = getCurrentUserId()
            val groupUserId = currentGroupUserId()
            val relativePath = request.relativePath
                ?: "${request.title.lowercase().replace(Regex("[^a-z0-9\\u4e00-\\u9fff]+"), "-").trim('-')}.md"

            val article = service.saveArticle(
                userId = userId,
                groupUserId = groupUserId,
                title = request.title,
                content = request.content,
                source = request.source,
                relativePath = relativePath,
                category = request.category,
                visibility = request.visibility,
                origin = request.origin,
                originSessionId = request.originSessionId,
                originMessageId = request.originMessageId,
                composedFrom = request.composedFrom
            )
            KnowledgeArticleDto(
                id = article.id,
                knowledgeKey = article.knowledgeKey,
                title = article.title,
                description = article.description,
                category = article.category,
                source = article.source,
                relativePath = article.relativePath,
                ext = article.ext,
                visibility = article.visibility,
                origin = article.origin,
                canWrite = true,
                createdAt = article.createdAt,
                updatedAt = article.updatedAt
            )
        }
    }

    @PutMapping("/article")
    fun updateArticle(@RequestBody request: UpdateArticleRequestDto): Mono<KnowledgeArticleDto> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            validateRelativePath(request.key)
            val userId = getCurrentUserId()
            val groupUserId = currentGroupUserId()
            val article = service.updateArticle(
                userId = userId,
                groupUserId = groupUserId,
                key = request.key,
                content = request.content,
                title = request.title,
                category = request.category
            )
            KnowledgeArticleDto(
                id = article.id,
                knowledgeKey = article.knowledgeKey,
                title = article.title,
                description = article.description,
                category = article.category,
                source = article.source,
                relativePath = article.relativePath,
                ext = article.ext,
                visibility = article.visibility,
                origin = article.origin,
                canWrite = true,
                createdAt = article.createdAt,
                updatedAt = article.updatedAt
            )
        }
    }

    @DeleteMapping("/article")
    fun deleteArticle(@RequestParam key: String): Mono<Map<String, Boolean>> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            validateRelativePath(key)
            val deleted = service.deleteArticle(getCurrentUserId(), currentGroupUserId(), key)
            mapOf("deleted" to deleted)
        }
    }

    @GetMapping("/article/content")
    fun getArticleContent(@RequestParam key: String): Mono<Map<String, String>> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            validateRelativePath(key)
            val content = service.readContent(getCurrentUserId(), currentGroupUserId(), key)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Article content not found: $key")
            mapOf("content" to content)
        }
    }

    @PostMapping("/ai-rewrite")
    fun aiRewrite(@RequestBody request: AiRewriteRequestDto): Mono<AiResultDto> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            validateRelativePath(request.key)
            val result = service.aiRewrite(
                userId = getCurrentUserId(),
                groupUserId = currentGroupUserId(),
                key = request.key,
                instruction = request.instruction,
                modelConfigId = request.modelConfigId
            )
            AiResultDto(content = result)
        }
    }

    @PostMapping("/ai-modify-selection")
    fun aiModifySelection(@RequestBody request: AiModifySelectionRequestDto): Mono<AiResultDto> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            validateRelativePath(request.key)
            val result = service.aiModifySelection(
                userId = getCurrentUserId(),
                groupUserId = currentGroupUserId(),
                key = request.key,
                selectedText = request.selectedText,
                instruction = request.instruction,
                modelConfigId = request.modelConfigId
            )
            AiResultDto(content = result)
        }
    }

    @PostMapping("/ai-compose")
    fun aiCompose(@RequestBody request: AiComposeRequestDto): Mono<AiResultDto> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            val result = service.aiCompose(
                userId = getCurrentUserId(),
                groupUserId = currentGroupUserId(),
                sourceKeys = request.sourceKeys,
                guidance = request.guidance,
                style = request.style,
                modelConfigId = request.modelConfigId
            )
            AiResultDto(content = result)
        }
    }

    @PostMapping("/access")
    fun grantAccess(@RequestBody request: GrantAccessRequestDto): Mono<Map<String, Boolean>> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            service.grantAccess(
                articleId = request.articleId,
                granteeId = request.granteeId,
                permission = request.permission,
                grantedBy = getCurrentUserId()
            )
            mapOf("success" to true)
        }
    }

    @DeleteMapping("/access")
    fun revokeAccess(
        @RequestParam articleId: String,
        @RequestParam granteeId: String
    ): Mono<Map<String, Boolean>> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            service.revokeAccess(articleId, granteeId, getCurrentUserId())
            mapOf("success" to true)
        }
    }

    @GetMapping("/access/{articleId}")
    fun listAccess(@PathVariable articleId: String): Mono<List<KnowledgeAccessDto>> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            service.listAccess(articleId, getCurrentUserId()).map { access ->
                KnowledgeAccessDto(
                    id = access.id,
                    articleId = access.articleId,
                    granteeId = access.granteeId,
                    permission = access.permission,
                    grantedBy = access.grantedBy,
                    grantedAt = access.grantedAt
                )
            }
        }
    }

    @PutMapping("/visibility")
    fun updateVisibility(@RequestBody request: UpdateVisibilityRequestDto): Mono<Map<String, Boolean>> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            service.updateVisibility(request.articleId, request.visibility, getCurrentUserId())
            mapOf("success" to true)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun knowledgeNotEnabled(): ResponseStatusException =
        ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Knowledge system is not enabled")

    /**
     * Validate that a path is relative and does not contain traversal sequences.
     */
    private fun validateRelativePath(path: String) {
        if (path.isBlank()) throw badRequest("Path must not be blank")
        if (path.startsWith("/")) throw badRequest("Path must be relative, not absolute: $path")
        if (path.contains("..")) throw badRequest("Path must not contain '..': $path")
        if (path.contains("\\")) throw badRequest("Path must not contain backslash: $path")
    }

    /**
     * Validate a source label: single path segment without separators or traversal.
     */
    private fun validateSource(source: String) {
        if (source.isBlank()) throw badRequest("Source must not be blank")
        if (source.contains("/")) throw badRequest("Source must not contain '/': $source")
        if (source.contains("..")) throw badRequest("Source must not contain '..': $source")
        if (source.contains("\\")) throw badRequest("Source must not contain backslash: $source")
    }

    private fun badRequest(message: String): ResponseStatusException =
        ResponseStatusException(HttpStatus.BAD_REQUEST, message)

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

    /**
     * Parse a JSON array of strings: `["path1","path2"]`.
     */
    private fun parsePathsJson(json: String): List<String> = try {
        SharedObjectMapper.instance.readValue(json, Array<String>::class.java).toList()
    } catch (e: Exception) {
        logger.warn("Invalid paths JSON in knowledge upload: {}", e.message)
        throw badRequest("Invalid paths JSON: ${e.message}")
    }

    private fun toEntryDto(e: KnowledgeEntry): KnowledgeEntryDto =
        KnowledgeEntryDto(
            key = e.key,
            source = e.source,
            relativePath = e.relativePath,
            title = e.title,
            description = e.description,
            category = e.kcategory,
            ext = e.ext,
            contentPreview = e.content,
            updatedAt = e.updatedAt,
            chunksCount = e.chunksCount
        )
}
