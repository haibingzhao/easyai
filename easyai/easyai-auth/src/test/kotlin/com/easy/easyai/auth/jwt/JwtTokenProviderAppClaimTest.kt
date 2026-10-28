package com.easy.easyai.auth.jwt

import io.jsonwebtoken.Jwts
import org.junit.jupiter.api.Test
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Every token is bound to the application that minted it. A token from another application — or one
 * minted before the claim existed — must not authenticate anywhere.
 */
class JwtTokenProviderAppClaimTest {

    private val keyPair = JwtTokenProvider.generateDevKeyPair()
    private val home = JwtTokenProvider(
        privateKey = keyPair.private,
        publicKey = keyPair.public,
        appName = HOME_APP
    )
    private val trading = JwtTokenProvider(
        privateKey = keyPair.private,
        publicKey = keyPair.public,
        appName = TRADING_APP
    )

    @Test
    fun `access and refresh tokens carry the application and round-trip`() {
        assertEquals(HOME_APP, home.validateAccessToken(home.generateAccessToken("user-1", "alice"))?.app)
        assertEquals(HOME_APP, home.validateRefreshToken(home.generateRefreshToken("user-1"))?.app)
    }

    @Test
    fun `a script token is scoped to the application too`() {
        val token = home.generateScriptToken("user-1", "session-1", "model-1")

        assertNotNull(home.validateScriptToken(token))
        assertNull(trading.validateScriptToken(token))
    }

    @Test
    fun `an access token minted by another application is rejected`() {
        val homeToken = home.generateAccessToken("user-1", "alice")

        assertNull(trading.validateAccessToken(homeToken))
        assertNull(trading.validateRefreshToken(home.generateRefreshToken("user-1")))
    }

    @Test
    fun `a token without the app claim is rejected`() {
        val legacyAccess = legacyToken(TOKEN_TYPE_ACCESS)
        val legacyRefresh = legacyToken(TOKEN_TYPE_REFRESH)
        val legacyScript = legacyToken(TOKEN_TYPE_SCRIPT)

        assertNull(home.validateAccessToken(legacyAccess))
        assertNull(home.validateRefreshToken(legacyRefresh))
        assertNull(home.validateScriptToken(legacyScript))
    }

    /** A pre-isolation token: correctly signed, but minted before the app claim existed. */
    private fun legacyToken(type: String): String {
        val now = Date()
        val builder = Jwts.builder()
            .id("legacy-token")
            .subject("user-1")
            .claim("type", type)
        if (type == TOKEN_TYPE_SCRIPT) {
            builder.claim("sessionId", "session-1").claim("modelConfigId", "model-1")
        }
        return builder
            .issuedAt(now)
            .expiration(Date(now.time + 60_000))
            .signWith(keyPair.private, Jwts.SIG.RS256)
            .compact()
    }

    private companion object {
        const val HOME_APP = "home-api"
        const val TRADING_APP = "trading-api"

        const val TOKEN_TYPE_ACCESS = "access"
        const val TOKEN_TYPE_REFRESH = "refresh"
        const val TOKEN_TYPE_SCRIPT = "script"
    }
}
