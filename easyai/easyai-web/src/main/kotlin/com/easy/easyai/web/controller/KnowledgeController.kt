package com.easy.easyai.web.controller

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.knowledge.ArticleStatus
import com.easy.easyai.core.knowledge.KnowledgeAccessContext
import com.easy.easyai.core.knowledge.KnowledgeAccessGuard
import com.easy.easyai.core.knowledge.KnowledgeArticle
import com.easy.easyai.core.knowledge.KnowledgeEntry
import com.easy.easyai.core.knowledge.KnowledgeOwnership
import com.easy.easyai.core.knowledge.KnowledgeStore
import com.easy.easyai.core.knowledge.KnowledgeUploadItem
import com.easy.easyai.web.model.*
import com.easy.easyai.web.security.currentGroupUserId
import com.easy.easyai.web.security.currentOwners
import com.easy.easyai.web.security.getCurrentUserId
import com.easy.easyai.web.service.AccessibleKnowledgeArticle
import com.easy.easyai.web.service.KnowledgeArticleService
import com.easy.easyai.web.service.KnowledgePathValidator
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
 * - DELETE /api/knowledge/source/{source} — Delete readable entries from a source
 *
 * Article editing endpoints (require KnowledgeArticleService):
 * - GET /api/knowledge/articles — List visible articles (DB-backed, optional status filter)
 * - POST /api/knowledge/save-text — Save a DRAFT article from text/chat
 * - PUT /api/knowledge/article — Update article content (published rows re-index)
 * - POST /api/knowledge/article/publish — Publish a draft / rebuild its index
 * - POST /api/knowledge/article/unpublish — Remove a published article from the index
 * - DELETE /api/knowledge/article — Delete article (RAG + OSS + DB)
 * - POST /api/knowledge/ai-rewrite — AI full article rewrite
 * - POST /api/knowledge/ai-modify-selection — AI modify selected text
 * - POST /api/knowledge/ai-compose — AI compose article from materials
 *
 * All content access goes through [KnowledgeAccessGuard] / [KnowledgeArticleService], which
 * delegate to the host-provided KnowledgeAccessPolicy. Listing endpoints answer an empty
 * collection when the policy denies view; single-entry lookups answer 404 instead of leaking
 * existence, including writes aimed at another user's draft, while other denied writes answer 403.
 */
