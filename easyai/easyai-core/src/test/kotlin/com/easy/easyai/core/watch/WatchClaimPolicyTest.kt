package com.easy.easyai.core.watch

import com.easy.easyai.core.model.ProjectKind
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A due watch is not always a check: an expired lifetime, a recurrence whose next occurrence falls
 * past `until`, and a badly missed one-shot all become an expiry review, because only the model can
 * tell a finished intent from one that still has a future milestone.
 */
class WatchClaimPolicyTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-25T04:00:00Z")
    private val props = WatchProperties(catchUpWindowMs = 3_600_000, oneShotRetryGuardMs = 60_000, expiryReviewGuardMs = 600_000)

    private fun watch(
        schedule: WatchSchedule = WatchSchedule(
            kind = WatchScheduleKind.DAILY,
            zone = zone,
            localTime = LocalTime.of(9, 0)
        ),
        nextDueAt: Instant = now.minusSeconds(60),
        status: WatchStatus = WatchStatus.ACTIVE,
        mode: WatchMode = WatchMode.CHECK,
        pending: WatchPendingAction = WatchPendingAction.CHECK,
        until: Instant? = null
    ) = Watch(
        id = "watch-1",
        userId = "alice",
        title = "Price",
        condition = "Check the price",
        mode = mode,
        reminderText = null,
        schedule = schedule,
        untilAt = until,
        nextDueAt = nextDueAt,
        status = status,
        pendingAction = pending,
        tags = emptyList(),
        completeOnMet = true,
        notifyPolicy = WatchNotifyPolicy.ON_CHANGE,
        notifyTags = emptySet(),
        sourceAgentId = "default-agent",
        modelConfigId = "model-1",
        projectId = null,
        projectPath = null,
        projectKind = ProjectKind.USER,
        owners = setOf("alice"),
        originSessionId = null,
        runSessionId = "run-session",
        allowedTools = emptyList(),
        sourceHints = null,
        createdAt = 0,
        updatedAt = 0
    )

    @Nested
    inner class `ordinary planning` {

        @Test
        fun `a daily watch runs a check and moves to its next occurrence`() {
            val decision = WatchClaimPolicy.decide(watch(), now, props)!!
            assertEquals(WatchRunKind.CHECK, decision.kind)
            assertEquals(WatchPendingAction.CHECK, decision.pendingAction)
            assertEquals(
                WatchScheduleCalculator.nextAfter(watch().schedule, now),
                decision.newNextDueAt
            )
        }

        @Test
        fun `a one-shot claim leaves a guard window for its own final write`() {
            val at = now.minusSeconds(120)
            val decision = WatchClaimPolicy.decide(
                watch(schedule = WatchSchedule(WatchScheduleKind.AT, zone, anchorAt = at), nextDueAt = at),
                now,
                props
            )!!
            assertEquals(WatchRunKind.CHECK, decision.kind)
            assertEquals(now.plusSeconds(60), decision.newNextDueAt)
        }

        @Test
        fun `a paused or completed watch is left alone`() {
            assertNull(WatchClaimPolicy.decide(watch(status = WatchStatus.PAUSED), now, props))
            assertNull(WatchClaimPolicy.decide(watch(status = WatchStatus.COMPLETED), now, props))
        }

        @Test
        fun `a recurrence whose next run would land past until is pinned to until`() {
            val until = now.plusSeconds(3_600)
            val decision = WatchClaimPolicy.decide(watch(until = until), now, props)!!
            assertEquals(WatchRunKind.CHECK, decision.kind)
            assertEquals(until, decision.newNextDueAt)
        }
    }

    @Nested
    inner class `expiry review` {

        @Test
        fun `a due instant past until becomes a review, not a check`() {
            val until = now.minusSeconds(3600)
            val decision = WatchClaimPolicy.decide(watch(until = until), now, props)!!
            assertEquals(WatchRunKind.EXPIRY_REVIEW, decision.kind)
            assertEquals(WatchPendingAction.EXPIRY_REVIEW, decision.pendingAction)
            assertEquals(now.plusSeconds(600), decision.newNextDueAt)
        }

        @Test
        fun `a recurrence claimed for a moment beyond until becomes a review`() {
            // The row came due at a normal instant, but the service is late enough that the scheduled
            // moment is already past the deadline; running a normal check would ignore the deadline.
            val until = now.minusSeconds(30)
            val decision = WatchClaimPolicy.decide(
                watch(nextDueAt = now.minusSeconds(120), until = until),
                now,
                props
            )!!
            assertEquals(WatchRunKind.EXPIRY_REVIEW, decision.kind)
        }

        @Test
        fun `a one-shot missed beyond the catch-up window becomes a review`() {
            val at = now.minusSeconds(3_700)
            val decision = WatchClaimPolicy.decide(
                watch(schedule = WatchSchedule(WatchScheduleKind.AT, zone, anchorAt = at), nextDueAt = at),
                now,
                props
            )!!
            assertEquals(WatchRunKind.EXPIRY_REVIEW, decision.kind)
        }

        @Test
        fun `an unfinished review keeps the row a review with a fresh guard`() {
            val decision = WatchClaimPolicy.decide(
                watch(pending = WatchPendingAction.EXPIRY_REVIEW),
                now,
                props
            )!!
            assertEquals(WatchRunKind.EXPIRY_REVIEW, decision.kind)
            assertEquals(now.plusSeconds(600), decision.newNextDueAt)
        }

        @Test
        fun `a reminder is never reviewed by the model`() {
            val until = now.minusSeconds(3600)
            val decision = WatchClaimPolicy.decide(
                watch(mode = WatchMode.REMIND, until = until),
                now,
                props
            )!!
            assertEquals(WatchRunKind.CHECK, decision.kind)
            assertEquals(WatchPendingAction.CHECK, decision.pendingAction)
        }
    }
}
