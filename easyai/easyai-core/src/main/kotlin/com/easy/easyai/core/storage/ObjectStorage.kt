package com.easy.easyai.core.storage

/**
 * Metadata describing one stored object.
 *
 * @param key object key (already prefixed by the implementation's path prefix)
 * @param size content length in bytes
 * @param etag server-provided entity tag, when available
 * @param lastModified epoch millis of the last modification, when available
 */
data class ObjectMeta(
    val key: String,
    val size: Long,
    val etag: String? = null,
    val lastModified: Long? = null
)

/**
 * An object read fully into memory.
 *
 * Skill packages are at most a few MB, so streaming is deliberately not modelled here;
 * implementations bound their own read size before materialising [bytes].
 */
data class ObjectContent(
    val meta: ObjectMeta,
    val bytes: ByteArray
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is ObjectContent && meta == other.meta && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * meta.hashCode() + bytes.contentHashCode()
}

/**
 * Raised by [ObjectStorage] implementations on any backend failure.
 *
 * Callers on critical paths must treat this as fatal and roll back;
 * callers on best-effort paths may log and continue.
 */
class ObjectStorageException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * Generic file/blob storage abstraction — large binary assets are its first consumer,
 * attachments and exports can reuse it later.
 *
 * Implementations wrap blocking SDK clients and therefore must run their work on
 * `Dispatchers.IO`; all methods here are `suspend` so callers never block a event-loop thread.
 *
 * Failure contract: implementations throw [ObjectStorageException]; a missing object is a
 * `null` result from [head]/[get], not an exception.
 */
interface ObjectStorage {

    /** Stat an object; null when it does not exist. */
    suspend fun head(key: String): ObjectMeta?

    /** Read an object fully; null when it does not exist. */
    suspend fun get(key: String): ObjectContent?

    /** Write (create) an object and return its metadata. */
    suspend fun put(key: String, bytes: ByteArray, contentType: String): ObjectMeta

    /** Delete an object; false when it did not exist. */
    suspend fun delete(key: String): Boolean

    /**
     * Time-limited direct-download URL, so desktop/browser clients fetch bytes from the
     * storage backend instead of proxying through this server.
     *
     * @return signed URL, a `file://` URI for local implementations, or null when the
     *   backend cannot sign URLs
     */
    suspend fun presignedGetUrl(key: String, ttlSeconds: Long): String?
}
