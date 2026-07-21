package com.easy.easyai.core.memory

import com.easy.easyai.core.agent.AgentContext
import java.nio.file.Path

/**
 * Storage interface for the memory system.
 *
 * All operations are scope-aware: callers specify [MemoryScope.GLOBAL] or [MemoryScope.PROJECT]
 * to determine which root directory is used. The [AgentContext] parameter provides runtime
 * project path information needed to resolve the PROJECT scope root.
 *
 * Implementations must be thread-safe for concurrent read access; write operations
 * should use atomic file writes (temp + rename) to prevent corruption.
 */
interface MemoryStore {

    /**
     * Load all memory entries for the given scope and return them as a formatted string
     * suitable for system prompt injection.
     *
     * @param agentContext Agent context providing runtime project path.
     * @param scope Global or Project scope.
     * @param totalCharLimit Maximum total characters to return. When exceeded, only the index
     *   summary is returned (degraded mode).
     * @param perFileCharLimit Maximum characters per individual entry content.
     */
    suspend fun loadAll(
        agentContext: AgentContext,
        scope: MemoryScope,
        totalCharLimit: Int = 8000,
        perFileCharLimit: Int = 2200
    ): String

    /**
     * Search memory entries by keyword using simple string matching (Phase 1).
     * Returns entries whose name, description, or content contain the query.
     *
     * @param agentContext Agent context providing runtime project path.
     * @param query Search keyword.
     * @param scope Global or Project scope.
     * @param limit Maximum results to return.
     */
    suspend fun search(agentContext: AgentContext, query: String, scope: MemoryScope, limit: Int = 10): List<MemoryEntry>

    /**
     * Write (create or update) a memory entry.
     * Writes the Markdown file with YAML frontmatter and updates the MEMORY.md index.
     *
     * Uses atomic write (temp + rename) to prevent corruption.
     *
     * @param agentContext Agent context providing runtime project path.
     * @param entry The entry to write.
     * @param scope Global or Project scope.
     * @return The resolved file path.
     */
    suspend fun write(agentContext: AgentContext, entry: MemoryEntry, scope: MemoryScope): Path

    /**
     * Read a specific memory file by relative path.
     *
     * @param agentContext Agent context providing runtime project path.
     * @param path Relative path from memory root, e.g. "feedback/testing.md".
     * @param scope Global or Project scope.
     * @return File content as string, or null if not found.
     */
    suspend fun read(agentContext: AgentContext, path: String, scope: MemoryScope): String?

    /**
     * Delete a memory file by relative path and update the MEMORY.md index.
     *
     * @param agentContext Agent context providing runtime project path.
     * @param path Relative path from memory root.
     * @param scope Global or Project scope.
     * @return true if the file was deleted, false if it did not exist.
     */
    suspend fun delete(agentContext: AgentContext, path: String, scope: MemoryScope): Boolean

    /**
     * Delete all memory entries for the given scope.
     *
     * @param agentContext Agent context providing runtime project path.
     * @param scope Global or Project scope.
     * @return Number of entries deleted.
     */
    suspend fun deleteAll(agentContext: AgentContext, scope: MemoryScope): Int

    /**
     * List all memory entries by scanning type directories.
     *
     * @param agentContext Agent context providing runtime project path.
     * @param scope Global or Project scope.
     * @param type Optional type filter.
     */
    suspend fun list(agentContext: AgentContext, scope: MemoryScope, type: MemoryType? = null): List<MemoryEntry>

    /**
     * Check if a memory entry exists by name.
     *
     * @param agentContext Agent context providing runtime project path.
     * @param name Entry name identifier.
     * @param scope Global or Project scope.
     */
    suspend fun exists(agentContext: AgentContext, name: String, scope: MemoryScope): Boolean

    /**
     * Find a memory entry by name, searching all type directories.
     *
     * @param agentContext Agent context providing runtime project path.
     * @param name Entry name identifier.
     * @param scope Global or Project scope.
     * @return The entry if found, or null.
     */
    suspend fun findByName(agentContext: AgentContext, name: String, scope: MemoryScope): MemoryEntry?

    /**
     * Regenerate the MEMORY.md index file by scanning all entries.
     *
     * @param agentContext Agent context providing runtime project path.
     * @param scope Global or Project scope.
     */
    suspend fun refreshIndex(agentContext: AgentContext, scope: MemoryScope)

    companion object {
        /**
         * Create a file-based [MemoryStore] instance.
         *
         * @param globalRoot Root directory for global memories (e.g. ~/.easyai/memory).
         * @param projectDir project relative path.
         */
        @JvmStatic
        fun fileBased(globalRoot: Path, projectDir: String): MemoryStore =
            FileMemoryStore(globalRoot, projectDir)
    }
}
