package com.easy.easyai.autoconfigure.anthropic

import io.mockk.every
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Verifies [AnthropicErrorLoggingInterceptor] passes the response through untouched and
 * leaves the body readable after peeking — the peek must not consume the stream that the
 * Anthropic SDK's own handlers read downstream.
 */
class AnthropicErrorLoggingInterceptorTest {

    private val interceptor = AnthropicErrorLoggingInterceptor()

    private fun response(
        code: Int,
        body: String,
        contentType: String
    ): Response = Response.Builder()
        .request(Request.Builder().url("https://example.com/v1/messages").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code in 200..299) "OK" else "Error")
        .header("content-type", contentType)
        .header("x-request-id", "req-abc")
        .body(body.toResponseBody(contentType.toMediaType()))
        .build()

    private fun chainReturning(response: Response): Interceptor.Chain =
        mockk<Interceptor.Chain> {
            every { request() } returns response.request
            every { proceed(any()) } returns response
        }

    @Nested
    inner class `error responses` {

        @Test
        fun `SSE error body is logged and the original stream stays readable`() {
            val payload = "event: error\ndata: {\"code\":\"InvalidParameter\"}"
            val original = response(400, payload, "text/event-stream")

            val returned = interceptor.intercept(chainReturning(original))

            assertSame(original, returned)
            assertEquals(payload, returned.body!!.string())
        }

        @Test
        fun `json error body is passed through`() {
            val payload = """{"type":"error","error":{"type":"invalid_request_error","message":"boom"}}"""
            val original = response(422, payload, "application/json")

            val returned = interceptor.intercept(chainReturning(original))

            assertSame(original, returned)
            assertEquals(payload, returned.body!!.string())
        }
    }

    @Nested
    inner class `success responses` {

        @Test
        fun `2xx is returned untouched without peeking`() {
            val original = response(200, "ok", "application/json")

            val returned = interceptor.intercept(chainReturning(original))

            assertSame(original, returned)
            assertEquals("ok", returned.body!!.string())
        }
    }
}
