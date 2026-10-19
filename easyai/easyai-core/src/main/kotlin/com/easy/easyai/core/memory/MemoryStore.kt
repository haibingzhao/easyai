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
 * Archive-related fields applied to an existing entry without rewriting its content.
 *
 * Only non-null fields are written by [MemoryStore.archive]; content and vectors are untouched,
 * so archiving never re-indexes.
 *
 * @property archived mark the entry as history (excluded from normal retrieval)
 * @property level consolidation depth to set
 * @property refs consolidated-entry source docIds
 * @property mergedInto docId of the consolidated entry that absorbed this source
 */
data class MemoryArchiveUpdate(
    val archived: Boolean? = null,
    val level: Int? = null,
    val refs: List<String>? = null,
    val mergedInto: String? = null
)

/**
 * One active memory as the consolidation grouping pass sees it: identity and shape, never the body.
 *
 * The grouping pass decides *which* entries belong together and must not pay for every body to do it —
 * a daily scheduler tick and a 300-entry slice both answer that question from names, sizes and depth.
 * The merge pass that follows still reads the real bodies, for the few clusters it is rewriting.
 *
 * @property docId stable backend id the output must cite
 * @property name entry file name without extension
 * @property type the memory type this entry is filed under
 * @property level consolidation depth; 0 for an untouched entry
 * @property contentChars stored body length in characters, so a cluster's total size is known before
 *   its bodies are ever fetched
 * @property blurb one-line summary, or an empty string when the backend cannot supply one cheaply
 */
data class MemoryIndexEntry(
    val docId: String,
    val name: String,
    val type: MemoryType,
    val level: Int,
    val contentChars: Int,
    val blurb: String
) {
    companion object {
        /**
         * A change detector for "has anything happened in this slice since the last pass".
         *
         * Only membership matters: archiving a source and writing its merged entry are the run's own
         * doing and must not re-dirty the slice, which is why the baseline is recomputed from the live
         * set *after* each run rather than before it. Editing a body in place keeps its docId, so an
         * edit alone never schedules a pass — that is the accepted cost of a cheap tick, and a user who
         * wants a pass can run one.
         */
        @JvmStatic
        fun fingerprint(docIds: Collection<String>): Int = docIds.filter { it.isNotEmpty() }.sorted().hashCode()
    }
}

