package com.easy.easyai.core.watch

import java.time.Instant

/**
 * What one planning pass should do with a due watch, including the schedule advance that goes with
 * it. Null from [WatchClaimPolicy.decide] means "leave it alone this tick".
 *
 * The planner applies this *before* the task exists, so [newNextDueAt] is the value stored together
 * with the claim. It doubles as the in-flight guard: a second tick reading the old due instant loses
 * the guarded update and proposes nothing.
 */
data class WatchClaimDecision(
    val kind: WatchRunKind,
    val newNextDueAt: Instant,
    val pendingAction: WatchPendingAction,
    val statusReason: String? = null
)

/**
 * Decides whether a due watch is a normal check or an expiry review, and where its schedule moves
 * next. Pure: the planner supplies the row, the tick instant and the deployment limits.
 *
 * A watch whose lifetime has run out is *not* settled here. `until` passing, a recurrence whose next
 * occurrence would land beyond `until`, and a one-shot missed past the catch-up window all route to
 * an [WatchRunKind.EXPIRY_REVIEW] run, because only the model can tell "this was a one-off" from
 * "there is still a future milestone worth watching". Reminders are the exception: a reminder has no
 * condition to re-judge, so the handler expires a missed one directly.
 */
object WatchClaimPolicy {

    fun decide(watch: Watch, now: Instant, properties: WatchProperties): WatchClaimDecision? {
        if (watch.status != WatchStatus.ACTIVE) return null
        val scheduledFor = watch.nextDueAt
        if (watch.pendingAction == WatchPendingAction.EXPIRY_REVIEW) {
            // A review whose run failed or died: keep it a review and give it a fresh guard window.
            return review(now, properties, watch.statusReason ?: "expiry review pending")
        }
        if (watch.mode == WatchMode.REMIND) {
            // Overdue reminders are settled by the handler (delivered, or expired when the moment is
            // gone); there is nothing for a model to decide about them.
            return WatchClaimDecision(
                kind = WatchRunKind.CHECK,
                newNextDueAt = nextCheckDue(watch, now, properties),
                pendingAction = WatchPendingAction.CHECK
            )
        }
        val expiry = expiryReason(watch, scheduledFor, now, properties)
        if (expiry != null) return review(now, properties, expiry)
        return WatchClaimDecision(
            kind = WatchRunKind.CHECK,
            newNextDueAt = nextCheckDue(watch, now, properties),
            pendingAction = WatchPendingAction.CHECK
        )
    }

    /**
     * Why this firing is the watch's last chance, or null when it is an ordinary check.
     *
     * `scheduledFor` (not just `now`) is compared against `until`: a row claimed for a moment beyond
     * its deadline has no business running a normal check even if the tick is only seconds late.
     */
    private fun expiryReason(
        watch: Watch,
        scheduledFor: Instant,
        now: Instant,
        properties: WatchProperties
    ): String? {
        val until = watch.untilAt
        if (until != null && (scheduledFor.isAfter(until) || now.isAfter(until))) {
            return "The watch reached its until date"
        }
        if (watch.schedule.kind == WatchScheduleKind.AT) {
            val anchor = watch.schedule.anchorAt
                ?: return "The one-shot watch has no scheduled time"
            val lateByMs = now.toEpochMilli() - anchor.toEpochMilli()
            if (lateByMs > properties.catchUpWindowMs) {
                return "The one-shot watch was missed by more than ${properties.catchUpWindowMs / 60_000} minutes"
            }
        }
        return null
    }

    /**
     * Where the row's due instant moves when this firing starts.
     *
     * A recurrence moves to its next future occurrence; a one-shot gets a short guard because its
     * final value is written by the handler. When the next recurrence would land beyond `until`, the
     * due instant is pinned to `until` so the expiry review happens when the lifetime ends rather
     * than one whole recurrence later.
     */
    private fun nextCheckDue(watch: Watch, now: Instant, properties: WatchProperties): Instant {
        if (watch.schedule.kind == WatchScheduleKind.AT) {
            return now.plusMillis(properties.oneShotRetryGuardMs)
        }
        val next = WatchScheduleCalculator.nextAfter(watch.schedule, now)
        val until = watch.untilAt
        return if (until != null && until.isAfter(now) && next.isAfter(until)) until else next
    }

    private fun review(now: Instant, properties: WatchProperties, reason: String): WatchClaimDecision =
        WatchClaimDecision(
            kind = WatchRunKind.EXPIRY_REVIEW,
            newNextDueAt = now.plusMillis(properties.expiryReviewGuardMs),
            pendingAction = WatchPendingAction.EXPIRY_REVIEW,
            statusReason = reason
        )
}
