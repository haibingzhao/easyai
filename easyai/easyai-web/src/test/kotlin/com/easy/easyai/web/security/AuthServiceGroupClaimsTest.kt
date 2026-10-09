package com.easy.easyai.web.security

import at.favre.lib.crypto.bcrypt.BCrypt
import com.easy.easyai.auth.RefreshTokenStore
import com.easy.easyai.auth.UserStore
import com.easy.easyai.auth.group.AccessTokenClaimsContributor
import com.easy.easyai.auth.group.GroupClaims
import com.easy.easyai.auth.jwt.JwtTokenProvider
import com.easy.easyai.auth.model.RefreshToken
import com.easy.easyai.auth.model.User
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The contributor is the one place group membership is resolved, and it must run on BOTH mint paths:
 * login (with the group the caller asked for) and refresh (with the group recovered from the refresh
 * token). Losing it on refresh would silently drop a multi-group user's shared assets after ~2h.
 */
class AuthServiceGroupClaimsTest {

    private val userStore = mockk<UserStore>()
    private val refreshTokenStore = mockk<RefreshTokenStore>(relaxed = true)
    private val jwtTokenProvider = mockk<JwtTokenProvider>()

    private val password = "secret123"
    private val alice = User(
        id = "alice-id",
        username = "alice",
        displayName = "Alice",
        passwordHash = String(BCrypt.withDefaults().hashToChar(12, password.toCharArray()))
    )

    // A correct product contributor returns only the ADDITIONAL owners (the group bucket); the
    // caller's own id is the token subject and is unioned in at read time, so it is not repeated here.
    private val group = GroupClaims(
        owners = listOf("grp_1"),
        groupId = "fam-1",
        groupUserId = "grp_1",
        isGroupOwner = true
    )

    /** Records every (userId, activeGroupId) the service asked for, then returns a fixed group. */
    private class RecordingContributor(private val result: GroupClaims) : AccessTokenClaimsContributor {
        val calls = mutableListOf<Pair<String, String?>>()
        override suspend fun contributions(userId: String, activeGroupId: String?): GroupClaims {
            calls.add(userId to activeGroupId)
            return result
        }
    }

    /**
     * Resolves a group only for the ids it knows; anything else (including null) yields a personal,
     * group-less result — how a real contributor reports "not a member of that group".
     */
    private class MapContributor(private val groups: Map<String?, GroupClaims>) : AccessTokenClaimsContributor {
        override suspend fun contributions(userId: String, activeGroupId: String?): GroupClaims =
            groups[activeGroupId] ?: GroupClaims()
    }

    private fun stubMint() {
        every { jwtTokenProvider.generateAccessToken(any(), any(), any()) } returns "access"
        every { jwtTokenProvider.generateRefreshToken(any(), any()) } returns "refresh"
    }

    /** A valid, unexpired refresh presentation for alice that currently sits in group `fam-1`. */
    private fun stubRefreshSession() {
        every {
            jwtTokenProvider.validateRefreshToken("refresh-token")
        } returns JwtTokenProvider.JwtClaims(
            userId = "alice-id", username = null, tokenId = "tid", groupId = "fam-1"
        )
        coEvery {
            refreshTokenStore.findByTokenHash(any())
        } returns RefreshToken(
            id = "rt-1", userId = "alice-id", tokenHash = "h",
            expiresAt = System.currentTimeMillis() + 60_000
        )
        coEvery { userStore.findById("alice-id") } returns alice
    }

    @Test
    fun `login hands the requested group to the contributor and mints its claims`() = runTest {
        val contributor = RecordingContributor(group)
        coEvery { userStore.findByUsername("alice") } returns alice
        stubMint()
        val service = AuthService(userStore, refreshTokenStore, jwtTokenProvider, AuthProperties(), contributor)

        service.login("alice", password, "fam-1")

        assertEquals(listOf<Pair<String, String?>>("alice-id" to "fam-1"), contributor.calls)
        verify { jwtTokenProvider.generateAccessToken("alice-id", "alice", group) }
        // the refresh token must carry the group id so a later refresh can recover it
        verify { jwtTokenProvider.generateRefreshToken("alice-id", "fam-1") }
    }

