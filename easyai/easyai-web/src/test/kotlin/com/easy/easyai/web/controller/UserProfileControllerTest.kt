package com.easy.easyai.web.controller

import com.easy.easyai.auth.model.User
import com.easy.easyai.auth.model.UserProfile
import com.easy.easyai.core.storage.ObjectStorageException
import com.easy.easyai.web.security.AuthException
import com.easy.easyai.web.security.AuthService
import com.easy.easyai.web.service.AvatarStorageService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.multipart.FilePart
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tests [UserProfileController]'s upload path: the identity gate has to run before any byte is written,
 * a replaced picture has to be cleaned up, and a storage outage has to surface instead of half-applying.
 */
class UserProfileControllerTest {

    private val authService = mockk<AuthService>()
    private val avatarStorage = mockk<AvatarStorageService>()
    private val controller = UserProfileController(authService, avatarStorage)

    private val auth = ReactiveSecurityContextHolder.withAuthentication(
        UsernamePasswordAuthenticationToken("alice", null, emptyList())
    )

    private val alice = User(id = "alice-id", username = "alice", displayName = "Alice", passwordHash = "hash")
    private val oldKey = "avatars/alice/0f0a2b1c-3d4e-5f60-7182-93a4b5c6d7e8.png"
    private val newKey = "avatars/alice/1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d.png"

