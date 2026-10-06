package com.easy.easyai.web.service

import com.easy.easyai.core.storage.ObjectStorage
import com.easy.easyai.core.storage.ObjectStorageException
import com.easy.easyai.core.storage.ObjectStorageResolver
import com.easy.easyai.web.util.AttachmentProcessor
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import java.util.UUID

/**
 * Stores profile pictures and hands back the key that goes into `app_user.avatar`.
 *
 * Keys are `avatars/{sanitized owner}/{uuid}.{ext}`, so ownership is readable from the key alone and
 * `GET /api/media/file` can serve an avatar without a session — unlike chat attachments, which are
 * authorized through their session and would be deleted with it.
 *
 * The persisted value is always that key: never a presigned URL, which expires, and never image bytes,
 * which would then ride on every profile read.
 */
class AvatarStorageService(
    @param:Autowired(required = false)
    private val objectStorageResolver: ObjectStorageResolver? = null,
    @param:Autowired(required = false)
    @param:Qualifier("localMediaObjectStorage")
    private val localStorage: ObjectStorage? = null
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Write one avatar and return its key.
     *
     * The extension comes from the declared mime only, exactly as uploads from the console are typed, so
     * a filename can never steer what the serve endpoint later reports as the content type.
     */
    suspend fun save(userId: String, bytes: ByteArray, mimeType: String): String {
        require(mimeType in AttachmentProcessor.SUPPORTED_IMAGE_MIMES) { "Unsupported avatar image type" }
        require(bytes.isNotEmpty()) { "Empty avatar file" }
        require(bytes.size <= MAX_AVATAR_BYTES) { "Avatar exceeds the 1 MB limit" }
        val storage = targetStorage(userId)
            ?: throw ObjectStorageException("No avatar storage tier is available")
        val extension = if (mimeType == "image/jpeg") "jpg" else mimeType.substringAfter('/')
        val key = "$KEY_PREFIX${sanitize(userId)}/${UUID.randomUUID()}.$extension"
        storage.put(key, bytes, mimeType)
        logger.debug("Saved avatar for user {} under {}", userId, key)
        return key
    }

    /** True only for a well-formed key inside this owner's segment. */
    fun isOwnedKey(key: String, userId: String): Boolean =
        KEY.matches(key) && key.startsWith("$KEY_PREFIX${sanitize(userId)}/")

    /**
     * Drop a replaced avatar. Best effort by contract: the row already points at the new key, so a stale
     * object is a leak, not a broken profile.
     */
    suspend fun delete(userId: String, key: String) {
        if (!isOwnedKey(key, userId)) return
        try {
            targetStorage(userId)?.delete(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to delete avatar {} for user {}: {}", key, userId, e.message)
        }
    }

    /** Per-user storage when configured, otherwise the always-present deployment-local media directory. */
    private suspend fun targetStorage(userId: String): ObjectStorage? =
        objectStorageResolver?.resolve(userId) ?: localStorage

    /** Mirrors the key sanitization in `MediaFileController`. */
    private fun sanitize(userId: String): String =
        userId.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }.joinToString("")

    companion object {
        const val KEY_PREFIX = "avatars/"
        const val MAX_AVATAR_BYTES = 1024 * 1024

        private val KEY = Regex("avatars/[A-Za-z0-9_-]{1,80}/[0-9a-fA-F-]{36}\\.(?:png|jpg|jpeg|gif|webp)")
    }
}
