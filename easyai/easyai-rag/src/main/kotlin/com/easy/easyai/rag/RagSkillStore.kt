package com.easy.easyai.rag

import com.easy.easyai.core.skill.SkillDeleteResult
import com.easy.easyai.core.skill.SkillDocumentState
import com.easy.easyai.core.skill.SkillEntry
import com.easy.easyai.core.skill.SkillOwnerContext
import com.easy.easyai.core.skill.SkillStore
import com.easy.easyai.core.skill.SkillSubmitResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant

/**
 * [SkillStore] implementation on top of EasyRAG.
 *
 * Each SKILL.md becomes one document:
 * - isolation: `biz_id = u_{userId}_s`, one slice per skill owner; the `system` shared layer is
 *   the `u_system_s` slice
 * - key layout: `skills/{name}.md` — two owners may hold the same name, they are separate
 *   documents because they sit in separate slices
 * - externalId: `easyai:{key}` (idempotent upsert, deterministic docId)
 * - content: YAML frontmatter (name/description/tags/examples/origin/location) + full body,
 *   mirroring [RagMemoryStore]; indexing the body is what makes semantic discovery work at all
 * - processing: `structure_aware` + `skipKg` + no structure index — retrieval is `mode=naive`,
 *   so the graph and structure indexes would be built but never read (zero LLM cost per write)
 *
 * Degradation differs deliberately from [RagMemoryStore]: skill indexing is a **non-critical**
 * path, so [RagException] is logged and swallowed (empty results / partial counts / false)
 * rather than rethrown. A RAG outage must never break the agent loop or startup.
 */
