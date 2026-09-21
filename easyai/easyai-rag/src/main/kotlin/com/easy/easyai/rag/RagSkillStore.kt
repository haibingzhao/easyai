package com.easy.easyai.rag

import com.easy.easyai.core.skill.SkillEntry
import com.easy.easyai.core.skill.SkillOwnerContext
import com.easy.easyai.core.skill.SkillScope
import com.easy.easyai.core.skill.SkillStore
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
 * - isolation: `biz_id` slices from [RagBizIdResolver] (`u_{userId}_s` /
 *   `u_{userId}-{seg}-{hash8}_s`), one slice per owner and granularity
 * - key layout: `skills/{name}.md` — GLOBAL and PROJECT may hold the same name, they are
 *   separate documents because they sit in separate slices
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

    override suspend fun index(
        entries: List<SkillEntry>,
        scope: SkillScope,
        owner: SkillOwnerContext,
        awaitIndexing: Boolean
    ): Int {
        if (entries.isEmpty()) return 0
        val bizId = bizIdOf(scope, owner)
        if (bizId == null) {
            logger.debug("Skill index skipped: PROJECT scope without a project path ({} entries)", entries.size)
            return 0
        }
        return indexToBiz(entries, bizId, awaitIndexing)
    }

    /** Upsert documents to one slice, bounded concurrency, log-and-continue per document. */
    private suspend fun indexToBiz(entries: List<SkillEntry>, bizId: String, awaitIndexing: Boolean): Int {
        var succeeded = 0
        for (chunk in entries.chunked(INDEX_CONCURRENCY)) {
            val results = coroutineScope {
                chunk.map { entry ->
                    async {
                        try {
                            client.upsert(documentOf(entry, bizId), bizId, awaitIndexing)
                            true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.warn("Skill index failed for {}: {}", entry.key, e.message)
                            false
                        }
                    }
                }.awaitAll()
            }
            succeeded += results.count { it }
        }
        logger.debug("Skill indexing submitted: {}/{} documents (bizId={})", succeeded, entries.size, bizId)
        return succeeded
    }

    private fun documentOf(entry: SkillEntry, bizId: String): RagDocument =
        RagDocument(
            key = entry.key.ifBlank { keyOf(entry.name) },
            content = buildFileContent(entry),
            metadata = buildMap {
                put("name", entry.name)
                put("description", entry.description)
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
        scopes: List<SkillScope>,
        owner: SkillOwnerContext,
        topK: Int
    ): List<SkillEntry> {
        val bizIds = RagBizIdResolver.skillBizIds(scopes, owner)
        if (bizIds.isEmpty()) return emptyList()
        // Over-fetch: the server ranks one global top-k over the union of slices, so without a
        // larger window a project with many skills would crowd the global slice out entirely.
        // Still a single HTTP round trip.
        val chunks = runCatchingRag("search bizIds=$bizIds") {
            client.search(query = query, topK = topK * bizIds.size, bizIds = bizIds)
        } ?: // A server rejecting the set (400 on an element) must not lose discovery:
        // degrade to the global slice alone, the one address that always exists.
        runCatchingRag("search degraded bizId=${bizIds.first()}") {
            client.search(query = query, topK = topK, bizId = bizIds.first())
        } ?: return emptyList()
        return mergeBySlice(chunks, owner, scopes, topK)
    }

    /**
     * Parse chunks, re-apply the per-slice quota, and merge.
     *
     * Deduplication is keyed on `(scope, name)` — not `name` — precisely because GLOBAL and
     * PROJECT skills may legitimately share a name; collapsing them would hide one granularity
     * from the agent.
     */
    private fun mergeBySlice(
        chunks: List<RagChunk>,
        owner: SkillOwnerContext,
        scopes: List<SkillScope>,
        topK: Int
    ): List<SkillEntry> {
        val perSlice = scopes.associateWith { mutableListOf<SkillEntry>() }
        for (chunk in chunks) {
            val scope = RagBizIdResolver.skillScopeOf(chunk.bizId, owner) ?: continue
            val entry = entryFromChunk(chunk, owner) ?: continue
            perSlice[scope]?.add(entry)
        }
        return perSlice.values
            .flatMap { it.sortedByDescending { e -> e.score ?: 0.0 }.take(topK) }
            .distinctBy { it.scope to it.name }
            .sortedByDescending { e -> e.score ?: 0.0 }
            .take(topK)
    }

    // ── delete ──────────────────────────────────────────────────────

    override suspend fun delete(name: String, scope: SkillScope, owner: SkillOwnerContext): Boolean {
        val bizId = bizIdOf(scope, owner) ?: return false
        val externalId = RagConstants.externalIdOf(keyOf(name))
        return runCatchingRag("delete $externalId") { client.delete(externalId, bizId) } ?: false
    }

    // ── Mapping helpers ────────────────────────────────────────────────

    /** Derive the EasyRAG slice; null when PROJECT scope has no project path to address. */
    private fun bizIdOf(scope: SkillScope, owner: SkillOwnerContext): String? = when (scope) {
        SkillScope.GLOBAL -> RagBizIdResolver.globalBizId(owner.userId, RagBizIdResolver.SKILL_TYPE)
        SkillScope.PROJECT -> RagBizIdResolver.projectBizId(owner.userId, owner.projectPath, RagBizIdResolver.SKILL_TYPE)
    }

    /** Best-effort user extraction from `u_{user}[_s]` / `u_{user}-{seg}-{hash}_s`. */
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

    private fun entryFromChunk(chunk: RagChunk, owner: SkillOwnerContext): SkillEntry? {
        val parsed = parseChunkToEntry(chunk.content, chunk.filePath) ?: return null
        val description = parsed.description.ifBlank { chunk.metadata["description"] as? String ?: "" }
        val tags = parsed.tags.ifEmpty {
            (chunk.metadata["tags"] as? String)
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        }
        val scope = RagBizIdResolver.skillScopeOf(chunk.bizId, owner)
        if (description == parsed.description && tags == parsed.tags && scope == null && chunk.score == null) {
            return parsed
        }
        return parsed.copy(description = description, tags = tags, scope = scope, score = chunk.score)
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
