package com.easy.easyai.web.security

import com.easy.easyai.auth.AuthConstants
import com.easy.easyai.auth.model.UserProfile
import com.easy.easyai.skills.SkillRefreshService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseCookie
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * REST controller for authentication endpoints.
 *
 * Endpoints:
 * - POST /api/auth/register  - Register a new user
 * - POST /api/auth/login     - Login with username/password
 * - POST /api/auth/refresh   - Refresh access token using refresh token cookie
 * - POST /api/auth/switch-group - Switch the active group and re-mint tokens (no password)
 * - POST /api/auth/logout    - Logout (revoke refresh token)
 * - GET  /api/auth/me        - Get current user profile
 */
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
    private val authProperties: AuthProperties,
    @param:Autowired(required = false) private val skillRefreshService: SkillRefreshService? = null
) : DisposableBean {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Background scope for the post-login skill sync: a slow object store must not delay a login. */
    private val skillSyncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun destroy() {
        skillSyncScope.cancel()
    }

    @PostMapping("/register")
    fun register(
        @RequestBody request: RegisterRequest,
        exchange: ServerWebExchange
    ): Mono<AuthResponseDto> = mono {
        val response = authService.register(request.username, request.password, request.displayName, request.email)
        setRefreshTokenCookie(exchange, response.refreshToken)
        response.toDto()
    }

    @PostMapping("/login")
    fun login(
        @RequestBody request: LoginRequest,
        exchange: ServerWebExchange
    ): Mono<AuthResponseDto> = mono {
        val response = authService.login(request.username, request.password, request.groupId)
        setRefreshTokenCookie(exchange, response.refreshToken)
        syncSkillsQuietly(response.user.id)
        response.toDto()
    }

    @PostMapping("/refresh")
    fun refresh(
        exchange: ServerWebExchange
    ): Mono<AuthResponseDto> = mono {
        val refreshToken = exchange.request.cookies.getFirst(AuthConstants.REFRESH_TOKEN_COOKIE)?.value
            ?: throw AuthException("Refresh token not found in cookie", 401)
        val response = authService.refresh(refreshToken)
        setRefreshTokenCookie(exchange, response.refreshToken)
        response.toDto()
    }

    /**
     * Switch the active group without re-entering credentials. Reads the same httpOnly refresh cookie
     * as /refresh; the service re-validates membership and re-mints the pair under the requested group.
     * A group the caller is not a member of is refused (403) and leaves the current session untouched.
     */
    @PostMapping("/switch-group")
    fun switchGroup(
        @RequestBody request: SwitchGroupRequest,
        exchange: ServerWebExchange
    ): Mono<AuthResponseDto> = mono {
        val refreshToken = exchange.request.cookies.getFirst(AuthConstants.REFRESH_TOKEN_COOKIE)?.value
            ?: throw AuthException("Refresh token not found in cookie", 401)
        val response = authService.switchGroup(refreshToken, request.groupId)
        setRefreshTokenCookie(exchange, response.refreshToken)
        syncSkillsQuietly(response.user.id)
        response.toDto()
    }

    @PostMapping("/logout")
    fun logout(exchange: ServerWebExchange): Mono<Map<String, String>> = mono {
        val refreshToken = exchange.request.cookies.getFirst(AuthConstants.REFRESH_TOKEN_COOKIE)?.value
        authService.logout(refreshToken)
        clearRefreshTokenCookie(exchange)
        mapOf("status" to "ok")
    }

    @GetMapping("/me")
    fun me(exchange: ServerWebExchange): Mono<UserProfileDto> = mono {
        if (!authProperties.enabled) {
            // Auth disabled: the caller is the system identity, whose skills the desktop relies on
            // immediately — this is the fallback trigger for a process that never saw a login.
            syncSkillsQuietly(AuthConstants.SYSTEM_USER_ID)
            UserProfileDto(
                id = AuthConstants.SYSTEM_USER_ID,
                username = "system",
                displayName = "System",
                avatar = AuthConstants.DEFAULT_AVATAR,
                email = null
            )
        } else {
            val userId = getCurrentUserId()
            if (userId == AuthConstants.SYSTEM_USER_ID) {
                // No valid authentication found — reject
                throw AuthException("Authentication required", 401)
            }
            syncSkillsQuietly(userId)
            val profile = authService.getProfile(userId)
            profile.toDto()
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    /**
     * Reconcile one owner's skills against the catalog and object storage, off the response path.
     * [SkillRefreshService.ensureSynced] runs at most once per owner per process and logs its own
     * failures, so an unreachable package store can never turn into a failed login or profile read.
     */
    private fun syncSkillsQuietly(userId: String) {
        val service = skillRefreshService ?: return
        skillSyncScope.launch {
            try {
                service.ensureSynced(userId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Background skill sync for '{}' did not complete: {}", userId, e.message)
            }
        }
    }

    private fun setRefreshTokenCookie(exchange: ServerWebExchange, refreshToken: String) {
        val isSecure = exchange.request.uri.scheme == "https"
        val cookie = ResponseCookie.from(AuthConstants.REFRESH_TOKEN_COOKIE, refreshToken)
            .httpOnly(true)
            .secure(isSecure)
            .path("/api/auth")
            .maxAge(authProperties.refreshTokenExpirationSeconds)
            .sameSite("Lax")
            .build()
        exchange.response.addCookie(cookie)
    }

    private fun clearRefreshTokenCookie(exchange: ServerWebExchange) {
        val cookie = ResponseCookie.from(AuthConstants.REFRESH_TOKEN_COOKIE, "")
            .httpOnly(true)
            .path("/api/auth")
            .maxAge(0)
            .build()
        exchange.response.addCookie(cookie)
    }

    private fun AuthResponse.toDto() = AuthResponseDto(
        accessToken = accessToken,
        user = user.toDto()
    )

    private fun UserProfile.toDto() = UserProfileDto.from(this)
}

// ─── Request / Response DTOs ──────────────────────────────────────────────────

data class RegisterRequest(
    val username: String,
    val password: String,
    val displayName: String? = null,
    val email: String? = null
)

data class LoginRequest(
    val username: String,
    val password: String,
    /**
     * Optional group this login acts under, for a user who belongs to more than one. The product's
     * claims contributor validates membership and resolves the group's shared-asset bucket; null (or
     * an unknown/unauthorized id) degrades to a personal, group-less session.
     */
    val groupId: String? = null
)

data class SwitchGroupRequest(
    /** Group to act under from now on; null leaves the group context (back to a personal session). */
    val groupId: String? = null
)

data class AuthResponseDto(
    val accessToken: String,
    val user: UserProfileDto
)

data class UserProfileDto(
    val id: String,
    val username: String,
    val displayName: String,
    val avatar: String,
    val email: String?
) {
    companion object {
        @JvmStatic
        fun from(profile: UserProfile) = UserProfileDto(
            id = profile.id,
            username = profile.username,
            displayName = profile.displayName,
            avatar = profile.avatar,
            email = profile.email
        )
    }
}
