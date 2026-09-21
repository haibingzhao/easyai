package com.easy.easyai.storage

import com.aliyun.oss.OSSClientBuilder
import com.easy.easyai.core.storage.ObjectStorage
import com.easy.easyai.core.storage.StorageSettings
import com.easy.easyai.storage.aliyun.AliyunOssObjectStorage
import com.easy.easyai.storage.local.LocalDirObjectStorage
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.UUID

/**
 * The single place that turns [StorageSettings] into a working [ObjectStorage].
 *
 * Two callers share it so validation can never drift between them: the resolver (per-user
 * DB rows) and the settings endpoint (dry-run on save plus the connectivity probe).
 * Structural problems throw [IllegalArgumentException];
 * backend reachability failures surface as [com.easy.easyai.core.storage.ObjectStorageException]
 * from the calls themselves.
 */
object ObjectStorageFactory {

    private val logger = LoggerFactory.getLogger(ObjectStorageFactory::class.java)

    /** Build the backend named by [settings]; throws [IllegalArgumentException] on bad config. */
    @JvmStatic
    fun create(settings: StorageSettings): ObjectStorage = when (settings.type.lowercase()) {
        StorageSettings.TYPE_LOCAL -> {
            val dir = localRoot(settings)
            logger.info("Object storage using local directory backend: {}", dir)
            LocalDirObjectStorage(Path.of(dir))
        }

        StorageSettings.TYPE_ALIYUN -> {
            require(settings.endpoint.isNotBlank() && settings.bucket.isNotBlank()) {
                "storage endpoint and bucket are required when type=aliyun"
            }
            require(settings.accessKeyId.isNotBlank() && settings.accessKeySecret.isNotBlank()) {
                "storage access-key id and secret are required when type=aliyun"
            }
            logger.info("Object storage using Aliyun OSS: bucket={} endpoint={}", settings.bucket, settings.endpoint)
            val client = OSSClientBuilder().build(settings.endpoint, settings.accessKeyId, settings.accessKeySecret)
            AliyunOssObjectStorage(client, settings.bucket)
        }

        else -> throw IllegalArgumentException(
            "Unknown storage type '${settings.type}' (expected '${StorageSettings.TYPE_ALIYUN}' or '${StorageSettings.TYPE_LOCAL}')"
        )
    }

    /**
     * Structural dry-run for the settings save path: builds the backend exactly as [create]
     * does and releases it again, so a draft is refused before it can shadow a working row.
     *
     * @return null when the configuration is buildable, otherwise the human-readable complaint
     */
    fun validate(settings: StorageSettings): String? {
        val storage = try {
            create(settings)
        } catch (e: IllegalArgumentException) {
            return e.message
        }
        (storage as? AliyunOssObjectStorage)?.shutdown()
        return null
    }

    /**
     * Round-trip one probe object (`put` → `head` → `delete`) against [settings] and release any
     * SDK client afterwards, so validating a configuration leaves nothing behind — in the bucket
     * or in threads.
     *
     * @return null when the round-trip succeeded, otherwise the human-readable failure reason
     */
    suspend fun probe(settings: StorageSettings, userId: String): String? {
        val storage = try {
            create(settings)
        } catch (e: IllegalArgumentException) {
            return e.message
        }
        val key = "$PROBE_DIR/${userId.replace('/', '-')}/${UUID.randomUUID()}"
        val outcome: String? = try {
            storage.put(key, PROBE_CONTENT, "text/plain")
            val reachable = storage.head(key) != null
            if (!reachable) "probe object vanished between put and head" else null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        } finally {
            runCatching { storage.delete(key) }
            (storage as? AliyunOssObjectStorage)?.shutdown()
        }
        return outcome
    }

    /** Local root directory, defaulting to `~/.easyai/storage` like the properties did. */
    fun localRoot(settings: StorageSettings): String =
        settings.localDir.ifBlank {
            Path.of(System.getProperty("user.home"), ".easyai", "storage").toString()
        }

    private const val PROBE_DIR = ".easyai-probe"
    private val PROBE_CONTENT = "easyai-storage-probe".toByteArray()
}
