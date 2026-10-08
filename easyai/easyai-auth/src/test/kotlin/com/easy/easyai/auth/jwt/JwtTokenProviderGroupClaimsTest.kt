package com.easy.easyai.auth.jwt

import com.easy.easyai.auth.group.GroupClaims
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Group-sharing claims must survive a mint→validate round-trip on both token types, and a token
 * minted without a group must parse back to the group-less defaults (so pre-group tokens and the
 * no-op contributor behave exactly as before).
 */
class JwtTokenProviderGroupClaimsTest {

    private val keyPair = JwtTokenProvider.generateDevKeyPair()
    private val provider = JwtTokenProvider(privateKey = keyPair.private, publicKey = keyPair.public)

    @Test
    fun `access token round-trips the full group claim set`() {
        val group = GroupClaims(
            owners = listOf("alice", "grp_abc123"),
            groupId = "family-1",
            groupUserId = "grp_abc123",
            isGroupOwner = true
        )

        val token = provider.generateAccessToken("alice", "alice", group)
        val claims = provider.validateAccessToken(token)

        assertNotNull(claims)
        assertEquals("alice", claims.userId)
        assertEquals(listOf("alice", "grp_abc123"), claims.owners)
        assertEquals("family-1", claims.groupId)
        assertEquals("grp_abc123", claims.groupUserId)
        assertTrue(claims.isGroupOwner)
        assertEquals(group, claims.toGroupClaims())
    }

    @Test
    fun `access token without a group parses to group-less defaults`() {
        val claims = provider.validateAccessToken(provider.generateAccessToken("bob", "bob"))

        assertNotNull(claims)
        assertEquals("bob", claims.userId)
        assertTrue(claims.owners.isEmpty())
        assertNull(claims.groupId)
        assertNull(claims.groupUserId)
        assertFalse(claims.isGroupOwner)
    }

    @Test
    fun `a member (not owner) keeps owners but not the owner flag`() {
        val group = GroupClaims(owners = listOf("carol", "grp_abc123"), groupId = "family-1", groupUserId = "grp_abc123", isGroupOwner = false)

        val claims = provider.validateAccessToken(provider.generateAccessToken("carol", "carol", group))

        assertNotNull(claims)
        assertEquals(listOf("carol", "grp_abc123"), claims.owners)
        assertEquals("grp_abc123", claims.groupUserId)
        assertFalse(claims.isGroupOwner, "a member must never carry the owner flag")
    }

    @Test
    fun `refresh token carries the active group id so refresh can recover it`() {
        val claims = provider.validateRefreshToken(provider.generateRefreshToken("alice", "family-1"))

        assertNotNull(claims)
        assertEquals("alice", claims.userId)
        assertEquals("family-1", claims.groupId)
    }

    @Test
    fun `refresh token without a group id parses to null group`() {
        val claims = provider.validateRefreshToken(provider.generateRefreshToken("bob"))

        assertNotNull(claims)
        assertNull(claims.groupId)
    }
}
