package com.easy.easyai.core.resilience

/**
 * Thrown when a request is rejected because the circuit breaker for the
 * target LLM endpoint is OPEN.
 *
 * Never escapes to a caller: the only production call site of
 * [com.easy.easyai.core.resilience.LlmCircuitBreaker.acquirePermission] is the agent loop, which
 * catches this and rethrows
 * [com.easy.easyai.core.agent.LlmEndpointUnavailableException] — a readable, user-facing message
 * that [com.easy.easyai.core.util.isRetryableError] matches by type, so the frontend still renders
 * the retry affordance on the existing ErrorEvent -> SSE channel. Retriability therefore does not
 * depend on this text at all; only [retryAfterSeconds] is worth surfacing, and the wrapper copies it
 * in.
 *
 * @property key circuit breaker key (protocol:baseUrl) identifying the endpoint
 * @property retryAfterSeconds approximate seconds until the breaker may half-open (0 while a probe is
 *   already in flight, meaning "re-check shortly")
 */
internal class CircuitBreakerOpenException(
    val key: String,
    val retryAfterSeconds: Long
) : RuntimeException(
    "LLM endpoint unavailable, circuit breaker open (endpoint=$key, retry after ${retryAfterSeconds}s)"
)
