package com.easy.easyai.web.service

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One read-aloud at a time per owner — and a *replacement* is not a second one.
 *
 * The guard exists for cost, not for concurrency: read-aloud is billed per character, so two
 * overlapping syntheses for one account are always a mistake somebody pays for. Three behaviours
 * make that safe instead of brittle, and all three are load-bearing:
 *
 *  - **A grace period.** A client replaces its own read-aloud by aborting the previous request and
 *    firing the next one straight away, but the abort is a network round-trip behind the server
 *    noticing it. Refusing on arrival turns "click another reply" into a 429 for a request that is
 *    not concurrent with anything, so a newcomer waits briefly for the slot instead of failing on
 *    the instant.
 *  - **Identity-checked release.** A slot is given back by the lease that took it, never by owner
 *    name. A late release from a superseded request must not free the slot of the request that
 *    replaced it: that is exactly how the double synthesis this class exists to prevent would slip
 *    through.
 *  - **Idle reclaim.** A slot whose holder has reported no work for [idleTimeoutMs] is presumed
 *    leaked — a cancelled response that never reached its `finally`, a tab closed mid-stream — and is
 *    taken over, loudly. Without it one leaked claim disables read-aloud for that account until the
 *    process restarts, which is what a plain `Set<String>` of owners does.
 *
 * A [Lease] is compared by identity on purpose: two claims for one owner are never equal, so
 * `remove(key, value)` can only ever drop the claim that is actually in the map.
 */
class SpeechSingleFlight(
    private val graceAttempts: Int = GRACE_ATTEMPTS,
    private val pollMs: Long = POLL_MS,
    private val idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val holders = ConcurrentHashMap<String, Lease>()

    /** A claimed slot. Opaque to callers: holding it is the proof of ownership, nothing else is. */
    class Lease internal constructor(internal val owner: String) {
        internal val token: String = UUID.randomUUID().toString()

        /** Last moment the holder was known to be working; [idleTimeoutMs] is measured from here. */
        @Volatile
        internal var touchedAt: Long = 0
    }

    /**
     * Take [owner]'s slot, waiting out a request that is in the middle of being replaced.
     *
     * Returns null when the slot is still held after the grace period: a genuine second read-aloud,
     * not a replacement. The caller turns that into a 429 and says why.
     */
    suspend fun acquire(owner: String): Lease? {
        for (attempt in 0..graceAttempts) {
            val candidate = Lease(owner)
            candidate.touchedAt = clock()
            val holder = holders.putIfAbsent(owner, candidate)
            if (holder == null) {
                // Debug on purpose: this fires for every read-aloud, but it is the line that makes a
                // slot which never comes back identifiable at all — without it the only log evidence
                // of a stuck slot is a refusal nobody can attribute to a request.
                logger.debug("Read-aloud slot claimed for owner '{}' (token {})", owner, candidate.token.take(8))
                return candidate
            }
            if (reclaimIfIdle(owner, holder, candidate)) return candidate
            // Nothing to wait for on the last pass; polling again would only delay the answer.
            if (attempt < graceAttempts) delay(pollMs)
        }
        val held = holders[owner]
        logger.warn(
            "Read-aloud refused for owner '{}': the slot has been held for {} ms (token {})",
            owner,
            held?.let { clock() - it.touchedAt } ?: 0L,
            held?.token?.take(8) ?: "-"
        )
        return null
    }

    /** Give the slot back. Idempotent, and a no-op once another lease owns the owner. */
    fun release(lease: Lease?) {
        if (lease == null) return
        if (holders.remove(lease.owner, lease)) {
            logger.debug(
                "Read-aloud slot released for owner '{}' after {} ms (token {})",
                lease.owner,
                clock() - lease.touchedAt,
                lease.token.take(8)
            )
        }
    }

    /**
     * Record that the holder is still working.
     *
     * Called per rendered segment: the only honest liveness signal the guard has, and what makes
     * "idle for ten minutes" distinguishable from "a long plan is still being synthesized".
     */
    fun touch(lease: Lease?) {
        if (lease != null) lease.touchedAt = clock()
    }

    /** Whether [owner] currently holds the slot; for tests and diagnostics, never for gating. */
    fun isHeld(owner: String): Boolean = holders.containsKey(owner)

    /**
     * Take over a slot whose holder has gone quiet.
     *
     * `replace` is the compare-and-set: two newcomers that both notice the same stale holder must not
     * both win, and the loser re-reads the map on its next pass.
     */
    private fun reclaimIfIdle(owner: String, holder: Lease, candidate: Lease): Boolean {
        val idleMs = clock() - holder.touchedAt
        if (idleMs <= idleTimeoutMs) return false
        if (!holders.replace(owner, holder, candidate)) return false
        logger.warn(
            "Reclaimed a stalled read-aloud slot for owner '{}' after {} ms idle (token {})",
            owner,
            idleMs,
            holder.token.take(8)
        )
        return true
    }

    companion object {
        /**
         * How long a newcomer waits for the request it is replacing to finish cancelling.
         *
         * Forty polls of 100 ms. The floor is a cancellation round-trip (tens of milliseconds); the
         * ceiling is set by a predecessor that has not started synthesizing yet — a `refine`
         * read-aloud sits in its own four-second planning call (see `SpeechDirector`) during which it
         * logs nothing at all, so a shorter window reads as "refused with nothing running".
         */
        const val GRACE_ATTEMPTS = 40

        const val POLL_MS = 100L

        /** Longer than any single provider call (the service caps a synthesis at 300 s). */
        const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
