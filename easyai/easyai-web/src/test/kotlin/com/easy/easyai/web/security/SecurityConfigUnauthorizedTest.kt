package com.easy.easyai.web.security

import com.easy.easyai.auth.jwt.JwtTokenProvider
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * A rejected request must be a bare 401. Spring Security ships `ExceptionTranslationWebFilter` with a
 * Basic entry point pre-installed, so a chain that never names an entry point answers with
 * `WWW-Authenticate: Basic` — and a same-origin browser turns that into a native login dialog the
 * JWT flow can never satisfy.
 */
class SecurityConfigUnauthorizedTest {

    @RestController
    internal class ProbeController {
        @GetMapping("/api/agent/list")
        fun protectedResource(): String = "protected"

        @GetMapping("/api/auth/refresh")
        fun publicAuthEndpoint(): String = "public"
    }

    private val securityConfig = SecurityConfig()
    private val keyPair = JwtTokenProvider.generateDevKeyPair()
    private val tokenProvider = JwtTokenProvider(
        privateKey = keyPair.private,
        publicKey = keyPair.public,
        accessExpirySeconds = 7200,
        refreshExpirySeconds = 15552000
    )

    private fun client(authEnabled: Boolean): WebTestClient {
        val authProperties = AuthProperties(enabled = authEnabled)
        val filter = JwtAuthenticationFilter(tokenProvider, authEnabled)
        val chain = securityConfig.securityWebFilterChain(
            ServerHttpSecurity.http(),
            filter,
            authProperties
        )
        var builder = WebTestClient.bindToController(ProbeController())
        chain.webFilters.collectList().block().orEmpty().forEach { builder = builder.webFilter(it) }
        return builder.build()
    }

    @Test
    fun `an anonymous protected request gets 401 without a Basic challenge`() {
        client(authEnabled = true).get().uri("/api/agent/list")
            .exchange()
            .expectStatus().isUnauthorized
            .expectHeader().doesNotExist(HttpHeaders.WWW_AUTHENTICATE)
    }

    @Test
    fun `a public auth route stays reachable`() {
        client(authEnabled = true).get().uri("/api/auth/refresh")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `a bearer token passes the chain through`() {
        val token = tokenProvider.generateAccessToken("user-1", "alice")
        client(authEnabled = true).get().uri("/api/agent/list")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `disabling auth permits every route`() {
        client(authEnabled = false).get().uri("/api/agent/list")
            .exchange()
            .expectStatus().isOk
    }
}
