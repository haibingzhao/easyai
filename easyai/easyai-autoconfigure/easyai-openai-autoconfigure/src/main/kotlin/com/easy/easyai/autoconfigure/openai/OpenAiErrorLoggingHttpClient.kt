package com.easy.easyai.autoconfigure.openai

import com.openai.core.RequestOptions
import com.openai.core.http.HttpClient
import com.openai.core.http.HttpRequest
import com.openai.core.http.HttpResponse
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CompletableFuture

/**
 * Logs the raw response body for non-2xx HTTP responses on the OpenAI transport.
 *
 * The SDK only recognizes an error body of the shape `{"error": {...}}`; a gateway whose error arrives
 * with another shape (Bailian compatible-mode wraps some rejections in an SSE frame) leaves the SDK
 * with nothing to parse, and the exception degrades to `"400: Unknown"` with `body = JsonMissing`.
 * That is not a diagnosable message: an unsupported `response_format`, a rejected parameter
 * combination and a spent quota all collapse into it. Buffering the error body here logs it and
 * replays it to the downstream handler; successful (streaming) bodies are passed through untouched so
 * the SSE stream is never consumed prematurely.
 */
internal class OpenAiErrorLoggingHttpClient(
    private val delegate: HttpClient
) : HttpClient {

    override fun execute(request: HttpRequest, requestOptions: RequestOptions): HttpResponse =
        logIfError(request, delegate.execute(request, requestOptions))

    override fun executeAsync(
        request: HttpRequest,
        requestOptions: RequestOptions
    ): CompletableFuture<HttpResponse> =
        delegate.executeAsync(request, requestOptions).thenApply { logIfError(request, it) }

    override fun close() = delegate.close()

    private fun logIfError(request: HttpRequest, response: HttpResponse): HttpResponse {
        val status = response.statusCode()
        if (status !in 400..599) return response
        val bytes = try {
            // Bounded buffer: error bodies are small JSON; the cap prevents a hostile or
            // misbehaving gateway from making this proxy hold an unbounded byte[].
            response.body().use { it.readNBytes(MAX_BUFFER_BYTES) }
        } catch (e: Exception) {
            logger.warn("OpenAI HTTP {} body read failed: {}", status, e.message)
            return response
        }
        val contentType = header(response, "content-type") ?: "-"
        val requestId = header(response, "x-request-id")
            ?: header(response, "x-acs-request-id")
            ?: header(response, "request-id")
            ?: "-"
        val peeked = String(bytes, Charsets.UTF_8).take(MAX_PEEK_BYTES)
        logger.error(
            "OpenAI HTTP {} requestId={} contentType={} path={} body={}",
            status,
            requestId,
            contentType,
            request.pathSegments.joinToString("/"),
            peeked
        )
        return BufferedResponse(response, unwrapSseFrame(bytes, contentType))
    }

    /**
     * Hands an SSE-framed error back to the SDK as the bare JSON object it knows how to parse.
     *
     * Gateways that answer a streaming request with `data: {"error": {...}}` (Bailian does this for
     * `response_format` rejections) defeat the SDK's error handler: it reads the whole body as one JSON
     * document, gets nothing, and the exception the agent loop actually receives is `"400: Unknown"` —
     * the reason survives only in the log line above, so nothing downstream can act on it. The frame
     * payload is passed through untouched when it is not an error object; a body the SDK then still
     * cannot read is exactly the case logged above, no worse than before.
     */
    private fun unwrapSseFrame(bytes: ByteArray, contentType: String?): ByteArray {
        if (contentType?.startsWith(EVENT_STREAM_TYPE) != true) return bytes
        val payload = String(bytes, Charsets.UTF_8).lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .firstOrNull { it.startsWith("{") && it.contains("\"error\"") }
            ?: return bytes
        return payload.toByteArray(Charsets.UTF_8)
    }

    private fun header(response: HttpResponse, name: String): String? =
        response.headers().names()
            .firstOrNull { it.equals(name, ignoreCase = true) }
            ?.let { response.headers().values(it).firstOrNull() }

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
        private const val EVENT_STREAM_TYPE = "text/event-stream"
        private val logger = LoggerFactory.getLogger(OpenAiErrorLoggingHttpClient::class.java)
    }
}
