package com.easy.easyai.web.security

import com.easy.easyai.auth.AuthConstants
import com.easy.easyai.auth.UserStore
import com.easy.easyai.auth.RefreshTokenStore
import com.easy.easyai.auth.jwt.JwtTokenProvider
import com.easy.easyai.auth.model.User
import com.easy.easyai.auth.model.UserProfile
import org.slf4j.LoggerFactory
import at.favre.lib.crypto.bcrypt.BCrypt
import java.security.MessageDigest
import java.util.UUID

/**
 * Service handling user registration, login, token refresh, and logout.
 */
class AuthService(
    private val userStore: UserStore,
    private val refreshTokenStore: RefreshTokenStore,
    private val jwtTokenProvider: JwtTokenProvider,
    private val authProperties: AuthProperties
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun register(username: String, password: String, displayName: String?, email: String?): AuthResponse {
        if (!authProperties.registrationEnabled) {
            throw AuthException("Registration is currently disabled", 403)
        }
        if (username.isBlank() || username.length < 3) {
            throw AuthException("Username must be at least 3 characters", 400)
        }
        if (password.length < 6) {
            throw AuthException("Password must be at least 6 characters", 400)
        }
        val existing = userStore.findByUsername(username)
        if (existing != null) {
            throw AuthException("Username already taken", 409)
        }

        val passwordHash = hashPassword(password)
        val user = User(
            id = UUID.randomUUID().toString(),
            username = username,
            displayName = displayName ?: username,
            passwordHash = passwordHash,
            email = email
        )
        userStore.save(user)
        logger.info("Registered new user: {} ({})", username, user.id)

        return generateTokenPair(user)
    }

    suspend fun login(username: String, password: String): AuthResponse {
        val user = userStore.findByUsername(username)
            ?: throw AuthException("Invalid username or password", 401)

        if (!verifyPassword(password, user.passwordHash)) {
            throw AuthException("Invalid username or password", 401)
        }

        logger.info("User logged in: {} ({})", username, user.id)
        return generateTokenPair(user)
    }

    suspend fun refresh(refreshTokenValue: String): AuthResponse {
        val claims = jwtTokenProvider.validateRefreshToken(refreshTokenValue)
            ?: throw AuthException("Invalid refresh token", 401)

        val tokenHash = hashToken(refreshTokenValue)
        val storedToken = refreshTokenStore.findByTokenHash(tokenHash)
            ?: throw AuthException("Refresh token not found or revoked", 401)

        if (storedToken.expiresAt < System.currentTimeMillis()) {
            refreshTokenStore.delete(storedToken.id)
            throw AuthException("Refresh token expired", 401)
        }

        val user = userStore.findById(claims.userId)
            ?: throw AuthException("User not found", 401)

        // Revoke old refresh token
        refreshTokenStore.delete(storedToken.id)

        return generateTokenPair(user)
    }

    suspend fun logout(refreshTokenValue: String?) {
        if (refreshTokenValue != null) {
            val tokenHash = hashToken(refreshTokenValue)
            refreshTokenStore.findByTokenHash(tokenHash)?.let {
                refreshTokenStore.delete(it.id)
            }
        }
    }

    suspend fun getProfile(userId: String): UserProfile {
        val user = userStore.findById(userId)
            ?: throw AuthException("User not found", 404)
        return user.toProfile()
    }

    private suspend fun generateTokenPair(user: User): AuthResponse {
        val accessToken = jwtTokenProvider.generateAccessToken(user.id, user.username)
        val refreshToken = jwtTokenProvider.generateRefreshToken(user.id)

        // Store refresh token hash
        val refreshTokenEntity = com.easy.easyai.auth.model.RefreshToken(
            id = UUID.randomUUID().toString(),
            userId = user.id,
            tokenHash = hashToken(refreshToken),
            expiresAt = System.currentTimeMillis() + (authProperties.refreshTokenExpirationSeconds * 1000)
        )
        refreshTokenStore.save(refreshTokenEntity)

        return AuthResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            user = user.toProfile()
        )
    }

    private fun hashPassword(password: String): String {
        val hash = BCrypt.withDefaults().hashToChar(12, password.toCharArray())
        return String(hash)
    }

    private fun verifyPassword(password: String, hash: String): Boolean {
        return BCrypt.verifyer().verify(password.toCharArray(), hash).verified
    }

    private fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(token.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun User.toProfile() = UserProfile(
        id = id,
        username = username,
        displayName = displayName,
        avatar = avatar,
        email = email
    )
}

/**
 * Authentication/authorization exception with HTTP status code.
 */
class AuthException(message: String, val statusCode: Int) : RuntimeException(message)

/**
 * Authentication response DTO.
 */
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val user: UserProfile
)
