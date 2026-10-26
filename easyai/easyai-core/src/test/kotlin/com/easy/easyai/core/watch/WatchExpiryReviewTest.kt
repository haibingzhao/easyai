package com.easy.easyai.core.watch

import com.easy.easyai.core.task.TaskOutcome
import io.mockk.coEvery
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Expiry review is the one place a watch's lifetime may be extended. The model proposes; the handler
 * enforces the limits (one automatic renewal, a 90-day horizon, a future date that matches the
 * schedule kind) and treats an unusable proposal as "expire", never as "retry until it works".
 */
class WatchExpiryReviewTest {

    private val now = Instant.parse("2026-10-25T01:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun handler(
        store: WatchStore,
        notifier: WatchNotifier? = null,
        model: WatchModelStub? = null,
        properties: WatchProperties = WatchProperties()
    ) = WatchTaskHandler(
        store = store,
        properties = properties,
        sessionManager = model?.sessionManager,
        agentService = model?.agentService,
        modelConfigService = model?.modelConfigService,
        notifier = notifier,
        clock = { now }
    )

    private fun reviewAnswer(
        decision: String = "renew",
        reason: String = "There is still a quarterly report coming",
        conclusion: String = "The watch was extended to the next report",
        newUntil: String? = "\"2026-12-31\"",
        rescheduleAt: String? = null
    ) = buildString {
        append("""{"decision":"$decision","reason":"$reason","conclusion":"$conclusion"""")
        if (newUntil != null) append(""","new_until":$newUntil""")
        if (rescheduleAt != null) append(""","reschedule_at":"$rescheduleAt"""")
        append("}")
    }

    private fun reviewStore(watch: Watch = testWatch(pendingAction = WatchPendingAction.EXPIRY_REVIEW)) =
        RecordingWatchStore(watch.copy(pendingAction = WatchPendingAction.EXPIRY_REVIEW))

    @Nested
    inner class `renewal` {

        @Test
        fun `a recurring watch is extended to the model's date and stays active`() = runTest {
            val store = reviewStore()
            val notifier = RecordingNotifier()
            val outcome = handler(store, notifier, WatchModelStub(reviewAnswer()))
                .execute(watchTask(store.current, WatchRunKind.EXPIRY_REVIEW))

            val success = assertIs<TaskOutcome.Success>(outcome)
            assertEquals("renew", success.result["decision"])
            assertEquals(true, success.result["applied"])
            assertEquals(WatchStatus.ACTIVE, store.reviewStatus)
            assertEquals(WatchPendingAction.CHECK, store.reviewPending)
            assertEquals(WatchScheduleCalculator.endOfDay(LocalDate.parse("2026-12-31"), zone).toEpochMilli(), store.reviewUntil)
            assertEquals(Instant.parse("2026-10-26T01:00:00Z").toEpochMilli(), store.reviewNextDue)
            assertEquals(1, store.reviewExtensions)
            assertEquals("expiry_review", notifier.notices.single().kind)
            assertTrue(notifier.notices.single().body!!.startsWith("Renewed:"))
        }

        @Test
        fun `a second automatic renewal is downgraded to expiry`() = runTest {
            val store = reviewStore(testWatch(pendingAction = WatchPendingAction.EXPIRY_REVIEW, extensionCount = 1))
            val notifier = RecordingNotifier()
            handler(store, notifier, WatchModelStub(reviewAnswer())).execute(watchTask(store.current, WatchRunKind.EXPIRY_REVIEW))

            assertEquals(WatchStatus.EXPIRED, store.reviewStatus)
            assertEquals(1, store.reviewExtensions)
            assertTrue(store.reviewReason!!.contains("Automatic renewal limit"))
            assertTrue(notifier.notices.single().body!!.startsWith("Expired:"))
        }

        @Test
        fun `a renewal date in the past is treated as an expiry, with the reason recorded`() = runTest {
            val store = reviewStore()
            handler(store, model = WatchModelStub(reviewAnswer(newUntil = "\"2020-01-01\"")))
                .execute(watchTask(store.current, WatchRunKind.EXPIRY_REVIEW))

            assertEquals(WatchStatus.EXPIRED, store.reviewStatus)
            assertTrue(store.reviewReason!!.contains("unusable"))
        }

        @Test
        fun `a renewal beyond the horizon is treated as an expiry`() = runTest {
            val store = reviewStore()
            handler(
                store,
                model = WatchModelStub(reviewAnswer(newUntil = "\"2027-06-01\"")),
                properties = WatchProperties(maxExtensionHorizonDays = 30)
            ).execute(watchTask(store.current, WatchRunKind.EXPIRY_REVIEW))

            assertEquals(WatchStatus.EXPIRED, store.reviewStatus)
            assertTrue(store.reviewReason!!.contains("within 30 days"))
        }

        @Test
        fun `a one-shot watch is rescheduled at the model's absolute instant`() = runTest {
            val anchor = Instant.parse("2026-10-20T01:00:00Z")
            val store = reviewStore(
                testWatch(
                    schedule = WatchSchedule(WatchScheduleKind.AT, zone, anchorAt = anchor),
                    nextDueAt = anchor,
                    pendingAction = WatchPendingAction.EXPIRY_REVIEW
                )
            )
            handler(
                store,
                model = WatchModelStub(
                    reviewAnswer(newUntil = null, rescheduleAt = "2026-11-01T09:00:00+08:00")
                )
            ).execute(watchTask(store.current, WatchRunKind.EXPIRY_REVIEW))

            assertEquals(WatchStatus.ACTIVE, store.reviewStatus)
            assertEquals(Instant.parse("2026-11-01T01:00:00Z").toEpochMilli(), store.reviewAnchor)
            assertEquals(Instant.parse("2026-11-01T01:00:00Z").toEpochMilli(), store.reviewNextDue)
        }
    }

    @Nested
    inner class `terminal decisions` {

        @Test
        fun `completion keeps the history and closes the watch`() = runTest {
            val store = reviewStore()
            val notifier = RecordingNotifier()
            handler(
                store,
                notifier,
                WatchModelStub(reviewAnswer(decision = "complete", conclusion = "The goal was reached"))
            ).execute(watchTask(store.current, WatchRunKind.EXPIRY_REVIEW))

            assertEquals(WatchStatus.COMPLETED, store.reviewStatus)
            assertEquals(WatchPendingAction.CHECK, store.reviewPending)
            assertTrue(store.reviewReason!!.contains("Completed at expiry review"))
            assertTrue(notifier.notices.single().body!!.startsWith("Completed:"))
        }

        @Test
        fun `expiry closes the watch with the model's reason`() = runTest {
            val store = reviewStore()
            handler(store, model = WatchModelStub(reviewAnswer(decision = "expire")))
                .execute(watchTask(store.current, WatchRunKind.EXPIRY_REVIEW))

            assertEquals(WatchStatus.EXPIRED, store.reviewStatus)
            assertTrue(store.reviewReason!!.contains("quarterly report"))
        }

        @Test
        fun `a review that loses the guard leaves the definition alone`() = runTest {
            val store = reviewStore()
            store.loseReviewGuard = true
            val notifier = RecordingNotifier()
            val success = assertIs<TaskOutcome.Success>(
                handler(store, notifier, WatchModelStub(reviewAnswer()))
                    .execute(watchTask(store.current, WatchRunKind.EXPIRY_REVIEW))
            )
            assertEquals(true, success.result["stale"])
            assertEquals(false, success.result["applied"])
            assertTrue(notifier.notices.isEmpty())
        }

        @Test
        fun `a failed review backs off while remaining a review`() = runTest {
            val store = reviewStore()
            val model = WatchModelStub(reviewAnswer())
            coEvery { model.modelConfigService.getUserConfiguration(any<String>(), any<Collection<String>>()) } returns null
            val outcome = handler(store, model = model).execute(watchTask(store.current, WatchRunKind.EXPIRY_REVIEW))

            assertIs<TaskOutcome.Failure>(outcome)
            assertEquals(1, store.failureCalls)
            assertEquals(WatchStatus.ACTIVE, store.failureStatus)
            assertEquals(1, store.failureCount)
        }
    }
}
