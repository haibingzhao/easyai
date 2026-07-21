package com.easy.easyai.observability.servlet

import io.micrometer.common.KeyValue
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationFilter
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.server.observation.ServerRequestObservationContext
import org.springframework.web.util.ContentCachingRequestWrapper
import org.springframework.web.util.ContentCachingResponseWrapper
import java.nio.charset.StandardCharsets

/**
 * Observation filter that enriches HTTP server observations with request/response details.
 *
 * Extracts headers, query parameters, request body, and response body from
 * [ServerRequestObservationContext] and adds them as high-cardinality key values.
 * Sensitive headers (e.g. Authorization) are masked.
 *
 * Body extraction requires [HttpBodyCachingFilter] to be active so that
 * [ContentCachingRequestWrapper] and [ContentCachingResponseWrapper] are available.
 *
 * Only active when `easyai.observability.trace-http-details=true`.
 *
 * @since 2026.0.1
 */
class HttpRequestObservationFilter(private val maxAttributeLength: Int) : ObservationFilter {

    private val log = LoggerFactory.getLogger(HttpRequestObservationFilter::class.java)

    companion object {
        private val TRACKED_HEADERS = setOf(
            "content-type", "accept", "authorization", "user-agent"
        )
        private val MASKED_HEADERS = setOf("authorization")
    }

    override fun map(context: Observation.Context): Observation.Context {
        if (context !is ServerRequestObservationContext) {
            return context
        }
        try {
            addHeaders(context)
            addParams(context)
            addRequestBody(context)
            addResponseBody(context)
        } catch (e: Exception) {
            log.debug("Failed to extract HTTP details from observation context", e)
        }
        return context
    }

    private fun addHeaders(context: ServerRequestObservationContext) {
        val request = context.carrier as HttpServletRequest
        val joiner = StringBuilder()
        val names = request.headerNames
        while (names.hasMoreElements()) {
            val name = names.nextElement()
            if (name.lowercase() !in TRACKED_HEADERS) {
                continue
            }
            val value = if (name.lowercase() in MASKED_HEADERS) "***"
            else request.getHeader(name)
            if (joiner.isNotEmpty()) joiner.append("; ")
            joiner.append("$name: $value")
        }
        if (joiner.isNotEmpty()) {
            context.addHighCardinalityKeyValue(KeyValue.of("http.request.headers", joiner.toString()))
        }
    }

    private fun addParams(context: ServerRequestObservationContext) {
        val request = context.carrier as HttpServletRequest
        val paramMap = request.parameterMap
        if (paramMap.isEmpty()) return
        val joiner = StringBuilder()
        paramMap.entries.forEach { entry ->
            for (value in entry.value) {
                if (joiner.isNotEmpty()) joiner.append("&")
                joiner.append("${entry.key}=$value")
            }
        }
        context.addHighCardinalityKeyValue(KeyValue.of("http.request.params", joiner.toString()))
    }

    private fun addRequestBody(context: ServerRequestObservationContext) {
        val request = context.carrier as HttpServletRequest
        if (request !is ContentCachingRequestWrapper) return
        val body = String(request.contentAsByteArray, StandardCharsets.UTF_8)
        if (body.isNotEmpty()) {
            context.addHighCardinalityKeyValue(KeyValue.of("http.request.body", truncate(body)))
        }
    }

    private fun addResponseBody(context: ServerRequestObservationContext) {
        val response = context.response
        if (response !is ContentCachingResponseWrapper) return
        val body = String(response.contentAsByteArray, StandardCharsets.UTF_8)
        if (body.isNotEmpty()) {
            context.addHighCardinalityKeyValue(KeyValue.of("http.response.body", truncate(body)))
        }
    }

    private fun truncate(value: String): String =
        if (value.length > maxAttributeLength) "${value.substring(0, maxAttributeLength)}..."
        else value
}