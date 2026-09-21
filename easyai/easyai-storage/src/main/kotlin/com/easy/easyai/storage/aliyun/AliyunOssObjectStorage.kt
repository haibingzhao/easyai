package com.easy.easyai.storage.aliyun

import com.aliyun.oss.OSS
import com.aliyun.oss.OSSException
import com.aliyun.oss.model.GeneratePresignedUrlRequest
import com.aliyun.oss.model.ObjectMetadata
import com.easy.easyai.core.storage.ObjectMeta
import com.easy.easyai.core.storage.ObjectContent
import com.easy.easyai.core.storage.ObjectStorage
import com.easy.easyai.core.storage.ObjectStorageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.util.Date

/**
 * Alibaba Cloud OSS implementation of [ObjectStorage] — the content source of truth for
 * uploaded binaries and exports.
 *
 * The SDK is synchronous, so every call is pinned to [Dispatchers.IO]; callers only ever see
 * `suspend` functions. Only this module depends on the SDK, which is the point of keeping it
 * separate from `easyai-core`.
 *
 * Keys are used verbatim (no implicit prefix), so a key persisted by a caller always names the
 * exact object it wrote.
 */
class AliyunOssObjectStorage(
    private val client: OSS,
    private val bucket: String
) : ObjectStorage {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun head(key: String): ObjectMeta? = withContext(Dispatchers.IO) {
        try {
            val meta = client.getObjectMetadata(bucket, key)
            ObjectMeta(
                key = key,
                size = meta.contentLength,
                etag = meta.eTag,
                lastModified = meta.lastModified?.time
            )
        } catch (e: OSSException) {
            if (isNotFound(e)) null else throw ObjectStorageException("OSS head failed for $key: ${e.message}", e)
        }
    }

    override suspend fun get(key: String): ObjectContent? = withContext(Dispatchers.IO) {
        try {
            client.getObject(bucket, key).use { downloaded ->
                downloaded.objectContent.use { stream ->
                    val bytes = stream.readBytes()
                    val details = downloaded.objectMetadata
                    ObjectContent(
                        meta = ObjectMeta(
                            key = key,
                            size = bytes.size.toLong(),
                            etag = details.eTag,
                            lastModified = details.lastModified?.time
                        ),
                        bytes = bytes
                    )
                }
            }
        } catch (e: OSSException) {
            if (isNotFound(e)) null else throw ObjectStorageException("OSS get failed for $key: ${e.message}", e)
        }
    }

    override suspend fun put(key: String, bytes: ByteArray, contentType: String): ObjectMeta =
        withContext(Dispatchers.IO) {
            try {
                val metadata = ObjectMetadata().apply {
                    setContentLength(bytes.size.toLong())
                    setContentType(contentType)
                }
                val result = client.putObject(bucket, key, ByteArrayInputStream(bytes), metadata)
                logger.debug("Uploaded OSS object {}: {} bytes (etag={})", key, bytes.size, result.eTag)
                ObjectMeta(key = key, size = bytes.size.toLong(), etag = result.eTag)
            } catch (e: Exception) {
                throw ObjectStorageException("OSS put failed for $key: ${e.message}", e)
            }
        }

    override suspend fun delete(key: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // OSS deletes silently succeed for missing keys, so existence is probed first to
            // keep the interface's "false means it was not there" contract.
            if (!client.doesObjectExist(bucket, key)) return@withContext false
            client.deleteObject(bucket, key)
            true
        } catch (e: Exception) {
            throw ObjectStorageException("OSS delete failed for $key: ${e.message}", e)
        }
    }

    override suspend fun presignedGetUrl(key: String, ttlSeconds: Long): String? =
        withContext(Dispatchers.IO) {
            try {
                if (!client.doesObjectExist(bucket, key)) return@withContext null
                val request = GeneratePresignedUrlRequest(bucket, key).apply {
                    expiration = Date(System.currentTimeMillis() + ttlSeconds * 1000L)
                }
                client.generatePresignedUrl(request).toString()
            } catch (e: Exception) {
                throw ObjectStorageException("OSS presign failed for $key: ${e.message}", e)
            }
        }

    /**
     * Releases the SDK connection pool. Long-lived beans never need it; short-lived clients —
     * a connectivity probe built to validate a stored configuration — must call it so probe
     * threads do not accumulate.
     */
    fun shutdown() {
        client.shutdown()
    }

    /**
     * The 3.18.x SDK exposes no HTTP status on [OSSException], so a missing object is detected
     * through its error code; anything else stays an error.
     */
    private fun isNotFound(e: OSSException): Boolean =
        e.errorCode == OSS_ERROR_NO_SUCH_KEY || e.errorCode == OSS_ERROR_NOT_FOUND

    private companion object {
        const val OSS_ERROR_NO_SUCH_KEY = "NoSuchKey"
        const val OSS_ERROR_NOT_FOUND = "NotFound"
    }
}
