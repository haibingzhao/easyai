package com.easy.easyai.autoconfigure.web

import io.netty.handler.codec.http.HttpHeaderNames
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Socket-level proof for the compression half of [ConsoleResourcesAutoConfiguration].
 *
 * The predicate reads the *response* `Content-Type`, which only holds if Netty consults it after the
 * writer has set that header — the same ordering Spring WebFlux produces. Asserting it over a real
 * connection keeps the SSE exclusion an observed fact rather than a comment: a compressed
 * `text/event-stream` is buffered by deflate, which stalls a chat stream instead of failing loudly.
 */
class ConsoleCompressionWiringTest {

    private var server: DisposableServer? = null

    @AfterEach
    fun tearDown() {
        server?.disposeNow()
    }

    @Nested
    inner class `the netty compression predicate` {

        @Test
        fun `a bundle script is gzipped on the way out`() {
            val port = serve("text/javascript", "console.log('probe');".repeat(400))

            assertEquals("gzip", contentEncoding(port))
        }

        @Test
        fun `a chat stream is handed to the client uncompressed`() {
            val port = serve("text/event-stream", "data: {\"type\":\"text_delta\"}\n\n".repeat(400))

            assertNull(contentEncoding(port))
        }
    }

    /** Starts a one-response server wired exactly like the customizer wires the host's. */
    private fun serve(contentType: String, body: String): Int {
        val bound = HttpServer.create()
            .port(0)
            .compress { _, response ->
                ConsoleResourcesAutoConfiguration.isCompressible(
                    response.responseHeaders().get(HttpHeaderNames.CONTENT_TYPE, "")
                )
            }
            .handle { _, response ->
                response.header(HttpHeaderNames.CONTENT_TYPE, contentType).sendString(Mono.just(body))
            }
            .bindNow()
        server = bound
        return bound.port()
    }

    /** `java.net.http` does not decode a body it was asked for with an explicit Accept-Encoding. */
    private fun contentEncoding(port: Int): String? {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/probe"))
            .header("Accept-Encoding", "gzip")
            .GET()
            .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding())
        return response.headers().firstValue("Content-Encoding").orElse(null)
    }
}
