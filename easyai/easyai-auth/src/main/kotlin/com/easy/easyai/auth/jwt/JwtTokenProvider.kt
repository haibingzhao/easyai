package com.easy.easyai.auth.jwt

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.SignatureException
import org.slf4j.LoggerFactory
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Date
import java.util.UUID

/**
 * JWT token provider using RS256 (RSA) asymmetric signing.
 *
 * - Private key: used to sign tokens (only the issuer needs it)
 * - Public key: used to verify tokens (can be shared across instances)
 *
 * Key management:
 * - Development: auto-generated temporary 2048-bit RSA key pair (logged as WARN)
 * - Production: loaded from PEM files via environment variables
 */
class JwtTokenProvider(
    private val privateKey: PrivateKey,
    private val publicKey: PublicKey,
    private val accessExpirySeconds: Long = DEFAULT_ACCESS_EXPIRY,
    private val refreshExpirySeconds: Long = DEFAULT_REFRESH_EXPIRY
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Parsed JWT claims.
     */
    data class JwtClaims(
        val userId: String,
        val username: String?,
        val tokenId: String
    )

    /**
     * Generate a short-lived access token (default 2 hours).
     */
    fun generateAccessToken(userId: String, username: String): String {
        val now = Date()
        val expiry = Date(now.time + accessExpirySeconds * 1000)
        return Jwts.builder()
            .id(UUID.randomUUID().toString())
            .subject(userId)
            .claim(CLAIM_USERNAME, username)
            .claim(CLAIM_TYPE, TOKEN_TYPE_ACCESS)
            .issuedAt(now)
            .expiration(expiry)
            .signWith(privateKey, Jwts.SIG.RS256)
            .compact()
    }

    /**
     * Generate a long-lived refresh token (default 180 days).
     */
    fun generateRefreshToken(userId: String): String {
        val now = Date()
        val expiry = Date(now.time + refreshExpirySeconds * 1000)
        return Jwts.builder()
            .id(UUID.randomUUID().toString())
            .subject(userId)
            .claim(CLAIM_TYPE, TOKEN_TYPE_REFRESH)
            .issuedAt(now)
            .expiration(expiry)
            .signWith(privateKey, Jwts.SIG.RS256)
            .compact()
    }

    /**
     * Validate an access token and extract claims.
     * @return parsed claims, or null if invalid/expired.
     */
    fun validateAccessToken(token: String): JwtClaims? =
        validateToken(token, TOKEN_TYPE_ACCESS)

    /**
     * Validate a refresh token and extract claims.
     * @return parsed claims, or null if invalid/expired.
     */
    fun validateRefreshToken(token: String): JwtClaims? =
        validateToken(token, TOKEN_TYPE_REFRESH)

    private fun validateToken(token: String, expectedType: String): JwtClaims? {
        return try {
            val claims: Claims = Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .payload

            val tokenType = claims[CLAIM_TYPE] as? String
            if (tokenType != expectedType) {
                logger.debug("Token type mismatch: expected={}, actual={}", expectedType, tokenType)
                return null
            }

            JwtClaims(
                userId = claims.subject,
                username = claims[CLAIM_USERNAME] as? String,
                tokenId = claims.id
            )
        } catch (e: SignatureException) {
            logger.debug("Invalid JWT signature")
            null
        } catch (e: Exception) {
            logger.debug("JWT validation failed: {}", e.message)
            null
        }
    }

    companion object {
        private const val CLAIM_USERNAME = "username"
        private const val CLAIM_TYPE = "type"
        private const val TOKEN_TYPE_ACCESS = "access"
        private const val TOKEN_TYPE_REFRESH = "refresh"

        /** Default access token expiry: 2 hours */
        const val DEFAULT_ACCESS_EXPIRY = 7200L
        /** Default refresh token expiry: 180 days */
        const val DEFAULT_REFRESH_EXPIRY = 15_552_000L

        private val logger = LoggerFactory.getLogger(JwtTokenProvider::class.java)

        /**
         * Generate a temporary RSA key pair for development use.
         * Production deployments should load keys from PEM files.
         */
        fun generateDevKeyPair(): KeyPair {
            logger.warn("Generating temporary RSA-2048 key pair for development. " +
                "All tokens will be invalidated on restart. " +
                "For production, configure easyai.auth.jwt-private-key-path and easyai.auth.jwt-public-key-path")
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048)
            return generator.generateKeyPair()
        }

        /**
         * Load a private key from PEM-encoded PKCS#8 string.
         */
        fun loadPrivateKey(pem: String): PrivateKey {
            val cleaned = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\s".toRegex(), "")
            val decoded = Base64.getDecoder().decode(cleaned)
            val keySpec = PKCS8EncodedKeySpec(decoded)
            return KeyFactory.getInstance("RSA").generatePrivate(keySpec)
        }

        /**
         * Load a public key from PEM-encoded X.509 string.
         */
        fun loadPublicKey(pem: String): PublicKey {
            val cleaned = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\\s".toRegex(), "")
            val decoded = Base64.getDecoder().decode(cleaned)
            val keySpec = X509EncodedKeySpec(decoded)
            return KeyFactory.getInstance("RSA").generatePublic(keySpec)
        }
    }
}
