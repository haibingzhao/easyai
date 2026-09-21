package com.easy.easyai.autoconfigure.media

import com.easy.easyai.core.media.MediaProviderResolver
import com.easy.easyai.core.media.MediaProviderSettings
import com.easy.easyai.core.media.MediaProviderSource
import com.easy.easyai.core.media.MediaProviderStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves each user's media-generation credential for one service kind: own DB row →
 * shared `system` DB row → nothing. The database is the only configuration source.
 *
 * Per `(user, kind)` entries are cached, and [refresh] (called by the settings endpoint right after
 * a save) is the whole hot-apply mechanism — no context refresh, no restart. Unlike the storage
 * resolver, an invalid stored row is *not* fatal: media tools fail loudly at call time, so here a
 * corrupt row simply resolves to [MediaProviderSource.NONE] and the tool hides itself.
 *
 * The `cache[k] ?: compute()` check-then-put of the storage resolver races under concurrency and
 * can build the same entry twice; because credentials may later carry shared clients, that leak
 * matters. This clone closes it with a per-key [Mutex] single-flight so only one caller computes.
 */
class DefaultMediaProviderResolver(
    private val store: MediaProviderStore?
) : MediaProviderResolver {

    private val logger = LoggerFactory.getLogger(javaClass)

    private data class Effective(val settings: MediaProviderSettings?, val source: MediaProviderSource)

    private val cache = ConcurrentHashMap<String, Effective>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    override suspend fun resolve(userId: String, serviceKind: String): MediaProviderSettings? =
        effective(userId, serviceKind).settings

    override suspend fun sourceOf(userId: String, serviceKind: String): MediaProviderSource =
        effective(userId, serviceKind).source

    override fun refresh(userId: String) {
        if (userId == SYSTEM_USER_ID) {
            // The system row is cached under every user without their own row and those entries
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
        val settingsStore = store ?: return Effective(null, MediaProviderSource.NONE)
        settingsStore.get(userId, serviceKind)?.let { row ->
            return fromRow(row, userId, serviceKind, MediaProviderSource.USER)
        }
        if (userId != SYSTEM_USER_ID) {
            settingsStore.get(SYSTEM_USER_ID, serviceKind)?.let { row ->
                return fromRow(row, SYSTEM_USER_ID, serviceKind, MediaProviderSource.SYSTEM)
            }
        }
        return Effective(null, MediaProviderSource.NONE)
    }

    /** An explicit `enabled=false` row shadows every lower layer; a valid row is served as-is. */
    private fun fromRow(
        row: MediaProviderSettings,
        userId: String,
        serviceKind: String,
        source: MediaProviderSource
    ): Effective {
        if (!row.enabled) return Effective(null, MediaProviderSource.NONE)
        MediaProviderFactory.validate(row)?.let { problem ->
            logger.warn("Ignoring invalid {} media credential for user '{}' kind '{}': {}", source, userId, serviceKind, problem)
            return Effective(null, MediaProviderSource.NONE)
        }
        return Effective(row, source)
    }

    private fun cacheKey(userId: String, serviceKind: String): String = "$userId|$serviceKind"

    companion object {
        /** Shared fallback owner, matching the `user_id` column default. */
        const val SYSTEM_USER_ID = "system"
    }
}
