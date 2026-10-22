package com.easy.easyai.web.security

import com.easy.easyai.auth.AuthConstants
import com.easy.easyai.auth.model.UserProfile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.HttpCookie
import org.springframework.http.HttpHeaders
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cookies are scoped by host, not port. Hosts that run more than one console behind the same host
 * (the family app and the trading app on one Mac Mini) must therefore name their refresh cookie per
 * app, or the last login overwrites the other app's session.
 */
class AuthControllerCookieNameTest {

    private val authService = mockk<AuthService>()
    private val profile = UserProfile("user-1", "alice", "Alice", AuthConstants.DEFAULT_AVATAR, null)
    private val response = AuthResponse("access-token", "refresh-token", profile)

    private fun controller(cookieName: String) = AuthController(
        authService,
        AuthProperties(enabled = true, refreshCookieName = cookieName)
    )

    @Test
    fun `login writes the configured refresh cookie name`() {
        coEvery { authService.login("alice", "secret123", null) } returns response
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/login").build())

        controller("easyai_home_refresh_token").login(LoginRequest("alice", "secret123"), exchange).block()
        // addCookie buffers cookies on the response and writes them on commit.
        exchange.response.setComplete().block()

        val setCookie = exchange.response.headers.getFirst(HttpHeaders.SET_COOKIE)
        assertTrue(
            setCookie != null && setCookie.startsWith("easyai_home_refresh_token="),
            "unexpected Set-Cookie: $setCookie"
        )
        assertTrue(
            setCookie.contains("Path=/api/auth"),
            "refresh cookie must stay scoped to the auth endpoints: $setCookie"
        )
    }

    @Test
    fun `refresh reads the configured cookie name`() {
        coEvery { authService.refresh("refresh-token") } returns response
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/auth/refresh")
                .cookie(HttpCookie("easyai_home_refresh_token", "refresh-token"))
                .build()
        )

        controller("easyai_home_refresh_token").refresh(exchange).block()

        coVerify(exactly = 1) { authService.refresh("refresh-token") }
    }

    @Test
    fun `the default cookie name is unchanged`() {
        assertEquals(AuthConstants.REFRESH_TOKEN_COOKIE, AuthProperties().refreshCookieName)
    }
}
