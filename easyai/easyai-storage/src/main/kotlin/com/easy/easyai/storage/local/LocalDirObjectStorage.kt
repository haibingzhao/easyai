package com.easy.easyai.storage.local

import com.easy.easyai.core.storage.ObjectMeta
import com.easy.easyai.core.storage.ObjectContent
import com.easy.easyai.core.storage.ObjectStorage
import com.easy.easyai.core.storage.ObjectStorageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Filesystem-backed [ObjectStorage], used for development, tests and offline/desktop
 * deployments that have no object store.
 *
 * Keys map onto a directory tree rooted at [root]; a key escaping that root (via `..` or an
 * absolute path) is rejected rather than resolved, so no caller can read outside the configured
 * bucket directory.
 */
class LocalDirObjectStorage(
    private val root: Path
) : ObjectStorage {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun head(key: String): ObjectMeta? = withContext(Dispatchers.IO) {
        val path = resolveSafe(key) ?: return@withContext null
        if (!Files.isRegularFile(path)) return@withContext null
        ObjectMeta(
            key = key,
            size = Files.size(path),
            lastModified = path.toFile().lastModified()
        )
    }

    override suspend fun get(key: String): ObjectContent? = withContext(Dispatchers.IO) {
        val path = resolveSafe(key) ?: return@withContext null
        if (!Files.isRegularFile(path)) return@withContext null
        val bytes = Files.readAllBytes(path)
        val meta = ObjectMeta(
            key = key,
            size = bytes.size.toLong(),
            // The bytes are already in hand, so hand back the fingerprint a caller would otherwise
            // have to compute again; `head` stays null because it must not read the object.
            etag = sha256Hex(bytes),
            lastModified = path.toFile().lastModified()
        )
        ObjectContent(meta = meta, bytes = bytes)
    }

    override suspend fun put(key: String, bytes: ByteArray, contentType: String): ObjectMeta =
        withContext(Dispatchers.IO) {
            val path = resolveSafe(key)
                ?: throw ObjectStorageException("Rejected object key escaping the storage root: $key")
            // Content type carries no meaning for a plain file; the extension in the key is
            // what a consumer sees, so it is only logged for traceability.
            logger.debug("Storing local object {}: {} bytes ({})", key, bytes.size, contentType)
            try {
                Files.createDirectories(path.parent)
                Files.write(path, bytes)
                ObjectMeta(
                    key = key,
                    size = bytes.size.toLong(),
                    etag = sha256Hex(bytes),
                    lastModified = path.toFile().lastModified()
                )
            } catch (e: Exception) {
                throw ObjectStorageException("Failed to write object $key: ${e.message}", e)
            }
        }

    override suspend fun delete(key: String): Boolean = withContext(Dispatchers.IO) {
        val path = resolveSafe(key) ?: return@withContext false
        try {
            Files.isRegularFile(path) && Files.deleteIfExists(path)
        } catch (e: Exception) {
            logger.warn("Failed to delete object {}: {}", key, e.message)
            false
        }
    }

    /** No signing exists for local files; a direct `file://` URI serves the same purpose offline. */
    override suspend fun presignedGetUrl(key: String, ttlSeconds: Long): String? =
        withContext(Dispatchers.IO) {
            val path = resolveSafe(key) ?: return@withContext null
            if (!Files.isRegularFile(path)) return@withContext null
            path.toAbsolutePath().normalize().toUri().toString()
        }

    /** Resolve a key under [root], returning null when it would escape the root. */
    private fun resolveSafe(key: String): Path? {
        if (key.isBlank()) return null
        val base = root.toAbsolutePath().normalize()
        val resolved = base.resolve(key.trimStart('/')).normalize()
        if (!resolved.startsWith(base)) {
            logger.warn("Rejected object key outside storage root: {} (root={})", key, base)
            return null
        }
        return resolved
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
