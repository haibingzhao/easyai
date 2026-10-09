package com.easy.easyai.core.storage

/**
 * Which configuration layer a user's effective [ObjectStorage] came from.
 *
 * Surfaced by the storage settings endpoint so the UI can show what is actually in force —
 * a saved row, the shared `system` row, or nothing at all.
 */
enum class StorageSource {
    /** A deployment-wide `easyai.storage.*` configuration, pinned above every database row. */
    STATIC,

    /** The user's own `storage_settings` row. */
    USER,

    /** The shared `system` row other users fall back to. */
    SYSTEM,

    /** No enabled configuration in the database. */
    NONE
}

/**
 * Per-user access point to object storage, replacing the single static `ObjectStorage` bean.
 *
 * Resolution order per owner set: a deployment-wide `easyai.storage.*` layer (when configured) →
 * own DB row → group DB row → `system` DB row → nothing. Owners are consulted in priority order
 * (self → group → system) and the first layer that has a row shadows the rest; an explicit
 * `enabled=false` row still shadows every lower layer. With no R2DBC and no static config,
 * storage-dependent features stay off. [refresh] invalidates cached delegates, which is how a
 * saved configuration takes effect without a restart.
 *
 * Cross-tenant caveat of per-user isolation: an object lives in the bucket its owner configured,
 * so a bucket private to one user is unreachable for another, while every user that falls back to
 * the shared `system` row reads and writes the very same space.
 */
interface ObjectStorageResolver {

    /**
     * The effective storage visible to [owners]; null when nothing in the chain is enabled. Owners
     * are consulted in priority order (self → group → system) and the first layer with a row wins.
     */
    suspend fun resolve(owners: Collection<String>): ObjectStorage?

    /** Which layer [resolve] would use for [owners] (or [StorageSource.NONE] when it yields null). */
    suspend fun sourceOf(owners: Collection<String>): StorageSource

    /** Single-owner convenience form of [resolve]; the shared `system` layer still folds in. */
    suspend fun resolve(userId: String): ObjectStorage? = resolve(listOf(userId))

    /** Single-owner convenience form of [sourceOf]. */
    suspend fun sourceOf(userId: String): StorageSource = sourceOf(listOf(userId))

    /**
     * Drop cached delegates so the next [resolve] re-reads the configuration.
     *
     * Refreshing the shared `system` owner must also invalidate every user that falls back to
     * that row; implementations are expected to handle that case broadly (e.g. drop all). For any
     * other owner, every cached entry whose owner set contains it is evicted, so one group-owner
     * save clears the whole group's cached resolution.
     */
    fun refresh(userId: String)
}

