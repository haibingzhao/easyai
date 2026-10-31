package com.easy.easyai.speech

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpeechSegmentSchedulerTest {

    @Nested
    inner class Ordering {
        @Test
        fun `publishes segments by index even when they finish out of order`() = runBlocking {
            val delays = listOf(80L, 10L, 1L)
            val emitted = mutableListOf<Int>()
            SpeechSegmentScheduler(concurrency = 2, maxOutstanding = 3).run(
                count = 3,
                render = { index ->
                    delay(delays[index])
                    "text-$index"
                },
                emit = { index, value ->
                    emitted += index
                    assertEquals("text-$index", value)
                }
            )
            assertEquals(listOf(0, 1, 2), emitted)
        }
    }

    @Nested
    inner class Bounds {
        @Test
        fun `never runs more segments at once than the configured concurrency`() = runBlocking {
            val running = AtomicInteger()
            val peak = AtomicInteger()
            SpeechSegmentScheduler(concurrency = 2, maxOutstanding = 3).run(
                count = 6,
                render = { index ->
                    val active = running.incrementAndGet()
                    peak.updateAndGet { maxOf(it, active) }
                    delay(20)
                    running.decrementAndGet()
                    index
                },
                emit = { _, _ -> }
            )
            assertTrue(peak.get() <= 2, "peak concurrency was ${peak.get()}")
        }

        @Test
        fun `keeps at most the outstanding cap rendered but un-emitted`() = runBlocking {
            val outstanding = AtomicInteger()
            val peak = AtomicInteger()
            // The first segment is slow, so without a cap every other segment would be rendered and
            // held in memory before the player ever saw segment 0.
            SpeechSegmentScheduler(concurrency = 2, maxOutstanding = 2).run(
                count = 6,
                render = { index ->
                    outstanding.incrementAndGet()
                    delay(if (index == 0) 120 else 5)
                    index
                },
                emit = { _, _ ->
                    peak.updateAndGet { maxOf(it, outstanding.get()) }
                    outstanding.decrementAndGet()
                }
            )
            assertTrue(peak.get() <= 2, "peak buffered segments was ${peak.get()}")
        }
    }

    @Nested
    inner class Failures {
        @Test
        fun `a failing segment stops delivery and bounds the work started after it`() = runBlocking {
            val delivered = Collections.synchronizedList(mutableListOf<Int>())
            val started = Collections.synchronizedSet(mutableSetOf<Int>())
            val error = assertFailsWith<IllegalStateException> {
                SpeechSegmentScheduler(concurrency = 1, maxOutstanding = 1).run(
                    count = 20,
                    render = { index ->
                        started += index
                        if (index == 1) throw IllegalStateException("provider said no")
                        index
                    },
                    emit = { index, _ -> delivered += index }
                )
            }
            assertEquals("provider said no", error.message)
            // Segment 0 is published before the failure is surfaced, which is what lets the caller
            // report "played 1 of 20" instead of a bare error.
            assertEquals(listOf(0), delivered.toList())
            // Prefetch is bounded: most of the plan is never rendered for a planner that already failed.
            assertTrue(started.size <= 3, "rendered ${started.size} of 20 segments after a failure")
        }

        @Test
        fun `a failure does not cancel a segment that was already rendering ahead of it`() = runBlocking {
            // Segment 0 is slow and segment 1 fails immediately. The clip for 0 has already been paid
            // for and comes first in playback order, so losing it would both waste the synthesis and
            // make `playedSegments` a lie. A plain `coroutineScope` cancels the joiner here, because a
            // failed child cancels the whole scope.
            val delivered = Collections.synchronizedList(mutableListOf<Int>())
            val firstStarted = CompletableDeferred<Unit>()
            val error = assertFailsWith<IllegalStateException> {
                SpeechSegmentScheduler(concurrency = 2, maxOutstanding = 3).run(
                    count = 3,
                    render = { index ->
                        if (index == 0) {
                            firstStarted.complete(Unit)
                            delay(150)
                            0
                        } else {
                            firstStarted.await()
                            throw IllegalStateException("segment $index failed")
                        }
                    },
                    emit = { index, _ -> delivered += index }
                )
            }
            assertEquals(listOf(0), delivered.toList())
            assertTrue(error.message.orEmpty().contains("failed"), "unexpected error: ${error.message}")
        }

        @Test
        fun `a planner that fails mid-stream stops the pipeline instead of hanging`() = runBlocking {
            val delivered = mutableListOf<Int>()
            val error = assertFailsWith<IllegalStateException> {
                SpeechSegmentScheduler(concurrency = 2, maxOutstanding = 2).stream(
                    render = { index: Int -> index },
                    deliver = { index, _, _ -> delivered += index },
                    produce = { send ->
                        send(0)
                        throw IllegalStateException("the plan stopped covering the source")
                    }
                )
            }
            assertEquals("the plan stopped covering the source", error.message)
            assertTrue(delivered.size <= 1)
        }
    }
}
