package com.easy.easyai.core.media

/**
 * Persistence for per-user [MediaProviderSettings] — one row per `(user, serviceKind)`.
 *
 * Interface lives in `easyai-core` (implemented by `R2dbcMediaProviderStore` in
 * `easyai-repository`) so the media auto-configuration can reach it without depending on the
 * repository module, mirroring [com.easy.easyai.core.storage.StorageSettingsStore].
 */
interface MediaProviderStore {

    /** The owner's stored configuration for one service kind; null when never saved. */
    suspend fun get(userId: String, serviceKind: String): MediaProviderSettings?

    /** All stored rows for the owner, keyed by service kind; kinds without a row are absent. */
    suspend fun list(userId: String): List<MediaProviderSettings>

    /** Insert or replace the owner's row for the settings' service kind. */
    suspend fun save(settings: MediaProviderSettings, userId: String)

    /** Drop the owner's row for one kind, returning them to the shared fallback; false if absent. */
    suspend fun delete(userId: String, serviceKind: String): Boolean
}
