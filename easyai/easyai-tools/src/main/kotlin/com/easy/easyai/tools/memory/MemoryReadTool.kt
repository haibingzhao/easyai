package com.easy.easyai.tools.memory

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.memory.MemoryEntry
import com.easy.easyai.core.memory.MemoryOwnerContext
import com.easy.easyai.core.memory.MemoryRef
import com.easy.easyai.core.memory.MemoryScope
import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import kotlinx.coroutines.CoroutineScope
import org.slf4j.LoggerFactory

internal class MemoryReadTool(
    metadata: ToolMetadata,
    private val store: MemoryStore
) : BaseToolDefinition(metadata) {

    private val logger = LoggerFactory.getLogger(javaClass)

    data class Parameters(
        @field:JsonPropertyDescription("Relative path of an active entry, e.g. 'feedback/testing.md'.")
        val path: String? = null,

        @field:JsonPropertyDescription(
            "docIds of the source entries a merged entry absorbed, taken from its 'sources' list. " +
                "Returns the full archived bodies, in the order given, at most " +
                "$MAX_DRILL_DOWN_IDS ids per call. A real JSON array of strings."
        )
        val docIds: List<String>? = null
    )

    override fun parameterType(): Class<*> = Parameters::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val normalized = HashMap<String, Any?>(args)
        args["docIds"]?.let { normalized["docIds"] = coerceToStringList(it) }
        val params = try {
            SharedObjectMapper.instance.convertValue(normalized, Parameters::class.java)
        } catch (e: Exception) {
            return errorResult("Error: Invalid parameters: ${e.message}")
        }

        val docIds = params.docIds?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()
        if (!docIds.isNullOrEmpty()) return readByDocIds(docIds, agentContext)

        val path = params.path
        if (path.isNullOrBlank()) {
            return errorResult(
                "Error: pass 'path' for an active entry, or 'docIds' for the archived sources of a merged entry."
            )
        }
        return readByPath(path, agentContext)
    }

    /** Read one active entry by path and record the access as a freshness signal. */
    private suspend fun readByPath(path: String, agentContext: AgentContext): ToolResult {
        val owner = MemoryOwnerContext(agentContext.userId, agentContext.projectPath)

        // Try project scope first, then global
        for (scope in listOf(MemoryScope.PROJECT, MemoryScope.GLOBAL)) {
            val content = store.read(path, scope, owner)
            if (content != null) {
                // Record access from the authoritative entry: touch marks the retrieval day and
                // returns the entry, replacing the old whole-scope list that cost one request
                // per stored entry.
                try {
                    val entry = store.touch(path, scope, owner)
                    if (entry != null) {
                        agentContext.memoryAccessTracker.recordAccess(MemoryRef(entry.name, entry.description, entry.type, scope))
                    } else {
                        logger.debug("Memory entry not found for path: {} (scope: {})", path, scope)
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to record memory access for path: {} (scope: {}): {}", path, scope, e.message)
                }
                return ToolResult(content = listOf(TextContent(content)))
            }
        }
        return errorResult("Memory file not found: $path")
    }

    /**
     * Drill down into the archived sources of a merged entry. Read-only by construction: this path
     * never touches an entry, because touching reads an entry back and rewrites it, and rewriting a
     * history document is how its `archived`/`level`/`ref_ids` columns get flattened.
     */
    private suspend fun readByDocIds(docIds: List<String>, agentContext: AgentContext): ToolResult {
        if (docIds.size > MAX_DRILL_DOWN_IDS) {
            return errorResult(
                "Error: at most $MAX_DRILL_DOWN_IDS docIds per call, ${docIds.size} were requested. " +
                    "Split them into separate calls."
            )
        }
        val owner = MemoryOwnerContext(agentContext.userId, agentContext.projectPath)

        // One docId lives in exactly one biz_id slice and the caller cannot know which, so every id still
        // unresolved is asked of the next scope. A scope whose read failed is recorded separately: an
        // unavailable backend has not shown that its ids are absent.
        val found = mutableMapOf<String, Pair<MemoryScope, MemoryEntry>>()
        val failedScopes = mutableListOf<String>()
        for (scope in listOf(MemoryScope.PROJECT, MemoryScope.GLOBAL)) {
            val pending = docIds.filter { !found.containsKey(it) }
            if (pending.isEmpty()) break
            val result = runCatching { store.findByIds(pending, scope, owner) }
                .onFailure { logger.warn("Memory drill-down failed in scope {}: {}", scope, it.message) }
            result.getOrNull()?.forEach { entry ->
                val id = entry.id
                if (id != null && pending.contains(id)) {
                    found[id] = scope to entry
                }
            }
            if (result.isFailure) failedScopes.add(scope.name.lowercase())
        }

        val missing = docIds.filter { !found.containsKey(it) }
        if (found.isEmpty()) {
            return errorResult(
                "No memory document was returned for these docIds${failureNote(failedScopes)}: ${docIds.joinToString(", ")}"
            )
        }

        // Preserve the order the caller listed: a merged entry's sources are ordered the way they should
        // be read, and re-sorting them by scope would break that.
        val text = StringBuilder("Drilled down ${found.size} of ${docIds.size} memory documents:\n\n")
        docIds.forEach { id ->
            val (scope, entry) = found[id] ?: return@forEach
            text.appendLine(formatEntry(scope, id, entry))
            text.appendLine()
            text.appendLine(entry.content)
            if (entry.refs.isNotEmpty()) {
                text.appendLine()
                text.appendLine("deeper sources: ${entry.refs.joinToString(", ")}")
            }
            text.appendLine()
            text.appendLine("──────────")
        }
        if (missing.isNotEmpty()) {
            text.appendLine()
            text.appendLine("Not returned by either scope: ${missing.joinToString(", ")}${failureNote(failedScopes)}")
        }
        return ToolResult(content = listOf(TextContent(text.toString().trimEnd())))
    }

    private fun formatEntry(scope: MemoryScope, docId: String, entry: MemoryEntry): String {
        val merged = entry.mergedInto?.let { " | merged into: $it" } ?: ""
        return "[${scope.name.lowercase()}:${entry.path}] ${entry.name} — ${entry.description}\n" +
            "docId: $docId | archived: ${entry.archived} | level: ${entry.level}$merged | " +
            "updated: ${entry.updated ?: "unknown"}"
    }

    private fun failureNote(failedScopes: List<String>): String =
        if (failedScopes.isEmpty()) "" else " (lookup failed in ${failedScopes.joinToString(", ")}, so absence is unproven there)"

    private companion object {
        /** DocIds accepted per drill-down call; full archived bodies are untruncated. */
        const val MAX_DRILL_DOWN_IDS = 10
    }
}
