package com.easy.easyai.core.util

import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/**
 * Determine if an error is potentially retryable.
 * Network errors, rate limits, and server errors (5xx) are retryable.
 * Client errors (4xx) like auth errors are not retryable.
 */
fun isRetryableError(e: Exception): Boolean {
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