    private fun part(bytes: ByteArray, mime: MediaType? = MediaType.IMAGE_PNG): FilePart = mockk {
        every { filename() } returns "avatar.png"
        every { headers() } returns HttpHeaders().apply { contentType = mime }
        every { content() } answers { Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes)) }
    }

    private fun profileWith(avatar: String, displayName: String = alice.displayName) =
        UserProfile(alice.id, alice.username, displayName, avatar, alice.email)

    @Nested
    inner class `uploadAvatar` {
        @Test
        fun `stores the picture, writes the key, then retires the previous one`() = runTest {
            val bytes = byteArrayOf(1, 2, 3)
            coEvery { authService.requireEditableUser("alice") } returns alice.copy(avatar = oldKey)
            coEvery { avatarStorage.save("alice", bytes, "image/png") } returns newKey
            coEvery { authService.setAvatarKey("alice", newKey) } returns profileWith(newKey)
            coEvery { avatarStorage.delete("alice", oldKey) } returns Unit

            val result = controller.uploadAvatar(part(bytes)).contextWrite(auth).awaitSingle()

            assertEquals(newKey, result.avatar)
            coVerify(exactly = 1) { avatarStorage.delete("alice", oldKey) }
        }

        @Test
        fun `hands the previous value to storage, which decides whether it is an object to delete`() = runTest {
            coEvery { authService.requireEditableUser("alice") } returns alice
            coEvery { avatarStorage.save("alice", any(), "image/png") } returns newKey
            coEvery { authService.setAvatarKey("alice", newKey) } returns profileWith(newKey)
            coEvery { avatarStorage.delete("alice", "avatar-1") } returns Unit

            controller.uploadAvatar(part(byteArrayOf(1))).contextWrite(auth).awaitSingle()

            // The preset and an external link are not objects: AvatarStorageService.delete owns that rule,
            // so the controller's job is only to hand over what was stored before.
            coVerify(exactly = 1) { avatarStorage.delete("alice", "avatar-1") }
        }

        @Test
        fun `refuses the system identity before writing any byte`() = runTest {
            coEvery { authService.requireEditableUser("system") } throws AuthException("nope", 403)

            val error = assertFailsWith<AuthException> {
                controller.uploadAvatar(part(byteArrayOf(1))).contextWrite(
                    ReactiveSecurityContextHolder.withAuthentication(
                        UsernamePasswordAuthenticationToken("system", null, emptyList())
                    )
                ).awaitSingle()
            }

            assertEquals(403, error.statusCode)
            coVerify(exactly = 0) { avatarStorage.save(any(), any(), any()) }
        }

        @Test
        fun `refuses an unsupported image type and a missing content type`() = runTest {
            coEvery { authService.requireEditableUser("alice") } returns alice

            assertEquals(
                HttpStatus.BAD_REQUEST,
                assertFailsWith<ResponseStatusException> {
                    controller.uploadAvatar(part(byteArrayOf(1), MediaType.parseMediaType("image/svg+xml")))
                        .contextWrite(auth).awaitSingle()
                }.statusCode
            )
            assertFailsWith<ResponseStatusException> {
                controller.uploadAvatar(part(byteArrayOf(1), null)).contextWrite(auth).awaitSingle()
            }
            coVerify(exactly = 0) { avatarStorage.save(any(), any(), any()) }
        }

        @Test
        fun `refuses a body over the avatar limit`() = runTest {
            coEvery { authService.requireEditableUser("alice") } returns alice

            val error = assertFailsWith<ResponseStatusException> {
                controller.uploadAvatar(part(ByteArray(AvatarStorageService.MAX_AVATAR_BYTES + 1)))
                    .contextWrite(auth).awaitSingle()
            }

            assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, error.statusCode)
            coVerify(exactly = 0) { avatarStorage.save(any(), any(), any()) }
        }

        @Test
        fun `a storage outage is reported instead of half-applied`() = runTest {
            coEvery { authService.requireEditableUser("alice") } returns alice
            coEvery { avatarStorage.save("alice", any(), "image/png") } throws ObjectStorageException("offline")

            val error = assertFailsWith<ResponseStatusException> {
                controller.uploadAvatar(part(byteArrayOf(1))).contextWrite(auth).awaitSingle()
            }

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.statusCode)
            coVerify(exactly = 0) { authService.setAvatarKey(any(), any()) }
        }
        @Test
        fun `a failed row write drops the picture it just stored`() = runTest {
            coEvery { authService.requireEditableUser("alice") } returns alice.copy(avatar = oldKey)
            coEvery { avatarStorage.save("alice", any(), "image/png") } returns newKey
            coEvery { authService.setAvatarKey("alice", newKey) } throws AuthException("database is unavailable", 500)
            coEvery { avatarStorage.delete("alice", newKey) } returns Unit

            assertFailsWith<AuthException> {
                controller.uploadAvatar(part(byteArrayOf(1))).contextWrite(auth).awaitSingle()
            }

            // The stored object would otherwise belong to nobody, and the working avatar stays untouched.
            coVerify(exactly = 1) { avatarStorage.delete("alice", newKey) }
            coVerify(exactly = 0) { avatarStorage.delete("alice", oldKey) }
        }
    }

    @Nested
    inner class `profile and avatar endpoints` {
        @Test
        fun `a text update answers with the fresh profile`() = runTest {
            coEvery { authService.updateProfile("alice", "Ali", null) } returns profileWith("avatar-1", "Ali")

            val result = controller.updateProfile(UpdateProfileRequest(displayName = "Ali"))
                .contextWrite(auth).awaitSingle()

            assertEquals("Ali", result.displayName)
            assertEquals("avatar-1", result.avatar)
        }

        @Test
        fun `an avatar link is handed to the service that validates it`() = runTest {
            coEvery { authService.setAvatarUrl("alice", "https://host/a.png") } returns profileWith("https://host/a.png")

            val result = controller.setAvatarUrl(SetAvatarUrlRequest("https://host/a.png"))
                .contextWrite(auth).awaitSingle()

            assertEquals("https://host/a.png", result.avatar)
        }

        @Test
        fun `removing restores the preset and deletes the stored object`() = runTest {
            coEvery { authService.requireEditableUser("alice") } returns alice.copy(avatar = oldKey)
            coEvery { authService.clearAvatar("alice") } returns profileWith("avatar-1")
            coEvery { avatarStorage.delete("alice", oldKey) } returns Unit

            val result = controller.removeAvatar().contextWrite(auth).awaitSingle()

            assertEquals("avatar-1", result.avatar)
            coVerify(exactly = 1) { avatarStorage.delete("alice", oldKey) }
        }

        @Test
        fun `the uploaded bytes are exactly what the client sent`() = runTest {
            val bytes = byteArrayOf(9, 8, 7, 6)
            var stored: ByteArray? = null
            coEvery { authService.requireEditableUser("alice") } returns alice
            coEvery {
                avatarStorage.save("alice", any(), "image/png")
            } answers { stored = secondArg(); newKey }
            coEvery { authService.setAvatarKey("alice", newKey) } returns profileWith(newKey)
            coEvery { avatarStorage.delete("alice", any()) } returns Unit

            controller.uploadAvatar(part(bytes)).contextWrite(auth).awaitSingle()

            assertContentEquals(bytes, stored)
        }
    }
}
