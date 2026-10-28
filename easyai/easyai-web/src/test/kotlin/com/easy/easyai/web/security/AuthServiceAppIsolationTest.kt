package com.easy.easyai.web.security

import com.easy.easyai.auth.RefreshTokenStore
import com.easy.easyai.auth.UserStore
import com.easy.easyai.auth.jwt.JwtTokenProvider
import com.easy.easyai.auth.model.RefreshToken
import com.easy.easyai.auth.model.User
import com.easy.easyai.core.host.HostIdentity
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two applications sharing one database and one signing key still authenticate independently: an
 * account belongs to exactly one application, and its tokens are only accepted there.
 */
class AuthServiceAppIsolationTest {

    private val keyPair = JwtTokenProvider.generateDevKeyPair()
    private val userStore = InMemoryUserStore()
    private val refreshTokens = InMemoryRefreshTokenStore()

    private fun auth(app: String) = AuthService(
        userStore = userStore,
        refreshTokenStore = refreshTokens,
        jwtTokenProvider = JwtTokenProvider(keyPair.private, keyPair.public, appName = app),
        authProperties = AuthProperties(),
        host = HostIdentity(app)
    )

    private val home get() = auth(HOME_APP)
    private val trading get() = auth(TRADING_APP)

    @Nested
    inner class registration {

        @Test
        fun `registration writes the account into the calling application`() = runTest {
            val registered = home.register("admin", "secret1", null, null)

            assertEquals(HOME_APP, userStore.findById(registered.user.id, HOME_APP)?.ownerApp)
            assertNull(userStore.findById(registered.user.id, TRADING_APP))
        }

        @Test
        fun `the same username can be registered in both applications`() = runTest {
            val homeAdmin = home.register("admin", "secret1", "Home", null)
            val tradingAdmin = trading.register("admin", "secret1", "Trading", null)

            assertTrue(homeAdmin.user.id != tradingAdmin.user.id)
            assertEquals(HOME_APP, userStore.findByUsername("admin", HOME_APP)?.ownerApp)
            assertEquals(TRADING_APP, userStore.findByUsername("admin", TRADING_APP)?.ownerApp)
        }

        @Test
        fun `a duplicate inside one application is still refused`() = runTest {
            home.register("admin", "secret1", null, null)

            val error = assertFailsWith<AuthException> { home.register("admin", "secret2", null, null) }

            assertEquals(409, error.statusCode)
        }
    }

    @Nested
    inner class login {

        @Test
        fun `an account of another application cannot log in`() = runTest {
            home.register("alice", "secret1", null, null)

            val error = assertFailsWith<AuthException> { trading.login("alice", "secret1") }

            assertEquals(401, error.statusCode)
        }

        @Test
        fun `the same credentials authenticate inside their own application`() = runTest {
            home.register("alice", "secret1", null, null)

            val response = home.login("alice", "secret1")

            assertEquals("alice", response.user.username)
            assertEquals(HOME_APP, JwtTokenProvider(keyPair.private, keyPair.public, HOME_APP)
                .validateAccessToken(response.accessToken)?.app)
        }
    }

    @Nested
    inner class refresh {

        @Test
        fun `a refresh token minted by another application is refused without rotation`() = runTest {
            val session = home.register("alice", "secret1", null, null)
            val storedBefore = refreshTokens.all().size

            val error = assertFailsWith<AuthException> { trading.refresh(session.refreshToken) }

            assertEquals(401, error.statusCode)
            assertEquals(storedBefore, refreshTokens.all().size, "a refused refresh must not revoke the session")
            assertNotNull(home.refresh(session.refreshToken), "the original token still works where it was minted")
        }

        @Test
        fun `a refresh token rotates inside its own application`() = runTest {
            val session = home.register("alice", "secret1", null, null)

            val refreshed = home.refresh(session.refreshToken)

            assertTrue(refreshed.refreshToken != session.refreshToken)
            assertFailsWith<AuthException> { home.refresh(session.refreshToken) }
        }
    }

    private companion object {
        const val HOME_APP = "home-api"
        const val TRADING_APP = "trading-api"
    }
}

private class InMemoryUserStore : UserStore {

    private val rows = linkedMapOf<String, User>()

    override suspend fun findByUsername(username: String, ownerApp: String): User? =
        rows.values.firstOrNull { it.username == username && it.ownerApp == ownerApp }

    override suspend fun findById(id: String, ownerApp: String): User? =
        rows[id]?.takeIf { it.ownerApp == ownerApp }

    override suspend fun save(user: User): User {
        check(findByUsername(user.username, user.ownerApp) == null) { "username already taken" }
        rows[user.id] = user
        return user
    }

    override suspend fun update(user: User): User {
        val current = rows[user.id] ?: error("user ${user.id} does not exist")
        check(current.ownerApp == user.ownerApp) { "ownership is immutable" }
        rows[user.id] = user
        return user
    }

    override suspend fun count(ownerApp: String): Long = rows.values.count { it.ownerApp == ownerApp }.toLong()
}

private class InMemoryRefreshTokenStore : RefreshTokenStore {

    private val rows = linkedMapOf<String, RefreshToken>()

    fun all(): List<RefreshToken> = rows.values.toList()

    override suspend fun save(token: RefreshToken): RefreshToken {
        rows[token.id] = token
        return token
    }

    override suspend fun findByTokenHash(tokenHash: String): RefreshToken? =
        rows.values.firstOrNull { it.tokenHash == tokenHash }

    override suspend fun delete(id: String) {
        rows.remove(id)
    }

    override suspend fun deleteByUserId(userId: String) {
        rows.values.removeIf { it.userId == userId }
    }
}
