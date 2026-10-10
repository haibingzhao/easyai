package com.easy.easyai.autoconfigure.anthropic

import com.anthropic.core.RequestOptions
import com.anthropic.core.http.HttpClient
import com.anthropic.core.http.HttpRequest
import com.anthropic.core.http.HttpResponse
import com.anthropic.core.http.Headers
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Verifies [AnthropicErrorLoggingInterceptor] on the SDK transport: an error (non-2xx) response is
 * logged and its body replayed so the SDK's downstream error parsing still reads it, while a
 * successful (streaming) response is returned untouched so the SSE stream is never consumed early.
 */
class AnthropicErrorLoggingInterceptorTest {

    private val interceptor = AnthropicErrorLoggingInterceptor()

    private class FakeResponse(
        private val code: Int,
        private val bodyText: String,
        private val contentType: String,
        private val requestId: String?
    ) : HttpResponse {
        override fun statusCode(): Int = code
        override fun headers(): Headers = Headers.builder().apply {
            put("content-type", contentType)
            requestId?.let { put("x-request-id", it) }
        }.build()
        override fun body(): InputStream = ByteArrayInputStream(bodyText.toByteArray())
        override fun close() {}
    }

    private fun wrappedFor(response: HttpResponse): HttpClient {
        val delegate = mockk<HttpClient>()
        every { delegate.execute(any<HttpRequest>(), any<RequestOptions>()) } returns response
        return interceptor.intercept(delegate)
    }

    private fun execute(client: HttpClient): HttpResponse =
        client.execute(mockk<HttpRequest>(relaxed = true), mockk<RequestOptions>(relaxed = true))

    @Nested
    inner class `error responses` {

        @Test
        fun `SSE error body stays readable after logging`() {
            val payload = "event: error\ndata: {\"code\":\"InvalidParameter\"}"
            val returned = execute(wrappedFor(FakeResponse(400, payload, "text/event-stream", "req-abc")))

            assertEquals(payload, returned.body().readBytes().toString(Charsets.UTF_8))
        }

        @Test
        fun `json error body is passed through`() {
            val payload = """{"type":"error","error":{"type":"invalid_request_error","message":"boom"}}"""
            val returned = execute(wrappedFor(FakeResponse(422, payload, "application/json", "req-abc")))

            assertEquals(payload, returned.body().readBytes().toString(Charsets.UTF_8))
        }
    }

    @Nested
    inner class `success responses` {

        @Test
        fun `2xx is returned untouched without peeking`() {
            val original = FakeResponse(200, "ok", "application/json", "req-abc")
            val returned = execute(wrappedFor(original))

            assertSame(original, returned)
            assertEquals("ok", returned.body().readBytes().toString(Charsets.UTF_8))
        }
    }
}