    @Test
    fun `refresh recovers the group from the refresh token and re-runs the contributor`() = runTest {
        val contributor = RecordingContributor(group)
        stubRefreshSession()
        stubMint()
        val service = AuthService(userStore, refreshTokenStore, jwtTokenProvider, AuthProperties(), contributor)

        service.refresh("refresh-token")

        assertEquals(listOf<Pair<String, String?>>("alice-id" to "fam-1"), contributor.calls)
        verify { jwtTokenProvider.generateAccessToken("alice-id", "alice", group) }
        verify { jwtTokenProvider.generateRefreshToken("alice-id", "fam-1") }
        coVerify { refreshTokenStore.delete("rt-1") }
    }

    @Test
    fun `switchGroup to a group the caller belongs to re-mints under it and rotates the token`() = runTest {
        val fam2 = GroupClaims(owners = listOf("grp_2"), groupId = "fam-2", groupUserId = "grp_2", isGroupOwner = false)
        val contributor = MapContributor(mapOf("fam-2" to fam2))
        stubRefreshSession()
        stubMint()
        val service = AuthService(userStore, refreshTokenStore, jwtTokenProvider, AuthProperties(), contributor)

        service.switchGroup("refresh-token", "fam-2")

        verify { jwtTokenProvider.generateAccessToken("alice-id", "alice", fam2) }
        verify { jwtTokenProvider.generateRefreshToken("alice-id", "fam-2") }
        coVerify { refreshTokenStore.delete("rt-1") }
    }

    @Test
    fun `switchGroup to a group the caller is not in is refused and leaves the session intact`() = runTest {
        // fam-9 is unknown to the contributor → it resolves no bucket → not a member.
        val contributor = MapContributor(emptyMap())
        stubRefreshSession()
        stubMint()
        val service = AuthService(userStore, refreshTokenStore, jwtTokenProvider, AuthProperties(), contributor)

        val error = assertFailsWith<AuthException> { service.switchGroup("refresh-token", "fam-9") }

        assertEquals(403, error.statusCode)
        // Refused BEFORE rotation: the current refresh token survives, so the caller is not logged out,
        // and nothing was minted under the group they cannot access.
        coVerify(exactly = 0) { refreshTokenStore.delete(any()) }
        verify(exactly = 0) { jwtTokenProvider.generateAccessToken(any(), any(), any()) }
    }

    @Test
    fun `switchGroup to null leaves the group and mints a personal session`() = runTest {
        val contributor = MapContributor(emptyMap())
        stubRefreshSession()
        stubMint()
        val service = AuthService(userStore, refreshTokenStore, jwtTokenProvider, AuthProperties(), contributor)

        service.switchGroup("refresh-token", null)

        verify { jwtTokenProvider.generateAccessToken("alice-id", "alice", GroupClaims()) }
        verify { jwtTokenProvider.generateRefreshToken("alice-id", null) }
        coVerify { refreshTokenStore.delete("rt-1") }
    }

    @Test
    fun `a contributor failure degrades to a personal session, never a guessed group`() = runTest {
        val contributor = AccessTokenClaimsContributor { _, _ -> error("group tables unreachable") }
        val minted: CapturingSlot<GroupClaims> = slot()
        coEvery { userStore.findByUsername("alice") } returns alice
        every { jwtTokenProvider.generateAccessToken(any(), any(), capture(minted)) } returns "access"
        every { jwtTokenProvider.generateRefreshToken(any(), any()) } returns "refresh"
        val service = AuthService(userStore, refreshTokenStore, jwtTokenProvider, AuthProperties(), contributor)

        service.login("alice", password, "fam-1")

        // No owners claim at all: the caller's own id is the token subject and is unioned in at read
        // time, so the degraded token stays small and grants nothing beyond self + system.
        assertEquals(GroupClaims(), minted.captured)
    }
}
