package com.easy.easyai.core.memory

import java.nio.file.Path

/**
 * Ownership context for memory operations, used to derive backend-level
 * isolation (e.g. EasyRAG biz_id slices).
 *
 * @param userId Authenticated user id; null falls back to the system tenant.
 * @param projectPath Runtime project path; required for [MemoryScope.PROJECT]
 *   writes, absent means project-scoped reads degrade to empty.
 */
data class MemoryOwnerContext(
    val userId: String? = null,
    val projectPath: Path? = null
)

/**
 * Storage interface for the memory system.
 *
 * All operations are scope-aware: callers specify [MemoryScope.GLOBAL] or [MemoryScope.PROJECT].
 *
 * Implementations must be thread-safe for concurrent read access.
 */
interface MemoryStore {

    /**
     * Load all memory entries for the given scope and return them as a formatted string
     * suitable for system prompt injection.
     *
     * @param scope Global or Project scope.
     * @param owner Ownership context for backend isolation.
     * @param totalCharLimit Maximum total characters to return. When exceeded, only the index
     *   summary is returned (degraded mode).
     * @param perFileCharLimit Maximum characters per individual entry content.
     */
    suspend fun loadAll(
        scope: MemoryScope,
        owner: MemoryOwnerContext = MemoryOwnerContext(),
        totalCharLimit: Int = 8000,
        perFileCharLimit: Int = 2200
    ): String

    /**
     * Search memory entries (semantic retrieval or keyword matching, depending on the backend).
     *
     * @param query Search keyword.
     * @param scope Global or Project scope.
     * @param owner Ownership context for backend isolation.
     * @param limit Maximum results to return.
     * @param timeRangeStart Optional business-time lower bound (epoch seconds).
     * @param timeRangeEnd Optional business-time upper bound (epoch seconds).
     */
    suspend fun search(
        query: String,
        scope: MemoryScope,
        owner: MemoryOwnerContext = MemoryOwnerContext(),
        limit: Int = 10,
        timeRangeStart: Long? = null,
        timeRangeEnd: Long? = null
    ): List<MemoryEntry>

    /**
     * Write (create or update) a memory entry. Idempotent: writing an existing entry
     * replaces its previous content.
     *
     * @param entry The entry to write.
     * @param scope Global or Project scope.
     * @param owner Ownership context for backend isolation.
     * @return Store-specific identifier of the written entry (e.g. logical path).
     */
    suspend fun write(entry: MemoryEntry, scope: MemoryScope, owner: MemoryOwnerContext = MemoryOwnerContext()): Path

    /**
     * Read a specific memory entry by relative path.
     *
     * @param path Relative path from memory root, e.g. "feedback/testing.md".
     * @param scope Global or Project scope.
     * @param owner Ownership context for backend isolation.
     * @return Entry content as string, or null if not found.
     */
    suspend fun read(path: String, scope: MemoryScope, owner: MemoryOwnerContext = MemoryOwnerContext()): String?

    /**
     * Delete a memory entry by relative path.
     *
     * @param path Relative path from memory root.
     * @param scope Global or Project scope.
     * @param owner Ownership context for backend isolation.
     * @return true if the entry was deleted, false if it did not exist.
     */
    suspend fun delete(path: String, scope: MemoryScope, owner: MemoryOwnerContext = MemoryOwnerContext()): Boolean

    /**
     * Delete all memory entries for the given scope.
     *
     * @param scope Global or Project scope.
     * @param owner Ownership context for backend isolation.
     * @return Number of entries deleted.
     */
    suspend fun deleteAll(scope: MemoryScope, owner: MemoryOwnerContext = MemoryOwnerContext()): Int

    /**
     * List all memory entries for the given scope.
     *
     * @param scope Global or Project scope.
     * @param owner Ownership context for backend isolation.
     * @param type Optional type filter.
     */
    suspend fun list(
        scope: MemoryScope,
        owner: MemoryOwnerContext = MemoryOwnerContext(),
        type: MemoryType? = null
    ): List<MemoryEntry>

    /**
     * Check if a memory entry exists by name.
     *
     * @param name Entry name identifier.
     * @param scope Global or Project scope.
     * @param owner Ownership context for backend isolation.
     */
    suspend fun exists(name: String, scope: MemoryScope, owner: MemoryOwnerContext = MemoryOwnerContext()): Boolean

    /**
     * Find a memory entry by name across all types.
     *
     * @param name Entry name identifier.
     * @param scope Global or Project scope.
     * @param owner Ownership context for backend isolation.
     * @return The entry if found, or null.
     */
    suspend fun findByName(
        name: String,
        scope: MemoryScope,
        owner: MemoryOwnerContext = MemoryOwnerContext()
    ): MemoryEntry?

    /**
     * Regenerate the store's index structure (no-op for backends without a local index).
     *
     * @param scope Global or Project scope.
     */
    suspend fun refreshIndex(scope: MemoryScope)
}
