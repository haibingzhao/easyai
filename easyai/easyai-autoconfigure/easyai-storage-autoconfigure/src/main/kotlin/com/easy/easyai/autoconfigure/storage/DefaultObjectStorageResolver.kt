package com.easy.easyai.autoconfigure.storage

import com.easy.easyai.core.storage.ObjectStorage
import com.easy.easyai.core.storage.ObjectStorageException
import com.easy.easyai.core.storage.ObjectStorageResolver
import com.easy.easyai.core.storage.StorageSettings
import com.easy.easyai.core.storage.StorageSettingsStore
import com.easy.easyai.core.storage.StorageSource
import com.easy.easyai.storage.ObjectStorageFactory
import com.easy.easyai.storage.aliyun.AliyunOssObjectStorage
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves object storage from the chain: a deployment-wide `easyai.storage.*` STATIC layer (when
 * configured) → own DB row → group DB row → shared `system` DB row → nothing. Owners are consulted
 * in priority order and the first layer with a row wins; an explicit `enabled=false` row still
 * shadows every lower layer.
 *
 * Per owner list delegates are cached, and [refresh] (called by the settings endpoint right after a
 * save) is the whole hot-apply mechanism — no context refresh, no restart. A corrupt stored row is
 * surfaced as an [ObjectStorageException] instead of silently falling through to a lower layer: a
 * misconfigured bucket must fail loudly, not publish bytes to a default nobody chose.
 *
 * The STATIC delegate is built once and shared across every owner set, so it is never released by
 * a per-owner eviction — changing `easyai.storage.*` requires a restart, exactly like any other
 * deployment property.
 */
class DefaultObjectStorageResolver(
    private val store: StorageSettingsStore?,
    private val staticProperties: StorageProperties? = null
) : ObjectStorageResolver {

    private val logger = LoggerFactory.getLogger(javaClass)

    private data class Effective(val storage: ObjectStorage?, val source: StorageSource)

    /**
     * Cache key: the exact owner list a resolution was computed for. Ordered, not a set — [compute]
     * walks the layers by position, so two callers passing the same owners in a different priority
     * order must not share an entry.
     */
    private data class Key(val owners: List<String>)

    private val cache = ConcurrentHashMap<Key, Effective>()

    /** Lazily-built, process-lived STATIC delegate; null when the layer is absent or disabled. */
    @Volatile
    private var staticEffective: Effective? = null

    override suspend fun resolve(owners: Collection<String>): ObjectStorage? = effective(owners).storage

    override suspend fun sourceOf(owners: Collection<String>): StorageSource = effective(owners).source

    override fun refresh(userId: String) {
        if (userId == SYSTEM_USER_ID) {
            // The system row is cached under every owner set that falls back to it, and those entries
            // cannot be traced back individually — drop the whole cache. Saves are rare enough for the
            // rebuild to be free. The shared STATIC delegate is memoized separately and survives.
            cache.keys.toList().forEach { evict(it) }
        } else {
            // Scan-clear: one group-owner save must invalidate every member whose owner set contains it.
            cache.keys.filter { userId in it.owners }.forEach { evict(it) }
        }
    }

    /** Evict one entry, releasing its DB-built SDK client (never the shared STATIC delegate). */
    private fun evict(key: Key) {
        cache.remove(key)
            ?.takeIf { it.source != StorageSource.STATIC }
            ?.storage
            ?.let { (it as? AliyunOssObjectStorage)?.shutdown() }
    }

    private suspend fun effective(owners: Collection<String>): Effective {
        staticLayer()?.let { return it }
        val key = Key(normalize(owners))
        return cache[key] ?: compute(key.owners).also { cache[key] = it }
    }

    /** The STATIC layer's effective delegate, built once, or null when unset/disabled. */
    private fun staticLayer(): Effective? {
        val props = staticProperties?.takeIf { it.enabled } ?: return null
        return staticEffective ?: synchronized(this) {
            staticEffective ?: buildStatic(props).also { staticEffective = it }
        }
    }

    private fun buildStatic(props: StorageProperties): Effective {
        val settings = StorageSettings(
            enabled = true,
            type = props.type,
            endpoint = props.endpoint,
            bucket = props.bucket,
            accessKeyId = props.accessKeyId,
            accessKeySecret = props.accessKeySecret,
            localDir = props.localDir
        )
        val storage = try {
            ObjectStorageFactory.create(settings)
        } catch (e: IllegalArgumentException) {
            throw ObjectStorageException("Static (easyai.storage.*) settings are invalid: ${e.message}", e)
        }
        logger.info("Resolved object storage from the STATIC easyai.storage.* layer")
        return Effective(storage, StorageSource.STATIC)
    }

    private fun normalize(owners: Collection<String>): List<String> =
        owners.filter { it.isNotBlank() }.distinct()

    private suspend fun compute(owners: List<String>): Effective {
        val settingsStore = store ?: return Effective(null, StorageSource.NONE)
        // The shared system row folds in as the last layer even when the caller omitted it.
        val layered = (owners + SYSTEM_USER_ID).filter { it.isNotBlank() }.distinct()
        for (owner in layered) {
            val row = settingsStore.get(owner) ?: continue
            val source = if (owner == SYSTEM_USER_ID) StorageSource.SYSTEM else StorageSource.USER
            return fromRow(row, source)
        }
        return Effective(null, StorageSource.NONE)
    }

    /** An explicit `enabled=false` row shadows every lower layer; a valid row builds the delegate. */
    private fun fromRow(row: StorageSettings, source: StorageSource): Effective {
        if (!row.enabled) return Effective(null, StorageSource.NONE)
        val storage = try {
            ObjectStorageFactory.create(row)
        } catch (e: IllegalArgumentException) {
            throw ObjectStorageException(
                "Stored ${source.name.lowercase()} storage settings are invalid: ${e.message}", e
            )
        }
        logger.info("Resolved object storage for source '{}'", source)
        return Effective(storage, source)
    }

    companion object {
        /** Shared fallback owner, matching the `user_id` column default. */
        const val SYSTEM_USER_ID = "system"
    }
}
