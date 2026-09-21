package com.easy.easyai.core.storage

/**
 * Outcome of a save attempt on the settings write path.
 *
 * [Invalid] carries a human-readable structural complaint (missing bucket, unknown type) so the
 * frontend can show why the draft was rejected; [Unavailable] means persistence is off (no R2DBC),
 * which is a service-level condition rather than user error.
 */
sealed interface StorageSettingsResult {

    /** Persisted (and hot-applied); [settings] includes the merged, unmasked secrets. */
    data class Saved(val settings: StorageSettings) : StorageSettingsResult

    /** Refused before persisting; the draft never reaches the resolver. */
    data class Invalid(val reason: String) : StorageSettingsResult

    /** No store is configured (database disabled); the caller answers 503. */
    data object Unavailable : StorageSettingsResult
}

/**
 * Write-side companion to [ObjectStorageResolver]: persists per-user storage configuration and
 * applies it live, so the frontend Settings page needs no restart cycle.
 *
 * Lives in `easyai-core` so the web layer can reach it without depending on the storage SDK —
 * validation and connectivity probing are executed by the implementation, which owns them.
 */
interface StorageSettingsService {

    /** The owner's stored row, unmasked and server-side only; null when none exists. */
    suspend fun current(userId: String): StorageSettings?

    /** Which layer is actually in force for this user right now. */
    suspend fun effectiveSource(userId: String): StorageSource

    /**
     * Validate, persist and hot-apply one configuration.
     *
     * A blank `accessKeySecret` keeps the stored secret (the read path only ever shows a mask),
     * and an enabled draft is dry-run validated before it is written — a broken save is refused,
     * not applied.
     */
    suspend fun save(userId: String, settings: StorageSettings): StorageSettingsResult

    /** Round-trip a probe object against [settings] without persisting anything; null = success. */
    suspend fun probe(userId: String, settings: StorageSettings): String?
}
