package com.easy.easyai.web.security

import com.easy.easyai.web.service.AsrTicket
import com.easy.easyai.web.service.AsrTicketStore
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * Authenticates the recognition socket's upgrade request from a one-time ticket.
 *
 * A native `WebSocket` cannot send an `Authorization` header, and reusing the existing `?token=`
 * JWT fallback would put a long-lived credential in proxy logs and browser history. This filter
 * therefore accepts exactly one credential — the ticket — on exactly one path, and refuses
 * everything else *before* the security chain can decide the request is anonymous:
 *
 *  - a `?token=` JWT is rejected even though the generic filter would honour it;
 *  - the ticket is consumed atomically, so a replay opens nothing;
 *  - the handshake `Origin` must be the one recorded when the ticket was issued, which makes the
 *    check independent of CORS configuration and of any forwarded-host header.
 *
 * The restored authentication is what keeps `anyExchange().authenticated()` meaningful: this path is
 * not added to the permit-all list, and the identity is a snapshot (user + owner set) rather than a
 * re-resolution, so a mid-session group change cannot move the session to another bucket.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class AsrHandshakeAuthenticationFilter(
    private val tickets: AsrTicketStore
) : WebFilter {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        if (exchange.request.path.value() != PATH) return chain.filter(exchange)
        if (exchange.request.queryParams.containsKey("token")) {
            logger.info("Rejected a URL token on the recognition socket; only tickets are accepted")
            return reject(exchange, HttpStatus.UNAUTHORIZED, "A ticket is required to open the recognition socket")
        }
        val value = exchange.request.queryParams.getFirst(TICKET_PARAM)
        if (value.isNullOrBlank()) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "A ticket is required to open the recognition socket")
        }
        val ticket = tickets.consume(value)
            ?: return reject(exchange, HttpStatus.UNAUTHORIZED, "The ticket is invalid, expired or already used")
        if (!originMatches(exchange, ticket)) {
            logger.info("Rejected recognition socket for {}: origin mismatch", ticket.userId)
            return reject(exchange, HttpStatus.FORBIDDEN, "Origin does not match the ticket")
        }
        // The socket handler cannot read exchange attributes (`HandshakeInfo.attributes` carries web
        // session attributes only), so park the ticket in the store for it to claim exactly once.
        tickets.handOver(ticket)
        val authentication = UsernamePasswordAuthenticationToken(
            ticket.userId,
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER"))
        )
        return chain.filter(exchange).contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
    }

    /**
     * A browser always sends `Origin` on a WebSocket upgrade, so an absent one means a non-browser
     * client: accepted only when the ticket was also issued without one.
     */
    private fun originMatches(exchange: ServerWebExchange, ticket: AsrTicket): Boolean {
        val presented = exchange.request.headers.getFirst(HttpHeaders.ORIGIN)
        return when {
            ticket.origin == null -> presented == null
            else -> presented != null && presented.equals(ticket.origin, ignoreCase = true)
        }
    }

    private fun reject(exchange: ServerWebExchange, status: HttpStatus, message: String): Mono<Void> {
        exchange.response.statusCode = status
        exchange.response.headers.contentType = MediaType.APPLICATION_JSON
        val body = """{"error":"$message"}""".toByteArray()
        return exchange.response.writeWith(Mono.just(exchange.response.bufferFactory().wrap(body)))
    }

    companion object {
        const val PATH = "/api/asr/realtime"
        const val TICKET_PARAM = "ticket"
    }
}
