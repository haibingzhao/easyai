package com.easy.easyai.core.watch

import com.easy.easyai.core.task.TaskOutcome
import com.easy.easyai.core.task.TaskTrigger
import io.mockk.coEvery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * One firing: what the handler applies, what it refuses, and what it books on failure.
 *
 * The model is stubbed at the session boundary, because the contract under test is not "does the
 * model answer well" but "is a malformed or losing answer kept out of the definition".
 */
class WatchTaskHandlerTest {

    private val now = Instant.parse("2026-10-25T01:00:00Z")

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
        modelConfigStore = model?.modelConfigStore,
        notifier = notifier,
        clock = { now }
    )

    private fun checkAnswer(
        conclusion: String = "Price crossed 100",
        verdict: String = "met",
        tags: String = """["crossed"]""",
        sources: String = """[{"label":"Quote","url":"https://example.com/q"}]"""
    ) = """{"conclusion":"$conclusion","verdict":"$verdict","tags":$tags,"sources":$sources}"""

    @Nested
    inner class `preconditions` {

        @Test
        fun `a task without a watch id fails with a readable message`() = runTest {
            val store = RecordingWatchStore(testWatch())
            val task = watchTask(store.current).copy(payload = emptyMap())
            val outcome = handler(store).execute(task)
            assertIs<TaskOutcome.Failure>(outcome)
            assertTrue(outcome.error.contains("no watch id"))
        }

        @Test
        fun `a deleted watch fails`() = runTest {
            val store = RecordingWatchStore(testWatch())
            val task = watchTask(store.current)
            val absent = object : WatchStore by store {
                override suspend fun findById(id: String): Watch? = null
            }
            val outcome = handler(absent).execute(task)
            assertIs<TaskOutcome.Failure>(outcome)
            assertTrue(outcome.error.contains("no longer exists"))
        }

        @Test
        fun `a task aimed at another user's watch fails`() = runTest {
            val store = RecordingWatchStore(testWatch(userId = "alice"))
            val task = watchTask(store.current).copy(userId = "bob")
            val outcome = handler(store).execute(task)
            assertIs<TaskOutcome.Failure>(outcome)
            assertTrue(outcome.error.contains("different user"))
        }

        @Test
        fun `an inactive watch is skipped without running the model`() = runTest {
            val store = RecordingWatchStore(testWatch(status = WatchStatus.PAUSED))
            val outcome = handler(store).execute(watchTask(store.current))
            val success = assertIs<TaskOutcome.Success>(outcome)
            assertEquals("skipped", success.result["kind"])
            assertTrue(store.runStartedTaskIds.isEmpty())
        }

        @Test
        fun `an expiry review whose definition moved on is stale on arrival`() = runTest {
            val store = RecordingWatchStore(
                testWatch(pendingAction = WatchPendingAction.CHECK)
            )
            val outcome = handler(store).execute(watchTask(store.current, WatchRunKind.EXPIRY_REVIEW))
            val success = assertIs<TaskOutcome.Success>(outcome)
            assertEquals(true, success.result["stale"])
            assertEquals(false, success.result["applied"])
            assertEquals(0, store.reviewCalls)
        }
    }

    @Nested
    inner class `a normal check` {

        @Test
        fun `a met verdict with complete-on-met writes the finding and completes the watch`() = runTest {
            val store = RecordingWatchStore(testWatch(completeOnMet = true))
            val notifier = RecordingNotifier()
            val outcome = handler(store, notifier, WatchModelStub(checkAnswer()))
                .execute(watchTask(store.current))

            val success = assertIs<TaskOutcome.Success>(outcome)
            assertEquals("met", success.result["verdict"])
            assertEquals(true, success.result["applied"])
            assertEquals(false, success.result["stale"])
            assertEquals(WatchStatus.COMPLETED, store.appliedStatus)
            assertEquals(WatchPendingAction.CHECK, store.appliedPending)
            val finding = store.appliedFinding!!
            assertEquals("Price crossed 100", finding.conclusion)
            assertEquals(listOf(WatchFoundTag("crossed", "已突破")), finding.tags)
            assertEquals(1, finding.sources.size)
            assertEquals(true, finding.changed)
            assertEquals(true, finding.notified)
            assertEquals(1, notifier.notices.size)
            assertEquals("finding", notifier.notices.single().kind)
        }

        @Test
        fun `a one-shot watch finishes even when the verdict is not met`() = runTest {
            val anchor = now.minusSeconds(60)
            val store = RecordingWatchStore(
                testWatch(
                    schedule = WatchSchedule(WatchScheduleKind.AT, ZoneId.of("Asia/Shanghai"), anchorAt = anchor),
                    nextDueAt = anchor
                )
            )
            val outcome = handler(store, model = WatchModelStub(checkAnswer(verdict = "not_met", tags = "[]")))
                .execute(watchTask(store.current))
            assertIs<TaskOutcome.Success>(outcome)
            assertEquals(WatchStatus.COMPLETED, store.appliedStatus)
        }

        @Test
        fun `a manual check does not consume a future one-shot firing`() = runTest {
            val anchor = now.plusSeconds(86_400)
            val store = RecordingWatchStore(
                testWatch(
                    schedule = WatchSchedule(WatchScheduleKind.AT, ZoneId.of("Asia/Shanghai"), anchorAt = anchor),
                    nextDueAt = anchor
                )
            )
            val task = watchTask(store.current).copy(trigger = TaskTrigger.FRONTEND)
            val outcome = handler(store, model = WatchModelStub(checkAnswer(verdict = "not_met", tags = "[]")))
                .execute(task)
            assertIs<TaskOutcome.Success>(outcome)
            assertEquals(WatchStatus.ACTIVE, store.appliedStatus)
        }

        @Test
        fun `an unchanged finding is stored without a notification under on-change`() = runTest {
            val previous = WatchFinding(
                conclusion = "still there",
                verdict = WatchVerdict.MET,
                tags = listOf(WatchFoundTag("crossed", "已突破")),
                definitionVersion = 3
            )
            val store = RecordingWatchStore(testWatch(lastFinding = previous, completeOnMet = false))
            val notifier = RecordingNotifier()
            val outcome = handler(store, notifier, WatchModelStub(checkAnswer()))
                .execute(watchTask(store.current))
            assertIs<TaskOutcome.Success>(outcome)
            assertEquals(false, store.appliedFinding!!.changed)
            assertEquals(false, store.appliedFinding!!.notified)
            assertTrue(notifier.notices.isEmpty())
            assertEquals(WatchStatus.ACTIVE, store.appliedStatus)
        }

        @Test
        fun `an always-notify tag overrides the unchanged state`() = runTest {
            val previous = WatchFinding(
                conclusion = "still there",
                verdict = WatchVerdict.MET,
                tags = listOf(WatchFoundTag("crossed", "已突破")),
                definitionVersion = 3
            )
            val store = RecordingWatchStore(
                testWatch(lastFinding = previous, completeOnMet = false, notifyTags = setOf("crossed"))
            )
            val notifier = RecordingNotifier()
            handler(store, notifier, WatchModelStub(checkAnswer())).execute(watchTask(store.current))
            assertEquals(true, store.appliedFinding!!.notified)
            assertEquals(1, notifier.notices.size)
        }

        @Test
        fun `a finding that uses an unknown tag fails the run instead of half-applying`() = runTest {
            val store = RecordingWatchStore(testWatch())
            val outcome = handler(store, model = WatchModelStub(checkAnswer(tags = """["invented"]""")))
                .execute(watchTask(store.current))
            assertIs<TaskOutcome.Failure>(outcome)
            assertTrue(outcome.error.contains("not part of this watch's tag universe"))
            assertEquals(0, store.applyFindingCalls)
            assertEquals(1, store.failureCalls)
        }

        @Test
        fun `a finding that lost the version guard stays in task history only`() = runTest {
            val store = RecordingWatchStore(testWatch())
            store.afterFirstRead = store.current.copy(version = 4)
            val notifier = RecordingNotifier()
            val outcome = handler(store, notifier, WatchModelStub(checkAnswer()))
                .execute(watchTask(store.current))
            val success = assertIs<TaskOutcome.Success>(outcome)
            assertEquals(true, success.result["stale"])
            assertEquals(false, success.result["applied"])
            assertEquals(0, store.applyFindingCalls)
            assertTrue(notifier.notices.isEmpty())
        }

        @Test
        fun `losing the write race is reported stale rather than thrown`() = runTest {
            val store = RecordingWatchStore(testWatch())
            store.loseApplyFindingGuard = true
            val success = assertIs<TaskOutcome.Success>(
                handler(store, model = WatchModelStub(checkAnswer())).execute(watchTask(store.current))
            )
            assertEquals(true, success.result["stale"])
            assertEquals(false, success.result["applied"])
        }
    }

    @Nested
    inner class `reminders` {

        @Test
        fun `a one-shot reminder completes after delivering its text`() = runTest {
            val anchor = now.minusSeconds(60)
            val store = RecordingWatchStore(
                testWatch(
                    mode = WatchMode.REMIND,
                    reminderText = "Take medicine",
                    schedule = WatchSchedule(WatchScheduleKind.AT, ZoneId.of("Asia/Shanghai"), anchorAt = anchor),
                    nextDueAt = anchor
                )
            )
            val notifier = RecordingNotifier()
            val outcome = handler(store, notifier).execute(watchTask(store.current))
            val success = assertIs<TaskOutcome.Success>(outcome)
            assertEquals("note", success.result["verdict"])
            assertEquals("Take medicine", success.result["conclusion"])
            assertEquals(WatchStatus.COMPLETED, store.appliedStatus)
            assertEquals(1, notifier.notices.size)
        }

        @Test
        fun `a reminder missed past the catch-up window expires instead of arriving late`() = runTest {
            val anchor = now.minusSeconds(7_200)
            val store = RecordingWatchStore(
                testWatch(
                    mode = WatchMode.REMIND,
                    reminderText = "Stand up",
                    schedule = WatchSchedule(WatchScheduleKind.AT, ZoneId.of("Asia/Shanghai"), anchorAt = anchor),
                    nextDueAt = anchor
                )
            )
            val notifier = RecordingNotifier()
            val outcome = handler(store, notifier, properties = WatchProperties(catchUpWindowMs = 3_600_000))
                .execute(watchTask(store.current))
            val success = assertIs<TaskOutcome.Success>(outcome)
            assertTrue((success.result["conclusion"] as String).contains("missed"))
            assertEquals(WatchStatus.EXPIRED, store.appliedStatus)
            assertEquals("status", notifier.notices.single().kind)
        }
    }

    @Nested
    inner class `failure handling` {

        @Test
        fun `a deleted model configuration is booked as a failure with backoff`() = runTest {
            val store = RecordingWatchStore(testWatch())
            val model = WatchModelStub(checkAnswer())
            coEvery { model.modelConfigStore.getConfig(any<String>(), any<Collection<String>>()) } returns null
            val outcome = handler(store, model = model).execute(watchTask(store.current))
            val failure = assertIs<TaskOutcome.Failure>(outcome)
            assertTrue(failure.error.contains("deleted"))
            assertEquals(1, store.failureCalls)
            assertEquals(WatchStatus.ACTIVE, store.failureStatus)
            assertEquals(1, store.failureCount)
            assertEquals(now.plusSeconds(3_600).toEpochMilli(), store.failureNextDue)
        }

        @Test
        fun `the last allowed failure pauses the watch and notifies once`() = runTest {
            val store = RecordingWatchStore(testWatch(consecutiveFailures = 2))
            val notifier = RecordingNotifier()
            val model = WatchModelStub(checkAnswer())
            coEvery { model.modelConfigStore.getConfig(any<String>(), any<Collection<String>>()) } returns null
            handler(store, notifier, model).execute(watchTask(store.current))
            assertEquals(WatchStatus.PAUSED, store.failureStatus)
            assertEquals(3, store.failureCount)
            assertEquals("status", notifier.notices.single().kind)
            assertTrue(notifier.notices.single().body!!.contains("Paused"))
        }

        @Test
        fun `a user cancel is rethrown and never booked as a watch failure`() = runTest {
            val store = RecordingWatchStore(testWatch())
            val model = WatchModelStub(failure = CancellationException("stopped"))
            assertThrows<CancellationException> {
                handler(store, model = model).execute(watchTask(store.current))
            }
            assertEquals(0, store.failureCalls)
        }

        @Test
        fun `a manual failure leaves a one-shot's scheduled instant alone`() = runTest {
            val anchor = now.plusSeconds(7_200)
            val store = RecordingWatchStore(
                testWatch(
                    schedule = WatchSchedule(
                        WatchScheduleKind.AT,
                        ZoneId.of("Asia/Shanghai"),
                        anchorAt = anchor
                    ),
                    nextDueAt = anchor
                )
            )
            val model = WatchModelStub(checkAnswer())
            coEvery {
                model.modelConfigStore.getConfig(any<String>(), any<Collection<String>>())
            } returns null

            handler(store, model = model)
                .execute(watchTask(store.current).copy(trigger = TaskTrigger.FRONTEND))

            assertEquals(1, store.failureCalls)
            assertEquals(anchor.toEpochMilli(), store.failureNextDue)
        }

        @Test
        fun `a manual failure leaves the next daily occurrence alone`() = runTest {
            val store = RecordingWatchStore(testWatch())
            val scheduled = store.current.nextDueAt
            val model = WatchModelStub(checkAnswer())
            coEvery {
                model.modelConfigStore.getConfig(any<String>(), any<Collection<String>>())
            } returns null

            handler(store, model = model)
                .execute(watchTask(store.current).copy(trigger = TaskTrigger.FRONTEND))

            assertEquals(1, store.failureCalls)
            assertEquals(scheduled.toEpochMilli(), store.failureNextDue)
        }

        @Test
        fun `a non-JSON answer fails the run without touching the definition`() = runTest {
            val store = RecordingWatchStore(testWatch())
            val outcome = handler(store, model = WatchModelStub("I could not check anything"))
                .execute(watchTask(store.current))
            assertIs<TaskOutcome.Failure>(outcome)
            assertEquals(0, store.applyFindingCalls)
            assertEquals(1, store.failureCalls)
        }
    }
}
