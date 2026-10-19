package com.easy.easyai.core.util

import com.easy.easyai.core.agent.ContentFilteredException
import com.easy.easyai.core.agent.LlmEndpointUnavailableException
import com.easy.easyai.core.agent.QuotaExhaustedException
import com.easy.easyai.core.agent.ResponseFormatUnsupportedException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/**
 * Determine if an error is potentially retryable.
 * Network errors, rate limits, and server errors (5xx) are retryable.
 * Client errors (4xx) like auth errors are not retryable.
 */
fun isRetryableError(e: Exception): Boolean {
    // Content-safety rejection is deterministic: replaying the same prompt re-triggers
    // the same filter, so it must never be presented as retryable.
    if (e is ContentFilteredException) {
        return false
    }
    // A spent quota is deterministic until it resets. Its user-facing message is Chinese, so it
    // matches none of the text checks below and the "retryable unless clearly a client error"
    // fallback would offer a retry that cannot succeed.
    if (e is QuotaExhaustedException) {
        return false
    }
    // A refused response format is a capability the model does not answer to. Its message is Chinese
    // and names no status code, so the fallback below would read it as retryable and offer a retry
    // that re-triggers the same 400 until the model row is edited.
    if (e is ResponseFormatUnsupportedException) {
        return false
    }
    // An open circuit means the endpoint kept failing, not that this request is doomed:
    // once the breaker half-opens a retry can succeed, so keep the retry affordance.
    // Matched by type because the user-facing wrapper carries neither "service unavailable"
    // nor any other English marker the text checks below would recognize.
    if (e is LlmEndpointUnavailableException) {
        return true
    }
    // Check exception type first (handles cases where message is null)
    // SocketException covers ConnectException and SocketTimeoutException as subclasses
    if (e is SocketException || e is UnknownHostException || e is TimeoutException) {
        return true
    }

    val message = e.message?.lowercase() ?: return false
    // Network errors are retryable
    if (message.contains("connection") || message.contains("network") ||
        message.contains("socket") || message.contains("io exception")) {
        return true
    }
    // Rate limit errors are retryable
    if (message.contains("rate limit") || message.contains("429") ||
        message.contains("too many requests")) {
        return true
    }
    // Server errors (5xx) are retryable
    if (message.contains("500") || message.contains("502") ||
        message.contains("503") || message.contains("504") ||
        message.contains("internal server error") ||
        message.contains("bad gateway") ||
        message.contains("service unavailable")) {
        return true
    }
    // Auth errors (401/403) are NOT retryable
    if (message.contains("401") || message.contains("403") ||
        message.contains("unauthorized") || message.contains("forbidden") ||
        message.contains("api key") || message.contains("authentication")) {
        return false
    }
    // Default: treat as retryable if it's not a clear client error
    return !message.contains("400") && !message.contains("bad request")
}