/**
 * Storage interface for the memory system.
 *
 * All operations are scope-aware: callers specify [MemoryScope.GLOBAL] or [MemoryScope.PROJECT].
 *
 * Retrieval operations ([loadAll], [search], [list]) only see **active** entries; archived
 * entries are reachable exclusively through [findByIds].
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
     * Write (create or update) a memory entry and return it with backend-populated identity
     * (at minimum [MemoryEntry.id], the stable docId used by consolidation references).
     *
     * The default implementation delegates to [write] and only enriches [MemoryEntry.path], for
     * backends without document ids.
     */
    suspend fun writeEntry(
        entry: MemoryEntry,
        scope: MemoryScope,
        owner: MemoryOwnerContext = MemoryOwnerContext()
    ): MemoryEntry = entry.copy(path = write(entry, scope, owner).toString())

    /**
     * Read entries by backend document id, **including archived ones**. Missing ids are simply
     * absent from the result. This is the only retrieval path that exposes history entries.
     *
     * The default returns an empty list.
     */
    suspend fun findByIds(
        docIds: List<String>,
        scope: MemoryScope,
        owner: MemoryOwnerContext = MemoryOwnerContext()
    ): List<MemoryEntry> = emptyList()

    /**
     * Apply archive metadata to existing entries without rewriting content or re-indexing.
     * This is how consolidated sources are marked as history.
     *
     * @return number of entries actually updated.
     */
    suspend fun archive(
        docIds: List<String>,
        update: MemoryArchiveUpdate,
        scope: MemoryScope,
        owner: MemoryOwnerContext = MemoryOwnerContext()
    ): Int = 0

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
     * The active entries of one slice as [MemoryIndexEntry] rows: identity, depth and size, no bodies.
     *
     * Two callers need exactly this and nothing more. The scheduler reads a slice per tick to compare
     * counts against thresholds and to decide whether the set changed since the last pass — one listing
     * answers both, so a separate counting call would be a second read of the same answer. The
     * consolidation grouping pass builds its cluster proposals from it, and only the merge pass that
     * follows goes back to [findByIds] for the handful of bodies it is actually rewriting.
     *
     * Implementations that can list this at the source override it; the default materializes every
     * entry, which is correct but reads the whole store.
     *
     * @param scope Global or Project scope.
     * @param owner Ownership context for backend isolation.
     * @param type Optional type filter; null lists every type of the slice.
     */
    suspend fun listActiveIndex(
        scope: MemoryScope,
        owner: MemoryOwnerContext = MemoryOwnerContext(),
        type: MemoryType? = null
    ): List<MemoryIndexEntry> = list(scope, owner, type)
        .mapNotNull { entry ->
            entry.id?.let {
                MemoryIndexEntry(
                    docId = it,
                    name = entry.name,
                    type = entry.type,
                    level = entry.level,
                    contentChars = entry.content.length,
                    blurb = entry.description
                )
            }
        }

    /**
     * Read a specific memory entry by relative path, fully materialized.
     *
     * Unlike [read], the result carries the parsed metadata (type, description, dates,
     * maturity) instead of the raw stored document, resolved from the backend's
     * authoritative full text in a single lookup.
     *
     * @param path Relative path from memory root, e.g. "experience_lessons/frp-setup.md".
     * @param scope Global or Project scope.
     * @param owner Ownership context for backend isolation.
     * @return The entry if found, or null.
     */
    suspend fun readEntry(
        path: String,
        scope: MemoryScope,
        owner: MemoryOwnerContext = MemoryOwnerContext()
    ): MemoryEntry?

    /**
     * Record that an entry was retrieved today, so staleness ("unused for N days") can be
     * reported later. Implementations must read the backend's authoritative full document
     * before rewriting it and must throttle to at most one write per entry per day.
     *
     * HARD CONSTRAINT: never implement this by writing back an entry obtained from [search] —
     * a retrieved chunk may hold only part of the body, so rewriting it would silently
     * truncate the stored document.
     *
     * @param path Relative path from memory root.
     * @param scope Global or Project scope.
     * @param owner Ownership context for backend isolation.
     * @return The authoritative entry as stored after this call, or null when the entry does
     *   not exist. Callers reuse it instead of a separate [readEntry] round trip.
     */
    suspend fun touch(path: String, scope: MemoryScope, owner: MemoryOwnerContext = MemoryOwnerContext()): MemoryEntry?

    /**
     * Check if a memory entry exists by name.
     *
     * @param name Entry name identifier.
     * @param scope Global or Project scope.
     * @param owner Ownership context for backend isolation.
     * @param type Known category of the entry; when non-null the lookup is resolved
     *   directly instead of probing every type in the active domain.
     */
    suspend fun exists(
        name: String,
        scope: MemoryScope,
        owner: MemoryOwnerContext = MemoryOwnerContext(),
        type: MemoryType? = null
    ): Boolean

    /**
     * Find a memory entry by name.
     *
     * @param name Entry name identifier.
     * @param scope Global or Project scope.
     * @param owner Ownership context for backend isolation.
     * @param type Known category of the entry; null searches across all types of the
     *   active domain.
     * @param includeArchived Also resolve an entry absorbed by a consolidation run. Callers that
     *   write must set it: a history document lives at the same key, so a name that only matches it
     *   would otherwise be treated as free and the write would revive it as an active duplicate of
     *   the merged entry above it.
     * @return The entry if found, or null.
     */
    suspend fun findByName(
        name: String,
        scope: MemoryScope,
        owner: MemoryOwnerContext = MemoryOwnerContext(),
        type: MemoryType? = null,
        includeArchived: Boolean = false
    ): MemoryEntry?

    /**
     * Regenerate the store's index structure (no-op for backends without a local index).
     *
     * @param scope Global or Project scope.
     */
    suspend fun refreshIndex(scope: MemoryScope)
}
