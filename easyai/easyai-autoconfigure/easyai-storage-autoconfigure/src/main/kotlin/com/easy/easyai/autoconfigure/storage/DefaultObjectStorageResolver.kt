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
 * Resolves each user's object storage from persisted [StorageSettings]: own DB row →
 * shared `system` DB row → nothing. The database is the only configuration source.
 *
 * Per-user delegates are cached, and [refresh] (called by the settings endpoint right after a
 * save) is the whole hot-apply mechanism — no context refresh, no restart. A corrupt stored row
 * is surfaced as an [ObjectStorageException] instead of silently falling through to a lower
 * layer: a misconfigured bucket must fail loudly, not publish bytes to a default nobody chose.
 */
class DefaultObjectStorageResolver(
    private val store: StorageSettingsStore?
) : ObjectStorageResolver {

    private val logger = LoggerFactory.getLogger(javaClass)

    private data class Effective(val storage: ObjectStorage?, val source: StorageSource)

    private val cache = ConcurrentHashMap<String, Effective>()

    override suspend fun resolve(userId: String): ObjectStorage? = effective(userId).storage

    override suspend fun sourceOf(userId: String): StorageSource = effective(userId).source

    override fun refresh(userId: String) {
        if (userId == SYSTEM_USER_ID) {
            // The system row is cached under every user without their own row, and those entries
            // cannot be traced back individually — drop the whole cache. Saves are rare enough
            // for the rebuild to be free.
            cache.keys.toList().forEach { evict(it) }
        } else {
            evict(userId)
        }
    }

    /** Evict one entry, releasing its SDK client with it (every cached delegate is DB-built). */
    private fun evict(userId: String) {
        cache.remove(userId)
            ?.storage
            ?.let { (it as? AliyunOssObjectStorage)?.shutdown() }
    }

    private suspend fun effective(userId: String): Effective =
        cache[userId] ?: compute(userId).also { cache[userId] = it }

    private suspend fun compute(userId: String): Effective {
        val settingsStore = store
        if (settingsStore != null) {
            settingsStore.get(userId)?.let { row ->
                return fromRow(row, StorageSource.USER)
            }
            if (userId != SYSTEM_USER_ID) {
                settingsStore.get(SYSTEM_USER_ID)?.let { row ->
                    return fromRow(row, StorageSource.SYSTEM)
                }
            }
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
