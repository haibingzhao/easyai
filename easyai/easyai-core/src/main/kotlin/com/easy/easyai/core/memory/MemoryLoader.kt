package com.easy.easyai.core.memory

import com.easy.easyai.core.agent.AgentContext

/**
 * Reference to a single memory entry that was injected into the system prompt.
 */
data class MemoryRef(
    val name: String,
    val description: String,
    val type: MemoryType,
    val scope: MemoryScope
)

/**
 * Result of loading system memory, containing the formatted index content
 * for prompt injection.
 *
 * Only the lightweight index summary (name + description per entry) is injected.
 * Full content is accessed on demand via memory_read / memory_search tools,
 * which record accesses to [MemoryAccessTracker] for accurate reference tracking.
 */
data class MemoryLoadResult(
    val formattedContent: String
)

/**
 * Loads memory index summary for injection into the system prompt.
 *
 * Implements the index-only injection strategy:
 * - Only the index summary (name + description) is injected into the system prompt.
 * - Full content is accessed on demand via memory_read / memory_search tools.
 * - Accessed entries are tracked via [MemoryAccessTracker] for accurate Reference display.
 *
 * @param store The memory store to load from.
 */
class MemoryLoader(
    private val store: MemoryStore
) {

    /**
     * Load memory index for system prompt injection.
     *
     * @param agentContext Agent context providing runtime project path.
     * @return Formatted memory index string, or empty string if no memories exist.
     */
    suspend fun loadSystemMemory(agentContext: AgentContext): String {
        return loadSystemMemoryWithRefs(agentContext).formattedContent
    }

    /**
     * Load memory index with structured result.
     *
     * Returns the formatted index string containing name and description for all entries.
     * Full content is not loaded — the LLM uses memory_read/memory_search tools on demand.
     *
     * @param agentContext Agent context providing runtime project path.
     * @return [MemoryLoadResult] with formatted index content.
     */
    suspend fun loadSystemMemoryWithRefs(agentContext: AgentContext): MemoryLoadResult {
        val workspacePath = agentContext.projectPath

        val globalEntries = store.list(agentContext, MemoryScope.GLOBAL)
        val projectEntries = if (workspacePath != null) {
            store.list(agentContext, MemoryScope.PROJECT)
        } else {
            emptyList()
        }

        if (globalEntries.isEmpty() && projectEntries.isEmpty()) {
            return MemoryLoadResult("")
        }

        val formatted = buildString {
            appendLine("# Memory")
            appendLine()

            if (globalEntries.isNotEmpty()) {
                appendLine("## Global Memory")
                globalEntries
                    .sortedBy { it.type.dirName }
                    .forEach { entry ->
                        appendLine("- [${entry.name}](${entry.path}) — ${entry.description}")
                    }
                appendLine()
            }

            if (projectEntries.isNotEmpty()) {
                appendLine("## Project Memory")
                projectEntries
                    .sortedBy { it.type.dirName }
                    .forEach { entry ->
                        appendLine("- [${entry.name}](${entry.path}) — ${entry.description}")
                    }
                appendLine()
            }

            appendLine("Use memory_search to find specific memories. Use memory_read to read full content.")
            appendLine("Use memory_write to save new memories or update existing ones.")
        }

        return MemoryLoadResult(formatted)
    }
}
