package com.easy.easyai.web.security

import com.easy.easyai.auth.AuthConstants
import com.easy.easyai.auth.model.UserProfile
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
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
 * - POST /api/auth/logout    - Logout (revoke refresh token)
 * - GET  /api/auth/me        - Get current user profile
 */
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
    private val authProperties: AuthProperties
) {
    private val logger = LoggerFactory.getLogger(javaClass)

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
        val response = authService.login(request.username, request.password)
        setRefreshTokenCookie(exchange, response.refreshToken)
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
            // Auth disabled: return a default system user profile
            UserProfileDto(
                id = AuthConstants.SYSTEM_USER_ID,
                username = "system",
                displayName = "System",
                avatar = "avatar-1",
                email = null
            )
        } else {
            val userId = getCurrentUserId()
            if (userId == AuthConstants.SYSTEM_USER_ID) {
                // No valid authentication found — reject
                throw AuthException("Authentication required", 401)
            }
            val profile = authService.getProfile(userId)
            profile.toDto()
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

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

    private fun UserProfile.toDto() = UserProfileDto(
        id = id,
        username = username,
        displayName = displayName,
        avatar = avatar,
        email = email
    )
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
    val password: String
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
)
