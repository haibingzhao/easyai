package com.easy.easyai.auth.jwt

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JwtTokenProviderScriptTokenTest {

    private val keyPair = JwtTokenProvider.generateDevKeyPair()
    private val provider = JwtTokenProvider(
        privateKey = keyPair.private,
        publicKey = keyPair.public
    )

    @Test
    fun `generate and validate script token returns correct claims`() {
        val token = provider.generateScriptToken(
            userId = "user-123",
            sessionId = "session-456",
            modelConfigId = "config-789"
        )

        val claims = provider.validateScriptToken(token)
        assertNotNull(claims)
        assertEquals("user-123", claims.userId)
        assertEquals("session-456", claims.sessionId)
        assertEquals("config-789", claims.modelConfigId)
        assertNotNull(claims.tokenId)
    }

    @Test
    fun `expired script token returns null`() {
        val token = provider.generateScriptToken(
            userId = "user-123",
            sessionId = "session-456",
            modelConfigId = "config-789",
            expirySeconds = -1 // already expired
        )

        val claims = provider.validateScriptToken(token)
        assertNull(claims)
    }

    @Test
    fun `access token is rejected by validateScriptToken`() {
        val accessToken = provider.generateAccessToken("user-123", "testuser")

        val claims = provider.validateScriptToken(accessToken)
        assertNull(claims, "Access token should not be accepted as script token")
    }

    @Test
    fun `script token is rejected by validateAccessToken`() {
        val scriptToken = provider.generateScriptToken(
            userId = "user-123",
            sessionId = "session-456",
            modelConfigId = "config-789"
        )

        val claims = provider.validateAccessToken(scriptToken)
        assertNull(claims, "Script token should not be accepted as access token")
    }

    @Test
    fun `invalid token string returns null`() {
        val claims = provider.validateScriptToken("not-a-valid-token")
        assertNull(claims)
    }

    @Test
    fun `token signed with different key is rejected`() {
        val otherKeyPair = JwtTokenProvider.generateDevKeyPair()
        val otherProvider = JwtTokenProvider(
            privateKey = otherKeyPair.private,
            publicKey = otherKeyPair.public
        )

        val token = otherProvider.generateScriptToken("user-1", "sess-1", "cfg-1")
        val claims = provider.validateScriptToken(token)
        assertNull(claims, "Token signed with different key should be rejected")
    }
}
