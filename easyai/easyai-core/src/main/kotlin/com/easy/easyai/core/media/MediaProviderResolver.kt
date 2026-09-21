package com.easy.easyai.core.media

/**
 * Which configuration layer a user's effective [MediaProviderSettings] came from.
 *
 * Surfaced by the media settings endpoint so the UI can show what is actually in force —
 * a saved row, the shared `system` row, or nothing at all. Mirrors
 * [com.easy.easyai.core.storage.StorageSource].
 */
enum class MediaProviderSource {
    /** The user's own `media_provider_settings` row for this kind. */
    USER,

    /** The shared `system` row other users fall back to. */
    SYSTEM,

    /** No enabled configuration in the database for this kind. */
    NONE
}

/**
 * Per-user access point to media-generation credentials.
 *
 * Resolution order per user+kind: own DB row → `system` DB row → nothing. The database is the
 * only configuration source; with no R2DBC or no stored row the resolver answers null everywhere
 * and the corresponding generation tool hides itself. [refresh] invalidates cached entries so a
 * saved credential takes effect without a restart, mirroring
 * [com.easy.easyai.core.storage.ObjectStorageResolver].
 */
interface MediaProviderResolver {

    /** The user's effective credential for [serviceKind]; null when nothing in the chain is enabled. */
    suspend fun resolve(userId: String, serviceKind: String): MediaProviderSettings?

    /** Which layer [resolve] would use (or [MediaProviderSource.NONE] when it yields null). */
    suspend fun sourceOf(userId: String, serviceKind: String): MediaProviderSource

    /**
     * Drop cached entries so the next [resolve] re-reads the configuration.
     *
     * Refreshing the shared `system` owner must also invalidate every user that falls back to
     * that row; implementations are expected to handle that case broadly (e.g. drop all).
     */
    fun refresh(userId: String)
}
