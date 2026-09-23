package com.easy.easyai.web.controller

import com.easy.easyai.core.storage.ObjectContent
import com.easy.easyai.core.storage.ObjectStorage
import com.easy.easyai.core.storage.ObjectStorageResolver
import com.easy.easyai.web.security.getCurrentUserId
import kotlinx.coroutines.reactor.mono
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

/**
 * Stable binary access point for generated media: `GET /api/media/file?key=...`.
 *
 * Generation tools store bytes in the caller's object storage and hand back only this URL (never a
 * presigned link), so a reference in chat history keeps working: every request re-reads the object
 * from the caller's *current* storage. Serving through the server also sidesteps the local backend's
 * browser-unusable `file://` presigned URLs.
 *
 * When the caller has no (or missing) object storage, the deployment-local media directory acts as
 * fallback; local keys embed the owner's sanitized user id, so reads there are ownership-checked.
 *
 * Chat image keys are reserved for the session-authorized file endpoint.
 */
@RestController
@RequestMapping("/api/media")
class MediaFileController(
    @param:Autowired(required = false)
    private val objectStorageResolver: ObjectStorageResolver? = null,
    @param:Autowired(required = false)
    @param:Qualifier("localMediaObjectStorage")
    private val localStorage: ObjectStorage? = null
) {

    @GetMapping("/file")
    fun serve(@RequestParam key: String): Mono<ResponseEntity<ByteArrayResource>> = mono {
        if (key.split('/', '\\').any { it.equals("chat-images", ignoreCase = true) }) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Media object not found")
        }
        if (key.isBlank() || key.contains("..")) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid media key")
        }
        val userId = getCurrentUserId()
        val userStorage = objectStorageResolver?.resolve(userId)
        if (userStorage == null && localStorage == null) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Object storage is not configured")
        }
        val content = userStorage?.get(key) ?: readLocalIfOwned(key, userId)
        if (content == null) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Media object not found")
        }
        val bytes = ByteArrayResource(content.bytes)
        ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(content.meta.let { contentTypeFor(it.key) }))
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
            .contentLength(content.bytes.size.toLong())
            .body(bytes)
    }

    /** Read from the local fallback only when the key is namespaced to the requesting user. */
    private suspend fun readLocalIfOwned(key: String, userId: String): ObjectContent? {
        val storage = localStorage ?: return null
        if (!key.startsWith("media/${sanitize(userId)}/")) return null
        return storage.get(key)
    }

    /** Mirrors the storage-side key sanitization in `MediaArtifacts`. */
    private fun sanitize(userId: String): String =
        userId.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }.joinToString("")

    private fun contentTypeFor(key: String): String = when {
        key.endsWith(".png") -> "image/png"
        key.endsWith(".jpg") || key.endsWith(".jpeg") -> "image/jpeg"
        key.endsWith(".webp") -> "image/webp"
        key.endsWith(".gif") -> "image/gif"
        key.endsWith(".mp3") -> "audio/mpeg"
        key.endsWith(".wav") -> "audio/wav"
        key.endsWith(".opus") -> "audio/opus"
        key.endsWith(".aac") -> "audio/aac"
        key.endsWith(".mp4") -> "video/mp4"
        key.endsWith(".webm") -> "video/webm"
        else -> "application/octet-stream"
    }
}
