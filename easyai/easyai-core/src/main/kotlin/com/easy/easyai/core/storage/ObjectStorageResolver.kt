package com.easy.easyai.core.storage

/**
 * Which configuration layer a user's effective [ObjectStorage] came from.
 *
 * Surfaced by the storage settings endpoint so the UI can show what is actually in force —
 * a saved row, the shared `system` row, or nothing at all.
 */
enum class StorageSource {
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
 * Resolution order per user: own DB row → `system` DB row → nothing. The database is the only
 * configuration source; with no R2DBC or no stored row, storage-dependent features stay off.
 * [refresh] invalidates a user's cached delegate, which is how a saved configuration takes
 * effect without a restart.
 *
 * Cross-tenant caveat of per-user isolation: an object lives in the bucket its owner configured,
 * so a bucket private to one user is unreachable for another, while every user that falls back to
 * the shared `system` row reads and writes the very same space.
 */
interface ObjectStorageResolver {

    /** The user's effective storage; null when nothing in the chain is enabled. */
    suspend fun resolve(userId: String): ObjectStorage?

    /** Which layer [resolve] would use (or [StorageSource.NONE] when it yields null). */
    suspend fun sourceOf(userId: String): StorageSource

    /**
     * Drop one user's cached delegate so the next [resolve] re-reads the configuration.
     *
     * Refreshing the shared `system` owner must also invalidate every user that falls back to
     * that row; implementations are expected to handle that case broadly (e.g. drop all).
     */
    fun refresh(userId: String)
}
