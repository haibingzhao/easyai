package com.easy.easyai.speech

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Renders pieces of a read-aloud plan with bounded parallelism and strictly ordered delivery.
 *
 * Two things a naive `map { }` gets wrong, and this class exists to get right:
 *
 *  - **Order.** Renders finish in whatever order the provider answers; audio must still be published
 *    by arrival order, because the listener cannot unhear a swapped sentence.
 *  - **Back-pressure.** A slow first segment must not let the rest of the plan pile up in memory. A
 *    render slot is taken before work starts and returned only once that piece has been handed
 *    downstream, so with a stalled head the planner stops being asked for more — which matters twice
 *    for [stream], where "the planner" is a paid LLM stream.
 *
 * Failures surface in order: the exception of piece *n* reaches the caller only after pieces `0..n-1`
 * were delivered, which is what lets the caller report "played 2 of 5" instead of a bare error.
 */
class SpeechSegmentScheduler(
    private val concurrency: Int = MAX_CONCURRENT_SEGMENTS,
    private val maxOutstanding: Int = MAX_BUFFERED_SEGMENTS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private class Rendered<P, T>(val index: Int, val payload: P, val job: Deferred<T>)

    /** Whole plan of known length: the P2 path, where every segment is already validated. */
    suspend fun <T : Any> run(
        count: Int,
        render: suspend (index: Int) -> T,
        emit: suspend (index: Int, value: T) -> Unit
    ) {
        require(count > 0) { "nothing to schedule" }
        stream(
            render = render,
            deliver = { index, _, value -> emit(index, value) },
            produce = { send ->
                for (index in 0 until count) send(index)
            }
        )
    }

    /**
     * Streaming plan of unknown length: the P3 path, where a segment is rendered as soon as the
     * planner has produced and validated it. [produce] must call `send` in delivery order.
     */
    suspend fun <P : Any, T : Any> stream(
        render: suspend (P) -> T,
        deliver: suspend (index: Int, payload: P, value: T) -> Unit,
        produce: suspend (send: suspend (P) -> Unit) -> Unit
    ) {
        require(concurrency >= 1) { "concurrency must be at least one" }
        require(maxOutstanding >= 1) { "maxOutstanding must be at least one" }

        // One permit per started-but-undelivered piece: this is the memory bound, not a thread bound.
        val permits = Semaphore(maxOutstanding)
        // Separate from `permits`: maxOutstanding bounds buffered results, concurrency bounds
        // simultaneous provider tasks (and therefore simultaneous spend).
        val slots = Semaphore(concurrency)
        val ordered = Channel<Rendered<P, T>>(capacity = maxOutstanding)
        val counter = AtomicInteger()
        // Set once any piece has failed, so the planner stops being asked for more work.
        val failure = AtomicReference<Throwable?>(null)

        // `supervisorScope`, not `coroutineScope`: with the latter a failure at segment 5 cancels the
        // joiner while it is still delivering segments 0..4 — audio that was already synthesized and
        // paid for — and leaves `playedSegments` smaller than the truth. A supervisor keeps the
        // siblings alive so the ordered prefix survives the failure.
        supervisorScope {
            // `async` rather than `launch` so the delivery failure is retrievable: `Job.join()` does
            // not rethrow, and the ordering of "delivery error first, producer error second" depends
            // on being able to tell them apart.
            val joiner = async(dispatcher) {
                try {
                    for (item in ordered) {
                        try {
                            deliver(item.index, item.payload, item.job.await())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            failure.compareAndSet(null, e)
                            throw e
                        } finally {
                            permits.release()
                        }
                    }
                } finally {
                    // Closing on the way out is what releases a producer parked in `send`; draining the
                    // channel is what releases the permits of pieces nobody will ever deliver, so a
                    // producer waiting in `acquire` cannot deadlock against a joiner that has stopped.
                    ordered.close()
                    while (true) {
                        val pending = ordered.tryReceive().getOrNull() ?: break
                        pending.job.cancel()
                        permits.release()
                    }
                }
            }

            var producerError: Throwable? = null
            try {
                produce { payload ->
                    failure.get()?.let { throw it }
                    val index = counter.getAndIncrement()
                    permits.acquire()
                    var job: Deferred<T>? = null
                    try {
                        // Re-checked after the wait: the joiner may have failed while this coroutine
                        // was parked, and starting a render nobody will hear wastes a paid call.
                        failure.get()?.let { throw it }
                        job = async(dispatcher) { slots.withPermit { render(payload) } }
                        ordered.send(Rendered(index, payload, job))
                    } catch (e: Throwable) {
                        // A piece that can never be delivered must not keep rendering: supervisorScope
                        // would await it, paying for a segment nobody can hear.
                        job?.cancel()
                        // The permit is only the joiner's to release once the piece is queued.
                        permits.release()
                        throw e
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                producerError = e
            } finally {
                ordered.close()
            }

            val deliveryError: Throwable? = try {
                joiner.await()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                e
            }
            // The delivery failure describes how far playback got, so it wins over a producer
            // failure that was only its consequence.
            (deliveryError ?: producerError)?.let { throw it }
        }
    }

    companion object {
        /** Two concurrent provider tasks: enough to hide per-task latency without doubling spend. */
        const val MAX_CONCURRENT_SEGMENTS = 2

        /** Un-emitted pieces allowed in memory; see the class comment. */
        const val MAX_BUFFERED_SEGMENTS = 3

        /** One synthesized segment above this size is treated as a fault, not as normal audio. */
        const val MAX_SEGMENT_BYTES = 8 * 1024 * 1024
    }
}
