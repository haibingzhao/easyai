package com.easy.easyai.autoconfigure.anthropic

import okhttp3.Interceptor
import okhttp3.Response
import org.slf4j.LoggerFactory

/**
 * Logs the raw response body for non-2xx HTTP responses on the Anthropic transport.
 *
 * The Anthropic SDK's error handler only parses `application/json` bodies; when a
 * gateway (e.g. Bailian token-plan) returns errors inside an SSE frame
 * (`content-type: text/event-stream`), the exception degrades to `"400: Unknown"` with
 * `body = JsonMissing` and `cause = null`, hiding the real reason. Peeking here preserves
 * the original stream for downstream handlers.
 */
internal class AnthropicErrorLoggingInterceptor : Interceptor {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful) logError(response)
        return response
    }

    private fun logError(response: Response) {
        val requestId = response.header("x-request-id") ?: response.header("request-id") ?: "-"
        val contentType = response.header("content-type") ?: "-"
        val peeked = try {
            response.peekBody(MAX_PEEK_BYTES).string()
        } catch (e: Exception) {
            "<peek failed: ${e.javaClass.simpleName}: ${e.message}>"
        }
        logger.error(
            "Anthropic HTTP {} {} requestId={} contentType={} body={}",
            response.code,
            response.message,
            requestId,
            contentType,
            peeked
        )
    }

    companion object {
        private const val MAX_PEEK_BYTES = 8_192L
    }
}
