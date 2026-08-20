package com.easy.easyai.rag

import com.easy.easyai.core.knowledge.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * [KnowledgeStore] implementation backed by EasyRAG.
 *
 * Mirrors the [RagMemoryStore] pattern:
 * - key layout: `{source}/{relativePath}` (e.g. `easyai-docs/README.md`)
 * - externalId: `easyai:{key}`
 * - bizId: `RagBizIdResolver.globalBizId(userId, "k")` (user-level, cross-project)
 * - content: YAML frontmatter (title/description/source/kcategory/ext/updated) + raw body
 * - metadata: `source, kcategory, ext, title`
 *
 * Processing profile by extension:
 * - `.md` → structure_aware chunking, buildStructure=true, skipKg=false
 * - text whitelist → default chunking, skipKg=false
 * - others → rejected
 *
 * Upload concurrency is limited to 4 parallel upserts to avoid overwhelming
 * the EasyRAG pipeline (which may return 409 when busy).
 */
internal class RagKnowledgeStore(
    private val client: RagClient
) : KnowledgeStore {

    private val logger = LoggerFactory.getLogger(RagKnowledgeStore::class.java)

    // ── uploadBatch ────────────────────────────────────────────────────

    override suspend fun uploadBatch(
        userId: String,
        source: String,
        items: List<KnowledgeUploadItem>,
        kcategory: String?
    ): List<UploadResult> {
        val bizId = RagBizIdResolver.globalBizId(userId, RagBizIdResolver.KNOWLEDGE_TYPE)
        val results = mutableListOf<UploadResult>()

        // Process in chunks of 4 to limit concurrency
        for (chunk in items.chunked(UPLOAD_CONCURRENCY)) {
            coroutineScope {
                val chunkResults = chunk.map { item ->
                    async { uploadSingle(source, item, kcategory, bizId) }
                }.awaitAll()
                results.addAll(chunkResults)
            }
        }
        return results
    }

    private suspend fun uploadSingle(
        source: String,
        item: KnowledgeUploadItem,
        kcategory: String?,
        bizId: String
    ): UploadResult {
        val relativePath = item.relativePath
        val ext = relativePath.substringAfterLast('.', "").lowercase()
        val options = processingOptionsFor(ext)
            ?: return UploadResult(
                relativePath = relativePath,
                success = false,
                reason = "Unsupported file extension: .$ext"
            )

        // Default uncategorised uploads to "other"
        val resolvedCategory = kcategory?.takeIf { it.isNotBlank() } ?: "other"

        val key = "$source/$relativePath"
        val externalId = RagConstants.externalIdOf(key)
        val title = extractTitle(item.content, relativePath)
        val description = extractDescription(item.content)
        val now = Instant.now()
        val updatedMillis = now.toEpochMilli()

        val content = buildFileContent(
            title = title,
            description = description,
            source = source,
            kcategory = resolvedCategory,
            ext = ext,
            updatedMillis = updatedMillis,
            body = stripFrontmatter(item.content)
        )

        val metadata = buildMap<String, String> {
            put("source", source)
            put("kcategory", resolvedCategory)
            put("ext", ext)
            put("title", title)
        }

        val doc = RagDocument(
            key = key,
            content = content,
            metadata = metadata,
            createTime = now.epochSecond,
            options = options
        )

        return try {
            val result = client.upsert(doc, bizId)
            if (!result.indexed) {
                logger.warn("Knowledge entry uploaded but indexing not confirmed (poll timeout): {}", externalId)
            } else {
                logger.debug("Knowledge entry uploaded: {}", externalId)
            }
            UploadResult(relativePath = relativePath, success = true, key = key)
        } catch (e: RagException) {
            logger.warn("Knowledge upload failed for {}: {}", relativePath, e.message)
            UploadResult(relativePath = relativePath, success = false, reason = e.message)
        }
    }

    // ── list ───────────────────────────────────────────────────────────

    override suspend fun list(
        userId: String,
        source: String?,
        kcategory: String?,
        query: String?
    ): List<KnowledgeEntry> {
        val bizId = RagBizIdResolver.globalBizId(userId, RagBizIdResolver.KNOWLEDGE_TYPE)

        // Semantic search mode
        if (!query.isNullOrBlank()) {
            return searchEntries(query, bizId, source, kcategory)
        }

        // List mode: fetch all docs under the knowledge prefix
        val pathPrefix = if (source != null) {
            "${RagConstants.FILE_PATH_ROOT}/$source/"
        } else {
            "${RagConstants.FILE_PATH_ROOT}/"
        }
        val docs = client.list(pathPrefix, bizId)
        if (docs.isEmpty()) return emptyList()

        // Read full details concurrently
        val entries = coroutineScope {
            docs.mapNotNull { doc ->
                val extId = doc.externalId ?: return@mapNotNull null
                async {
                    val detail = client.readByExternalId(extId, bizId) ?: return@async null
                    parseDetailToEntry(detail.content, detail.filePath)
                }
            }.awaitAll().filterNotNull()
        }

        // Client-side category filter
        return if (kcategory != null) {
            entries.filter { it.kcategory == kcategory }
        } else {
            entries
        }
    }

    private suspend fun searchEntries(
        query: String,
        bizId: String,
        source: String?,
        kcategory: String?
    ): List<KnowledgeEntry> {
        val chunks = client.search(
            query = query,
            topK = SEARCH_TOP_K,
            bizId = bizId
        )
        // Deduplicate by extracting unique document keys from chunk filePaths
        val seen = mutableSetOf<String>()
        val keys = mutableListOf<String>()
        for (chunk in chunks) {
            val key = extractKeyFromPath(chunk.filePath) ?: continue
            if (source != null && !key.startsWith("$source/")) continue
            if (!seen.add(key)) continue
            keys.add(key)
        }
        // Read full documents concurrently instead of one-by-one (N+1 HTTP calls).
        // Per-item failure degrades gracefully (mirrors uploadBatch): one bad doc
        // must not abort the whole search.
        val entries = coroutineScope {
            keys.map { key ->
                async {
                    val detail = try {
                        client.readByExternalId(RagConstants.externalIdOf(key), bizId)
                    } catch (ex: RagException) {
                        logger.warn("Knowledge search: failed to read entry {}: {}", key, ex.message)
                        null
                    }
                    if (detail == null) return@async null
                    // Longer preview than list mode: reduces follow-up knowledge_read calls.
                    parseDetailToEntry(detail.content, detail.filePath, SEARCH_PREVIEW_LENGTH)
                }
            }.awaitAll().filterNotNull()
        }
        return if (kcategory != null) {
            entries.filter { it.kcategory == kcategory }
        } else {
            entries
        }
    }

    // ── read ─────────────────────────────────────────────────────────

    override suspend fun read(userId: String, key: String): String? {
        val bizId = RagBizIdResolver.globalBizId(userId, RagBizIdResolver.KNOWLEDGE_TYPE)
        val doc = client.readByExternalId(RagConstants.externalIdOf(key), bizId) ?: return null
        // Keep the not-found contract aligned with detail(): null content means no entry.
        val content = doc.content ?: return null
        return stripFrontmatter(content)
    }

    // ── detail ─────────────────────────────────────────────────────────

    override suspend fun detail(userId: String, key: String): KnowledgeDetail? {
        val bizId = RagBizIdResolver.globalBizId(userId, RagBizIdResolver.KNOWLEDGE_TYPE)
        val externalId = RagConstants.externalIdOf(key)
        val doc = client.readByExternalId(externalId, bizId) ?: return null
        val entry = parseDetailToEntry(doc.content, doc.filePath) ?: return null
        val fullContent = stripFrontmatter(doc.content ?: "")
        val toc = extractToc(fullContent)
        val parent = deriveParent(key, bizId)
        val children = deriveChildren(key, bizId)
        val related = deriveRelated(entry, bizId)

        return KnowledgeDetail(
            entry = entry,
            fullContent = fullContent,
            toc = toc,
            parent = parent,
            children = children,
            related = related
        )
    }

    // ── delete / deleteSource ──────────────────────────────────────────

    override suspend fun delete(userId: String, key: String): Boolean {
        val bizId = RagBizIdResolver.globalBizId(userId, RagBizIdResolver.KNOWLEDGE_TYPE)
        val externalId = RagConstants.externalIdOf(key)
        val existing = client.readByExternalId(externalId, bizId) ?: return false
        client.delete(externalId, bizId)
        logger.debug("Knowledge entry deleted: {}", externalId)
        return true
    }

    override suspend fun deleteSource(userId: String, source: String): Int {
        val bizId = RagBizIdResolver.globalBizId(userId, RagBizIdResolver.KNOWLEDGE_TYPE)
        val prefix = "${RagConstants.FILE_PATH_ROOT}/$source/"
        val docs = client.list(prefix, bizId)
        if (docs.isEmpty()) return 0
        val docIds = docs.map { it.docId }
        val deleted = client.batchDelete(docIds, bizId)
        logger.debug("Deleted {} knowledge entries for source {}", deleted, source)
        return deleted
    }

    // ── sources ────────────────────────────────────────────────────────

    override suspend fun sources(userId: String): List<String> {
        val bizId = RagBizIdResolver.globalBizId(userId, RagBizIdResolver.KNOWLEDGE_TYPE)
        val prefix = "${RagConstants.FILE_PATH_ROOT}/"
        val docs = client.list(prefix, bizId)
        return docs.map { doc ->
            // Extract source from filePath: easyai/{source}/...
            val fp = doc.filePath
            val afterPrefix = fp.removePrefix(prefix)
            afterPrefix.substringBefore('/')
        }.distinct().sorted()
    }

    // ── Processing profile registry ────────────────────────────────────

    private fun processingOptionsFor(ext: String): RagProcessingOptions? = when (ext) {
        "md" -> RagProcessingOptions(
            chunkMethod = CHUNK_METHOD_STRUCTURE_AWARE,
            buildStructure = true,
            skipKg = false
        )
        in TEXT_WHITELIST -> RagProcessingOptions(skipKg = false)
        else -> null
    }

    // ── Content parsing ────────────────────────────────────────────────

    private fun parseDetailToEntry(
        content: String?,
        filePath: String?,
        previewLength: Int = CONTENT_PREVIEW_LENGTH
    ): KnowledgeEntry? {
        if (content == null) return null
        val (frontmatter, body) = splitFrontmatter(content)
        val meta = parseFrontmatter(frontmatter)
        val key = extractKeyFromPath(filePath) ?: return null
        val source = key.substringBefore('/')
        val relativePath = key.substringAfter('/')
        val ext = relativePath.substringAfterLast('.', "").lowercase()

        return KnowledgeEntry(
            key = key,
            source = source,
            relativePath = relativePath,
            title = meta["title"] ?: relativePath.substringAfterLast('/').removeSuffix(".$ext"),
            description = meta["description"] ?: "",
            kcategory = meta["kcategory"] ?: "",
            ext = ext,
            content = body.take(previewLength),
            updatedAt = meta["updated"]?.toLongOrNull(),
            chunksCount = null
        )
    }

    private fun extractKeyFromPath(filePath: String?): String? {
        if (filePath == null) return null
        val prefix = "${RagConstants.FILE_PATH_ROOT}/"
        if (!filePath.startsWith(prefix)) return null
        val relative = filePath.removePrefix(prefix)
        return relative.ifBlank { null }
    }

    // ── Relationship derivation (MVP: directory convention) ────────────

    /** Extract Markdown headings as table of contents, skipping fenced code blocks. */
    private fun extractToc(content: String): List<String> {
        val headings = mutableListOf<String>()
        var inFence = false
        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) {
                inFence = !inFence
                continue
            }
            if (inFence) continue
            if (line.startsWith("#")) {
                val text = line.trimStart('#').trim()
                if (text.isNotBlank()) headings.add(text)
            }
        }
        return headings
    }

    /**
     * Derive the parent key by looking for README.md / index.md / {dirname}.md
     * in ancestor directories. Only returns a candidate that actually exists.
     */
    private suspend fun deriveParent(key: String, bizId: String): String? {
        val parts = key.split('/')
        if (parts.size < 2) return null
        // Walk up directory segments
        for (i in parts.size - 2 downTo 1) {
            val dir = parts.subList(0, i).joinToString("/")
            val candidates = listOf("$dir/README.md", "$dir/index.md", "$dir/${parts[i - 1]}.md")
            for (candidate in candidates) {
                val extId = RagConstants.externalIdOf(candidate)
                if (client.readByExternalId(extId, bizId) != null) {
                    return candidate
                }
            }
        }
        return null
    }

    /**
     * When this entry is a directory index document (README.md / index.md),
     * list the direct child documents in the same directory.
     */
    private suspend fun deriveChildren(key: String, bizId: String): List<String> {
        val fileName = key.substringAfterLast('/')
        if (fileName !in INDEX_FILE_NAMES) return emptyList()
        val dir = key.substringBeforeLast('/')
        val prefix = "${RagConstants.FILE_PATH_ROOT}/$dir/"
        val docs = client.list(prefix, bizId)
        return docs.mapNotNull { doc ->
            val childKey = extractKeyFromPath(doc.filePath) ?: return@mapNotNull null
            if (!childKey.startsWith("$dir/")) return@mapNotNull null
            // Only direct children: no further path separators below $dir
            val rel = childKey.removePrefix("$dir/")
            if (rel.contains('/')) return@mapNotNull null
            val childName = childKey.substringAfterLast('/')
            if (childName != fileName && childName !in INDEX_FILE_NAMES) childKey else null
        }.sorted()
    }

    /** Semantic search for related entries based on title + description. */
    private suspend fun deriveRelated(entry: KnowledgeEntry, bizId: String): List<String> {
        val queryText = listOfNotNull(
            entry.title.takeIf { it.isNotBlank() },
            entry.description.takeIf { it.isNotBlank() }
        ).joinToString(" ")
        if (queryText.isBlank()) return emptyList()

        val chunks = client.search(
            query = queryText,
            topK = RELATED_TOP_K,
            bizId = bizId
        )
        return chunks.mapNotNull { extractKeyFromPath(it.filePath) }
            .filter { it != entry.key }
            .distinct()
            .take(RELATED_MAX)
    }

    // ── Frontmatter helpers ────────────────────────────────────────────

    private fun buildFileContent(
        title: String,
        description: String,
        source: String,
        kcategory: String,
        ext: String,
        updatedMillis: Long,
        body: String
    ): String = buildString {
        appendLine(FRONTMATTER_DELIMITER)
        appendLine("title: ${quoteYamlValue(title)}")
        appendLine("description: ${quoteYamlValue(description)}")
        appendLine("source: ${quoteYamlValue(source)}")
        if (kcategory.isNotBlank()) {
            appendLine("kcategory: ${quoteYamlValue(kcategory)}")
        }
        appendLine("ext: $ext")
        appendLine("updated: $updatedMillis")
        appendLine(FRONTMATTER_DELIMITER)
        appendLine()
        append(body)
    }

    private fun extractTitle(content: String, fallbackPath: String): String {
        val (frontmatter, body) = splitFrontmatter(content)
        val meta = parseFrontmatter(frontmatter)
        meta["title"]?.let { return it }
        // Fallback: first heading or filename
        body.lines().firstOrNull { it.startsWith("#") }
            ?.let { return it.trimStart('#').trim() }
        return fallbackPath.substringAfterLast('/').substringBeforeLast('.')
    }

    private fun extractDescription(content: String): String {
        val (frontmatter, _) = splitFrontmatter(content)
        val meta = parseFrontmatter(frontmatter)
        return meta["description"] ?: ""
    }

    private fun stripFrontmatter(content: String): String {
        val (_, body) = splitFrontmatter(content)
        return body
    }

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

    private fun quoteYamlValue(value: String): String {
        val sanitized = value.replace("\n", " ").replace("\r", "")
        val needsQuoting = sanitized.any { it in ":#\"{}[]&*!|>%@" }
        return if (needsQuoting) {
            "\"${sanitized.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        } else {
            sanitized
        }
    }

    // ── Path safety ────────────────────────────────────────────────────

    companion object {
        private const val FRONTMATTER_DELIMITER = "---"
        private const val CHUNK_METHOD_STRUCTURE_AWARE = "structure_aware"
        private const val UPLOAD_CONCURRENCY = 4
        private const val SEARCH_TOP_K = 10
        /** Preview length for semantic search hits (matches KnowledgeSearchTool.CONTENT_PREVIEW_LIMIT). */
        private const val SEARCH_PREVIEW_LENGTH = 1500
        private const val RELATED_TOP_K = 6
        private const val RELATED_MAX = 5
        private const val CONTENT_PREVIEW_LENGTH = 200

        private val INDEX_FILE_NAMES = setOf("README.md", "readme.md", "index.md")

        private val TEXT_WHITELIST = setOf(
            "txt", "kt", "java", "py", "ts", "tsx", "js", "json",
            "yml", "yaml", "sql", "properties", "html", "csv", "sh"
        )
    }
}

/**
 * Public factory for the RAG-backed [KnowledgeStore].
 */
object RagKnowledgeStores {

    @JvmStatic
    fun create(client: RagClient): KnowledgeStore = RagKnowledgeStore(client)
}
