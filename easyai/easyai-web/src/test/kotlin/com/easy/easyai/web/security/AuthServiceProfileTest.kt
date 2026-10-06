package com.easy.easyai.web.security

import com.easy.easyai.auth.RefreshTokenStore
import com.easy.easyai.auth.UserStore
import com.easy.easyai.auth.jwt.JwtTokenProvider
import com.easy.easyai.auth.model.User
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tests the profile mutations of [AuthService]: what a user may change, what must never be written,
 * and that a partial update cannot damage the rest of the row.
 */
class AuthServiceProfileTest {

    private val userStore = mockk<UserStore>()
    private val refreshTokenStore = mockk<RefreshTokenStore>(relaxed = true)
    private val jwtTokenProvider = mockk<JwtTokenProvider> {
        every { generateAccessToken(any(), any()) } returns "access"
        every { generateRefreshToken(any()) } returns "refresh"
    }
    private val service = AuthService(userStore, refreshTokenStore, jwtTokenProvider, AuthProperties())

    private fun stored(user: User) {
        coEvery { userStore.findById(user.id) } returns user
        coEvery { userStore.update(any()) } answers { firstArg() }
    }

    private val alice = User(
        id = "alice-id",
        username = "alice",
        displayName = "Alice",
        passwordHash = "bcrypt\$2a\$12\$stored-hash",
        email = "alice@example.com",
    )

    @Nested
    inner class `updateProfile` {
        @Test
        fun `keeps the stored password hash`() = runTest {
            stored(alice)

            service.updateProfile(alice.id, "Ali", null)

            // R2dbcUserStore.update writes password_hash from the entity it is handed.
            coVerify {
                userStore.update(match { it.passwordHash == alice.passwordHash && it.displayName == "Ali" })
            }
        }

        @Test
        fun `keeps the current nickname and email when a field is omitted`() = runTest {
            stored(alice)

            val profile = service.updateProfile(alice.id, null, null)

            assertEquals("Alice", profile.displayName)
            assertEquals("alice@example.com", profile.email)
            coVerify { userStore.update(match { it.displayName == "Alice" && it.email == "alice@example.com" }) }
        }

        @Test
        fun `an empty email clears it`() = runTest {
            stored(alice)

            val profile = service.updateProfile(alice.id, null, "   ")

            assertEquals(null, profile.email)
        }

        @Test
        fun `refuses a blank or over-long nickname`() = runTest {
            stored(alice)

            assertEquals(400, assertFailsWith<AuthException> { service.updateProfile(alice.id, "  ", null) }.statusCode)
            assertEquals(
                400,
                assertFailsWith<AuthException> { service.updateProfile(alice.id, "n".repeat(65), null) }.statusCode
            )
            coVerify(exactly = 0) { userStore.update(any()) }
        }

        @Test
        fun `refuses an address that is not an address`() = runTest {
            stored(alice)

            assertEquals(
                400,
                assertFailsWith<AuthException> { service.updateProfile(alice.id, null, "not-an-email") }.statusCode
            )
            coVerify(exactly = 0) { userStore.update(any()) }
        }
    }

    @Nested
    inner class `avatar` {
        @Test
        fun `accepts an uploaded key and an external link`() = runTest {
            stored(alice)

            val key = "avatars/alice-id/0f0a2b1c-3d4e-5f60-7182-93a4b5c6d7e8.png"
            assertEquals(key, service.setAvatarKey(alice.id, key).avatar)
            assertEquals(
                "https://host/png/alice.png",
                service.setAvatarUrl(alice.id, "  https://host/png/alice.png  ").avatar
            )
        }

        @Test
        fun `the link endpoint refuses anything that is not a bare http(s) URL`() = runTest {
            stored(alice)

            for (rejected in listOf(
                "data:image/png;base64,AAAA",
                "javascript:alert(1)",
                "file:///etc/passwd",
                "https://host/a b.png",
                "avatars/alice-id/0f0a2b1c-3d4e-5f60-7182-93a4b5c6d7e8.png",
            )) {
                // A key is minted by the upload path alone: pasting one into the link field must not
                // become a way to name an arbitrary object.
                val error = assertFailsWith<AuthException> { service.setAvatarUrl(alice.id, rejected) }
                assertEquals(400, error.statusCode, "expected $rejected to be refused")
            }
            coVerify(exactly = 0) { userStore.update(any()) }
        }

        @Test
        fun `clearing restores the preset`() = runTest {
            stored(alice)

            assertEquals("avatar-1", service.clearAvatar(alice.id).avatar)
        }

        @Test
        fun `the shared system identity has no profile to edit`() = runTest {
            val error = assertFailsWith<AuthException> { service.clearAvatar("system") }

            assertEquals(403, error.statusCode)
            coVerify(exactly = 0) { userStore.update(any()) }
        }
    }

    @Nested
    inner class `registration` {
        @Test
        fun `validates the same email rule the profile editor uses`() = runTest {
            coEvery { userStore.findByUsername("carol") } returns null

            assertEquals(
                400,
                assertFailsWith<AuthException> { service.register("carol", "secret123", null, "bad-email") }.statusCode
            )
            coVerify(exactly = 0) { userStore.save(any()) }
        }

        @Test
        fun `stores the trimmed address and the default avatar`() = runTest {
            coEvery { userStore.findByUsername("carol") } returns null
            coEvery { userStore.save(any()) } answers { firstArg() }

            service.register("carol", "secret123", null, "  carol@example.com  ")

            coVerify {
                userStore.save(match { it.email == "carol@example.com" && it.avatar == "avatar-1" })
            }
        }
    }
}
