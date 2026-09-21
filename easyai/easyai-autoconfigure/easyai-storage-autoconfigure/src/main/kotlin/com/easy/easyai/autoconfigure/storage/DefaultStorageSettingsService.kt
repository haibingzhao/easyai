package com.easy.easyai.autoconfigure.storage

import com.easy.easyai.core.storage.ObjectStorageResolver
import com.easy.easyai.core.storage.StorageSettings
import com.easy.easyai.core.storage.StorageSettingsResult
import com.easy.easyai.core.storage.StorageSettingsService
import com.easy.easyai.core.storage.StorageSettingsStore
import com.easy.easyai.core.storage.StorageSource
import com.easy.easyai.storage.ObjectStorageFactory
import org.slf4j.LoggerFactory

/**
 * Write side of the storage settings: validate → persist → [ObjectStorageResolver.refresh], which
 * is the entire hot-apply path. A broken draft is refused before it can shadow a working row,
 * and a blank secret on save keeps the stored one — the read endpoint only ever shows a mask, so
 * "resubmit what you saw" must never wipe the credential.
 */
class DefaultStorageSettingsService(
    private val store: StorageSettingsStore?,
    private val resolver: ObjectStorageResolver
) : StorageSettingsService {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun current(userId: String): StorageSettings? = store?.get(userId)

    override suspend fun effectiveSource(userId: String): StorageSource = resolver.sourceOf(userId)

    override suspend fun save(userId: String, settings: StorageSettings): StorageSettingsResult {
        val settingsStore = store ?: return StorageSettingsResult.Unavailable
        val merged = mergeSecret(settings, settingsStore.get(userId))
        if (merged.enabled) {
            ObjectStorageFactory.validate(merged)?.let { return StorageSettingsResult.Invalid(it) }
        }
        settingsStore.save(merged, userId)
        resolver.refresh(userId)
        logger.info("Saved storage settings for user '{}' (enabled={}, type={})", userId, merged.enabled, merged.type)
        return StorageSettingsResult.Saved(merged)
    }

    override suspend fun probe(userId: String, settings: StorageSettings): String? {
        val merged = mergeSecret(settings, store?.get(userId))
        return ObjectStorageFactory.probe(merged, userId)
    }

    /** A blank secret means "unchanged" — the masked value the UI shows can never be typed back. */
    private suspend fun mergeSecret(settings: StorageSettings, stored: StorageSettings?): StorageSettings =
        if (settings.accessKeySecret.isBlank() && stored != null) {
            settings.copy(accessKeySecret = stored.accessKeySecret)
        } else {
            settings
        }
}
