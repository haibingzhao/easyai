package com.easy.easyai.observability.servlet

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingRequestWrapper
import org.springframework.web.util.ContentCachingResponseWrapper

/**
 * Servlet filter that wraps request and response with content-caching wrappers,
 * allowing downstream [HttpRequestObservationFilter] to read bodies for tracing.
 *
 * Runs at [Ordered.HIGHEST_PRECEDENCE] to ensure wrapping happens before
 * Spring's ServerHttpObservationFilter creates its observation context.
 *
 * Only active when `easyai.observability.trace-http-details=true`.
 *
 * @since 2026.0.1
 */
class HttpBodyCachingFilter : OncePerRequestFilter(), Ordered {

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val wrappedRequest = if (request is ContentCachingRequestWrapper) request
        else ContentCachingRequestWrapper(request, 1024 * 1024) // 1MB cache limit

        val wrappedResponse = if (response is ContentCachingResponseWrapper) response
        else ContentCachingResponseWrapper(response)

        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse)
        } finally {
            wrappedResponse.copyBodyToResponse()
        }
    }
}