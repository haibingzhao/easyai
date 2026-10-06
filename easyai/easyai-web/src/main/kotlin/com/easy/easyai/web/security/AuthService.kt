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
            email = normalizeEmail(email)
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

    /**
     * Change the nickname and email a user shows. A `null` field keeps the stored value, so the console
     * can send only what the user touched; an empty email clears it.
     */
    suspend fun updateProfile(userId: String, displayName: String?, email: String?): UserProfile {
        val user = requireEditableUser(userId)
        val name = displayName?.trim()?.also {
            if (it.isEmpty()) throw AuthException("Display name must not be blank", 400)
            if (it.length > MAX_DISPLAY_NAME_LENGTH) {
                throw AuthException("Display name must be at most $MAX_DISPLAY_NAME_LENGTH characters", 400)
            }
        } ?: user.displayName
        val mail = if (email == null) user.email else normalizeEmail(email)
        // copy() off the loaded row, never a rebuilt User: R2dbcUserStore.update writes password_hash
        // unconditionally, so a partially built entity would blank the stored hash.
        return userStore.update(user.copy(displayName = name, email = mail)).toProfile()
    }

    /**
     * Store an avatar key. Only an upload handler may call this, with the key [save] just minted for
     * this same owner: a client must not be able to name an arbitrary object through the profile editor.
     */
    suspend fun setAvatarKey(userId: String, key: String): UserProfile = writeAvatar(userId, key)

    /** Store an externally hosted picture. A bare http(s) link only: a `data:` URI would put image bytes in every read. */
    suspend fun setAvatarUrl(userId: String, url: String): UserProfile = writeAvatar(userId, validateAvatarUrl(url))

    /** Back to the preset, which is what makes the console render initials again. */
    suspend fun clearAvatar(userId: String): UserProfile = writeAvatar(userId, AuthConstants.DEFAULT_AVATAR)

    private suspend fun writeAvatar(userId: String, avatar: String): UserProfile {
        val user = requireEditableUser(userId)
        return userStore.update(user.copy(avatar = avatar)).toProfile()
    }

    /** `matches` anchors both ends, so a `data:` URI, embedded whitespace and over-long links all fail. */
    private fun validateAvatarUrl(url: String): String {
        val value = url.trim()
        if (!AVATAR_URL.matches(value)) {
            throw AuthException("Avatar link must be a direct http(s) image URL", 400)
        }
        return value
    }

    /**
     * Only a signed-in account owns a profile. The `system` identity is the shared read-only owner of
     * seed data and storage settings, never a row to write to, and it is what an unauthenticated call to
     * the publicly reachable auth range resolves to. Upload handlers call this before writing any bytes.
     */
    internal suspend fun requireEditableUser(userId: String): User {
        if (userId == AuthConstants.SYSTEM_USER_ID) {
            throw AuthException("Profile editing requires an account", 403)
        }
        return userStore.findById(userId) ?: throw AuthException("User not found", 404)
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

    /**
     * One email rule for both entry points: `null` keeps the stored value, blank clears it, anything
     * else must look like an address. Registration used to accept whatever was typed.
     */
    private fun normalizeEmail(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.length > MAX_EMAIL_LENGTH || !EMAIL.matches(trimmed)) {
            throw AuthException("Email address is not valid", 400)
        }
        return trimmed
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

    companion object {
        private const val MAX_DISPLAY_NAME_LENGTH = 64
        private const val MAX_EMAIL_LENGTH = 255
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

        private val AVATAR_URL = Regex("https?://\\S{1,500}")
    }
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
