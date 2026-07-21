package com.easy.easyai.web.service

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.*

/**
 * Service for storing and managing uploaded files (primarily clipboard images).
 *
 * Files are stored under `{dataDir}/images/{sessionId}/{uuid}.{ext}`.
 * This service also provides security validation to ensure file access is restricted
 * to the images directory only.
 */
class FileStorageService(
    dataDir: String
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

    /**
     * Save a clipboard image to disk.
     * @return Absolute path to the saved file.
     */
    fun saveImage(sessionId: String, bytes: ByteArray, extension: String): String {
        val sessionDir = imagesRoot.resolve(sessionId).normalize()
        // Security: ensure session dir is under images root
        require(sessionDir.startsWith(imagesRoot)) {
            "Invalid session ID: path traversal detected"
        }
        Files.createDirectories(sessionDir)

        val fileName = "${UUID.randomUUID()}.${extension.removePrefix(".")}"
        val filePath = sessionDir.resolve(fileName).normalize()
        Files.write(filePath, bytes)
        logger.debug("Saved clipboard image: {} ({} bytes)", filePath, bytes.size)
        return filePath.toAbsolutePath().toString()
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
        return if (Files.isRegularFile(path)) path else null
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
