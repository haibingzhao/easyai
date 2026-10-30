package com.easy.easyai.web.config

import com.easy.easyai.web.controller.AsrRealtimeWebSocketHandler
import com.easy.easyai.web.security.AsrHandshakeAuthenticationFilter
import com.easy.easyai.web.service.AsrRealtimeService
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.web.reactive.HandlerMapping
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter

/**
 * Registers the recognition socket under an exact URL.
 *
 * The mapping is separate from the annotation-driven one on purpose: the path is matched verbatim
 * (no wildcard pattern that could also answer an HTTP request), and its order puts it ahead of the
 * controller mapping so an upgrade never falls through to a handler that only speaks HTTP.
 *
 * [WebSocketHandlerAdapter] is conditional because a host that already serves its own sockets —
 * the desktop shell, a future MCP transport — supplies one; two adapters would both claim the
 * upgrade.
 */
@Configuration
class AsrRealtimeConfiguration {

    @Bean
    fun asrRealtimeWebSocketHandler(asrRealtimeService: AsrRealtimeService) =
        AsrRealtimeWebSocketHandler(asrRealtimeService)

    @Bean
    fun asrRealtimeHandlerMapping(handler: AsrRealtimeWebSocketHandler): HandlerMapping =
        SimpleUrlHandlerMapping(mapOf(AsrHandshakeAuthenticationFilter.PATH to handler), Ordered.HIGHEST_PRECEDENCE + 1)

    @Bean
    @ConditionalOnMissingBean(WebSocketHandlerAdapter::class)
    fun webSocketHandlerAdapter() = WebSocketHandlerAdapter()
}