internal class RagSkillStore(
    private val client: RagClient
) : SkillStore {

    private val logger = LoggerFactory.getLogger(RagSkillStore::class.java)

    // ── index ──────────────────────────────────────────────────────────

    override suspend fun submit(
        entries: List<SkillEntry>,
        owner: SkillOwnerContext,
        awaitIndexing: Boolean
    ): List<SkillSubmitResult> {
        val bizId = RagBizIdResolver.skillBizId(owner.userId)
        return entries.chunked(INDEX_CONCURRENCY).flatMap { chunk ->
            coroutineScope {
                chunk.map { entry ->
                    async {
                        val state = try {
                            require(!entry.checksum.isNullOrBlank()) { "Skill source checksum is required" }
                            val result = client.upsert(documentOf(entry, bizId), bizId, awaitIndexing)
                            if (result.indexed) {
                                // Verify the stored version too: another revision may have won the upsert.
                                inspect(entry.name, owner)
                            } else {
                                SkillDocumentState.Submitted(entry.checksum)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            SkillDocumentState.Failed(e.message ?: "Skill submission failed")
                        }
                        SkillSubmitResult(entry.key, state)
                    }
                }.awaitAll()
            }
        }
    }

    override suspend fun inspect(name: String, owner: SkillOwnerContext): SkillDocumentState {
        val bizId = RagBizIdResolver.skillBizId(owner.userId)
        return try {
            val document = client.inspectByExternalId(RagConstants.externalIdOf(keyOf(name)), bizId)
                ?: return SkillDocumentState.Absent
            val checksum = document.metadata["checksum"] as? String
            when (document.status) {
                "processed" -> SkillDocumentState.Processed(checksum)
                "failed" -> SkillDocumentState.Failed("Remote skill indexing failed")
                else -> SkillDocumentState.Submitted(checksum)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SkillDocumentState.Failed(e.message ?: "Skill inspection failed")
        }
    }

    private fun documentOf(entry: SkillEntry, bizId: String): RagDocument =
        RagDocument(
            key = entry.key.ifBlank { keyOf(entry.name) },
            content = buildFileContent(entry),
            metadata = buildMap {
                put("name", entry.name)
                put("description", entry.description)
                entry.checksum?.let { put("checksum", it) }
                if (entry.tags.isNotEmpty()) put("tags", entry.tags.joinToString(","))
                entry.origin?.let { put("origin", it) }
                entry.location?.let { put("location", it) }
                // Record the owning user alongside the slice it is written to; the biz_id is
                // the security boundary, this copy exists so server-side log inspection is easy.
                put("userId", userOfBizId(bizId))
            },
            createTime = createTimeOf(entry),
            options = RagProcessingOptions(
                chunkMethod = CHUNK_METHOD_STRUCTURE_AWARE,
                skipKg = true,
                buildStructure = false
            )
        )

    // ── search ─────────────────────────────────────────────────────────

    override suspend fun search(
        query: String,
        ownerUserIds: List<String>,
        topK: Int
    ): List<SkillEntry> {
        val bizIds = RagBizIdResolver.skillBizIds(ownerUserIds)
        if (bizIds.isEmpty()) return emptyList()
        // Over-fetch: the server ranks one global top-k over the union of slices, so without a
        // larger window an owner with many skills would crowd the other slices out entirely.
        // Still a single HTTP round trip.
        val chunks = runCatchingRag("search bizIds=$bizIds") {
            client.search(query = query, topK = topK * bizIds.size, bizIds = bizIds)
        } ?: // A server rejecting the set (400 on an element) must not lose discovery:
        // degrade to the first slice, which is the requesting user's own.
        runCatchingRag("search degraded bizId=${bizIds.first()}") {
            client.search(query = query, topK = topK, bizId = bizIds.first())
        } ?: return emptyList()
        return mergeByOwner(chunks, bizIds, topK)
    }

    /**
     * Parse chunks, re-apply the per-owner quota, and merge.
     *
     * Deduplication runs over the slices in the order the owners were given, so an earlier owner
     * (the requesting user) shadows a same-named skill of a later one (the shared `system` layer)
     * instead of letting the server-side score decide ownership.
     */
    private fun mergeByOwner(
        chunks: List<RagChunk>,
        bizIds: List<String>,
        topK: Int
    ): List<SkillEntry> {
        val perOwner = bizIds.associateWith { mutableListOf<SkillEntry>() }
        for (chunk in chunks) {
            val bucket = perOwner[chunk.bizId] ?: continue
            val entry = entryFromChunk(chunk) ?: continue
            bucket.add(entry)
        }
        return perOwner.values
            .flatMap { it.sortedByDescending { e -> e.score ?: 0.0 }.take(topK) }
            .distinctBy { it.name }
            .sortedByDescending { e -> e.score ?: 0.0 }
            .take(topK)
    }

    // ── delete ──────────────────────────────────────────────────────

    override suspend fun ensureAbsent(name: String, owner: SkillOwnerContext): SkillDeleteResult {
        val bizId = RagBizIdResolver.skillBizId(owner.userId)
        val externalId = RagConstants.externalIdOf(keyOf(name))
        return try {
            try {
                client.delete(externalId, bizId)
            } catch (e: RagException) {
                if (e.statusCode != 404) throw e
            }
            if (client.inspectByExternalId(externalId, bizId) == null) SkillDeleteResult.Absent
            else SkillDeleteResult.Failed("Remote document still exists after deletion")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SkillDeleteResult.Failed(e.message ?: "Skill deletion failed")
        }
    }

    // ── Mapping helpers ────────────────────────────────────────────────

    /** Best-effort user extraction from `u_{user}[_s]`, for log-inspectable metadata. */
    private fun userOfBizId(bizId: String): String {
        val body = bizId.removePrefix("u_")
        val user = body.substringBefore('_').substringBefore('-')
        return user.ifBlank { "system" }
    }

    private fun keyOf(name: String): String = SkillEntry.keyFor(name)

    /**
     * Business time prefers the on-disk SKILL.md mtime: repeated reconciliation of unchanged
     * content then keeps a stable document date instead of pushing every skill to "just now".
     */
    private fun createTimeOf(entry: SkillEntry): Long {
        val location = entry.location
        if (location != null) {
            val file = File(location)
            val modified = file.lastModified()
            if (modified > 0) return modified / 1000
        }
        return Instant.now().epochSecond
    }

    private fun entryFromChunk(chunk: RagChunk): SkillEntry? {
        val parsed = parseChunkToEntry(chunk.content, chunk.filePath) ?: return null
        val description = parsed.description.ifBlank { chunk.metadata["description"] as? String ?: "" }
        val tags = parsed.tags.ifEmpty {
            (chunk.metadata["tags"] as? String)
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        }
        if (description == parsed.description && tags == parsed.tags && chunk.score == null) {
            return parsed
        }
        return parsed.copy(description = description, tags = tags, score = chunk.score)
    }

    /**
     * Rebuild an entry from a stored document chunk.
     * [filePath] is the EasyRAG logical path `easyai/skills/{name}.md`, the fallback name source
     * when a later chunk carries no frontmatter.
     */
    private fun parseChunkToEntry(content: String, filePath: String?): SkillEntry? {
        val (frontmatter, body) = splitFrontmatter(content)
        val meta = parseFrontmatter(frontmatter)
        val fallbackName = relativePathOf(filePath)?.substringAfterLast('/')?.removeSuffix(".md")
        val name = meta["name"] ?: fallbackName
        if (name.isNullOrBlank()) {
            logger.debug("Skipping RAG chunk without skill name: {}", filePath)
            return null
        }
        return SkillEntry(
            key = relativePathOf(filePath) ?: keyOf(name),
            name = name,
            description = meta["description"] ?: "",
            tags = meta["tags"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            examples = meta["examples"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            content = body.trim(),
            location = meta["location"],
            origin = meta["origin"]
        )
    }

    /** Strip the `easyai/` prefix, returning `skills/{name}.md` or null. */
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

    /** Build the stored document: YAML frontmatter + Markdown body (mirrors [RagMemoryStore]). */
    private fun buildFileContent(entry: SkillEntry): String = buildString {
        appendLine(FRONTMATTER_DELIMITER)
        appendLine("name: ${quoteYamlValue(entry.name)}")
        appendLine("description: ${quoteYamlValue(entry.description)}")
        if (entry.tags.isNotEmpty()) {
            appendLine("tags: ${entry.tags.joinToString(",") { quoteYamlValue(it) }}")
        }
        if (entry.examples.isNotEmpty()) {
            appendLine("examples: ${entry.examples.joinToString(",") { quoteYamlValue(it) }}")
        }
        entry.origin?.let { appendLine("origin: ${quoteYamlValue(it)}") }
        entry.location?.let { appendLine("location: ${quoteYamlValue(it)}") }
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

    /**
     * Log-and-continue wrapper: the skill discovery path never propagates RAG failures.
     *
     * Catches every non-cancellation exception, not just [RagException]: the KDoc promise that
     * "a RAG outage must never break the agent loop or startup" also has to hold for transport
     * failures that surface as raw [java.io.IOException], JSON parse errors, or client-library
     * runtime exceptions before they can be wrapped into [RagException]. [CancellationException]
     * is rethrown so structured concurrency stays intact.
     */
    private inline fun <T> runCatchingRag(operation: String, block: () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RagException) {
            logger.warn("Skill RAG {} degraded: {}", operation, e.message)
            null
        } catch (e: Exception) {
            logger.warn("Skill RAG {} failed unexpectedly ({}): {}", operation, e.javaClass.simpleName, e.message)
            null
        }

    private companion object {
        const val FRONTMATTER_DELIMITER = "---"

        /** Markdown heading-based chunking: no LLM cost, keeps frontmatter + body together. */
        const val CHUNK_METHOD_STRUCTURE_AWARE = "structure_aware"

        /** Caps upsert fan-out so a large skill set cannot starve the HTTP pool. */
        const val INDEX_CONCURRENCY = 4
    }
}

/**
 * Public factory for the RAG-backed [SkillStore]. The implementation class is internal;
 * auto-configuration obtains instances through this object.
 */
object RagSkillStores {

    @JvmStatic
    fun create(client: RagClient): SkillStore = RagSkillStore(client)
}
