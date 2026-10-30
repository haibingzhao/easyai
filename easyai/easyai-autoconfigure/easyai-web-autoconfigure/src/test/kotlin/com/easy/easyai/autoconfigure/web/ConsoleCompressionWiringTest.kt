package com.easy.easyai.autoconfigure.web

import org.junit.jupiter.api.Nested
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory
import org.springframework.boot.reactor.netty.autoconfigure.NettyReactiveWebServerAutoConfiguration
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService
import org.springframework.web.server.adapter.WebHttpHandlerBuilder
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Properties
import java.util.zip.GZIPInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsoleCompressionWiringTest {

    private val contextRunner = ReactiveWebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                NettyReactiveWebServerAutoConfiguration::class.java,
                ConsoleResourcesAutoConfiguration::class.java,
            )
        )
        .withPropertyValues("server.port=0", "server.address=127.0.0.1")

    @Nested
    inner class `standard HTTP compression` {

        @ParameterizedTest
        @ValueSource(strings = ["easyai-web-server", "easyai-desktop-server"])
        fun `session history is gzipped without changing its content`(application: String) {
            val body = """{"messages":[{"content":"${"历史消息内容".repeat(1000)}"}]}"""
            withServer(application, body = body) { port ->
                val response = request(port)
                assertEquals("gzip", response.headers().firstValue("Content-Encoding").orElse(null))
                val decoded = GZIPInputStream(response.body().inputStream()).bufferedReader().use { it.readText() }
                assertEquals(body, decoded)
            }
        }

        @ParameterizedTest
        @ValueSource(strings = ["easyai-web-server", "easyai-desktop-server"])
        fun `static assets and problem JSON remain compressible`(application: String) {
            for (contentType in listOf("text/javascript", "image/svg+xml", "application/problem+json")) {
                withServer(application, contentType) { port ->
                    assertEquals("gzip", request(port).headers().firstValue("Content-Encoding").orElse(null), contentType)
                }
            }
        }

        @ParameterizedTest
        @ValueSource(strings = ["easyai-web-server", "easyai-desktop-server"])
        fun `known response sizes respect the two kilobyte threshold`(application: String) {
            for (size in listOf(2047, 2048)) {
                val body = "\"${"a".repeat(size - 2)}\""
                withServer(application, body = body) { port ->
                    val expected = if (size < 2048) null else "gzip"
                    assertEquals(expected, request(port).headers().firstValue("Content-Encoding").orElse(null))
                }
            }
        }

        @ParameterizedTest
        @ValueSource(strings = ["easyai-web-server", "easyai-desktop-server"])
        fun `disabling compression applies to JSON and static assets`(application: String) {
            for (contentType in listOf("application/json", "text/javascript")) {
                withServer(application, contentType, overrides = arrayOf("server.compression.enabled=false")) { port ->
                    assertNull(request(port).headers().firstValue("Content-Encoding").orElse(null))
                }
            }
        }

        @ParameterizedTest
        @ValueSource(strings = ["easyai-web-server", "easyai-desktop-server"])
        fun `host MIME configuration is not overridden`(application: String) {
            withServer(application, overrides = arrayOf("server.compression.mime-types=text/javascript")) { port ->
                assertNull(request(port).headers().firstValue("Content-Encoding").orElse(null))
            }
        }

        @ParameterizedTest
        @ValueSource(strings = ["easyai-web-server", "easyai-desktop-server"])
        fun `a client that declines compression receives plain content`(application: String) {
            withServer(application) { port ->
                assertNull(request(port, "identity").headers().firstValue("Content-Encoding").orElse(null))
            }
        }
    }

    @Nested
    inner class `realtime protocols are not compressed` {

        @ParameterizedTest
        @ValueSource(strings = ["easyai-web-server", "easyai-desktop-server"])
        fun `chunked SSE stays uncompressed even above the size threshold`(application: String) {
            val body = "data: {\"type\":\"text_delta\"}\n\n".repeat(400)
            for (contentType in listOf("text/event-stream", "text/event-stream;charset=UTF-8")) {
                withServer(application, contentType, body) { port ->
                    val response = request(port)
                    assertNull(response.headers().firstValue("Content-Encoding").orElse(null))
                    assertEquals(body, response.body().toString(Charsets.UTF_8))
                }
            }
        }

        @ParameterizedTest
        @ValueSource(strings = ["easyai-web-server", "easyai-desktop-server"])
        fun `WebSocket refuses permessage deflate and echoes uncompressed frames`(application: String) {
            withServer(application) { port ->
                Socket("127.0.0.1", port).use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().buffered()
                    val output = socket.getOutputStream()
                    val headers = listOf(
                        "GET /ws HTTP/1.1",
                        "Host: 127.0.0.1:$port",
                        "Connection: Upgrade",
                        "Upgrade: websocket",
                        "Sec-WebSocket-Version: 13",
                        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==",
                        "Sec-WebSocket-Extensions: permessage-deflate",
                        "Accept-Encoding: gzip",
                        "",
                        "",
                    ).joinToString("\r\n")
                    output.write(headers.toByteArray(Charsets.US_ASCII))
                    output.flush()
                    val handshake = StringBuilder()
                    while (!handshake.endsWith("\r\n\r\n")) {
                        val next = input.read()
                        assertTrue(next >= 0, "Connection closed before the WebSocket handshake completed")
                        handshake.append(next.toChar())
                    }
                    assertTrue(handshake.startsWith("HTTP/1.1 101 "), handshake.toString())
                    assertTrue(handshake.contains("s3pPLMBiTxaQ9kYGzzhZRbK+xOo="), handshake.toString())
                    assertFalse(handshake.contains("Sec-WebSocket-Extensions:", ignoreCase = true))
                    assertFalse(handshake.contains("Content-Encoding:", ignoreCase = true))

                    val payload = "websocket message ".repeat(300).toByteArray()
                    // Client frames must be masked; an all-zero mask leaves these test bytes unchanged.
                    output.write(byteArrayOf(0x81.toByte(), 0xfe.toByte(), (payload.size shr 8).toByte(), payload.size.toByte(), 0, 0, 0, 0))
                    output.write(payload)
                    output.flush()
                    assertEquals(0x81, input.read(), "Expected a final text frame with RSV1 clear")
                    assertEquals(126, input.read(), "Expected an unmasked server frame with a 16-bit length")
                    val length = (input.read() shl 8) or input.read()
                    assertEquals(payload.size, length)
                    assertEquals(payload.toString(Charsets.UTF_8), input.readNBytes(length).toString(Charsets.UTF_8))
                    output.write(byteArrayOf(0x88.toByte(), 0x80.toByte(), 0, 0, 0, 0))
                    output.flush()
                }
            }
        }
    }

    private fun withServer(
        application: String,
        contentType: String = "application/json",
        body: String = "\"${"payload".repeat(1000)}\"",
        overrides: Array<String> = emptyArray(),
        verify: (Int) -> Unit,
    ) {
        val path = Path.of("../../../easyai-apps", application, "src/main/resources/application.properties")
        val properties = Properties().apply { Files.newInputStream(path).use { load(it) } }
        val compression = properties.stringPropertyNames()
            .filter { it.startsWith("server.compression.") }
            .map { "$it=${properties.getProperty(it)}" }
            .toTypedArray()
        contextRunner.withPropertyValues(*compression).withPropertyValues(*overrides).run { context ->
            val handshakeService = HandshakeWebSocketService()
            val echoHandler = WebSocketHandler { session ->
                session.send(session.receive().map { session.textMessage(it.payloadAsText) })
            }
            val handler = WebHttpHandlerBuilder.webHandler { exchange ->
                if (exchange.request.path.value() == "/ws") {
                    handshakeService.handleRequest(exchange, echoHandler)
                } else {
                    val response = exchange.response
                    response.headers.set(HttpHeaders.CONTENT_TYPE, contentType)
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    if (MediaType.TEXT_EVENT_STREAM.isCompatibleWith(MediaType.parseMediaType(contentType))) {
                        val chunks = body.split("\n\n").filter { it.isNotEmpty() }.map {
                            Mono.just(response.bufferFactory().wrap("$it\n\n".toByteArray(Charsets.UTF_8)))
                        }
                        response.writeAndFlushWith(Flux.fromIterable(chunks))
                    } else {
                        response.headers.contentLength = bytes.size.toLong()
                        response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)))
                    }
                }
            }.build()
            val server = context.getBean(NettyReactiveWebServerFactory::class.java).getWebServer(handler)
            server.start()
            try {
                verify(server.port)
            } finally {
                server.stop()
            }
        }
    }

    private fun request(port: Int, acceptEncoding: String = "gzip"): HttpResponse<ByteArray> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/probe"))
            .timeout(Duration.ofSeconds(5))
            .header("Accept-Encoding", acceptEncoding)
            .GET()
            .build()
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().use { client ->
            client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        }
    }
}