@RestController
@RequestMapping("/api/knowledge")
class KnowledgeController(
    @param:Autowired(required = false) private val knowledgeStore: KnowledgeStore?,
    @param:Autowired(required = false) private val articleService: KnowledgeArticleService?,
    @param:Autowired(required = false) private val accessGuard: KnowledgeAccessGuard?,
    @param:Value("\${easyai.knowledge.shared-within-group:false}") private val sharedWithinGroup: Boolean = false
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private suspend fun knowledgeOwnerId(): String =
        if (sharedWithinGroup) {
            KnowledgeOwnership.ownerId(getCurrentUserId(), currentGroupUserId(), true)
        } else {
            KnowledgeOwnership.ownerId(getCurrentUserId(), null, false)
        }

    private suspend fun accessContext(): KnowledgeAccessContext = KnowledgeAccessContext(
        userId = getCurrentUserId(),
        groupUserId = currentGroupUserId(),
        owners = currentOwners(),
    )

    // ── Original RAG-backed endpoints ─────────────────────────────────

    @PostMapping("/upload", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadFiles(
        exchange: ServerWebExchange,
        @RequestParam(value = "source", required = false) source: String?,
        @RequestParam(value = "category", required = false) category: String?
    ): Mono<UploadResponseDto> {
        return exchange.multipartData.flatMap { multipartData ->
            mono {
                val service = articleService ?: throw knowledgeNotEnabled()
                val context = accessContext()

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

                // Validate before reading file bodies; the service repeats the check for callers
                // that do not come through this controller.
                paths.forEach { path -> validateRelativePath(path) }

                val resolvedSource = source?.takeIf { it.isNotBlank() } ?: "default"
                validateSource(resolvedSource)

                logger.info(
                    "Uploading {} file(s) for source '{}' with category '{}' by user '{}'",
                    fileParts.size, resolvedSource, category, context.userId
                )

                val items = fileParts.zip(paths).map { (filePart, path) ->
                    KnowledgeUploadItem(relativePath = path, content = readPartContent(filePart))
                }
                val results = service.uploadItems(context, resolvedSource, category, items)
                UploadResponseDto(
                    results = results.map { UploadResultDto(it.relativePath, it.success, it.key, it.reason) },
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
            val service = articleService
            val context = accessContext()
            if (service != null && !service.canView(context)) return@mono emptyList()
            val guard = accessGuard ?: throw knowledgeNotEnabled()
            val ownerId = knowledgeOwnerId()
            val entries = store.list(ownerId, source, category, q)
            val readable = guard.filterReadableEntries(context, ownerId, entries.map { it.key })
            entries.filter { it.key in readable }.map { toEntryDto(it) }
        }
    }

    @GetMapping("/sources")
    fun listSources(): Mono<List<String>> {
        return mono {
            val store = knowledgeStore ?: return@mono emptyList()
            val service = articleService
            val context = accessContext()
            if (service != null && !service.canView(context)) return@mono emptyList()
            val guard = accessGuard ?: throw knowledgeNotEnabled()
            val ownerId = knowledgeOwnerId()
            // Keys only: listing document bodies just to derive their sources is an N+1.
            val readable = guard.filterReadableEntries(context, ownerId, store.listKeys(ownerId))
            readable.map { it.substringBefore('/') }.distinct().sorted()
        }
    }

    @GetMapping("/detail")
    fun getDetail(@RequestParam key: String): Mono<KnowledgeDetailDto> {
        return mono {
            val store = knowledgeStore ?: throw knowledgeNotEnabled()
            val guard = accessGuard ?: throw knowledgeNotEnabled()
            val context = accessContext()
            validateRelativePath(key)
            if (!guard.canReadEntry(context, knowledgeOwnerId(), key)) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "Knowledge entry not found: $key")
            }
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
            val store = knowledgeStore ?: throw knowledgeNotEnabled()
            val guard = accessGuard ?: throw knowledgeNotEnabled()
            val context = accessContext()
            validateRelativePath(key)
            if (!guard.canManageEntry(context, knowledgeOwnerId(), key)) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "Knowledge entry not found: $key")
            }
            val deleted = store.delete(knowledgeOwnerId(), key)
            mapOf("deleted" to deleted)
        }
    }

    @DeleteMapping("/source/{source}")
    fun deleteSource(@PathVariable source: String): Mono<Map<String, Int>> {
        return mono {
            val store = knowledgeStore ?: throw knowledgeNotEnabled()
            val guard = accessGuard ?: throw knowledgeNotEnabled()
            val context = accessContext()
            validateSource(source)
            val ownerId = knowledgeOwnerId()
            val keys = store.listKeys(ownerId, source)
            val deletable = guard.filterManageableEntries(context, ownerId, keys)
            // Batch delete only the entries the caller may manage; deleteSource() would remove
            // entries that were just filtered out.
            mapOf("deleted" to store.deleteEntries(ownerId, deletable))
        }
    }

    // ── Article editing endpoints ─────────────────────────────────────

    @GetMapping("/articles")
    fun listArticles(
        @RequestParam(required = false) source: String?,
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) status: String?
    ): Mono<List<KnowledgeArticleDto>> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            // Same rule as /entries and /sources: a denied view yields an empty list, while
            // single-entry endpoints answer 404 instead of leaking existence.
            service.listAccessible(
                accessContext(), source, category, resolveStatusFilter(status)
            ).map { toArticleDto(it) }
        }
    }

    @PostMapping("/save-text")
    fun saveArticle(@RequestBody request: SaveArticleRequestDto): Mono<KnowledgeArticleDto> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            val context = accessContext()
            val article = service.saveArticle(
                context = context,
                title = request.title,
                content = request.content,
                source = request.source,
                relativePath = request.relativePath,
                category = request.category,
                origin = request.origin,
                originSessionId = request.originSessionId,
                originMessageId = request.originMessageId,
                composedFrom = request.composedFrom
            )
            toArticleDto(context, article, service)
        }
    }

    @PutMapping("/article")
    fun updateArticle(@RequestBody request: UpdateArticleRequestDto): Mono<KnowledgeArticleDto> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            validateRelativePath(request.key)
            val context = accessContext()
            val result = service.updateArticle(
                context = context,
                key = request.key,
                content = request.content,
                title = request.title,
                category = request.category
            )
            toArticleDto(context, result.article, service, result.indexPending)
        }
    }

    @PostMapping("/article/publish")
    fun publishArticle(@RequestBody request: PublishArticleRequestDto): Mono<KnowledgeArticleDto> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            validateRelativePath(request.key)
            val context = accessContext()
            val article = service.publish(
                context = context,
                key = request.key,
                category = request.category,
                title = request.title
            )
            toArticleDto(context, article, service)
        }
    }

    @PostMapping("/article/unpublish")
    fun unpublishArticle(@RequestParam key: String): Mono<KnowledgeArticleDto> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            validateRelativePath(key)
            val context = accessContext()
            val article = service.unpublish(context, key)
            toArticleDto(context, article, service)
        }
    }

    @DeleteMapping("/article")
    fun deleteArticle(@RequestParam key: String): Mono<Map<String, Boolean>> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            validateRelativePath(key)
            val deleted = service.deleteArticle(accessContext(), key)
            mapOf("deleted" to deleted)
        }
    }

    @GetMapping("/article/content")
    fun getArticleContent(@RequestParam key: String): Mono<Map<String, String>> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            validateRelativePath(key)
            val content = service.readContent(accessContext(), key)
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
                context = accessContext(),
                key = request.key,
                content = request.content,
                instruction = request.instruction,
                modelConfigId = request.modelConfigId,
                owners = currentOwners()
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
                context = accessContext(),
                key = request.key,
                content = request.content,
                selectedText = request.selectedText,
                instruction = request.instruction,
                modelConfigId = request.modelConfigId,
                owners = currentOwners()
            )
            AiResultDto(content = result)
        }
    }

    @PostMapping("/ai-compose")
    fun aiCompose(@RequestBody request: AiComposeRequestDto): Mono<AiResultDto> {
        return mono {
            val service = articleService ?: throw knowledgeNotEnabled()
            val result = service.aiCompose(
                context = accessContext(),
                sourceKeys = request.sourceKeys,
                guidance = request.guidance,
                style = request.style,
                modelConfigId = request.modelConfigId,
                owners = currentOwners()
            )
            AiResultDto(content = result)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun knowledgeNotEnabled(): ResponseStatusException =
        ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Knowledge system is not enabled")

    /** Delegates to the service-boundary validator so HTTP and tool callers share one rule. */
    private fun validateRelativePath(path: String) = KnowledgePathValidator.validateRelativePath(path)

    /**
     * Validate a source label: single path segment without separators or traversal.
     */
    private fun validateSource(source: String) = KnowledgePathValidator.validateSource(source)

    private fun badRequest(message: String): ResponseStatusException =
        ResponseStatusException(HttpStatus.BAD_REQUEST, message)

    /** Blank means "no filter"; a typo must fail instead of quietly listing everything. */
    private fun resolveStatusFilter(status: String?): ArticleStatus? {
        val value = status?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return ArticleStatus.fromFilter(value)
            ?: throw badRequest("Invalid status filter: $value (expected draft or published)")
    }

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

    private fun toArticleDto(item: AccessibleKnowledgeArticle): KnowledgeArticleDto =
        toArticleDto(item.article, item.canWrite, item.canManage)

    private suspend fun toArticleDto(
        context: KnowledgeAccessContext,
        article: KnowledgeArticle,
        service: KnowledgeArticleService,
        indexPending: Boolean = false,
    ): KnowledgeArticleDto = toArticleDto(
        article = article,
        canWrite = service.canWrite(context, article),
        canManage = service.canManage(context, article),
        indexPending = indexPending,
    )

    private fun toArticleDto(
        article: KnowledgeArticle,
        canWrite: Boolean,
        canManage: Boolean,
        indexPending: Boolean = false,
    ): KnowledgeArticleDto = KnowledgeArticleDto(
        id = article.id,
        knowledgeKey = article.knowledgeKey,
        title = article.title,
        description = article.description,
        category = article.category,
        source = article.source,
        relativePath = article.relativePath,
        ext = article.ext,
        origin = article.origin,
        status = ArticleStatus.toWire(article.status),
        indexPending = indexPending,
        canWrite = canWrite,
        canManage = canManage,
        createdAt = article.createdAt,
        updatedAt = article.updatedAt
    )
}
