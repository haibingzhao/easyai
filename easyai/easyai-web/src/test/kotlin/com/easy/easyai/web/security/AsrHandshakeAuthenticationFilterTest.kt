package com.easy.easyai.web.security

import com.easy.easyai.web.service.AsrTicketStore
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AsrHandshakeAuthenticationFilterTest {

    private val tickets = AsrTicketStore()
    private val filter = AsrHandshakeAuthenticationFilter(tickets)

    private fun issue(origin: String? = ORIGIN): String = assertNotNull(
        tickets.issue("alice", "easyai", listOf("alice", "group-1"), origin, "row-1", "qwen-audio-3.1-asr-flash-streaming")
    ).value

    private class Capture {
        val ran = AtomicBoolean(false)
        val authentication = AtomicReference<Authentication?>(null)

        val chain: WebFilterChain = WebFilterChain { exchange ->
            ran.set(true)
            ReactiveSecurityContextHolder.getContext()
                .doOnNext { context -> authentication.set(context.authentication) }
                .then()
        }
    }

    private fun run(request: MockServerHttpRequest.BaseBuilder<*>): Pair<MockServerWebExchange, Capture> {
        val exchange = MockServerWebExchange.from(request.build())
        val capture = Capture()
        filter.filter(exchange, capture.chain).block(Duration.ofSeconds(5))
        return exchange to capture
    }

    @Nested
    inner class Admission {

        @Test
        fun `a valid ticket authenticates the upgrade and passes the caller on`() {
            val ticket = issue()
            val (_, capture) = run(
                MockServerHttpRequest.get("${AsrHandshakeAuthenticationFilter.PATH}?ticket=$ticket")
                    .header("Origin", ORIGIN)
            )
            assertTrue(capture.ran.get())
            assertEquals("alice", capture.authentication.get()?.principal)
            // The handler picks the ticket up from the store; exchange attributes cannot reach it.
            val recorded = tickets.claim(ticket)
            assertNotNull(recorded)
            assertEquals("alice", recorded.userId)
            assertNull(tickets.claim(ticket))
        }

        @Test
        fun `another path is left alone`() {
            val ticket = issue()
            val (_, capture) = run(
                MockServerHttpRequest.get("/api/asr/availability?ticket=$ticket").header("Origin", ORIGIN)
            )
            assertTrue(capture.ran.get())
            // Untouched: a request to another path must not consume the caller's ticket.
            assertNull(tickets.claim(ticket))
        }
    }

    @Nested
    inner class Refusals {

        @Test
        fun `a missing ticket is rejected before the security chain decides`() {
            val (exchange, capture) = run(
                MockServerHttpRequest.get(AsrHandshakeAuthenticationFilter.PATH).header("Origin", ORIGIN)
            )
            assertEquals(HttpStatus.UNAUTHORIZED, exchange.response.statusCode)
            assertTrue(!capture.ran.get())
        }

        @Test
        fun `a url token is refused even alongside a valid ticket`() {
            val ticket = issue()
            val (exchange, capture) = run(
                MockServerHttpRequest.get("${AsrHandshakeAuthenticationFilter.PATH}?ticket=$ticket&token=jwt")
                    .header("Origin", ORIGIN)
            )
            assertEquals(HttpStatus.UNAUTHORIZED, exchange.response.statusCode)
            assertTrue(!capture.ran.get())
        }

        @Test
        fun `a replayed ticket is refused`() {
            val ticket = issue()
            run(
                MockServerHttpRequest.get("${AsrHandshakeAuthenticationFilter.PATH}?ticket=$ticket")
                    .header("Origin", ORIGIN)
            )
            val (exchange, capture) = run(
                MockServerHttpRequest.get("${AsrHandshakeAuthenticationFilter.PATH}?ticket=$ticket")
                    .header("Origin", ORIGIN)
            )
            assertEquals(HttpStatus.UNAUTHORIZED, exchange.response.statusCode)
            assertTrue(!capture.ran.get())
        }

        @Test
        fun `an upgrade from another origin is refused`() {
            val ticket = issue()
            val (exchange, capture) = run(
                MockServerHttpRequest.get("${AsrHandshakeAuthenticationFilter.PATH}?ticket=$ticket")
                    .header("Origin", "https://evil.example.com")
            )
            assertEquals(HttpStatus.FORBIDDEN, exchange.response.statusCode)
            assertTrue(!capture.ran.get())
        }

        @Test
        fun `a ticket issued without an origin is refused when an origin appears`() {
            val ticket = issue(origin = null)
            val (exchange, _) = run(
                MockServerHttpRequest.get("${AsrHandshakeAuthenticationFilter.PATH}?ticket=$ticket")
                    .header("Origin", ORIGIN)
            )
            assertEquals(HttpStatus.FORBIDDEN, exchange.response.statusCode)
        }

        @Test
        fun `an origin-less client is accepted only when the ticket has none`() {
            val ticket = issue(origin = null)
            val (exchange, capture) = run(
                MockServerHttpRequest.get("${AsrHandshakeAuthenticationFilter.PATH}?ticket=$ticket")
            )
            assertEquals(null, exchange.response.statusCode)
            assertTrue(capture.ran.get())
        }
    }

    private companion object {
        const val ORIGIN = "https://app.example.com"
    }
}
