package com.easy.easyai.web.security

import com.easy.easyai.auth.AuthConstants
import com.easy.easyai.auth.UserStore
import com.easy.easyai.auth.RefreshTokenStore
import com.easy.easyai.auth.group.AccessTokenClaimsContributor
import com.easy.easyai.auth.group.GroupClaims
import com.easy.easyai.auth.group.NoopClaimsContributor
import com.easy.easyai.auth.jwt.JwtTokenProvider
import com.easy.easyai.auth.model.RefreshToken
import com.easy.easyai.auth.model.User
import com.easy.easyai.auth.model.UserProfile
import kotlinx.coroutines.CancellationException
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
    private val authProperties: AuthProperties,
    private val claimsContributor: AccessTokenClaimsContributor = NoopClaimsContributor
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

    suspend fun login(username: String, password: String, groupId: String? = null): AuthResponse {
        val user = userStore.findByUsername(username)
            ?: throw AuthException("Invalid username or password", 401)

        if (!verifyPassword(password, user.passwordHash)) {
            throw AuthException("Invalid username or password", 401)
        }

        logger.info("User logged in: {} ({}) group={}", username, user.id, groupId)
        return generateTokenPair(user, groupId)
    }

    suspend fun refresh(refreshTokenValue: String): AuthResponse {
        val session = authorizeRefresh(refreshTokenValue)
        // Rotate: revoke the presented token, then mint its replacement.
        refreshTokenStore.delete(session.storedTokenId)
        // Re-run the contributor against the group this login acted under (recovered from the
        // refresh token) so member/owner changes take effect on the next refresh, no invalidation hook.
        return generateTokenPair(session.user, session.claims.groupId)
    }

    /**
     * Switch the active group without re-entering credentials: validate the current refresh token and
     * re-mint a token pair under [groupId] (null leaves the group, back to a personal session).
     *
     * Membership is re-checked by the contributor, never trusted from the request. If the caller asked
     * for a group whose bucket the contributor could not resolve, they are not a member — refuse with
     * 403 BEFORE rotating, so a rejected switch leaves the current session usable instead of logging
     * the caller out.
     */
    suspend fun switchGroup(refreshTokenValue: String, groupId: String?): AuthResponse {
        val session = authorizeRefresh(refreshTokenValue)
        val group = resolveGroupClaims(session.user.id, groupId)
        if (groupId != null && group.groupUserId == null) {
            throw AuthException("Not a member of the requested group", 403)
        }
        refreshTokenStore.delete(session.storedTokenId)
        return mintTokenPair(session.user, group)
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

    private suspend fun generateTokenPair(user: User, groupId: String? = null): AuthResponse =
        mintTokenPair(user, resolveGroupClaims(user.id, groupId))

    private suspend fun mintTokenPair(user: User, group: GroupClaims): AuthResponse {
        val accessToken = jwtTokenProvider.generateAccessToken(user.id, user.username, group)
        val refreshToken = jwtTokenProvider.generateRefreshToken(user.id, group.groupId)

        // Store refresh token hash
        val refreshTokenEntity = RefreshToken(
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
     * Resolve the group claims for this mint, falling back to a personal session (no additional owners)
     * on any contributor failure: a broken group lookup must degrade to the caller's own assets, never
     * to a guessed owner set that could leak another group's data. The claims are then frozen into the
     * access token for its whole lifetime, so this is the one place membership is checked.
     */
    private suspend fun resolveGroupClaims(userId: String, groupId: String?): GroupClaims =
        try {
            claimsContributor.contributions(userId, groupId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Pass the throwable, not just its message: a product contributor's unexpected failure
            // (e.g. an NPE) must be diagnosable from the stack trace.
            logger.warn(
                "Group claims contributor failed for user '{}' group '{}'; falling back to personal session",
                userId, groupId, e
            )
            GroupClaims()
        }

    /** A validated refresh presentation: the user and claims it carries, loaded but not yet rotated. */
    private data class ValidatedRefresh(
        val user: User,
        val claims: JwtTokenProvider.JwtClaims,
        val storedTokenId: String
    )

    /**
     * Validate a refresh token and load its user, WITHOUT rotating it. Rotation (revoking the old
     * token) stays a separate caller step so a request that must be refused — e.g. [switchGroup]
     * rejecting a group the caller is not in — can bail out leaving the current session intact.
     */
    private suspend fun authorizeRefresh(refreshTokenValue: String): ValidatedRefresh {
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

        return ValidatedRefresh(user, claims, storedToken.id)
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
