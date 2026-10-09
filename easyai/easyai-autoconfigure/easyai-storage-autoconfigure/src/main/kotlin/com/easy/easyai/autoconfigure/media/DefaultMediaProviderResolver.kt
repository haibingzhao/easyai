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
 * Resolves media-generation entries per caller and kind from `model_provider_config` rows of the
 * matching [ModelType]. The caller passes its full visibility set (self → group → system); the
 * layers are consulted in order and the first one with enabled rows shadows the rest, so a member's
 * own rows win over the group bucket, which wins over the shared `system` rows. [sourceOf] reports
 * whether the leading layer is the shared `system` fallback or something caller-specific.
 *
 * Per `(owners, kind)` entry lists are cached, and [refresh] (called by the model-config endpoint
 * right after a save or delete) is the whole hot-apply mechanism — no restart. Refreshing the shared
 * `system` owner drops the entire cache (its rows fold into every caller's view and cannot be traced
 * back individually); refreshing any other owner evicts every cached entry whose owner set contains
 * it, so one group-owner save clears the whole group. An invalid stored row is *not* fatal: media
 * tools fail loudly at call time, so a corrupt row is dropped from the list and, if nothing valid
 * remains, the tool hides itself.
 *
 * The check-then-put race of the storage resolver is closed here with a per-key [Mutex]
 * single-flight so only one caller computes a given entry list.
 */
class DefaultMediaProviderResolver(
    private val store: ModelProviderConfigStore?
) : MediaProviderResolver {

    private val logger = LoggerFactory.getLogger(javaClass)

    private data class Effective(val entries: List<MediaProviderSettings>, val source: MediaProviderSource)

    /**
     * Cache key: the exact owner list a resolution was computed for, plus the generation kind.
     * Ordered, not a set — [compute] picks the winning layer by position, so two callers passing the
     * same owners in a different priority order must not share an entry.
     */
    private data class Key(val owners: List<String>, val serviceKind: String)

    private val cache = ConcurrentHashMap<Key, Effective>()
    private val locks = ConcurrentHashMap<Key, Mutex>()

    override suspend fun resolveEntries(owners: Collection<String>, serviceKind: String): List<MediaProviderSettings> =
        effective(owners, serviceKind).entries

    override suspend fun resolveEntry(
        owners: Collection<String>,
        serviceKind: String,
        model: String?
    ): MediaProviderSettings? {
        val entries = resolveEntries(owners, serviceKind)
        if (entries.isEmpty()) return null
        val wanted = model?.trim()?.takeIf { it.isNotEmpty() }
            ?: return entries.firstOrNull { it.isDefault } ?: entries.first()
        return entries.firstOrNull { it.defaultModel.equals(wanted, ignoreCase = true) }
            ?: entries.firstOrNull { it.displayName.equals(wanted, ignoreCase = true) }
            ?: entries.firstOrNull { it.isDefault }
            ?: entries.first()
    }

    override suspend fun sourceOf(owners: Collection<String>, serviceKind: String): MediaProviderSource =
        effective(owners, serviceKind).source

    override fun refresh(userId: String) {
        if (userId == SYSTEM_USER_ID) {
            // System rows fold into every caller's view and those entries cannot be traced back
            // individually — drop the whole cache. Saves are rare enough.
            cache.keys.toList().forEach { cache.remove(it) }
            locks.keys.toList().forEach { locks.remove(it) }
        } else {
            cache.keys.toList().filter { userId in it.owners }.forEach { cache.remove(it) }
        }
    }

    private suspend fun effective(owners: Collection<String>, serviceKind: String): Effective {
        val key = keyOf(owners, serviceKind) ?: return Effective(emptyList(), MediaProviderSource.NONE)
        cache[key]?.let { return it }
        // Single-flight: only one caller computes a given (owners, kind); others await the lock and
        // then read the freshly-populated cache instead of racing to rebuild the same entry.
        val mutex = locks.getOrPut(key) { Mutex() }
        return mutex.withLock {
            cache[key] ?: compute(key.owners, serviceKind).also { cache[key] = it }
        }
    }

    private suspend fun compute(owners: List<String>, serviceKind: String): Effective {
        val configStore = store ?: return Effective(emptyList(), MediaProviderSource.NONE)
        val modelType = toModelType(serviceKind)
            ?: return Effective(emptyList(), MediaProviderSource.NONE)
        // The shared `system` layer is always the final fallback, even for single-owner callers.
        val layered = (owners + SYSTEM_USER_ID).filter { it.isNotBlank() }.distinct()
        val visible = configStore.getModelConfigs(modelType, layered).filter { it.enabled && it.isCustom }
        // Priority: the first owner in `layered` order with enabled rows shadows every lower layer.
        val rows = layered.firstNotNullOfOrNull { owner ->
            visible.filter { it.userId == owner }.takeIf { it.isNotEmpty() }
        } ?: emptyList()
        val entries = rows.mapNotNull { row ->
            val settings = row.toMediaSettings(serviceKind)
            val problem = MediaProviderFactory.validate(settings)
            if (problem != null) {
                logger.warn("Ignoring invalid {} media entry '{}' for owners '{}': {}", serviceKind, row.id, owners, problem)
                null
            } else settings
        }
        val matchedOwner = layered.firstOrNull { owner -> rows.any { it.userId == owner } }
        val source = when {
            rows.isEmpty() -> MediaProviderSource.NONE
            matchedOwner == SYSTEM_USER_ID -> MediaProviderSource.SYSTEM
            else -> MediaProviderSource.USER
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

    private fun keyOf(owners: Collection<String>, serviceKind: String): Key? {
        val ordered = owners.filter { it.isNotBlank() }.distinct()
        return if (ordered.isEmpty()) null else Key(ordered, serviceKind)
    }

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
