package com.easy.easyai.core.media

/**
 * Which configuration layer a user's effective media entries came from.
 *
 * Surfaced so the UI can show what is actually in force — the user's own rows, the shared
 * `system` rows, or nothing at all. Mirrors [com.easy.easyai.core.storage.StorageSource].
 */
enum class MediaProviderSource {
    /** The user's own `model_provider_config` rows of this generation type. */
    USER,

    /** The shared `system` rows other users fall back to. */
    SYSTEM,

    /** No enabled configuration in the database for this kind. */
    NONE
}

/**
 * Read access point to media-generation model entries, one kind per generation tool.
 *
 * Entries are `model_provider_config` rows partitioned by `model_type` (user rows plus shared
 * `system` rows are visible together; [MediaProviderSource] reports which owner the first entry
 * belongs to). With no R2DBC or no stored rows the resolver answers empty everywhere and the
 * corresponding generation tool hides itself. [refresh] invalidates cached entries so a saved
 * model takes effect without a restart, mirroring [com.easy.easyai.core.storage.ObjectStorageResolver].
 */
interface MediaProviderResolver {

    /**
     * Every effective entry for [serviceKind] visible to [owners], in stable order; empty when
     * nothing is configured. Owners are consulted in priority order (self → group → system) and the
     * first layer that has enabled rows shadows the rest.
     */
    suspend fun resolveEntries(owners: Collection<String>, serviceKind: String): List<MediaProviderSettings>

    /**
     * The single entry a tool should use for an optional `model` name, resolved within [owners].
     *
     * Match order: [model] equal to a entry's model id (ignoring case), then its display name,
     * then — when [model] is blank or unmatched — the default entry, a sole entry, the first.
     * Null when the kind has no effective entry.
     */
    suspend fun resolveEntry(owners: Collection<String>, serviceKind: String, model: String?): MediaProviderSettings?

    /** Which layer [resolveEntries] reads for [owners] (or [MediaProviderSource.NONE] when it yields nothing). */
    suspend fun sourceOf(owners: Collection<String>, serviceKind: String): MediaProviderSource

    /** Single-owner convenience form of [resolveEntries]; the shared `system` layer still folds in. */
    suspend fun resolveEntries(userId: String, serviceKind: String): List<MediaProviderSettings> =
        resolveEntries(listOf(userId), serviceKind)

    /** Single-owner convenience form of [resolveEntry]. */
    suspend fun resolveEntry(userId: String, serviceKind: String, model: String?): MediaProviderSettings? =
        resolveEntry(listOf(userId), serviceKind, model)

    /** Single-owner convenience form of [sourceOf]. */
    suspend fun sourceOf(userId: String, serviceKind: String): MediaProviderSource =
        sourceOf(listOf(userId), serviceKind)

    /**
     * Drop cached entries so the next [resolveEntries] re-reads the configuration.
     *
     * Refreshing the shared `system` owner must also invalidate every user that falls back to
     * those rows; implementations are expected to handle that case broadly (e.g. drop all). For any
     * other owner, every cached entry whose owner set contains it is evicted, so one group-owner
     * save clears the whole group's cached resolution.
     */
    fun refresh(userId: String)
}
