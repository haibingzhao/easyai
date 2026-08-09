package com.easy.easyai.core.resilience

import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

/**
 * Tunable parameters of an [LlmCircuitBreaker].
 *
 * @property failureThreshold consecutive outage-class failures that trip the breaker
 * @property initialCooldownMs OPEN duration before the first half-open probe
 * @property maxCooldownMs upper bound for the exponentially backed-off cooldown
 */
internal data class CircuitBreakerSettings(
    val failureThreshold: Int = 3,
    val initialCooldownMs: Long = 30_000L,
    val maxCooldownMs: Long = 600_000L
)

/**
 * Process-local circuit breaker for a single LLM endpoint.
 *
 * State transitions:
 * - CLOSED -> OPEN after [CircuitBreakerSettings.failureThreshold] consecutive
 *   outage-class failures (recorded by the caller, filtered via
 *   [com.easy.easyai.core.agent.LlmErrorClassifier.isEndpointOutage]).
 * - OPEN -> HALF_OPEN after the cooldown elapses; exactly one probe request is
 *   admitted (CAS winner), all concurrent requests fail fast.
 * - HALF_OPEN -> CLOSED on probe success; back to OPEN on probe failure with
 *   doubled cooldown (bounded by [CircuitBreakerSettings.maxCooldownMs]).
 *
 * If a probe never settles the state (request cancelled, or it ended with a
 * non-outage error that is never reported back), the breaker would otherwise
 * wedge in HALF_OPEN forever. As an escape hatch, once the probe has been in
 * flight longer than the current cooldown, the next acquire admits a fresh
 * probe instead of rejecting indefinitely.
 *
 * All state lives in a single immutable [State] behind an [AtomicReference];
 * transitions use CAS loops. The CLOSED hot path is one volatile read.
 *
 * [clock] is injectable for deterministic tests (epoch millis).
 */
internal class LlmCircuitBreaker(
    val key: String,
    private val settings: CircuitBreakerSettings,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    internal enum class Phase { CLOSED, OPEN, HALF_OPEN }

    internal data class State(
        val phase: Phase,
        val consecutiveFailures: Int,
        val openedAtMs: Long,
        val cooldownMs: Long
    )

    private val state = AtomicReference(
        State(Phase.CLOSED, 0, 0L, settings.initialCooldownMs)
    )

    /** Current phase (for logging/tests). */
    fun phase(): Phase = state.get().phase

    /** Consecutive outage-class failures (for tests). */
    fun consecutiveFailures(): Int = state.get().consecutiveFailures

    /**
     * Gate a request. Returns normally when the request may proceed;
     * throws [CircuitBreakerOpenException] when the endpoint is known-bad.
     *
     * When the cooldown of an OPEN breaker has elapsed, the thread that wins
     * the CAS to HALF_OPEN becomes the single probe request; all others are
     * rejected until the probe settles the state.
     */
    fun acquirePermission() {
        while (true) {
            val current = state.get()
            when (current.phase) {
                Phase.CLOSED -> return
                Phase.HALF_OPEN -> {
                    val probeElapsed = clock() - current.openedAtMs
                    if (probeElapsed < current.cooldownMs) {
                        // Probe in flight; reject fast (retryAfter=0: re-check shortly).
                        throw CircuitBreakerOpenException(key, 0L)
                    }
                    // Probe never reported back (cancelled, or ended with a
                    // non-outage error): restart the probe timer and admit a
                    // new one instead of staying wedged in HALF_OPEN forever.
                    if (state.compareAndSet(current, current.copy(openedAtMs = clock()))) {
                        logger.info("Circuit breaker HALF_OPEN probe expired for endpoint {}, admitting new probe", key)
                        return
                    }
                    // CAS lost: re-evaluate with the new state.
                }
                Phase.OPEN -> {
                    val elapsed = clock() - current.openedAtMs
                    if (elapsed < current.cooldownMs) {
                        val retryAfterSeconds = (current.cooldownMs - elapsed + 999) / 1000
                        throw CircuitBreakerOpenException(key, retryAfterSeconds)
                    }
                    // Reset openedAtMs to the probe start time so the HALF_OPEN
                    // escape hatch above can detect an abandoned probe.
                    if (state.compareAndSet(current, current.copy(phase = Phase.HALF_OPEN, openedAtMs = clock()))) {
                        logger.info("Circuit breaker OPEN->HALF_OPEN for endpoint {}, allowing probe request", key)
                        return
                    }
                    // CAS lost: re-evaluate with the new state.
                }
            }
        }
    }

    /** Record a successful call: resets the breaker to CLOSED. */
    fun recordSuccess() {
        while (true) {
            val current = state.get()
            if (current.phase == Phase.CLOSED && current.consecutiveFailures == 0) {
                return
            }
            val next = State(Phase.CLOSED, 0, 0L, settings.initialCooldownMs)
            if (state.compareAndSet(current, next)) {
                if (current.phase != Phase.CLOSED) {
                    logger.info("Circuit breaker {}->CLOSED for endpoint {} (probe succeeded)", current.phase, key)
                }
                return
            }
        }
    }

    /**
     * Record an outage-class failure (caller must filter via
     * [com.easy.easyai.core.agent.LlmErrorClassifier.isEndpointOutage]).
     */
    fun recordFailure() {
        while (true) {
            val current = state.get()
            val next = when (current.phase) {
                Phase.HALF_OPEN -> {
                    val doubled = (current.cooldownMs * 2).coerceAtMost(settings.maxCooldownMs)
                    State(Phase.OPEN, current.consecutiveFailures, clock(), doubled)
                }
                Phase.OPEN -> current // already open (concurrent failures), no-op
                Phase.CLOSED -> {
                    val failures = current.consecutiveFailures + 1
                    if (failures >= settings.failureThreshold) {
                        State(Phase.OPEN, failures, clock(), settings.initialCooldownMs)
                    } else {
                        State(Phase.CLOSED, failures, 0L, current.cooldownMs)
                    }
                }
            }
            if (state.compareAndSet(current, next)) {
                if (next.phase == Phase.OPEN && current.phase == Phase.CLOSED) {
                    logger.warn("Circuit breaker OPEN for endpoint {} after {} consecutive failures (retryAfter={}s)",
                        key, next.consecutiveFailures, next.cooldownMs / 1000)
                } else if (next.phase == Phase.OPEN && current.phase == Phase.HALF_OPEN) {
                    logger.warn("Circuit breaker HALF_OPEN->OPEN for endpoint {} (probe failed, retryAfter={}s)",
                        key, next.cooldownMs / 1000)
                }
                return
            }
        }
    }
}
