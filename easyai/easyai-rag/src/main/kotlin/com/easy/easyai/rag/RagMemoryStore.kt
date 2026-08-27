package com.easy.easyai.rag

import com.easy.easyai.core.domain.DomainCatalog
import com.easy.easyai.core.memory.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * [MemoryStore] implementation that stores memories entirely in EasyRAG
 * (no local folder storage). Each memory entry is one EasyRAG document:
 *
 * - isolation: EasyRAG `biz_id` slices derived by [RagBizIdResolver]
 *   (GLOBAL -> user slice, PROJECT -> user + project slice)
 * - key layout: `{type}/{name}.md` (e.g. `experience_lessons/frp-setup.md`)
 * - externalId: `easyai:{key}` (idempotent upsert, deterministic docId)
 * - content: YAML-frontmatter Markdown format (frontmatter carries description, type,
 *   keywords, maturity, scenarios, created/updated dates)
 * - writes submit indexing fire-and-forget (no polling): the write returns as soon as
 *   the document is stored and indexing is triggered server-side; entries become
 *   searchable once the server finishes vectorization / knowledge-graph building
 *
 * PROJECT operations without a project path degrade: reads return empty,
 * writes raise [MemoryBackendException].
 *
 * [RagException] failures are wrapped in [MemoryBackendException] so callers
 * surface the outage instead of silently losing memories.
 */
