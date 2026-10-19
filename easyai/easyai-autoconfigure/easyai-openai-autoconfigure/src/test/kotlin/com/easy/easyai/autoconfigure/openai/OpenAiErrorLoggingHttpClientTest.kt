package com.easy.easyai.autoconfigure.openai

import com.openai.core.RequestOptions
import com.openai.core.http.HttpClient
import com.openai.core.http.Headers
import com.openai.core.http.HttpRequest
import com.openai.core.http.HttpResponse
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Verifies [OpenAiErrorLoggingHttpClient] on the SDK transport: an error (non-2xx) response has its raw
 * body logged and replayed so the SDK's downstream error parsing still reads it, while a successful
 * (streaming) response is returned untouched so the SSE stream is never consumed early.
 */
class OpenAiErrorLoggingHttpClientTest {

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
        return OpenAiErrorLoggingHttpClient(delegate)
    }

    private fun execute(client: HttpClient): HttpResponse =
        client.execute(mockk<HttpRequest>(relaxed = true), mockk<RequestOptions>(relaxed = true))

    @Nested
    inner class `error responses` {

        @Test
        fun `an sse-framed error is replayed as the json the sdk can read`() {
            // Bailian answers a refused response_format inside an SSE frame; the SDK reads the whole
            // body as one JSON document, fails, and reports "400: Unknown". Unwrapping the frame is
            // what lets the exception carry the sentence, which is what the loop then acts on.
            val envelope = """{"error":{"code":"invalid_parameter_error",""" +
                """"param":null,"message":"This response_format type is unavailable now",""" +
                """"type":"invalid_request_error"},"id":"chatcmpl-a054ba66"}"""
            val frame = "data: $envelope\n\n"
            val returned = execute(wrappedFor(FakeResponse(400, frame, "text/event-stream; charset=utf-8", "req-abc")))

            assertEquals(envelope, returned.body().readBytes().toString(Charsets.UTF_8))
        }

        @Test
        fun `a frame with no error node is passed through as it arrived`() {
            val frame = "data: [DONE]\n\n"
            val returned = execute(wrappedFor(FakeResponse(400, frame, "text/event-stream", "req-abc")))

            assertEquals(frame, returned.body().readBytes().toString(Charsets.UTF_8))
        }

        @Test
        fun `json error body is passed through`() {
            // The shape the SDK parses natively must not be rewritten.
            val payload = """{"error":{"message":"boom","type":"invalid_request_error"}}"""
            val returned = execute(wrappedFor(FakeResponse(400, payload, "application/json", "req-abc")))

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
