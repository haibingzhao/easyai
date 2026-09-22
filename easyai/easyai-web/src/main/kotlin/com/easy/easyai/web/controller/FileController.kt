package com.easy.easyai.web.controller

import com.easy.easyai.core.storage.ObjectStorageException
import com.easy.easyai.core.storage.StoredFileReference
import com.easy.easyai.repository.session.AsyncSessionStore
import com.easy.easyai.web.security.getCurrentUserId
import com.easy.easyai.web.service.FileStorageService
import com.easy.easyai.web.util.AttachmentProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.multipart.FilePart
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import java.nio.file.Path

@RestController
@RequestMapping("/api/files")
class FileController(
    private val fileStorageService: FileStorageService,
    private val sessionStore: AsyncSessionStore
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @PostMapping("/upload", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadFile(
        @RequestPart("file") filePart: FilePart,
        @RequestParam("sessionId") sessionId: String
    ): Mono<Map<String, String>> = mono {
        val userId = getCurrentUserId()
        verifyOwnership(sessionId, userId)
        val name = filePart.filename()
        val extension = name.substringAfterLast('.', "bin").lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,16}")) } ?: "bin"
        val detectedMime = fileStorageService.resolveMimeType(Path.of("upload.$extension"))
        val declaredMime = filePart.headers().contentType?.let { "${it.type}/${it.subtype}" }
        val mimeType = if (detectedMime.startsWith("image/")) detectedMime
            else declaredMime?.takeUnless { it == MediaType.APPLICATION_OCTET_STREAM_VALUE } ?: detectedMime
        val isImage = mimeType.startsWith("image/")
        if (isImage && mimeType !in AttachmentProcessor.SUPPORTED_IMAGE_MIMES) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported image type")
        }
        val limit = if (isImage) AttachmentProcessor.MAX_IMAGE_DECODED_BYTES else 20 * 1024 * 1024
        val buffer = try {
            DataBufferUtils.join(filePart.content(), limit).awaitSingleOrNull()
        } catch (e: DataBufferLimitException) {
            throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "File exceeds the size limit", e)
        } ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty file")
        val bytes = try {
            ByteArray(buffer.readableByteCount()).also { buffer.read(it) }
        } finally {
            DataBufferUtils.release(buffer)
        }
        if (bytes.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty file")
        val filePath = try {
            fileStorageService.saveImage(sessionId, bytes, extension, userId, mimeType)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        } catch (e: ObjectStorageException) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Image upload failed", e)
        }
        val url = if (isImage) {
            try {
                fileStorageService.resolveImageUrl(filePath, userId) ?: fileStorageService.proxyUrl(filePath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Failed to resolve uploaded image URL: {}", e.message)
                fileStorageService.proxyUrl(filePath)
            }
        } else null
        buildMap {
            put("filePath", filePath)
            put("name", name)
            put("mimeType", mimeType)
            url?.let { put("url", it) }
        }
    }

    @GetMapping("/serve")
    fun serveFile(@RequestParam("path") path: String): Mono<ResponseEntity<Resource>> = mono {
        val userId = getCurrentUserId()
        val sessionId = try {
            fileStorageService.sessionIdFor(path, userId)
        } catch (_: IllegalArgumentException) {
            null
        } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "File not found")
        verifyOwnership(sessionId, userId)
        val resource: Resource = if (StoredFileReference.isStored(path)) {
            val bytes = try {
                fileStorageService.readStoredImage(path, userId)
            } catch (e: ObjectStorageException) {
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Image storage unavailable", e)
            } catch (e: IllegalArgumentException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
            } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "File not found")
            ByteArrayResource(bytes)
        } else {
            val file = withContext(Dispatchers.IO) { fileStorageService.getFile(path) }
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "File not found")
            FileSystemResource(file)
        }
        val mimeType = fileStorageService.resolveMimeType(Path.of("upload.${path.substringAfterLast('.')}"))
        ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(mimeType))
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
            .header("X-Content-Type-Options", "nosniff")
            .body(resource)
    }

    private suspend fun verifyOwnership(sessionId: String, userId: String) {
        if (!sessionStore.isSessionOwnedByUser(sessionId, userId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Session not accessible")
        }
    }
}
