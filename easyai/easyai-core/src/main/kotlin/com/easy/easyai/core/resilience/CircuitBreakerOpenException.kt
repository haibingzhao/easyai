package com.easy.easyai.core.resilience

/**
 * Thrown when a request is rejected because the circuit breaker for the
 * target LLM endpoint is OPEN.
 *
 * The message intentionally contains "service unavailable" so that
 * [com.easy.easyai.core.util.isRetryableError] classifies it as retryable,
 * letting the frontend render the retry affordance on the existing
 * ErrorEvent -> SSE channel (no new event type required).
 *
 * This exception is NOT a timeout, so the AgentLoopRunner retry loop does
 * not retry it — the request fails fast and propagates through the usual
 * AgentRunner error path.
 *
 * @property key circuit breaker key (protocol:baseUrl) identifying the endpoint
 * @property retryAfterSeconds approximate seconds until the breaker may half-open
 */
internal class CircuitBreakerOpenException(
    val key: String,
    val retryAfterSeconds: Long
) : RuntimeException(
    "LLM endpoint unavailable, circuit breaker open (endpoint=$key, " +
        "retry after ${retryAfterSeconds}s) - service unavailable"
)
