package com.easy.easyai.rag

import com.easy.easyai.core.knowledge.KnowledgeEntry
import com.easy.easyai.core.knowledge.KnowledgeStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * [KnowledgeStore] implementation backed by EasyRAG.
 *
 * - key / externalId: the article id (`easyai:{id}`); folder layout lives only in the database
 * - bizId: `RagBizIdResolver.globalBizId(userId, "k")` (user-level, cross-project)
 * - content: YAML frontmatter (title/description/kcategory/ext/updated) + raw body
 *
 * Processing profile by extension:
 * - `.md` → structure_aware chunking, buildStructure=true, skipKg=false
 * - text whitelist → default chunking, skipKg=false
 * - others → rejected (`index` returns false)
 */
internal class RagKnowledgeStore(
    private val client: RagClient
) : KnowledgeStore {

    private val logger = LoggerFactory.getLogger(RagKnowledgeStore::class.java)

    // ── search / read ──────────────────────────────────────────────────

    override suspend fun search(userId: String, query: String, topK: Int): List<KnowledgeEntry> {
        val bizId = RagBizIdResolver.globalBizId(userId, RagBizIdResolver.KNOWLEDGE_TYPE)
        val chunks = client.search(query = query, topK = topK, bizId = bizId)
        val ids = chunks.mapNotNull { extractIdFromPath(it.filePath) }.distinct()
        if (ids.isEmpty()) return emptyList()

        // Read the distinct documents concurrently; one bad document must not abort the search.
        return coroutineScope {
            ids.map { id ->
                async {
                    val doc = try {
                        client.readByExternalId(RagConstants.externalIdOf(id), bizId)
                    } catch (e: RagException) {
                        logger.warn("Knowledge search: failed to read entry {}: {}", id, e.message)
                        null
                    } ?: return@async null
                    parseEntry(doc.content, doc.filePath ?: RagConstants.filePathOf(id), doc.chunksCount)
                }
            }.awaitAll().filterNotNull()
        }
    }

    override suspend fun read(userId: String, id: String): String? {
        val bizId = RagBizIdResolver.globalBizId(userId, RagBizIdResolver.KNOWLEDGE_TYPE)
        val doc = client.readByExternalId(RagConstants.externalIdOf(id), bizId) ?: return null
        return doc.content?.let { stripFrontmatter(it) }
    }

    override fun supports(ext: String): Boolean = processingOptionsFor(ext) != null

    // ── index / delete ─────────────────────────────────────────────────

    override suspend fun index(
        userId: String,
        id: String,
        content: String,
        title: String?,
        kcategory: String?,
        ext: String,
    ): Boolean {
        val bizId = RagBizIdResolver.globalBizId(userId, RagBizIdResolver.KNOWLEDGE_TYPE)
        val resolvedTitle = title?.takeIf { it.isNotBlank() } ?: extractTitle(content, id)
        val resolvedCategory = kcategory?.takeIf { it.isNotBlank() } ?: "other"
        return upsertDoc(
            bizId = bizId,
            id = id,
            content = content,
            title = resolvedTitle,
            description = extractDescription(content),
            kcategory = resolvedCategory,
            ext = ext
        )
    }

    override suspend fun delete(userId: String, id: String): Boolean {
        val bizId = RagBizIdResolver.globalBizId(userId, RagBizIdResolver.KNOWLEDGE_TYPE)
        val externalId = RagConstants.externalIdOf(id)
        client.readByExternalId(externalId, bizId) ?: return false
        client.delete(externalId, bizId)
        logger.debug("Knowledge entry deleted: {}", externalId)
        return true
    }

    override suspend fun chunkCounts(userId: String, ids: Collection<String>): Map<String, Int> {
        if (ids.isEmpty()) return emptyMap()
        val bizId = RagBizIdResolver.globalBizId(userId, RagBizIdResolver.KNOWLEDGE_TYPE)
        val result = mutableMapOf<String, Int>()
        // Bounded concurrency: upload batches can be large.
        for (chunk in ids.chunked(CHUNK_STATUS_CONCURRENCY)) {
            coroutineScope {
                chunk.map { id ->
                    async {
                        val doc = try {
                            client.readByExternalId(RagConstants.externalIdOf(id), bizId)
                        } catch (e: RagException) {
                            logger.warn("Knowledge index-status: failed to read {}: {}", id, e.message)
                            null
                        }
                        doc?.chunksCount?.let { id to it }
                    }
                }.awaitAll().filterNotNull().forEach { (id, count) -> result[id] = count }
            }
        }
        return result
    }

    private suspend fun upsertDoc(
        bizId: String,
        id: String,
        content: String,
        title: String,
        description: String,
        kcategory: String,
        ext: String,
    ): Boolean {
        val options = processingOptionsFor(ext)
        if (options == null) {
            logger.warn("Unsupported knowledge file extension: .{}", ext)
            return false
        }
        val now = Instant.now()
        val wrappedContent = buildFileContent(
            title = title,
            description = description,
            kcategory = kcategory,
            ext = ext,
            updatedMillis = now.toEpochMilli(),
            body = stripFrontmatter(content)
        )
        val metadata = buildMap<String, String> {
            put("kcategory", kcategory)
            put("ext", ext)
            put("title", title)
        }
        val doc = RagDocument(
            key = id,
            content = wrappedContent,
            metadata = metadata,
            createTime = now.epochSecond,
            options = options
        )
        return try {
            client.upsert(doc, bizId, awaitIndexing = false)
            logger.debug("Knowledge entry written: {}", RagConstants.externalIdOf(id))
            true
        } catch (e: RagException) {
            logger.warn("Knowledge write failed for {}: {}", id, e.message)
            false
        }
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

    private fun parseEntry(
        content: String?,
        filePath: String?,
        chunksCount: Int?,
        previewLength: Int = SEARCH_PREVIEW_LENGTH,
    ): KnowledgeEntry? {
        if (content == null) return null
        val id = extractIdFromPath(filePath) ?: return null
        val (frontmatter, _) = splitFrontmatter(content)
        val meta = parseFrontmatter(frontmatter)
        val ext = meta["ext"]?.takeIf { it.isNotBlank() }
            ?: id.substringAfterLast('.', "").lowercase()
        return KnowledgeEntry(
            id = id,
            title = meta["title"] ?: id,
            description = meta["description"] ?: "",
            kcategory = meta["kcategory"] ?: "",
            ext = ext,
            content = stripFrontmatter(content).take(previewLength),
            updatedAt = meta["updated"]?.toLongOrNull(),
            chunksCount = chunksCount
        )
    }

    private fun extractIdFromPath(filePath: String?): String? {
        if (filePath == null) return null
        val prefix = "${RagConstants.FILE_PATH_ROOT}/"
        if (!filePath.startsWith(prefix)) return null
        return filePath.removePrefix(prefix).ifBlank { null }
    }

    // ── Frontmatter helpers ────────────────────────────────────────────

    private fun buildFileContent(
        title: String,
        description: String,
        kcategory: String,
        ext: String,
        updatedMillis: Long,
        body: String
    ): String = buildString {
        appendLine(FRONTMATTER_DELIMITER)
        appendLine("title: ${quoteYamlValue(title)}")
        appendLine("description: ${quoteYamlValue(description)}")
        if (kcategory.isNotBlank()) {
            appendLine("kcategory: ${quoteYamlValue(kcategory)}")
        }
        appendLine("ext: $ext")
        appendLine("updated: $updatedMillis")
        appendLine(FRONTMATTER_DELIMITER)
        appendLine()
        append(body)
    }

    private fun extractTitle(content: String, fallbackId: String): String {
        val (frontmatter, body) = splitFrontmatter(content)
        val meta = parseFrontmatter(frontmatter)
        meta["title"]?.let { return it }
        body.lines().firstOrNull { it.startsWith("#") }
            ?.let { return it.trimStart('#').trim() }
        return fallbackId
    }

    private fun extractDescription(content: String): String {
        val (frontmatter, _) = splitFrontmatter(content)
        val meta = parseFrontmatter(frontmatter)
        return meta["description"] ?: ""
    }

    private fun stripFrontmatter(content: String): String {
        val (_, body) = splitFrontmatter(content)
        // The wrapper writes a blank line after the delimiter; drop it so a read round-trips
        // the body exactly as it was indexed.
        return body.trimStart('\n')
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

    companion object {
        private const val FRONTMATTER_DELIMITER = "---"
        private const val CHUNK_METHOD_STRUCTURE_AWARE = "structure_aware"
        private const val SEARCH_PREVIEW_LENGTH = 1500
        private const val CHUNK_STATUS_CONCURRENCY = 8

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
