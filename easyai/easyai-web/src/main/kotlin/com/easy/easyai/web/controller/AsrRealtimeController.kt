package com.easy.easyai.web.controller

import com.easy.easyai.web.security.AsrHandshakeAuthenticationFilter
import com.easy.easyai.web.security.currentGroupOwners
import com.easy.easyai.web.service.AsrRealtimeService
import com.easy.easyai.web.service.AsrTicketStore
import kotlinx.coroutines.reactor.mono
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * Handshake credentials for the recognition socket.
 *
 * `POST` rather than `GET` because issuing a credential is a state change, and because the browser
 * can then send its normal authenticated headers through `authFetch` instead of putting anything
 * durable in a URL. The response never contains provider credentials — only the ticket and the path
 * to open.
 */
@RestController
@RequestMapping("/api/asr/realtime")
class AsrRealtimeController(
    private val realtimeService: AsrRealtimeService
) {

    @PostMapping("/tickets", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun issueTicket(exchange: ServerWebExchange): Mono<AsrTicketDto> = mono {
        val owners = currentGroupOwners()
        // The origin is what the upgrade will be compared against; a missing one is recorded as such
        // so the handshake can then demand the same absence instead of trusting anything.
        val origin = exchange.request.headers.getFirst(HttpHeaders.ORIGIN)
        val ticket = realtimeService.issueTicket(owners, origin)
        AsrTicketDto(
            ticket = ticket.value,
            recordingId = ticket.recordingId,
            expiresInSeconds = AsrTicketStore.TTL_SECONDS,
            webSocketPath = AsrHandshakeAuthenticationFilter.PATH
        )
    }
}

/** Short-lived handshake credential plus the round it belongs to. */
data class AsrTicketDto(
    val ticket: String,
    val recordingId: String,
    val expiresInSeconds: Long,
    val webSocketPath: String
)
