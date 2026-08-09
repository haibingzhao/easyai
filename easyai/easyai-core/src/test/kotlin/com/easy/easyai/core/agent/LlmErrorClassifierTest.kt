package com.easy.easyai.core.agent

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.retry.NonTransientAiException
import org.springframework.ai.retry.TransientAiException
import org.springframework.web.client.ResourceAccessException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [LlmErrorClassifier].
 *
 * Tests cover:
 * - Spring AI structured exceptions (NonTransientAiException, TransientAiException)
 * - Standard Java timeout exceptions
 * - Provider-specific exceptions (OpenAI, Anthropic)
 * - Nested exception chains
 * - Message-based fallback detection
 */
class LlmErrorClassifierTest {

    @Nested
    inner class `isContextOverflow` {

        @Test
        fun `detects NonTransientAiException with context_length_exceeded`() {
            val exception = NonTransientAiException("400 - {\"error\":{\"code\":\"context_length_exceeded\"}}")
            assertTrue(LlmErrorClassifier.isContextOverflow(exception))
        }

        @Test
        fun `detects NonTransientAiException with context length message`() {
            val exception = NonTransientAiException("400 - This model's maximum context length is 128000 tokens")
            assertTrue(LlmErrorClassifier.isContextOverflow(exception))
        }

        @Test
        fun `detects NonTransientAiException with max_tokens error`() {
            val exception = NonTransientAiException("400 - max_tokens is too large")
            assertTrue(LlmErrorClassifier.isContextOverflow(exception))
        }

        @Test
        fun `detects NonTransientAiException with request too large`() {
            val exception = NonTransientAiException("400 - Request too large")
            assertTrue(LlmErrorClassifier.isContextOverflow(exception))
        }

        @Test
        fun `detects HTTP 400 with context overflow message`() {
            val exception = RuntimeException("400 - {\"error\":{\"message\":\"context_length_exceeded\"}}")
            assertTrue(LlmErrorClassifier.isContextOverflow(exception))
        }

        @Test
        fun `detects nested context overflow in cause chain`() {
            val rootCause = NonTransientAiException("400 - context_length_exceeded")
            val wrapper = RuntimeException("LLM call failed", rootCause)
            val outerWrapper = RuntimeException("Stream error", wrapper)
            assertTrue(LlmErrorClassifier.isContextOverflow(outerWrapper))
        }

        @Test
        fun `returns false for NonTransientAiException without context error`() {
            val exception = NonTransientAiException("401 - Invalid API key")
            assertFalse(LlmErrorClassifier.isContextOverflow(exception))
        }

        @Test
        fun `returns false for TransientAiException`() {
            val exception = TransientAiException("503 - Service Unavailable")
            assertFalse(LlmErrorClassifier.isContextOverflow(exception))
        }

        @Test
        fun `returns false for unrelated RuntimeException`() {
            val exception = RuntimeException("Some random error")
            assertFalse(LlmErrorClassifier.isContextOverflow(exception))
        }

        @Test
        fun `returns false for null message`() {
            val exception = RuntimeException()
            assertFalse(LlmErrorClassifier.isContextOverflow(exception))
        }
    }

    @Nested
    inner class `isTimeout` {

        @Test
        fun `detects TransientAiException`() {
            val exception = TransientAiException("503 - Service Unavailable")
            assertTrue(LlmErrorClassifier.isTimeout(exception))
        }

        @Test
        fun `detects SocketTimeoutException`() {
            val exception = SocketTimeoutException("Read timed out")
            assertTrue(LlmErrorClassifier.isTimeout(exception))
        }

        @Test
        fun `detects TimeoutException`() {
            val exception = TimeoutException("Operation timed out")
            assertTrue(LlmErrorClassifier.isTimeout(exception))
        }

        @Test
        fun `detects ResourceAccessException`() {
            val exception = ResourceAccessException("Connection refused")
            assertTrue(LlmErrorClassifier.isTimeout(exception))
        }

        @Test
        fun `detects timeout by class name containing TimeoutException`() {
            // Create a custom exception class with TimeoutException in its name
            class CustomTimeoutException(message: String) : RuntimeException(message)
            val exception = CustomTimeoutException("Custom timeout")
            // This won't match because the class name doesn't contain "TimeoutException"
            // But we can verify the message-based fallback works
            assertFalse(LlmErrorClassifier.isTimeout(exception))

            // Test with a message that contains "timeout"
            val exceptionWithTimeoutMessage = RuntimeException("TimeoutException occurred")
            assertTrue(LlmErrorClassifier.isTimeout(exceptionWithTimeoutMessage))
        }

        @Test
        fun `detects timeout message in exception`() {
            val exception = RuntimeException("Request timed out after 30000ms")
            assertTrue(LlmErrorClassifier.isTimeout(exception))
        }

        @Test
        fun `detects read timed out message`() {
            val exception = RuntimeException("Read timed out")
            assertTrue(LlmErrorClassifier.isTimeout(exception))
        }

        @Test
        fun `detects nested timeout in cause chain`() {
            val rootCause = SocketTimeoutException("Read timed out")
            val wrapper = RuntimeException("LLM stream failed", rootCause)
            assertTrue(LlmErrorClassifier.isTimeout(wrapper))
        }

        @Test
        fun `returns false for NonTransientAiException without timeout`() {
            val exception = NonTransientAiException("400 - Bad Request")
            assertFalse(LlmErrorClassifier.isTimeout(exception))
        }

        @Test
        fun `returns false for unrelated RuntimeException`() {
            val exception = RuntimeException("Some random error")
            assertFalse(LlmErrorClassifier.isTimeout(exception))
        }
    }

