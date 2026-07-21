package com.easy.easyai.core.agent

import org.springframework.ai.retry.NonTransientAiException
import org.springframework.ai.retry.TransientAiException
import org.springframework.web.client.ResourceAccessException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

/**
 * Classifies LLM errors for appropriate handling (retry, compact context, etc.)
 *
 * This utility leverages Spring AI's structured exception hierarchy:
 * - [NonTransientAiException]: 4xx client errors (context overflow, invalid API key, rate limit)
 * - [TransientAiException]: 5xx server errors (overload, unavailable)
 * - [ResourceAccessException]: Network errors (connection timeout, read timeout)
 *
 * Using exception types instead of keyword-based string matching provides:
 * - Type safety
 * - Robustness across different LLM providers
 * - Maintainability
 * - Accuracy
 */
internal object LlmErrorClassifier {

    /**
     * Check if the error indicates context length exceeded.
     * This is a non-transient error that requires context compaction.
     *
     * Detection strategy (in order of priority):
     * 1. Check for [NonTransientAiException] with context-related messages
     * 2. Check for HTTP 400 status code with context-related messages
     * 3. Check for provider-specific exceptions (OpenAI, Anthropic, etc.)
     * 4. Fall back to message-based detection
     */
    fun isContextOverflow(e: Throwable): Boolean {
        var current: Throwable? = e
        while (current != null) {
            // Strategy 1: Spring AI structured exception (4xx errors)
            if (current is NonTransientAiException) {
                val message = current.message?.lowercase() ?: ""
                if (isContextOverflowMessage(message)) {
                    return true
                }
            }

            // Strategy 2: HTTP 400 status code in message
            // Spring AI formats errors as "400 - {error_json}"
            val message = current.message?.lowercase() ?: ""
            if ((message.startsWith("400") || message.contains("400 -")) &&
                isContextOverflowMessage(message)
            ) {
                return true
            }

            // Strategy 3: Provider-specific exceptions
            if (isProviderContextOverflowException(current)) {
                return true
            }

            current = current.cause
        }

        // Strategy 4: Message-based fallback (check outermost exception only)
        val outerMessage = e.message?.lowercase() ?: ""
        if (isContextOverflowMessage(outerMessage)) {
            return true
        }

        return false
    }

    /**
     * Check if the error is a timeout that can be retried.
     *
     * Detection strategy (in order of priority):
     * 1. Check for [TransientAiException] (Spring AI retryable errors)
     * 2. Check for standard Java timeout exceptions
     * 3. Check for Reactor/Netty timeout exceptions
     * 4. Fall back to message-based detection
     */
    fun isTimeout(e: Throwable): Boolean {
        var current: Throwable? = e
        while (current != null) {
            // Strategy 1: Spring AI transient exception (retryable)
            if (current is TransientAiException) {
                return true
            }

            // Strategy 2: Standard Java timeout exceptions
            if (current is SocketTimeoutException ||
                current is TimeoutException ||
                current is ResourceAccessException
            ) {
                return true
            }

            // Strategy 3: Message-based fallback
            val message = current.message?.lowercase() ?: ""
            if (message.contains("timed out") ||
                message.contains("timeoutexception") ||
                message.contains("read timed out")
            ) {
                return true
            }

            current = current.cause
        }
        return false
    }

    /**
     * Check if the error is retryable (transient).
     *
     * Retryable errors include:
     * - [TransientAiException] (5xx server errors)
     * - [ResourceAccessException] (network errors)
     * - Timeout exceptions
     */
    fun isRetryable(e: Throwable): Boolean {
        var current: Throwable? = e
        while (current != null) {
            if (current is TransientAiException) {
                return true
            }
            if (current is ResourceAccessException) {
                return true
            }
            if (isTimeout(current)) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /**
     * Check if the message contains context overflow indicators.
     */
    private fun isContextOverflowMessage(message: String): Boolean {
        return message.contains("context_length_exceeded") ||
            message.contains("context length") ||
            message.contains("max_tokens") ||
            message.contains("maximum context") ||
            message.contains("request too large") ||
            message.contains("token limit") ||
            message.contains("too many tokens")
    }

    /**
     * Check if the exception is a provider-specific context overflow exception.
     *
     * This handles cases where the LLM SDK throws its own exception types
     * before Spring AI can wrap them.
     */
    private fun isProviderContextOverflowException(e: Throwable): Boolean {
        val className = e.javaClass.simpleName
        val fullClassName = e.javaClass.name
        val message = e.message?.lowercase() ?: ""

        // OpenAI SDK: BadRequestException for 400 errors
        val isOpenAIBadRequest = className == "BadRequestException" ||
            fullClassName.contains("openai") && fullClassName.contains("BadRequest")

        // Anthropic SDK: InvalidRequestException for 400 errors
        val isAnthropicInvalidRequest = className == "InvalidRequestException" ||
            fullClassName.contains("anthropic") && fullClassName.contains("InvalidRequest")

        if ((isOpenAIBadRequest || isAnthropicInvalidRequest) &&
            isContextOverflowMessage(message)
        ) {
            return true
        }

        return false
    }
}