internal class RagMemoryStore(
    private val client: RagClient
) : MemoryStore {

    private val logger = LoggerFactory.getLogger(RagMemoryStore::class.java)

    // ── loadAll ────────────────────────────────────────────────────────

    override suspend fun loadAll(
        scope: MemoryScope,
        owner: MemoryOwnerContext,
        totalCharLimit: Int,
        perFileCharLimit: Int
    ): String = translateBackendErrors("loadAll") {
        val bizId = bizIdOf(scope, owner) ?: return@translateBackendErrors ""
        val entries = list(scope, owner)
        if (entries.isEmpty()) return@translateBackendErrors ""

        val indexSection = buildIndexSection(entries)
        val contentBudget = (totalCharLimit - indexSection.length).coerceAtLeast(0)
        val contentSection = if (contentBudget > 0) {
            buildContentSection(entries, contentBudget, perFileCharLimit)
        } else null

        buildString {
            appendLine(indexSection)
            if (contentSection != null) {
                appendLine()
                append(contentSection)
            } else {
                appendLine()
                appendLine("Memory index shown. Use memory_read to read full content of specific entries.")
            }
        }
    }

    private fun buildIndexSection(entries: List<MemoryEntry>): String = buildString {
        appendLine("## Memory Index")
        entries
            .sortedBy { it.type.dirName }
            .forEach { entry ->
                appendLine("- [${entry.name}](${entry.path}) — ${entry.description}")
            }
    }

    private fun buildContentSection(
        entries: List<MemoryEntry>,
        totalCharLimit: Int,
        perFileCharLimit: Int
    ): String? {
        val sb = StringBuilder()
        sb.appendLine("## Memory Content")
        var totalChars = sb.length

        for ((index, entry) in entries.withIndex()) {
            val truncated = if (entry.content.length > perFileCharLimit) {
                entry.content.take(perFileCharLimit) + "\n...[truncated]"
            } else {
                entry.content
            }
            val block = "[${entry.path}]\n$truncated\n\n"
            if (totalChars + block.length > totalCharLimit) {
                return if (index == 0) null else sb.toString()
            }
            sb.append(block)
            totalChars += block.length
        }
        return sb.toString()
    }

    // ── search ─────────────────────────────────────────────────────────

    override suspend fun search(
        query: String,
        scope: MemoryScope,
        owner: MemoryOwnerContext,
        limit: Int,
        timeRangeStart: Long?,
        timeRangeEnd: Long?
    ): List<MemoryEntry> = translateBackendErrors("search") {
        val bizId = bizIdOf(scope, owner) ?: return@translateBackendErrors emptyList()
        val chunks = client.search(
            query = query,
            topK = limit,
            timeRangeStart = timeRangeStart,
            timeRangeEnd = timeRangeEnd,
            bizId = bizId
        )
        chunks.mapNotNull { chunk -> parseChunkToEntry(chunk.content, chunk.filePath) }
            .distinctBy { "${it.type.dirName}/${it.name}" }
    }

    // ── write ──────────────────────────────────────────────────────────

    override suspend fun write(entry: MemoryEntry, scope: MemoryScope, owner: MemoryOwnerContext): Path =
        translateBackendErrors("write") {
            val bizId = requireBizId(scope, owner, "write")
            val key = keyOf(entry.path)
            val doc = RagDocument(
                key = key,
                content = buildFileContent(entry),
                metadata = buildMap {
                    put("type", entry.type.dirName)
                    put("name", entry.name)
                    put("description", entry.description)
                    entry.maturity?.let { put("maturity", it.apiName) }
                },
                createTime = createTimeOf(entry),
                // Memory entries are Markdown: chunk by heading structure, build the
                // knowledge graph (skipKg=false) and the structure index (TOC + summaries).
                options = RagProcessingOptions(
                    chunkMethod = CHUNK_METHOD_STRUCTURE_AWARE,
                    skipKg = false,
                    buildStructure = true
                )
            )
            // Fire-and-forget: memory writes must not block on indexing confirmation;
            // vectorization / knowledge-graph building continues server-side.
            client.upsert(doc, bizId, awaitIndexing = false)
            logger.debug("Memory entry written to RAG (indexing submitted): {} (bizId={})", doc.externalId, bizId)
            Path.of(doc.filePath)
        }

    // ── read ───────────────────────────────────────────────────────────

    override suspend fun read(path: String, scope: MemoryScope, owner: MemoryOwnerContext): String? =
        translateBackendErrors("read") {
            val bizId = bizIdOf(scope, owner) ?: return@translateBackendErrors null
            val detail = client.readByExternalId(
                RagConstants.externalIdOf(keyOf(path)), bizId
            )
            detail?.content
        }

    // ── delete / deleteAll ─────────────────────────────────────────────

    override suspend fun delete(path: String, scope: MemoryScope, owner: MemoryOwnerContext): Boolean =
        translateBackendErrors("delete") {
            val bizId = requireBizId(scope, owner, "delete")
            val externalId = RagConstants.externalIdOf(keyOf(path))
            if (client.readByExternalId(externalId, bizId) == null) {
                return@translateBackendErrors false
            }
            client.delete(externalId, bizId)
            logger.debug("Memory entry deleted from RAG: {}", externalId)
            true
        }

    override suspend fun deleteAll(scope: MemoryScope, owner: MemoryOwnerContext): Int =
        translateBackendErrors("deleteAll") {
            val bizId = bizIdOf(scope, owner) ?: return@translateBackendErrors 0
            val docs = client.list(listPrefix(null), bizId)
            val docIds = docs.map { it.docId }
            if (docIds.isEmpty()) return@translateBackendErrors 0
            val deleted = client.batchDelete(docIds, bizId)
            logger.debug("Deleted {} memory entries from RAG for scope {}", deleted, scope)
            deleted
        }

    // ── list ───────────────────────────────────────────────────────────

    override suspend fun list(scope: MemoryScope, owner: MemoryOwnerContext, type: MemoryType?): List<MemoryEntry> =
        translateBackendErrors("list") {
            val bizId = bizIdOf(scope, owner) ?: return@translateBackendErrors emptyList()
            val docs = client.list(listPrefix(type), bizId)
            val externalIds = docs.mapNotNull { it.externalId }.distinct()
            coroutineScope {
                externalIds.map { externalId ->
                    async { client.readByExternalId(externalId, bizId) }
                }.awaitAll()
            }.mapNotNull { detail ->
                val content = detail?.content ?: return@mapNotNull null
                parseChunkToEntry(content, detail.filePath)
            }
        }

    // ── exists / findByName ────────────────────────────────────────────

    override suspend fun exists(name: String, scope: MemoryScope, owner: MemoryOwnerContext): Boolean =
        findByName(name, scope, owner) != null

    override suspend fun findByName(name: String, scope: MemoryScope, owner: MemoryOwnerContext): MemoryEntry? =
        translateBackendErrors("findByName") {
            val bizId = bizIdOf(scope, owner) ?: return@translateBackendErrors null
            for (type in MemoryType.entriesFor(DomainCatalog.activeDomain)) {
                val key = "${type.dirName}/$name.md"
                val detail = client.readByExternalId(RagConstants.externalIdOf(key), bizId)
                    ?: continue
                val content = detail.content ?: continue
                parseChunkToEntry(content, detail.filePath)?.let { return@translateBackendErrors it }
            }
            null
        }

    // ── refreshIndex ───────────────────────────────────────────────────

    override suspend fun refreshIndex(scope: MemoryScope) {
        // No-op: RAG retrieval is semantic; there is no MEMORY.md index to maintain.
    }

    // ── Mapping helpers ────────────────────────────────────────────────

    /** Derive the EasyRAG biz_id slice; null when PROJECT scope lacks a project path. */
    private fun bizIdOf(scope: MemoryScope, owner: MemoryOwnerContext): String? = when (scope) {
        MemoryScope.GLOBAL -> RagBizIdResolver.globalBizId(owner.userId, RagBizIdResolver.MEMORY_TYPE)
        MemoryScope.PROJECT -> RagBizIdResolver.projectBizId(owner.userId, owner.projectPath, RagBizIdResolver.MEMORY_TYPE)
    }

    /** Like [bizIdOf] but fails mutating operations that lack a PROJECT context. */
    private fun requireBizId(scope: MemoryScope, owner: MemoryOwnerContext, operation: String): String {
        if (scope == MemoryScope.PROJECT && owner.projectPath == null) {
            throw MemoryBackendException("Memory $operation requires a project path for PROJECT scope")
        }
        return bizIdOf(scope, owner)
            ?: throw MemoryBackendException("Memory $operation cannot resolve biz_id for scope $scope")
    }

    private fun keyOf(path: String): String = path.trimStart('/')

    private fun listPrefix(type: MemoryType?): String {
        val base = "${RagConstants.FILE_PATH_ROOT}/"
        return if (type != null) "$base${type.dirName}/" else base
    }

    private fun createTimeOf(entry: MemoryEntry): Long {
        val date = entry.updated ?: LocalDate.now()
        return date.atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
    }

    /**
     * Parse a stored memory document (frontmatter + body) into a [MemoryEntry].
     * [filePath] is the EasyRAG logical path `easyai/{type}/{name}.md`,
     * used as fallback source for type/name when frontmatter is missing.
     */
    private fun parseChunkToEntry(content: String, filePath: String?): MemoryEntry? {
        val relativePath = relativePathOf(filePath)
        val (frontmatter, body) = splitFrontmatter(content)
        val meta = parseFrontmatter(frontmatter)
        if (meta.isEmpty() && relativePath == null) {
            logger.debug("Skipping RAG chunk without frontmatter or derivable path: {}", filePath)
            return null
        }

        val fallbackType = relativePath?.substringBefore('/')?.let { MemoryType.fromDirName(it) }
        val fallbackName = relativePath?.substringAfterLast('/')?.removeSuffix(".md")
        val type = meta["type"]?.let { MemoryType.fromDirName(it) } ?: fallbackType ?: MemoryType.OTHER
        val name = meta["name"] ?: fallbackName ?: return null
        val keywords = meta["keywords"]?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        val scenarios = meta["scenarios"]?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        val maturity = meta["maturity"]?.let { MemoryMaturity.fromApiName(it) }
        val created = meta["created"]?.let { runCatching { LocalDate.parse(it, DATE_FMT) }.getOrNull() }
        val updated = meta["updated"]?.let { runCatching { LocalDate.parse(it, DATE_FMT) }.getOrNull() }

        return MemoryEntry(
            name = name,
            description = meta["description"] ?: "",
            type = type,
            content = body.trim(),
            path = "${type.dirName}/$name.md",
            keywords = keywords,
            created = created,
            updated = updated,
            maturity = maturity,
            scenarios = scenarios
        )
    }

    /** Strip the `easyai/` prefix, returning `{type}/{name}.md` or null. */
    private fun relativePathOf(filePath: String?): String? {
        if (filePath == null) return null
        val prefix = "${RagConstants.FILE_PATH_ROOT}/"
        if (!filePath.startsWith(prefix)) return null
        val relative = filePath.removePrefix(prefix)
        return if (relative.contains('/')) relative else null
    }

    /** Split document text into frontmatter (between --- delimiters) and body. */
    private fun splitFrontmatter(text: String): Pair<String, String> {
        val lines = text.lines()
        if (lines.isEmpty() || lines[0].trim() != FRONTMATTER_DELIMITER) {
            return "" to text
        }
        val endIndex = lines.drop(1).indexOfFirst { it.trim() == FRONTMATTER_DELIMITER }
        if (endIndex < 0) return "" to text
        val frontmatter = lines.subList(1, endIndex + 1).joinToString("\n")
        val body = lines.subList(endIndex + 2, lines.size).joinToString("\n")
        return frontmatter to body
    }

    /** Parse simple flat `key: value` YAML frontmatter, stripping surrounding quotes. */
    private fun parseFrontmatter(frontmatter: String): Map<String, String> {
        if (frontmatter.isBlank()) return emptyMap()
        return frontmatter.lines()
            .filter { it.contains(':') }
            .associate { line ->
                val colonIndex = line.indexOf(':')
                val key = line.substring(0, colonIndex).trim()
                var value = line.substring(colonIndex + 1).trim()
                if (value.length >= 2 &&
                    ((value.startsWith("\"") && value.endsWith("\"")) ||
                        (value.startsWith("'") && value.endsWith("'")))
                ) {
                    value = value.substring(1, value.length - 1)
                        .replace("\\\"", "\"")
                }
                key to value
            }
    }

    /** Build the stored document: YAML frontmatter + Markdown body (same as file store). */
    private fun buildFileContent(entry: MemoryEntry): String = buildString {
        appendLine(FRONTMATTER_DELIMITER)
        appendLine("name: ${quoteYamlValue(entry.name)}")
        appendLine("description: ${quoteYamlValue(entry.description)}")
        appendLine("type: ${entry.type.dirName}")
        if (entry.keywords.isNotEmpty()) {
            appendLine("keywords: ${entry.keywords.joinToString(", ") { quoteYamlValue(it) }}")
        }
        if (entry.scenarios.isNotEmpty()) {
            appendLine("scenarios: ${entry.scenarios.joinToString(", ") { quoteYamlValue(it) }}")
        }
        entry.maturity?.let { appendLine("maturity: ${it.apiName}") }
        appendLine("created: ${(entry.created ?: LocalDate.now()).format(DATE_FMT)}")
        appendLine("updated: ${(entry.updated ?: LocalDate.now()).format(DATE_FMT)}")
        appendLine(FRONTMATTER_DELIMITER)
        appendLine()
        append(entry.content)
    }

    private fun quoteYamlValue(value: String): String {
        val sanitized = value.replace("\n", " ").replace("\r", "")
        val needsQuoting = sanitized.any { it in ":#\"{}[]&*!|>%@" }
        return if (needsQuoting) {
            "\"${sanitized.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        } else {
            sanitized
        }
    }

    private inline fun <T> translateBackendErrors(operation: String, block: () -> T): T {
        return try {
            block()
        } catch (e: RagException) {
            throw MemoryBackendException("Memory $operation failed: ${e.message}", e)
        }
    }

    private companion object {
        const val FRONTMATTER_DELIMITER = "---"
        /** Markdown heading-based chunking, required for Markdown memories. */
        const val CHUNK_METHOD_STRUCTURE_AWARE = "structure_aware"
        val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    }
}

/**
 * Public factory for the RAG-backed [MemoryStore]. The implementation class is
 * internal; auto-configuration obtains instances through this object.
 */
object RagMemoryStores {

    @JvmStatic
    fun create(client: RagClient): MemoryStore = RagMemoryStore(client)
}
