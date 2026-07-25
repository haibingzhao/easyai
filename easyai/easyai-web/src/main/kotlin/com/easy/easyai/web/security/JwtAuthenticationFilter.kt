package com.easy.easyai.web.security

import com.easy.easyai.auth.AuthConstants
import com.easy.easyai.auth.jwt.JwtTokenProvider
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * WebFilter that validates JWT Bearer tokens and sets the Spring Security context.
 *
 * When auth is disabled (`easyai.auth.enabled=false`), this filter is a no-op.
 * When auth is enabled, it extracts the Bearer token from the Authorization header,
 * or as a fallback from the `token` query parameter (for SSE/EventSource which cannot
 * set custom headers), validates it, and sets an [UsernamePasswordAuthenticationToken]
 * in the reactive security context with userId as the principal.
 */
class JwtAuthenticationFilter(
    private val jwtTokenProvider: JwtTokenProvider,
    private val authEnabled: Boolean
) : WebFilter {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        if (!authEnabled) {
            return chain.filter(exchange)
        }

        // Skip internal endpoints — they handle their own script-token authentication
        val path = exchange.request.path.value()
        if (path.startsWith("/api/internal/")) {
            return chain.filter(exchange)
        }

        // Prefer Authorization header; fall back to `token` query param (for SSE/EventSource)
        val token = extractToken(exchange) ?: return chain.filter(exchange)

        return try {
            val claims = jwtTokenProvider.validateAccessToken(token)
            if (claims != null) {
                val auth = UsernamePasswordAuthenticationToken(
                    claims.userId, null, listOf(SimpleGrantedAuthority("ROLE_USER"))
                )
                chain.filter(exchange)
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
            } else {
                logger.debug("Invalid JWT access token")
                unauthorized(exchange)
            }
        } catch (e: Exception) {
            logger.warn("JWT validation failed: {}", e.message)
            unauthorized(exchange)
        }
    }

    /**
     * Extract JWT token from the Authorization header or the `token` query parameter.
     * The header takes precedence; the query param is a fallback for browser EventSource
     * API which does not support custom request headers.
     */
    private fun extractToken(exchange: ServerWebExchange): String? {
        val authHeader = exchange.request.headers.getFirst(HttpHeaders.AUTHORIZATION)
        if (authHeader != null && authHeader.startsWith(AuthConstants.BEARER_PREFIX)) {
            return authHeader.removePrefix(AuthConstants.BEARER_PREFIX)
        }
        // Fallback: query parameter (used by SSE EventSource)
        // TODO(security): Token in URL is logged in server/proxy access logs and stored in browser history.
        //  Consider replacing with a short-lived one-time ticket exchanged for a token server-side,
        //  or enforce a stricter max-age on query-param tokens to limit the exposure window.
        return exchange.request.queryParams.getFirst("token")
    }

    /**
     * Return a 401 Unauthorized response without continuing the filter chain.
     * Used when a Bearer token is present but invalid.
     */
    private fun unauthorized(exchange: ServerWebExchange): Mono<Void> {
        exchange.response.statusCode = HttpStatus.UNAUTHORIZED
        exchange.response.headers.contentType = MediaType.APPLICATION_JSON
        val body = "{\"error\":\"Invalid or expired token\"}".toByteArray()
        val buffer = exchange.response.bufferFactory().wrap(body)
        return exchange.response.writeWith(Mono.just(buffer))
    }
}
