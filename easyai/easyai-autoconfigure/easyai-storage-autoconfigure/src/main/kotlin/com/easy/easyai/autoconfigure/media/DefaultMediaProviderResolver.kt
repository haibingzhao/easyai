package com.easy.easyai.autoconfigure.media

import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelType
import com.easy.easyai.core.media.MediaProviderResolver
import com.easy.easyai.core.media.MediaProviderSettings
import com.easy.easyai.core.media.MediaProviderSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves media-generation entries per user and kind from `model_provider_config` rows of the
 * matching [ModelType]. The store's user scoping already folds shared `system` rows into every
 * user's view; [sourceOf] reports whether the leading entry is the caller's own.
 *
 * Per `(user, kind)` entry lists are cached, and [refresh] (called by the model-config endpoint
 * right after a save or delete) is the whole hot-apply mechanism — no restart. An invalid stored
 * row is *not* fatal: media tools fail loudly at call time, so a corrupt row is dropped from the
 * list and, if nothing valid remains, the tool hides itself.
 *
 * The check-then-put race of the storage resolver is closed here with a per-key [Mutex]
 * single-flight so only one caller computes a given entry list.
 */
class DefaultMediaProviderResolver(
    private val store: ModelProviderConfigStore?
) : MediaProviderResolver {

    private val logger = LoggerFactory.getLogger(javaClass)

    private data class Effective(val entries: List<MediaProviderSettings>, val source: MediaProviderSource)

    private val cache = ConcurrentHashMap<String, Effective>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    override suspend fun resolveEntries(userId: String, serviceKind: String): List<MediaProviderSettings> =
        effective(userId, serviceKind).entries

    override suspend fun resolveEntry(
        userId: String,
        serviceKind: String,
        model: String?
    ): MediaProviderSettings? {
        val entries = resolveEntries(userId, serviceKind)
        if (entries.isEmpty()) return null
        val wanted = model?.trim()?.takeIf { it.isNotEmpty() }
            ?: return entries.firstOrNull { it.isDefault } ?: entries.first()
        return entries.firstOrNull { it.defaultModel.equals(wanted, ignoreCase = true) }
            ?: entries.firstOrNull { it.displayName.equals(wanted, ignoreCase = true) }
            ?: entries.firstOrNull { it.isDefault }
            ?: entries.first()
    }

    override suspend fun sourceOf(userId: String, serviceKind: String): MediaProviderSource =
        effective(userId, serviceKind).source

    override fun refresh(userId: String) {
        if (userId == SYSTEM_USER_ID) {
            // System rows are cached under every user without their own rows and those entries
            // cannot be traced back individually — drop the whole cache. Saves are rare enough.
            cache.keys.toList().forEach { cache.remove(it) }
            locks.keys.toList().forEach { locks.remove(it) }
        } else {
            val prefix = "$userId|"
            cache.keys.toList().filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
        }
    }

    private suspend fun effective(userId: String, serviceKind: String): Effective {
        val key = cacheKey(userId, serviceKind)
        cache[key]?.let { return it }
        // Single-flight: only one caller computes a given (user, kind); others await the lock and
        // then read the freshly-populated cache instead of racing to rebuild the same entry.
        val mutex = locks.getOrPut(key) { Mutex() }
        return mutex.withLock {
            cache[key] ?: compute(userId, serviceKind).also { cache[key] = it }
        }
    }

    private suspend fun compute(userId: String, serviceKind: String): Effective {
        val configStore = store ?: return Effective(emptyList(), MediaProviderSource.NONE)
        val modelType = toModelType(serviceKind)
            ?: return Effective(emptyList(), MediaProviderSource.NONE)
        val visible = configStore.getModelConfigs(modelType, userId).filter { it.enabled && it.isCustom }
        // Two layers: the caller's own rows shadow the shared `system` rows entirely.
        val userRows = visible.filter { it.userId == userId }
        val rows = userRows.ifEmpty { visible }
        val entries = rows.mapNotNull { row ->
            val settings = row.toMediaSettings(serviceKind)
            val problem = MediaProviderFactory.validate(settings)
            if (problem != null) {
                logger.warn("Ignoring invalid {} media entry '{}' for user '{}': {}", serviceKind, row.id, userId, problem)
                null
            } else settings
        }
        val source = when {
            rows.isEmpty() -> MediaProviderSource.NONE
            userRows.isNotEmpty() -> MediaProviderSource.USER
            else -> MediaProviderSource.SYSTEM
        }
        return Effective(entries, source)
    }

    private fun ModelProviderConfig.toMediaSettings(serviceKind: String) = MediaProviderSettings(
        id = id,
        displayName = name,
        enabled = enabled,
        serviceKind = serviceKind,
        providerType = protocol.name.lowercase(),
        baseUrl = baseUrl ?: "",
        apiKey = apiKey ?: "",
        defaultModel = modelId,
        options = mediaOptions ?: "",
        timeoutSeconds = timeoutSeconds,
        isDefault = isDefault
    )

    private fun cacheKey(userId: String, serviceKind: String): String = "$userId|$serviceKind"

    companion object {
        /** Shared fallback owner, matching the `user_id` column default. */
        const val SYSTEM_USER_ID = "system"

        /** Generation tool kind → config row type. */
        internal fun toModelType(serviceKind: String): ModelType? = when (serviceKind) {
            MediaProviderSettings.SERVICE_KIND_SPEECH -> ModelType.SPEECH
            MediaProviderSettings.SERVICE_KIND_IMAGE -> ModelType.IMAGE
            MediaProviderSettings.SERVICE_KIND_VIDEO -> ModelType.VIDEO
            MediaProviderSettings.SERVICE_KIND_MUSIC -> ModelType.MUSIC
            MediaProviderSettings.SERVICE_KIND_ASR -> ModelType.ASR
            else -> null
        }
    }
}
