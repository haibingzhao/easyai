package com.easy.easyai.observability.annotation

/**
 * Marks a method for automatic observability tracking.
 * Creates a span capturing inputs, outputs, duration, and errors.
 * When called within an agent session, the span is enriched with
 * session ID from the current MDC context.
 *
 * Example usage:
 * ```kotlin
 * @Tracked(value = "enrichCustomer", type = TrackType.PROCESSING)
 * fun enrich(input: Customer): Customer {
 *     // ...
 * }
 * ```
 *
 * **Important:** This annotation uses Spring AOP, which is proxy-based.
 * Internal method calls within the same class are NOT intercepted.
 * See the module documentation for workarounds.
 *
 * @since 2026.0.1
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Tracked(
    /**
     * Name for the tracked operation. Defaults to the method name if empty.
     */
    val value: String = "",

    /**
     * Classification of the tracked operation.
     */
    val type: TrackType = TrackType.CUSTOM,

    /**
     * Optional description of what this operation does.
     */
    val description: String = ""
)
