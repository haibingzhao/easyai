package com.easy.easyai.core.skill

/**
 * Async store for the `skill` catalog table — directory & lifecycle source of truth.
 *
 * Interface lives in `easyai-core` (implemented by `R2dbcAsyncSkillCatalogStore` in
 * `easyai-repository`) following the [com.easy.easyai.core.command.AsyncUserCommandStore]
 * precedent, so `easyai-skills` can reach the catalog layer without depending on the
 * repository module.
 *
 * Every mutation is strictly owner-scoped. Rows are addressed by the **(user_id, name,
 * project_hash)** triple, so a GLOBAL skill and same-named PROJECT skills of different
 * workspaces coexist as distinct rows. Reads that must pick *which* granularity a request sees
 * go through [listByName] and resolve the row one layer up, where the requesting project path is
 * known; nothing here guesses a single row from a bare name.
 */
interface AsyncSkillCatalogStore {

    /** Insert or update the row keyed by `(userId, name, projectHash)`; returns the persisted entry. */
    suspend fun upsert(entry: SkillCatalogEntry): SkillCatalogEntry

    /**
     * Every row of [userId] carrying this [name], across all granularities. Empty when the user
     * owns no such skill. The caller picks which row applies using the requesting project path.
     */
    suspend fun listByName(name: String, userId: String): List<SkillCatalogEntry>

    /** All rows owned by [userId]. */
    suspend fun listByUser(userId: String): List<SkillCatalogEntry>

    /**
     * Every row across every owner.
     *
     * Reserved for startup passes that need a global view in one round trip (e.g. deriving the
     * set of project roots to re-scan). Request-scoped code must keep using [listByUser] or
     * [listByName] so tenant isolation stays a storage-layer invariant.
     */
    suspend fun listAll(): List<SkillCatalogEntry>

    /**
     * Distinct owners present in the catalog.
     *
     * This is what lets startup reconciliation enumerate per-user slices without a request
     * context: owners come from persisted rows instead of being guessed from "the current user".
     */
    suspend fun listDistinctUserIds(): List<String>

    /** Toggle enablement for one row by primary key; false when no row has that [id]. */
    suspend fun setEnabled(id: String, enabled: Boolean): Boolean

    /** Record a new content fingerprint after the on-disk SKILL.md changed. */
    suspend fun updateChecksum(id: String, checksum: String, version: String): Boolean

    /** Remove one row by primary key; false when no row has that [id]. */
    suspend fun delete(id: String): Boolean
}
