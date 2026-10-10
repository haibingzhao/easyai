package com.easy.easyai.autoconfigure.anthropic

import com.anthropic.core.RequestOptions
import com.anthropic.core.http.HttpClient
import com.anthropic.core.http.HttpRequest
import com.anthropic.core.http.HttpResponse
import com.anthropic.core.http.Interceptor
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CompletableFuture

/**
 * Logs the raw response body for non-2xx HTTP responses on the Anthropic transport.
 *
 * The Anthropic SDK's error handler only parses `application/json` bodies; when a
 * gateway (e.g. Bailian token-plan) returns errors inside an SSE frame
 * (`content-type: text/event-stream`), the exception degrades to `"400: Unknown"` with
 * `body = JsonMissing` and `cause = null`, hiding the real reason. Buffering the error body
 * here logs it and replays it to the downstream handler; successful (streaming) bodies are
 * passed through untouched so the SSE stream is never consumed prematurely.
 */
internal class AnthropicErrorLoggingInterceptor : Interceptor {

    override fun intercept(delegate: HttpClient): HttpClient = LoggingHttpClient(delegate)

    private class LoggingHttpClient(
        private val delegate: HttpClient
    ) : HttpClient {
        override fun execute(request: HttpRequest, options: RequestOptions): HttpResponse =
            logIfError(delegate.execute(request, options))

        override fun executeAsync(request: HttpRequest, options: RequestOptions): CompletableFuture<HttpResponse> =
            delegate.executeAsync(request, options).thenApply { logIfError(it) }

        override fun close() = delegate.close()

        private fun logIfError(response: HttpResponse): HttpResponse {
            val status = response.statusCode()
            if (status !in 400..599) return response
            val bytes = try {
                // Bounded buffer: error bodies are small JSON; the cap prevents a hostile or
                // misbehaving gateway from making this proxy hold an unbounded byte[].
                response.body().use { it.readNBytes(MAX_BUFFER_BYTES) }
            } catch (e: Exception) {
                logger.warn("Anthropic HTTP {} body read failed: {}", status, e.message)
                return response
            }
            val requestId = response.headers().values("x-request-id").firstOrNull()
                ?: response.headers().values("request-id").firstOrNull() ?: "-"
            val contentType = response.headers().values("content-type").firstOrNull() ?: "-"
            val peeked = String(bytes, Charsets.UTF_8).take(MAX_PEEK_BYTES)
            logger.error(
                "Anthropic HTTP {} requestId={} contentType={} body={}",
                status,
                requestId,
                contentType,
                peeked
            )
            return BufferedResponse(response, bytes)
        }
    }

    /** A non-streaming replay of an error response whose body was already buffered for logging. */
    private class BufferedResponse(
        private val delegate: HttpResponse,
        private val body: ByteArray
    ) : HttpResponse {
        override fun statusCode(): Int = delegate.statusCode()
        override fun headers() = delegate.headers()
        override fun body(): InputStream = ByteArrayInputStream(body)
        override fun close() {}
    }

    companion object {
        private const val MAX_PEEK_BYTES = 8_192
        private const val MAX_BUFFER_BYTES = 1_048_576
        private val logger = LoggerFactory.getLogger(AnthropicErrorLoggingInterceptor::class.java)
    }
}
