package com.easy.easyai.rag

import com.easy.easyai.core.domain.DomainCatalog
import com.easy.easyai.core.memory.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant
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
 *   keywords, maturity, scenarios, created/updated and optionally last_accessed dates)
 * - processing: heading-aligned chunking only (`skipKg=true`, `buildStructure=false`) —
 *   memory retrieval is raw-chunk (`mode=naive`), so the knowledge graph and structure
 *   index would be built but never read
 * - freshness: retrieval reports `updated` / `maturity`. Heading-aligned chunking keeps the
 *   frontmatter in the first chunk only, so hits on later chunks are backfilled from the
 *   per-chunk metadata echoed by the server
 * - name lookup: resolved through the document list (one request) or directly through the
 *   known type, never by probing every type in the domain
 * - writes submit indexing fire-and-forget (no polling): the write returns as soon as
 *   the document is stored and indexing is triggered server-side; entries become
 *   searchable once the server finishes chunking and vectorization
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
        chunks.mapNotNull { entryFromChunk(it) }
            .distinctBy { "${it.type.dirName}/${it.name}" }
    }

    /**
     * Rebuild an entry from a retrieval hit. The stored frontmatter is the source of truth,
     * but heading-aligned chunking keeps it in the first chunk only: a hit on a later chunk
     * has no description or dates, so backfill them from the per-chunk metadata and the
     * chunk's business create time (which [createTimeOf] sets to `updated`).
     */
    private fun entryFromChunk(chunk: RagChunk): MemoryEntry? {
        val parsed = parseChunkToEntry(chunk.content, chunk.filePath) ?: return null
        val maturity = parsed.maturity
            ?: (chunk.metadata["maturity"] as? String)?.let { MemoryMaturity.fromApiName(it) }
        val description = parsed.description.ifBlank { chunk.metadata["description"] as? String ?: "" }
        val updated = parsed.updated ?: epochToLocalDate(chunk.createTime)
        if (maturity == parsed.maturity && description == parsed.description && updated == parsed.updated) {
            return parsed
        }
        return parsed.copy(maturity = maturity, description = description, updated = updated)
    }

    private fun epochToLocalDate(epochSeconds: Long?): LocalDate? = epochSeconds?.let {
        runCatching { Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
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
                    // Redundant freshness copy: chunks after the first one carry no
                    // frontmatter, and retrieval needs these dates to judge staleness.
                    entry.created?.let { put("created", it.format(DATE_FMT)) }
                    entry.updated?.let { put("updated", it.format(DATE_FMT)) }
                },
                createTime = createTimeOf(entry),
                // Memory entries are short Markdown documents retrieved as raw chunks:
                // heading-aligned chunking keeps frontmatter + body inside one chunk so
                // parseChunkToEntry can rebuild a complete entry, while KG extraction and
                // the structure index are skipped — memory_search queries with mode=naive,
                // which never reads entities/relations or the structure index, so both
                // would only add per-write LLM cost.
                options = RagProcessingOptions(
                    chunkMethod = CHUNK_METHOD_STRUCTURE_AWARE,
                    skipKg = true,
                    buildStructure = false
                )
            )
            // Fire-and-forget: memory writes must not block on indexing confirmation;
            // chunking and vectorization continue server-side.
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

    override suspend fun readEntry(path: String, scope: MemoryScope, owner: MemoryOwnerContext): MemoryEntry? =
        translateBackendErrors("readEntry") {
            val bizId = bizIdOf(scope, owner) ?: return@translateBackendErrors null
            readEntryByExternalId(RagConstants.externalIdOf(keyOf(path)), bizId)
        }

    /** Read one document by externalId and materialize it; one request, no type probing. */
    private suspend fun readEntryByExternalId(externalId: String, bizId: String): MemoryEntry? {
        val detail = client.readByExternalId(externalId, bizId) ?: return null
        val content = detail.content ?: return null
        return parseChunkToEntry(content, detail.filePath)
    }

    // ── delete / deleteAll ─────────────────────────────────────────────

    override suspend fun delete(path: String, scope: MemoryScope, owner: MemoryOwnerContext): Boolean =
        translateBackendErrors("delete") {
            val bizId = requireBizId(scope, owner, "delete")
            val externalId = RagConstants.externalIdOf(keyOf(path))
            // The client resolves externalId -> docId and reports whether anything matched;
            // pre-reading here used to cost one extra round trip per deletion.
            val deleted = client.delete(externalId, bizId)
            if (deleted) {
                logger.debug("Memory entry deleted from RAG: {}", externalId)
            }
            deleted
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
            val semaphore = Semaphore(MAX_CONCURRENT_READS)
            coroutineScope {
                externalIds.map { externalId ->
                    async { semaphore.withPermit { client.readByExternalId(externalId, bizId) } }
                }.awaitAll()
            }.mapNotNull { detail ->
                val content = detail?.content ?: return@mapNotNull null
                parseChunkToEntry(content, detail.filePath)
            }
        }

    // ── exists / findByName ────────────────────────────────────────────

    override suspend fun exists(
        name: String,
        scope: MemoryScope,
        owner: MemoryOwnerContext,
        type: MemoryType?
    ): Boolean = findByName(name, scope, owner, type) != null

    override suspend fun findByName(
        name: String,
        scope: MemoryScope,
        owner: MemoryOwnerContext,
        type: MemoryType?
    ): MemoryEntry? = translateBackendErrors("findByName") {
        val bizId = bizIdOf(scope, owner) ?: return@translateBackendErrors null
        // Resolve through documents (written synchronously server-side, so a fresh write is
        // immediately visible) and never through search: the vector index is submitted
        // fire-and-forget and would miss an entry written moments ago.
        val externalId = if (type != null) {
            RagConstants.externalIdOf("${type.dirName}/$name.md")
        } else {
            val candidates = candidateExternalIds(name)
            client.list(listPrefix(null), bizId)
                .firstOrNull { it.externalId in candidates }
                ?.externalId
                ?: return@translateBackendErrors null
        }
        readEntryByExternalId(externalId, bizId)
    }

    /** Possible externalIds for [name] across the types of the active domain. */
    private fun candidateExternalIds(name: String): Set<String> =
        MemoryType.entriesFor(DomainCatalog.activeDomain)
            .map { RagConstants.externalIdOf("${it.dirName}/$name.md") }
            .toSet()

    override suspend fun touch(path: String, scope: MemoryScope, owner: MemoryOwnerContext): MemoryEntry? {
        val today = LocalDate.now()
        // Read the authoritative full document first: a search hit may carry only part of
        // the body, and rewriting that would truncate the stored entry.
        val entry = readEntry(path, scope, owner) ?: return null
        if (entry.lastAccessed == today) return entry
        val touched = entry.copy(lastAccessed = today)
        write(touched, scope, owner)
        return touched
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
        // Business time is the last modification date only: lastAccessed is a staleness hint
        // and must not shift the timeRangeStart/timeRangeEnd window of retrieval.
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
        val lastAccessed = meta["last_accessed"]?.let { runCatching { LocalDate.parse(it, DATE_FMT) }.getOrNull() }

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
            scenarios = scenarios,
            lastAccessed = lastAccessed
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
        entry.lastAccessed?.let { appendLine("last_accessed: ${it.format(DATE_FMT)}") }
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
        /** Markdown heading-based chunking: no LLM cost, keeps entry sections intact. */
        const val CHUNK_METHOD_STRUCTURE_AWARE = "structure_aware"
        /** Caps the per-document read fan-out of [list] so large scopes cannot starve the pool. */
        const val MAX_CONCURRENT_READS = 16
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