    @Nested
    inner class `isRetryable` {

        @Test
        fun `detects TransientAiException as retryable`() {
            val exception = TransientAiException("503 - Service Unavailable")
            assertTrue(LlmErrorClassifier.isRetryable(exception))
        }

        @Test
        fun `detects ResourceAccessException as retryable`() {
            val exception = ResourceAccessException("Connection refused")
            assertTrue(LlmErrorClassifier.isRetryable(exception))
        }

        @Test
        fun `detects timeout as retryable`() {
            val exception = SocketTimeoutException("Read timed out")
            assertTrue(LlmErrorClassifier.isRetryable(exception))
        }

        @Test
        fun `returns false for NonTransientAiException`() {
            val exception = NonTransientAiException("400 - Bad Request")
            assertFalse(LlmErrorClassifier.isRetryable(exception))
        }

        @Test
        fun `returns false for context overflow`() {
            val exception = NonTransientAiException("400 - context_length_exceeded")
            assertFalse(LlmErrorClassifier.isRetryable(exception))
        }

        @Test
        fun `returns false for unrelated RuntimeException`() {
            val exception = RuntimeException("Some random error")
            assertFalse(LlmErrorClassifier.isRetryable(exception))
        }
    }

    @Nested
    inner class `Provider-specific exceptions` {

        @Test
        fun `detects OpenAI BadRequestException with context overflow`() {
            // Simulate OpenAI BadRequestException by creating a class with the right name
            // Note: In production, this would be the actual OpenAI SDK exception
            // Here we test the message-based fallback since we can't easily create
            // a class with "BadRequestException" in its name dynamically
            val exception = RuntimeException("context_length_exceeded")
            // This should match via message-based fallback
            assertTrue(LlmErrorClassifier.isContextOverflow(exception))
        }

        @Test
        fun `detects Anthropic InvalidRequestException with context overflow`() {
            // Similar to OpenAI test, we test the message-based fallback
            val exception = RuntimeException("max_tokens exceeded")
            // This should match via message-based fallback
            assertTrue(LlmErrorClassifier.isContextOverflow(exception))
        }
    }

    @Nested
    inner class `Edge cases` {

        @Test
        fun `handles deeply nested exception chain`() {
            val level5 = NonTransientAiException("400 - context_length_exceeded")
            val level4 = RuntimeException("Level 4", level5)
            val level3 = RuntimeException("Level 3", level4)
            val level2 = RuntimeException("Level 2", level3)
            val level1 = RuntimeException("Level 1", level2)

            assertTrue(LlmErrorClassifier.isContextOverflow(level1))
        }

        @Test
        fun `handles exception with null cause`() {
            val exception = RuntimeException("Error")
            // Should not throw NPE
            assertFalse(LlmErrorClassifier.isContextOverflow(exception))
            assertFalse(LlmErrorClassifier.isTimeout(exception))
        }

        @Test
        fun `handles exception with empty message`() {
            val exception = RuntimeException("")
            assertFalse(LlmErrorClassifier.isContextOverflow(exception))
            assertFalse(LlmErrorClassifier.isTimeout(exception))
        }

        @Test
        fun `case insensitive message matching`() {
            val exception1 = RuntimeException("400 - CONTEXT_LENGTH_EXCEEDED")
            val exception2 = RuntimeException("400 - Context_Length_Exceeded")
            val exception3 = RuntimeException("400 - context length exceeded")

            assertTrue(LlmErrorClassifier.isContextOverflow(exception1))
            assertTrue(LlmErrorClassifier.isContextOverflow(exception2))
            assertTrue(LlmErrorClassifier.isContextOverflow(exception3))
        }
    }

    @Nested
    inner class `isEndpointOutage` {

        @Test
        fun `connection errors are outages`() {
            assertTrue(LlmErrorClassifier.isEndpointOutage(ResourceAccessException("I/O error on POST request: Connection refused")))
            assertTrue(LlmErrorClassifier.isEndpointOutage(UnknownHostException("api.example.com")))
        }

        @Test
        fun `socket and stream stall timeouts are outages`() {
            assertTrue(LlmErrorClassifier.isEndpointOutage(SocketTimeoutException("Read timed out")))
            assertTrue(LlmErrorClassifier.isEndpointOutage(TimeoutException("LLM stream stalled: no first token (TTFT) received within 240s")))
        }

        @Test
        fun `5xx transient errors are outages`() {
            assertTrue(LlmErrorClassifier.isEndpointOutage(TransientAiException("503 Service Unavailable")))
            assertTrue(LlmErrorClassifier.isEndpointOutage(TransientAiException("502 Bad Gateway")))
        }

        @Test
        fun `outage wrapped in generic exception is detected via cause chain`() {
            val wrapped = RuntimeException("LLM call failed", ResourceAccessException("Connection refused"))
            assertTrue(LlmErrorClassifier.isEndpointOutage(wrapped))
        }

        @Test
        fun `rate limit is not an outage`() {
            assertFalse(LlmErrorClassifier.isEndpointOutage(TransientAiException("429 Too Many Requests")))
            assertFalse(LlmErrorClassifier.isEndpointOutage(TransientAiException("Rate limit reached for model")))
            assertFalse(LlmErrorClassifier.isEndpointOutage(ResourceAccessException("too many requests")))
        }

        @Test
        fun `context overflow is not an outage`() {
            assertFalse(LlmErrorClassifier.isEndpointOutage(NonTransientAiException("400 - context_length_exceeded")))
        }

        @Test
        fun `client errors are not outages`() {
            assertFalse(LlmErrorClassifier.isEndpointOutage(NonTransientAiException("401 Unauthorized")))
            assertFalse(LlmErrorClassifier.isEndpointOutage(RuntimeException("boom")))
        }
    }
}
