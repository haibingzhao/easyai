package com.easy.easyai.web.controller

import com.easy.easyai.auth.model.UserProfile
import com.easy.easyai.core.storage.ObjectStorageException
import com.easy.easyai.web.security.AuthService
import com.easy.easyai.web.security.UserProfileDto
import com.easy.easyai.web.security.getCurrentUserId
import com.easy.easyai.web.service.AvatarStorageService
import com.easy.easyai.web.util.AttachmentProcessor
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.multipart.FilePart
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

/**
 * The signed-in user's own profile: nickname, email and avatar.
 *
 * Under `/api/users` rather than `/api/auth` on purpose: the auth range is `permitAll` so a new route
 * there would be public, while everything else already requires a token. Every mutation answers with the
 * fresh profile, so the console never has to re-read `/api/auth/me` (which also fires a skill sync).
 */
@RestController
@RequestMapping("/api/users")
class UserProfileController(
    private val authService: AuthService,
    private val avatarStorage: AvatarStorageService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @PutMapping("/me")
    fun updateProfile(@RequestBody request: UpdateProfileRequest): Mono<UserProfileDto> = mono {
        val userId = getCurrentUserId()
        authService.updateProfile(userId, request.displayName, request.email).toDto()
    }

    /** Replace the avatar with a locally uploaded picture, resized by the client to a square. */
    @PostMapping("/me/avatar", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadAvatar(@RequestPart("file") filePart: FilePart): Mono<UserProfileDto> = mono {
        val userId = getCurrentUserId()
        // Rejected before a byte is written, so an anonymous or system caller cannot leave objects behind.
        val current = authService.requireEditableUser(userId)
        val mimeType = filePart.headers().contentType?.let { "${it.type}/${it.subtype}" }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Avatar upload needs a content type")
        if (mimeType !in AttachmentProcessor.SUPPORTED_IMAGE_MIMES) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported avatar image type")
        }
        val buffer = try {
            DataBufferUtils.join(filePart.content(), AvatarStorageService.MAX_AVATAR_BYTES)
                .awaitSingleOrNull()
        } catch (e: DataBufferLimitException) {
            throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Avatar exceeds the size limit", e)
        } ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty avatar file")
        val bytes = try {
            ByteArray(buffer.readableByteCount()).also { buffer.read(it) }
        } finally {
            DataBufferUtils.release(buffer)
        }

        val key = try {
            avatarStorage.save(userId, bytes, mimeType)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        } catch (e: ObjectStorageException) {
            logger.warn("Avatar upload for user {} failed: {}", userId, e.message)
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Avatar storage is unavailable", e)
        }
        // If the row cannot be pointed at the new picture, the picture itself is nobody's: drop it.
        val profile = try {
            authService.setAvatarKey(userId, key)
        } catch (e: Exception) {
            avatarStorage.delete(userId, key)
            throw e
        }
        avatarStorage.delete(userId, current.avatar)
        profile.toDto()
    }

    @PutMapping("/me/avatar")
    fun setAvatarUrl(@RequestBody request: SetAvatarUrlRequest): Mono<UserProfileDto> = mono {
        val userId = getCurrentUserId()
        authService.setAvatarUrl(userId, request.url).toDto()
    }

    /** Back to the preset: the console renders the colour-seeded initial letter again. */
    @DeleteMapping("/me/avatar")
    fun removeAvatar(): Mono<UserProfileDto> = mono {
        val userId = getCurrentUserId()
        val current = authService.requireEditableUser(userId)
        val profile = authService.clearAvatar(userId)
        avatarStorage.delete(userId, current.avatar)
        profile.toDto()
    }

    private fun UserProfile.toDto() = UserProfileDto.from(this)
}

data class UpdateProfileRequest(
    val displayName: String? = null,
    val email: String? = null
)

data class SetAvatarUrlRequest(
    val url: String
)
