package com.easy.easyai.api.llm

/**
 * Base for LLM call failures surfaced by the protocol adapters, replacing
 * Spring AI's `retry.*AiException`. The agent loop's [LlmErrorClassifier] distinguishes
 * transient from non-transient by concrete type, and reads the message for status/provider details,
 * so adapters must lead the message with the HTTP status (e.g. `"400: code=... message=..."`).
 */
sealed class AiException(message: String?, cause: Throwable?) : RuntimeException(message, cause)

/**
 * A retryable failure: transport error, 429, or 5xx. Counted toward the endpoint circuit breaker.
 */
class TransientAiException(message: String?, cause: Throwable? = null) : AiException(message, cause)

/**
 * A deterministic failure that must not be retried: 4xx such as context overflow, invalid key, or
 * content-safety rejection.
 */
class NonTransientAiException(message: String?, cause: Throwable? = null) : AiException(message, cause)
