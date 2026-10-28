package com.easy.easyai.core.skill

/**
 * Async store for the `skill` catalog table — existence, ownership & lifecycle source of truth.
 *
 * Interface lives in `easyai-core` (implemented by `R2dbcAsyncSkillCatalogStore` in
 * `easyai-repository`) following the [com.easy.easyai.core.command.AsyncUserCommandStore]
 * precedent, so `easyai-skills` can reach the catalog layer without depending on the
 * repository module.
 *
 * Every mutation is strictly owner-scoped. Rows are addressed by the **(user_id, name)**
 * pair: one user's skill and the `system` shared layer are distinct rows, and a name is unique
 * inside each owner.
 */
interface AsyncSkillCatalogStore {

    /** Insert only, returning the existing row on (userId, name) conflict without modifying it. */
    suspend fun claim(entry: SkillCatalogEntry): SkillCatalogEntry

    suspend fun findById(id: String): SkillCatalogEntry?

    /** CAS on one package snapshot. [enable] is used only by an explicit enable request. */
    suspend fun updateContent(
        id: String, expectedRevision: Long, checksum: String, version: String, enable: Boolean = false
    ): Boolean

    /** CAS completion; never writes owner, enabled, observed checksum or install path. */
    suspend fun updateSync(id: String, expectedRevision: Long, update: SkillSyncUpdate): Boolean

    /**
     * Take a short lease on one row before a background pass touches its directory or its index
     * document, so two processes serving one database do not both do that work.
     *
     * `next_attempt_at` carries the lease because it already means "the earliest instant this row may
     * be worked on again". [updateSync]'s `revision` is deliberately left alone: it is the content CAS
     * every writer relies on, and bumping it here would invalidate the CAS the lease holder is about
     * to perform.
     *
     * @param expectedNextAttemptAt the value read with the row; null when the column was null
     * @return true when this caller holds the lease, false when someone else took it first
     */
    suspend fun claimForSync(id: String, expectedNextAttemptAt: Long?, leaseUntil: Long): Boolean

    /** The single row of [userId] carrying this [name]; null when the owner has no such skill. */
    suspend fun findByName(userId: String, name: String): SkillCatalogEntry?

    /** All rows owned by [userId]. */
    suspend fun listByUser(userId: String): List<SkillCatalogEntry>

    /** All rows owned by any of [userIds], in the order owners are given. */
    suspend fun listByOwners(userIds: List<String>): List<SkillCatalogEntry>

    /**
     * Distinct owners this application is responsible for synchronizing, plus the shared `system` layer.
     *
     * Startup reconciliation and background index retries use this without a request context. Account
     * ownership is derived from `app_user.owner_app`; request-scoped owner sets, including product-defined
     * group buckets, continue to use [listByOwners] directly.
     */
    suspend fun listDistinctUserIds(): List<String>

    /** Toggle enablement for one row by primary key; false when no row has that [id]. */
    suspend fun setEnabled(id: String, enabled: Boolean): Boolean

    /** Remove one row by primary key; false when no row has that [id]. */
    suspend fun delete(id: String): Boolean
}
