package com.easy.easyai.core.resilience

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for [LlmCircuitBreaker] state machine.
 *
 * Uses an injectable fake clock so all transitions are deterministic
 * (no sleeps, no virtual time).
 */
class LlmCircuitBreakerTest {

    private var now = 1_000L
    private val settings = CircuitBreakerSettings(
        failureThreshold = 3,
        initialCooldownMs = 30_000L,
        maxCooldownMs = 60_000L
    )

    private fun breaker() = LlmCircuitBreaker("OPENAI:https://api.example.com", settings) { now }

    @Nested
    inner class `closed state` {

        @Test
        fun `acquire passes through while closed`() {
            val cb = breaker()
            cb.acquirePermission()
            assertEquals(LlmCircuitBreaker.Phase.CLOSED, cb.phase())
        }

        @Test
        fun `failures below threshold keep breaker closed`() {
            val cb = breaker()
            cb.recordFailure()
            cb.recordFailure()
            assertEquals(LlmCircuitBreaker.Phase.CLOSED, cb.phase())
            assertEquals(2, cb.consecutiveFailures())
            cb.acquirePermission()
        }

        @Test
        fun `success resets failure counter`() {
            val cb = breaker()
            cb.recordFailure()
            cb.recordFailure()
            cb.recordSuccess()
            assertEquals(0, cb.consecutiveFailures())
            cb.recordFailure()
            cb.recordFailure()
            assertEquals(LlmCircuitBreaker.Phase.CLOSED, cb.phase())
        }
    }

    @Nested
    inner class `trip to open` {

        @Test
        fun `consecutive failures reaching threshold open the breaker`() {
            val cb = breaker()
            repeat(3) { cb.recordFailure() }
            assertEquals(LlmCircuitBreaker.Phase.OPEN, cb.phase())
        }

        @Test
        fun `open breaker rejects with retryAfter during cooldown`() {
            val cb = breaker()
            repeat(3) { cb.recordFailure() }
            now += 10_000 // 10s into the 30s cooldown
            val e = assertFailsWith<CircuitBreakerOpenException> { cb.acquirePermission() }
            assertEquals(20L, e.retryAfterSeconds)
            assertTrue(e.message!!.contains("service unavailable"))
        }

        @Test
        fun `success after partial failures prevents tripping`() {
            val cb = breaker()
            cb.recordFailure()
            cb.recordFailure()
            cb.recordSuccess()
            cb.recordFailure()
            cb.recordFailure()
            assertEquals(LlmCircuitBreaker.Phase.CLOSED, cb.phase())
        }
    }

    @Nested
    inner class `half-open probe` {

        private fun trippedBreaker(): LlmCircuitBreaker {
            val cb = breaker()
            repeat(3) { cb.recordFailure() }
            now += 30_000 // cooldown elapsed
            return cb
        }

        @Test
        fun `cooldown expiry admits exactly one probe`() {
            val cb = trippedBreaker()
            cb.acquirePermission() // wins the probe
            assertEquals(LlmCircuitBreaker.Phase.HALF_OPEN, cb.phase())
            assertFailsWith<CircuitBreakerOpenException> { cb.acquirePermission() }
        }

        @Test
        fun `probe success closes the breaker and resets cooldown`() {
            val cb = trippedBreaker()
            cb.acquirePermission()
            cb.recordSuccess()
            assertEquals(LlmCircuitBreaker.Phase.CLOSED, cb.phase())
            assertEquals(0, cb.consecutiveFailures())
            cb.acquirePermission()
        }

        @Test
        fun `probe failure reopens with doubled cooldown`() {
            val cb = trippedBreaker()
            cb.acquirePermission()
            cb.recordFailure()
            assertEquals(LlmCircuitBreaker.Phase.OPEN, cb.phase())
            // Cooldown doubled to 60s: 30s in, still rejected
            now += 30_000
            assertFailsWith<CircuitBreakerOpenException> { cb.acquirePermission() }
            // After the full doubled cooldown, a new probe is admitted
            now += 30_000
            cb.acquirePermission()
            assertEquals(LlmCircuitBreaker.Phase.HALF_OPEN, cb.phase())
        }

        @Test
        fun `cooldown doubling is capped at maxCooldown`() {
            val cb = trippedBreaker()
            // Probe fail #1: 30s -> 60s (max)
            cb.acquirePermission()
            cb.recordFailure()
            now += 60_000
            // Probe fail #2: stays at 60s (capped)
            cb.acquirePermission()
            cb.recordFailure()
            now += 59_999
            assertFailsWith<CircuitBreakerOpenException> { cb.acquirePermission() }
            now += 1
            cb.acquirePermission()
            assertEquals(LlmCircuitBreaker.Phase.HALF_OPEN, cb.phase())
        }

        @Test
        fun `abandoned probe does not wedge the breaker`() {
            val cb = trippedBreaker()
            cb.acquirePermission() // wins the probe, HALF_OPEN
            assertEquals(LlmCircuitBreaker.Phase.HALF_OPEN, cb.phase())
            // Probe never reports back (cancelled, or ended with a non-outage
            // error). While the probe window is open, others still fail fast.
            assertFailsWith<CircuitBreakerOpenException> { cb.acquirePermission() }
            // Once the probe window (= current cooldown) elapses, a fresh
            // probe is admitted instead of rejecting forever.
            now += 30_000
            cb.acquirePermission()
            assertEquals(LlmCircuitBreaker.Phase.HALF_OPEN, cb.phase())
            // The fresh probe can still settle the state normally.
            cb.recordSuccess()
            assertEquals(LlmCircuitBreaker.Phase.CLOSED, cb.phase())
        }

        @Test
        fun `concurrent acquires admit only one probe`() {
            val cb = trippedBreaker()
            val threads = 16
            val start = CountDownLatch(1)
            val admitted = AtomicInteger(0)
            val workers = (1..threads).map {
                Thread {
                    start.await()
                    try {
                        cb.acquirePermission()
                        admitted.incrementAndGet()
                    } catch (_: CircuitBreakerOpenException) {
                        // rejected — expected for all but the probe winner
                    }
                }.also { it.start() }
            }
            start.countDown()
            workers.forEach { it.join(5_000) }
            assertEquals(1, admitted.get())
            assertEquals(LlmCircuitBreaker.Phase.HALF_OPEN, cb.phase())
        }
    }
}
