package com.easy.easyai.web.service

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The read-aloud single-flight guard: a replacement is not a second synthesis, a late release must
 * not free someone else's slot, and a leaked claim must not disable the feature for good.
 */
class SpeechSingleFlightTest {

    private fun guard(now: () -> Long = { 0L }) = SpeechSingleFlight(clock = now)

    @Nested
    inner class Claims {

        @Test
        fun `a genuine second request is refused once the grace period is spent`() = runTest {
            val slots = guard()
            val first = slots.acquire("alice")
            assertNotNull(first)

            assertNull(slots.acquire("alice"))
            assertTrue(slots.isHeld("alice"))
        }

        @Test
        fun `a replacement waits for the release of the request it supersedes`() = runTest {
            val slots = guard()
            val first = slots.acquire("alice")
            assertNotNull(first)

            // The client has already aborted its previous read-aloud; only the release is in flight.
            val second = async { slots.acquire("alice") }
            runCurrent()
            assertFalse(second.isCompleted, "the replacement must wait, not fail on arrival")

            slots.release(first)
            assertNotNull(second.await())
        }

        @Test
        fun `another owner is not affected by a busy one`() = runTest {
            val slots = guard()
            assertNotNull(slots.acquire("alice"))
            assertNotNull(slots.acquire("bob"))
        }
    }

    @Nested
    inner class Releases {

        @Test
        fun `a late release cannot free the slot of the request that replaced it`() = runTest {
            val slots = guard()
            val first = slots.acquire("alice")
            assertNotNull(first)
            slots.release(first)

            val second = slots.acquire("alice")
            assertNotNull(second)
            // The superseded request finishing its own `finally` must not hand read-aloud back out.
            slots.release(first)

            assertTrue(slots.isHeld("alice"))
            slots.release(second)
            assertFalse(slots.isHeld("alice"))
        }

        @Test
        fun `releasing twice is harmless`() = runTest {
            val slots = guard()
            val lease = slots.acquire("alice")
            slots.release(lease)
            slots.release(lease)
            assertFalse(slots.isHeld("alice"))
        }
    }

    @Nested
    inner class IdleReclaim {

        @Test
        fun `a slot nobody is working on is taken over`() = runTest {
            var now = 0L
            val slots = guard { now }
            val leaked = slots.acquire("alice")
            assertNotNull(leaked)

            now = SpeechSingleFlight.IDLE_TIMEOUT_MS + 1
            val reclaim = slots.acquire("alice")
            assertNotNull(reclaim)
            // The leaked claim may still reach its own `finally`; that must not free the new holder.
            slots.release(leaked)
            assertTrue(slots.isHeld("alice"))
        }

        @Test
        fun `a long plan that keeps reporting segments is never reclaimed`() = runTest {
            var now = 0L
            val slots = guard { now }
            val lease = slots.acquire("alice")
            assertNotNull(lease)

            repeat(3) {
                now += SpeechSingleFlight.IDLE_TIMEOUT_MS / 2
                slots.touch(lease)
            }

            assertNull(slots.acquire("alice"))
            assertTrue(slots.isHeld("alice"))
        }
    }
}
