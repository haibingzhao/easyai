package com.easy.easyai.web.controller

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.knowledge.KnowledgeEntry
import com.easy.easyai.core.knowledge.KnowledgeStore
import com.easy.easyai.core.knowledge.KnowledgeUploadItem
import com.easy.easyai.web.model.KnowledgeDetailDto
import com.easy.easyai.web.model.KnowledgeEntryDto
import com.easy.easyai.web.model.UploadResponseDto
import com.easy.easyai.web.model.UploadResultDto
import com.easy.easyai.web.security.getCurrentUserId
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
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
 */
@RestController
@RequestMapping("/api/knowledge")
class KnowledgeController(
    @param:Autowired(required = false) private val knowledgeStore: KnowledgeStore?
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @PostMapping("/upload", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadFiles(
        exchange: ServerWebExchange,
        @RequestParam(value = "source", required = false) source: String?,
        @RequestParam(value = "category", required = false) category: String?
    ): Mono<UploadResponseDto> {
        return exchange.multipartData.flatMap { multipartData ->
            mono {
                val store = knowledgeStore ?: throw knowledgeNotEnabled()
                val userId = getCurrentUserId()

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

                val results = store.uploadBatch(userId, resolvedSource, items, category)
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
            val userId = getCurrentUserId()
            store.list(userId, source, category, q).map { toEntryDto(it) }
        }
    }

    @GetMapping("/sources")
    fun listSources(): Mono<List<String>> {
        return mono {
            val store = knowledgeStore ?: return@mono emptyList()
            val userId = getCurrentUserId()
            store.sources(userId)
        }
    }

    @GetMapping("/detail")
    fun getDetail(@RequestParam key: String): Mono<KnowledgeDetailDto> {
        return mono {
            val store = knowledgeStore
                ?: throw knowledgeNotEnabled()
            validateRelativePath(key)
            val userId = getCurrentUserId()
            val detail = store.detail(userId, key)
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
            val userId = getCurrentUserId()
            val deleted = store.delete(userId, key)
            mapOf("deleted" to deleted)
        }
    }

    @DeleteMapping("/source/{source}")
    fun deleteSource(@PathVariable source: String): Mono<Map<String, Int>> {
        return mono {
            val store = knowledgeStore
                ?: throw knowledgeNotEnabled()
            validateSource(source)
            val userId = getCurrentUserId()
            val count = store.deleteSource(userId, source)
            mapOf("deleted" to count)
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
