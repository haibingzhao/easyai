package com.easy.easyai.web.controller

import com.easy.easyai.web.service.FileStorageService
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.multipart.FilePart
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import java.io.ByteArrayOutputStream

/**
 * Controller for file upload and serving.
 *
 * Endpoints:
 * - POST /api/files/upload — Upload a file (multipart), saves to disk and returns file path
 * - GET /api/files/serve — Serve a stored file by path (security: only ~/.easyai/images/)
 */
@RestController
@RequestMapping("/api/files")
class FileController(
    private val fileStorageService: FileStorageService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Upload a file (typically a clipboard image) and store it on disk.
     * Returns the file path that can be referenced in subsequent chat requests.
     */
    @PostMapping("/upload", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadFile(
        @RequestPart("file") filePart: FilePart,
        @RequestParam("sessionId") sessionId: String
    ): Mono<Map<String, String>> {
        return mono {
            val extension = filePart.filename()
                .substringAfterLast('.', "png")
            // Collect file bytes, releasing each DataBuffer to prevent off-heap leaks
            val baos = ByteArrayOutputStream()
            filePart.content()
                .doOnNext { dataBuffer ->
                    val buf = ByteArray(dataBuffer.readableByteCount())
                    dataBuffer.read(buf)
                    baos.write(buf)
                    DataBufferUtils.release(dataBuffer)
                }
                .then()
                .block()
            val bytes = baos.toByteArray()
            if (bytes.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty file")

            val filePath = fileStorageService.saveImage(sessionId, bytes, extension)
            val mimeType = fileStorageService.resolveMimeType(java.nio.file.Path.of(filePath))
            logger.debug("Uploaded file: {} -> {}", filePart.filename(), filePath)

            mapOf(
                "filePath" to filePath,
                "name" to (filePart.filename() ?: "unknown"),
                "mimeType" to mimeType
            )
        }
    }

    /**
     * Serve a stored file by its absolute path.
     * Security: only files under ~/.easyai/images/ are accessible.
     */
    @GetMapping("/serve")
    fun serveFile(@RequestParam("path") path: String): Mono<ResponseEntity<Resource>> {
        return mono {
            val file = fileStorageService.getFile(path)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "File not found: $path")

            val mimeType = fileStorageService.resolveMimeType(file)
            val resource = FileSystemResource(file)

            ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mimeType))
                .header(HttpHeaders.CACHE_CONTROL, "max-age=3600")
                .body(resource)
        }
    }
}
