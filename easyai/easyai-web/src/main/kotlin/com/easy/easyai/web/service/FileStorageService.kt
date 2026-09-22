package com.easy.easyai.web.service

import com.easy.easyai.core.storage.ObjectStorageException
import com.easy.easyai.core.storage.ObjectStorageResolver
import com.easy.easyai.core.storage.StoredFileReference
import com.easy.easyai.web.util.AttachmentProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.*

class FileStorageService(
    dataDir: String,
    private val objectStorageResolver: ObjectStorageResolver? = null
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    val imagesRoot: Path = Path.of(dataDir, "images").toAbsolutePath().normalize()

    init {
        try {
            Files.createDirectories(imagesRoot)
        } catch (e: IOException) {
            logger.warn("Failed to create images root directory: {}", imagesRoot, e)
        }
    }

    suspend fun saveImage(
        sessionId: String,
        bytes: ByteArray,
        extension: String,
        userId: String = "system",
        mimeType: String = resolveMimeType(Path.of("upload.$extension"))
    ): String {
        require(sessionId.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid session ID" }
        require(extension.matches(Regex("[A-Za-z0-9]{1,16}"))) { "Invalid file extension" }
        require(bytes.isNotEmpty()) { "Empty file" }
        val isImage = mimeType.startsWith("image/")
        val safeExtension = if (isImage) {
            require(mimeType in AttachmentProcessor.SUPPORTED_IMAGE_MIMES) { "Unsupported image type" }
            require(bytes.size <= AttachmentProcessor.MAX_IMAGE_DECODED_BYTES) { "Image exceeds the 6 MB size limit" }
            when (mimeType) {
                "image/jpeg" -> "jpg"
                else -> mimeType.substringAfter('/')
            }
        } else extension.lowercase()
        if (isImage) {
            val storage = objectStorageResolver?.resolve(userId)
            if (storage != null) {
                val reference = StoredFileReference.create(userId, sessionId, safeExtension)
                storage.put(StoredFileReference.parse(reference, userId).key, bytes, mimeType)
                return reference
            }
        }
        return withContext(Dispatchers.IO) {
            val sessionDir = imagesRoot.resolve(sessionId)
            Files.createDirectories(sessionDir)
            require(sessionDir.toRealPath() == imagesRoot.toRealPath().resolve(sessionId)) { "Invalid upload directory" }
            val filePath = sessionDir.resolve("${UUID.randomUUID()}.$safeExtension")
            Files.write(filePath, bytes)
            logger.debug("Saved attachment: {} ({} bytes)", filePath, bytes.size)
            filePath.toAbsolutePath().toString()
        }
    }

    fun sessionIdFor(filePath: String, userId: String): String? {
        if (StoredFileReference.isStored(filePath)) {
            return StoredFileReference.parse(filePath, userId).sessionId
        }
        val path = Path.of(filePath).toAbsolutePath().normalize()
        if (!path.startsWith(imagesRoot)) return null
        val relative = imagesRoot.relativize(path)
        return if (relative.nameCount == 2) relative.getName(0).toString() else null
    }

    fun proxyUrl(filePath: String): String =
        "/api/files/serve?path=${URLEncoder.encode(filePath, StandardCharsets.UTF_8)}"

    suspend fun resolveImageUrl(filePath: String, userId: String): String? {
        if (!StoredFileReference.isStored(filePath)) return proxyUrl(filePath)
        val reference = StoredFileReference.parse(filePath, userId)
        val storage = objectStorageResolver?.resolve(userId) ?: return null
        val metadata = storage.head(reference.key) ?: return null
        require(metadata.size in 1..StoredFileReference.MAX_IMAGE_BYTES.toLong()) { "Invalid image size" }
        val signedUri = try {
            storage.presignedGetUrl(reference.key, StoredFileReference.URL_TTL_SECONDS)?.let { URI(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to sign image {}: {}", reference.key, e.message)
            null
        }
        if (signedUri?.scheme in setOf("https", "http") && !signedUri?.host.isNullOrBlank()) {
            return signedUri.toString()
        }
        return proxyUrl(filePath)
    }

    suspend fun readStoredImage(filePath: String, userId: String): ByteArray? {
        val reference = StoredFileReference.parse(filePath, userId)
        val storage = objectStorageResolver?.resolve(userId)
            ?: throw ObjectStorageException("Object storage is not configured")
        val metadata = storage.head(reference.key) ?: return null
        require(metadata.size in 1..StoredFileReference.MAX_IMAGE_BYTES.toLong()) { "Invalid image size" }
        val content = storage.get(reference.key) ?: return null
        require(content.bytes.size in 1..StoredFileReference.MAX_IMAGE_BYTES) { "Invalid image size" }
        return content.bytes
    }

    /**
     * Get a file from the images directory.
     * Returns null if the file doesn't exist or is outside the images root (security check).
     */
    fun getFile(filePath: String): Path? {
        val path = Path.of(filePath).toAbsolutePath().normalize()
        if (!path.startsWith(imagesRoot)) {
            logger.warn("File access denied — path outside images root: {}", filePath)
            return null
        }
        if (!Files.isRegularFile(path)) return null
        val relative = imagesRoot.relativize(path)
        if (relative.nameCount != 2) return null
        val expectedParent = imagesRoot.toRealPath().resolve(relative.getName(0))
        return path.toRealPath().takeIf { it.parent == expectedParent }
    }

    /**
     * Resolve MIME type for a file.
     */
    fun resolveMimeType(filePath: Path): String {
        return try {
            Files.probeContentType(filePath) ?: "application/octet-stream"
        } catch (_: Exception) {
            "application/octet-stream"
        }
    }

    /**
     * Delete all stored images for a session.
     * Silently ignores errors (logged as warnings).
     */
    fun cleanupSession(sessionId: String) {
        val sessionDir = imagesRoot.resolve(sessionId).normalize()
        if (!sessionDir.startsWith(imagesRoot)) {
            logger.warn("Cleanup denied — invalid session ID: {}", sessionId)
            return
        }
        if (!Files.isDirectory(sessionDir)) return

        try {
            Files.walkFileTree(sessionDir, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
            logger.info("Cleaned up images for session: {}", sessionId)
        } catch (e: IOException) {
            logger.warn("Failed to clean up images for session {}: {}", sessionId, e.message)
        }
    }
}
