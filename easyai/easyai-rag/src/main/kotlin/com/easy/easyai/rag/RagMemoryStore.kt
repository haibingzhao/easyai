package com.easy.easyai.rag

import com.easy.easyai.core.domain.DomainCatalog
import com.easy.easyai.core.memory.*
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
            bizId = bizId,
            excludeArchived = true
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
        val parsed = parseChunkToEntry(
            content = chunk.content,
            filePath = chunk.filePath,
            docId = chunk.docId,
            archived = chunk.archived,
            level = chunk.level,
            refs = chunk.refIds,
            mergedInto = chunk.mergedInto
        ) ?: return null
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
            val doc = buildRagDocument(entry)
            // Fire-and-forget: memory writes must not block on indexing confirmation;
            // chunking and vectorization continue server-side.
            client.upsert(doc, bizId, awaitIndexing = false)
            logger.debug("Memory entry written to RAG (indexing submitted): {} (bizId={})", doc.externalId, bizId)
            Path.of(doc.filePath)
        }

    override suspend fun writeEntry(
        entry: MemoryEntry,
        scope: MemoryScope,
        owner: MemoryOwnerContext
    ): MemoryEntry = translateBackendErrors("writeEntry") {
        val bizId = requireBizId(scope, owner, "writeEntry")
        val doc = buildRagDocument(entry)
        val result = client.upsert(doc, bizId, awaitIndexing = false)
        logger.debug("Memory entry written to RAG: {} -> docId={} (bizId={})", doc.externalId, result.docId, bizId)
        entry.copy(id = result.docId)
    }

    override suspend fun findByIds(
        docIds: List<String>,
        scope: MemoryScope,
        owner: MemoryOwnerContext
    ): List<MemoryEntry> = translateBackendErrors("findByIds") {
        val bizId = bizIdOf(scope, owner) ?: return@translateBackendErrors emptyList()
        if (docIds.isEmpty()) return@translateBackendErrors emptyList()
        client.readByDocIds(docIds, bizId).mapNotNull { entryFromDocument(it) }
    }

    override suspend fun archive(
        docIds: List<String>,
        update: MemoryArchiveUpdate,
        scope: MemoryScope,
        owner: MemoryOwnerContext
    ): Int = translateBackendErrors("archive") {
        val bizId = requireBizId(scope, owner, "archive")
        if (docIds.isEmpty()) return@translateBackendErrors 0
        val result = client.archiveDocIds(
            docIds = docIds,
            update = RagArchiveUpdate(
                archived = update.archived,
                level = update.level,
                refIds = update.refs,
                mergedInto = update.mergedInto
            ),
            bizId = bizId
        )
        if (result.notFound.isNotEmpty() || result.errors.isNotEmpty()) {
            logger.warn(
                "Memory archive partially applied: updated={}, notFound={}, errors={}",
                result.updated, result.notFound.size, result.errors.size
            )
        }
        result.updated
    }

    /** Build the EasyRAG document (frontmatter Markdown + metadata) for [entry]. */
    private fun buildRagDocument(entry: MemoryEntry): RagDocument {
        val key = keyOf(entry.path)
        return RagDocument(
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
            ),
            archived = entry.archived,
            level = entry.level,
            refIds = entry.refs,
            mergedInto = entry.mergedInto
        )
    }

    // ── read ───────────────────────────────────────────────────────────

    override suspend fun read(path: String, scope: MemoryScope, owner: MemoryOwnerContext): String? =
        translateBackendErrors("read") {
            val bizId = bizIdOf(scope, owner) ?: return@translateBackendErrors null
            val detail = client.readByExternalId(
                RagConstants.externalIdOf(keyOf(path)), bizId
            )
            // Archived entries are reachable only through findByIds, matching readEntry: a caller
            // that guesses a superseded key must not read history through the raw-content path.
            if (detail == null || detail.archived) return@translateBackendErrors null
            detail.content
        }

    override suspend fun readEntry(path: String, scope: MemoryScope, owner: MemoryOwnerContext): MemoryEntry? =
        translateBackendErrors("readEntry") {
            val bizId = bizIdOf(scope, owner) ?: return@translateBackendErrors null
            readEntryByExternalId(RagConstants.externalIdOf(keyOf(path)), bizId)
        }

    /** Read one document by externalId and materialize it; one request, no type probing. */
    private suspend fun readEntryByExternalId(
        externalId: String,
        bizId: String,
        includeArchived: Boolean = false
    ): MemoryEntry? {
        val detail = client.readByExternalId(externalId, bizId) ?: return null
        // Archived entries are reachable only through findByIds (the drill-down path) and through
        // findByName when the caller is about to write and must know the key is taken by history.
        if (detail.archived && !includeArchived) return null
        return entryFromDocument(detail)
    }

    /** Materialize a full document payload, carrying the archive/reference columns over. */
    private fun entryFromDocument(detail: RagDocumentDetail): MemoryEntry? {
        val content = detail.content ?: return null
        return parseChunkToEntry(
            content = content,
            filePath = detail.filePath,
            docId = detail.docId,
            archived = detail.archived,
            level = detail.level,
            refs = detail.refIds,
            mergedInto = detail.mergedInto
        )
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
            // Retrieval/listing only sees active entries; archived ones are excluded in the DB.
            val docs = client.list(listPrefix(type), bizId, excludeArchived = true)
            val docIds = docs.map { it.docId }.filter { it.isNotEmpty() }.distinct()
            if (docIds.isEmpty()) return@translateBackendErrors emptyList()
            val details = client.readByDocIds(docIds, bizId).associateBy { it.docId }
            docIds.mapNotNull { docId -> details[docId]?.let { entryFromDocument(it) } }
        }

    /** The type directory of a listed document's `{type}/{name}.md` path; null for a foreign path. */
    private fun typeOfListedPath(filePath: String): MemoryType? =
        relativePathOf(filePath)?.substringBefore('/')?.let { MemoryType.fromDirName(it) }

    /**
     * The document listing alone: `id`, path, `level` and stored length are all fields the list endpoint
     * already echoes, so one paginated read describes a whole slice with no body fetched.
     *
     * [contentChars] counts the stored document, frontmatter included, and so reads slightly above the
     * parsed body. Every consumer budgets prompt text with it, never enforces a content limit — the
     * consolidation writer still measures the real body before it stores anything.
     */
    override suspend fun listActiveIndex(
        scope: MemoryScope,
        owner: MemoryOwnerContext,
        type: MemoryType?
    ): List<MemoryIndexEntry> = translateBackendErrors("listActiveIndex") {
        val bizId = bizIdOf(scope, owner) ?: return@translateBackendErrors emptyList()
        client.list(listPrefix(type), bizId, excludeArchived = true)
            .mapNotNull { doc ->
                val relative = relativePathOf(doc.filePath) ?: return@mapNotNull null
                val entryType = typeOfListedPath(doc.filePath)
                    ?: return@mapNotNull null
                val name = relative.substringAfterLast('/').removeSuffix(".md")
                if (doc.docId.isEmpty() || name.isEmpty()) return@mapNotNull null
                MemoryIndexEntry(
                    docId = doc.docId,
                    name = name,
                    type = entryType,
                    level = doc.level,
                    contentChars = doc.contentLength ?: 0,
                    blurb = doc.contentSummary.orEmpty().take(INDEX_BLURB_CHARS)
                )
            }
            .distinctBy { it.docId }
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
        type: MemoryType?,
        includeArchived: Boolean
    ): MemoryEntry? = translateBackendErrors("findByName") {
        val bizId = bizIdOf(scope, owner) ?: return@translateBackendErrors null
        // Resolve through documents (written synchronously server-side, so a fresh write is
        // immediately visible) and never through search: the vector index is submitted
        // fire-and-forget and would miss an entry written moments ago.
        val externalId = if (type != null) {
            RagConstants.externalIdOf("${type.dirName}/$name.md")
        } else {
            val candidates = candidateExternalIds(name)
            client.list(listPrefix(null), bizId, excludeArchived = !includeArchived)
                .firstOrNull { it.externalId in candidates }
                ?.externalId
                ?: return@translateBackendErrors null
        }
        readEntryByExternalId(externalId, bizId, includeArchived)
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
    private fun parseChunkToEntry(
        content: String,
        filePath: String?,
        docId: String? = null,
        archived: Boolean = false,
        level: Int = 0,
        refs: List<String> = emptyList(),
        mergedInto: String? = null
    ): MemoryEntry? {
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
        val name = unquoteYamlScalar(meta["name"]) ?: fallbackName ?: return null
        val keywords = parseYamlList(meta["keywords"])
        val scenarios = parseYamlList(meta["scenarios"])
        val maturity = meta["maturity"]?.let { MemoryMaturity.fromApiName(it) }
        val created = meta["created"]?.let { runCatching { LocalDate.parse(it, DATE_FMT) }.getOrNull() }
        val updated = meta["updated"]?.let { runCatching { LocalDate.parse(it, DATE_FMT) }.getOrNull() }
        val lastAccessed = meta["last_accessed"]?.let { runCatching { LocalDate.parse(it, DATE_FMT) }.getOrNull() }

        return MemoryEntry(
            name = name,
            description = unquoteYamlScalar(meta["description"]) ?: "",
            type = type,
            content = body.trim(),
            path = "${type.dirName}/$name.md",
            keywords = keywords,
            created = created,
            updated = updated,
            maturity = maturity,
            scenarios = scenarios,
            lastAccessed = lastAccessed,
            id = docId,
            archived = archived,
            level = level,
            refs = refs,
            mergedInto = mergedInto
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

    /**
     * Parse flat `key: value` YAML frontmatter into raw value strings.
     *
     * Quotes are kept so each caller decodes by kind: a scalar through [unquoteYamlScalar], a flow list
     * through [parseYamlList]. Stripping them here is what mangled multi-item lists, whose items are each
     * quoted and therefore leave the line starting and ending with a quote that is not a pair.
     */
    private fun parseFrontmatter(frontmatter: String): Map<String, String> {
        if (frontmatter.isBlank()) return emptyMap()
        return frontmatter.lines()
            .filter { it.contains(':') }
            .associate { line ->
                val colonIndex = line.indexOf(':')
                line.substring(0, colonIndex).trim() to line.substring(colonIndex + 1).trim()
            }
    }

    /** Decode a scalar written by [quoteYamlValue]; an unquoted value is returned as-is. */
    private fun unquoteYamlScalar(value: String?): String? {
        if (value == null) return null
        if (value.length < 2) return value
        val wrapped = (value.startsWith("\"") && value.endsWith("\"")) ||
            (value.startsWith("'") && value.endsWith("'"))
        if (!wrapped) return value
        return value.substring(1, value.length - 1).replace("\\\"", "\"")
    }

    /**
     * Decode a flow list written by [quoteYamlListItem]: comma-separated items, each double-quoted with
     * `\\` and `\"` escapes. Quoting every item is what makes an item containing a comma survive the
     * round trip, so the split must ignore a comma inside quotes rather than break the item in two.
     * Items written before every element was quoted still read back unchanged.
     */
    private fun parseYamlList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val items = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var escaped = false
        for (c in raw) {
            if (escaped) {
                current.append(c)
                escaped = false
                continue
            }
            if (quoted && c == '\\') {
                escaped = true
                continue
            }
            if (c == '"') {
                quoted = !quoted
                continue
            }
            if (c == ',' && !quoted) {
                items.add(current.toString())
                current.setLength(0)
                continue
            }
            current.append(c)
        }
        items.add(current.toString())
        return items.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Build the stored document: YAML frontmatter + Markdown body (same as file store). */
    private fun buildFileContent(entry: MemoryEntry): String = buildString {
        appendLine(FRONTMATTER_DELIMITER)
        appendLine("name: ${quoteYamlValue(entry.name)}")
        appendLine("description: ${quoteYamlValue(entry.description)}")
        appendLine("type: ${entry.type.dirName}")
        if (entry.keywords.isNotEmpty()) {
            appendLine("keywords: ${entry.keywords.joinToString(", ") { quoteYamlListItem(it) }}")
        }
        if (entry.scenarios.isNotEmpty()) {
            appendLine("scenarios: ${entry.scenarios.joinToString(", ") { quoteYamlListItem(it) }}")
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
            "\"${escapeYamlDoubleQuoted(sanitized)}\""
        } else {
            sanitized
        }
    }

    /**
     * A list item, always double-quoted: comma is the item separator, so an item that contains one is
     * only distinguishable from two items by its quotes. [parseYamlList] reads this back.
     */
    private fun quoteYamlListItem(value: String): String =
        "\"${escapeYamlDoubleQuoted(value.replace("\n", " ").replace("\r", ""))}\""

    private fun escapeYamlDoubleQuoted(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

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
        /** How much of the server's `contentSummary` reaches a grouping prompt as one entry's blurb. */
        const val INDEX_BLURB_CHARS = 200
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
