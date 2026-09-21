package com.easy.easyai.core.media

/**
 * Outcome of a save attempt on the media-provider write path.
 *
 * Mirrors [com.easy.easyai.core.storage.StorageSettingsResult]: [Invalid] carries a human-readable
 * structural complaint so the frontend can show why the draft was rejected; [Unavailable] means
 * persistence is off (no R2DBC), a service-level condition rather than user error.
 */
sealed interface MediaProviderResult {

    /** Persisted (and hot-applied); [settings] includes the merged, unmasked secrets. */
    data class Saved(val settings: MediaProviderSettings) : MediaProviderResult

    /** Refused before persisting; the draft never reaches the resolver. */
    data class Invalid(val reason: String) : MediaProviderResult

    /** No store is configured (database disabled); the caller answers 503. */
    data object Unavailable : MediaProviderResult
}

/**
 * Write-side companion to [MediaProviderResolver]: persists per-user media-generation credentials
 * and applies them live, so the frontend Settings page needs no restart cycle.
 *
 * Lives in `easyai-core` so the web layer can reach it without depending on the repository or
 * vendor SDKs — validation and connectivity probing are executed by the implementation.
 */
interface MediaProviderService {

    /** The owner's stored row for one kind, unmasked and server-side only; null when none exists. */
    suspend fun current(userId: String, serviceKind: String): MediaProviderSettings?

    /** Every stored row for the owner, across all service kinds. */
    suspend fun list(userId: String): List<MediaProviderSettings>

    /** Which layer is actually in force for this user + kind right now. */
    suspend fun effectiveSource(userId: String, serviceKind: String): MediaProviderSource

    /**
     * Validate, persist and hot-apply one configuration.
     *
     * A blank `apiKey`/`accessKeySecret` keeps the stored value (the read path only ever shows a
     * mask), and an enabled draft is dry-run validated before it is written — a broken save is
     * refused, not applied.
     */
    suspend fun save(userId: String, settings: MediaProviderSettings): MediaProviderResult

    /** Structural + light connectivity probe against [settings] without persisting; null = success. */
    suspend fun probe(userId: String, settings: MediaProviderSettings): String?
}
