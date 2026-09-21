package com.easy.easyai.autoconfigure.media

import com.easy.easyai.core.media.MediaProviderResult
import com.easy.easyai.core.media.MediaProviderResolver
import com.easy.easyai.core.media.MediaProviderService
import com.easy.easyai.core.media.MediaProviderSettings
import com.easy.easyai.core.media.MediaProviderSource
import com.easy.easyai.core.media.MediaProviderStore
import org.slf4j.LoggerFactory

/**
 * Write side of the media-provider settings: validate → persist → [MediaProviderResolver.refresh],
 * which is the entire hot-apply path. A broken draft is refused before it can shadow a working row,
 * and a blank secret on save keeps the stored one — the read endpoint only ever shows a mask, so
 * "resubmit what you saw" must never wipe a credential.
 *
 * Probing is structural only here ([MediaProviderFactory.validate]); vendor connectivity is checked
 * by the tools at call time, keeping this module free of any HTTP/SDK dependency and avoiding a
 * paid generate call behind a "Test" button.
 */
class DefaultMediaProviderService(
    private val store: MediaProviderStore?,
    private val resolver: MediaProviderResolver
) : MediaProviderService {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun current(userId: String, serviceKind: String): MediaProviderSettings? =
        store?.get(userId, serviceKind)

    override suspend fun list(userId: String): List<MediaProviderSettings> =
        store?.list(userId) ?: emptyList()

    override suspend fun effectiveSource(userId: String, serviceKind: String): MediaProviderSource =
        resolver.sourceOf(userId, serviceKind)

    override suspend fun save(userId: String, settings: MediaProviderSettings): MediaProviderResult {
        val settingsStore = store ?: return MediaProviderResult.Unavailable
        val stored = settingsStore.get(userId, settings.serviceKind)
        val merged = mergeSecret(settings, stored)
        if (merged.enabled) {
            MediaProviderFactory.validate(merged)?.let { return MediaProviderResult.Invalid(it) }
        }
        settingsStore.save(merged, userId)
        resolver.refresh(userId)
        logger.info(
            "Saved media provider settings for user '{}' kind '{}' (enabled={}, type={})",
            userId, merged.serviceKind, merged.enabled, merged.providerType
        )
        return MediaProviderResult.Saved(merged)
    }

    override suspend fun probe(userId: String, settings: MediaProviderSettings): String? {
        val merged = mergeSecret(settings, store?.get(userId, settings.serviceKind))
        return MediaProviderFactory.validate(merged)
    }

    /**
     * A blank secret means "unchanged" — the masked value the UI shows can never be typed back.
     * Both auth styles are preserved independently so an API-key user editing nothing keeps the key,
     * and an AK/SK user keeps the pair.
     */
    private suspend fun mergeSecret(
        settings: MediaProviderSettings,
        stored: MediaProviderSettings?
    ): MediaProviderSettings {
        if (stored == null) return settings
        var merged = settings
        if (settings.apiKey.isBlank()) merged = merged.copy(apiKey = stored.apiKey)
        if (settings.accessKeySecret.isBlank()) merged = merged.copy(accessKeySecret = stored.accessKeySecret)
        return merged
    }
}
