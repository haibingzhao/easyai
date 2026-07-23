package com.easy.easyai.web.security

import com.easy.easyai.auth.AuthConstants
import com.easy.easyai.auth.jwt.JwtTokenProvider
import com.easy.easyai.tools.mcp.McpClientManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.http.HttpHeaders
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * WebFilter that eagerly triggers async MCP connection for authenticated users.
 *
 * On each authenticated request, extracts the userId from the JWT token and
 * fires-and-forgets [McpClientManager.ensureUserConnected]. This pre-warms
 * MCP connections in the background so that by the time the user sends a chat
 * message, their MCP servers are already connected.
 *
 * The call is idempotent — returns immediately if the user is already initialized.
 * Never blocks or delays the current request.
 */
class McpPreConnectFilter(
    private val mcpClientManager: McpClientManager,
    private val jwtTokenProvider: JwtTokenProvider,
    private val authEnabled: Boolean
) : WebFilter, DisposableBean {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        // Only pre-connect for authenticated API requests
        if (authEnabled) {
            val token = extractToken(exchange)
            if (token != null) {
                try {
                    val claims = jwtTokenProvider.validateAccessToken(token)
                    if (claims != null) {
                        triggerPreConnect(claims.userId)
                    }
                } catch (_: Exception) {
                    // Token validation failed — skip pre-connect, let the auth filter handle it
                }
            }
        }
        return chain.filter(exchange)
    }

    private fun triggerPreConnect(userId: String) {
        if (userId == AuthConstants.SYSTEM_USER_ID) return
        scope.launch {
            try {
                mcpClientManager.ensureUserConnected(userId)
            } catch (e: Exception) {
                logger.debug("MCP pre-connect failed for user '{}': {}", userId, e.message)
            }
        }
    }

    private fun extractToken(exchange: ServerWebExchange): String? {
        val authHeader = exchange.request.headers.getFirst(HttpHeaders.AUTHORIZATION)
        if (authHeader != null && authHeader.startsWith(AuthConstants.BEARER_PREFIX)) {
            return authHeader.removePrefix(AuthConstants.BEARER_PREFIX)
        }
        return exchange.request.queryParams.getFirst("token")
    }

    override fun destroy() {
        scope.coroutineContext.let { ctx ->
            (ctx[kotlinx.coroutines.Job])?.cancel()
        }
    }
}
