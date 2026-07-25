package com.easy.easyai.core.team

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/**
 * Shared channel debounce/drain utility for team coordination event loops.
 *
 * Both Swarm's TeamTaskExecutor and Team Agent's WaitForMemberEventsTool use the
 * identical pattern: receive the first event, wait a brief debounce window for
 * additional events to arrive, then drain all buffered events without suspending.
 *
 * This batching prevents the Leader from reacting to events one-by-one when
 * multiple members finish nearly simultaneously.
 */
object TeamEventDrain {

    /** Default debounce window in milliseconds. */
    const val DEFAULT_DEBOUNCE_MS = 2000L

    /**
     * Wait a debounce window after [first] event, then drain all remaining
     * buffered events from [channel] without suspending.
     *
     * @param channel The event channel to drain from.
     * @param first The first already-received event.
     * @param debounceMs How long to wait for additional events to accumulate.
     * @return Batch of events including [first], in arrival order.
     */
    suspend fun <T> drain(
        channel: Channel<T>,
        first: T,
        debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    ): List<T> {
        delay(debounceMs)
        val batch = mutableListOf(first)
        while (true) {
            val next = channel.tryReceive().getOrNull() ?: break
            batch.add(next)
        }
        return batch
    }
}
