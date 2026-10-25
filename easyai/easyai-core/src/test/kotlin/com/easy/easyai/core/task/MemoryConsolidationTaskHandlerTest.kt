package com.easy.easyai.core.task

import com.easy.easyai.core.memory.ConsolidationReport
import com.easy.easyai.core.memory.MemoryConsolidationAgent
import com.easy.easyai.core.memory.MemoryIndexEntry
import com.easy.easyai.core.memory.MemoryOwnerContext
import com.easy.easyai.core.memory.MemoryScope
import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.core.memory.MemoryType
import com.easy.easyai.core.memory.consolidation.ConsolidationOutcome
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationSessionTitlePort
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationSettingsStore
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationSliceState
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationUserSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests [MemoryConsolidationTaskHandler]'s payload restoration: it must rebuild the slice, owner,
 * visibility set, type and dry-run flag from the persisted [AsyncTask.payload] (so a task survives a
 * restart), mint/link the hidden session, serialize the agent's report into the task result, and record
 * the per-slice memory the scheduler needs so the same question is not asked again tomorrow.
 * The agent itself is mocked.
 */
class MemoryConsolidationTaskHandlerTest {

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    }

    private val agent = mockk<MemoryConsolidationAgent>(relaxed = true)
    private val taskStore = mockk<AsyncTaskStore>(relaxed = true)
    private val titlePort = mockk<MemoryConsolidationSessionTitlePort>(relaxed = true)
    private val memoryStore = mockk<MemoryStore>(relaxed = true)
    private val settingsStore = mockk<MemoryConsolidationSettingsStore>(relaxed = true)
    private val recordedKeys = mutableListOf<String>()

    /** The handler wired for the bookkeeping path, with backoff days as small numbers a test can assert. */
    private fun recordingHandler(failedRunBackoffBaseDays: Int = 1) = MemoryConsolidationTaskHandler(
        agent, taskStore, titlePort, memoryStore, settingsStore,
        backoffBaseDays = 7, backoffMaxDays = 30, failedRunBackoffBaseDays = failedRunBackoffBaseDays
    )

    /**
     * The bookkeeping half of the fixture: the post-run listing, the capture hooks and the previous slice
     * state. Split from the agent stub because a run that *threw* has no report, and the failure path is
     * exactly what needs the same bookkeeping asserted against it.
     */
    private fun givenBookkeeping(
        previousStreak: Int = 0,
        previousFingerprint: Int? = null
    ): MemoryConsolidationSliceState {
        coEvery { memoryStore.listActiveIndex(MemoryScope.GLOBAL, any(), MemoryType.EXPERIENCE_LESSONS) } returns
            listOf(
                MemoryIndexEntry("doc-2", "b", MemoryType.EXPERIENCE_LESSONS, 0, 500, ""),
                MemoryIndexEntry("doc-1", "a", MemoryType.EXPERIENCE_LESSONS, 0, 500, "")
            )
        val previous = MemoryConsolidationSliceState(
            activeFingerprint = previousFingerprint,
            zeroGainStreak = previousStreak
        )
        recordedStates.clear()
        recordedKeys.clear()
        coEvery { settingsStore.recordSliceState(any(), any(), any()) } coAnswers {
            recordedKeys += secondArg<String>()
            recordedStates += thirdArg<MemoryConsolidationSliceState>()
        }
        coEvery { settingsStore.get("alice") } returns MemoryConsolidationUserSettings(
            userId = "alice",
            enabled = true,
            sliceStates = mapOf("global||experience_lessons" to previous)
        )
        return previous
    }

    private fun consolidationTask(dryRun: Boolean = false) = task(
        mapOf(
            MemoryConsolidationTaskHandler.KEY_SCOPE to "global",
            MemoryConsolidationTaskHandler.KEY_USER_ID to "alice",
            MemoryConsolidationTaskHandler.KEY_TYPE to "experience_lessons",
            MemoryConsolidationTaskHandler.KEY_DRY_RUN to dryRun
        )
    )

    private fun realTask(mergedWritten: Int, previousStreak: Int = 0): Pair<AsyncTask, MemoryConsolidationSliceState> {
        coEvery { agent.consolidate(any(), any(), any(), any(), any(), any()) } returns
            ConsolidationReport(
                type = MemoryType.EXPERIENCE_LESSONS,
                scope = MemoryScope.GLOBAL,
                activeBefore = 20,
                mergedWritten = mergedWritten,
                archived = if (mergedWritten > 0) 4 else 0
            )
        return consolidationTask() to givenBookkeeping(previousStreak)
    }

    /** Every state this test's handler tried to record, in order — a loop may record several. */
    private val recordedStates = mutableListOf<MemoryConsolidationSliceState>()

    private fun recorded(): MemoryConsolidationSliceState {
        assertTrue(recordedStates.isNotEmpty(), "no slice state was recorded")
        assertEquals("global||experience_lessons", recordedKeys.last())
        return recordedStates.last()
    }

    private fun task(payload: Map<String, Any?>, sessionId: String? = "session-1", retryCount: Int = 0) = AsyncTask(
        id = "task-1",
        userId = "alice",
        type = AsyncTaskType.MEMORY_CONSOLIDATION,
        trigger = TaskTrigger.SCHEDULE,
        title = "记忆整理",
        sessionId = sessionId,
        payload = payload,
        retryCount = retryCount
    )

    @Test
    fun `restores slice, owner, type and dryRun from payload and serializes the report`() = runTest {
        val scopeSlot = slot<MemoryScope>()
        val ownerSlot = slot<MemoryOwnerContext>()
        val typeSlot = slot<MemoryType>()
        val ownersSlot = slot<Collection<String>>()
        val dryRunSlot = slot<Boolean>()
        val report = ConsolidationReport(
            type = MemoryType.EXPERIENCE_LESSONS,
            scope = MemoryScope.PROJECT,
            activeBefore = 7,
            mergedWritten = 2,
            archived = 5,
            skippedSingletons = 2,
            skippedOverCap = 1,
            dryRun = true,
            sessionId = "session-1",
            plan = listOf(mapOf("title" to "cluster-a", "sources" to 3))
        )
        coEvery {
            agent.consolidate(
                capture(scopeSlot),
                capture(ownerSlot),
                capture(typeSlot),
                capture(ownersSlot),
                "session-1",
                capture(dryRunSlot)
            )
        } returns report

        val handler = MemoryConsolidationTaskHandler(agent, taskStore, titlePort)
        val outcome = handler.execute(
            task(
                mapOf(
                    MemoryConsolidationTaskHandler.KEY_SCOPE to "project",
                    MemoryConsolidationTaskHandler.KEY_USER_ID to "alice",
                    MemoryConsolidationTaskHandler.KEY_PROJECT_PATH to "/tmp/demo",
                    MemoryConsolidationTaskHandler.KEY_OWNERS to listOf("alice", "system"),
                    MemoryConsolidationTaskHandler.KEY_TYPE to "experience_lessons",
                    MemoryConsolidationTaskHandler.KEY_SESSION_ID to "session-1",
                    MemoryConsolidationTaskHandler.KEY_DRY_RUN to true
                )
            )
        )

        assertTrue(outcome is TaskOutcome.Success)
        val result = (outcome as TaskOutcome.Success).result
        assertEquals("experience_lessons", result["type"])
        assertEquals("PROJECT", result["scope"])
        assertEquals(7, result["activeBefore"])
        assertEquals(2, result["mergedWritten"])
        assertEquals(5, result["archived"])
        assertEquals(true, result["dryRun"])
        assertEquals("session-1", result["sessionId"])
        assertNull(result["message"], "the persisted result carries numbers only; the UI localizes them")

        assertEquals(MemoryScope.PROJECT, scopeSlot.captured)
        assertEquals("alice", ownerSlot.captured.userId)
        assertEquals(Path.of("/tmp/demo"), ownerSlot.captured.projectPath)
        assertEquals(MemoryType.EXPERIENCE_LESSONS, typeSlot.captured)
        assertEquals(listOf("alice", "system"), ownersSlot.captured)
        assertEquals(true, dryRunSlot.captured)
        coVerify { titlePort.rename("session-1", match { it.contains("experience_lessons") }, "alice") }
    }

    @Test
    fun `a retry does not rename the hidden session again`() = runTest {
        coEvery { agent.consolidate(any(), any(), any(), any(), any(), any()) } returns
            ConsolidationReport(type = MemoryType.USER_PREFERENCES, scope = MemoryScope.GLOBAL, activeBefore = 0)

        val handler = MemoryConsolidationTaskHandler(agent, taskStore, titlePort)
        handler.execute(
            task(
                mapOf(
                    MemoryConsolidationTaskHandler.KEY_SCOPE to "global",
                    MemoryConsolidationTaskHandler.KEY_USER_ID to "alice",
                    MemoryConsolidationTaskHandler.KEY_TYPE to "user_preferences"
                ),
                retryCount = 1
            )
        )

        coVerify(exactly = 0) { titlePort.rename(any(), any(), any()) }
    }

    @Test
    fun `a task without a session mints one and links it back to the task row`() = runTest {
        coEvery { agent.consolidate(any(), any(), any(), any(), any(), any()) } returns
            ConsolidationReport(type = MemoryType.USER_PREFERENCES, scope = MemoryScope.GLOBAL, activeBefore = 0)

        val handler = MemoryConsolidationTaskHandler(agent, taskStore, titlePort)
        val outcome = handler.execute(
            task(
                mapOf(
                    MemoryConsolidationTaskHandler.KEY_SCOPE to "global",
                    MemoryConsolidationTaskHandler.KEY_TYPE to "user_preferences"
                ),
                sessionId = null
            )
        )

        assertTrue(outcome is TaskOutcome.Success)
        coVerify { taskStore.updateSessionId("task-1", match { it.isNotBlank() }) }
    }

    @Test
    fun `an invalid scope in the payload fails the task`() = runTest {
        val handler = MemoryConsolidationTaskHandler(agent, taskStore, titlePort)
        val outcome = handler.execute(task(mapOf(MemoryConsolidationTaskHandler.KEY_SCOPE to "nonsense")))
        assertTrue(outcome is TaskOutcome.Failure)
    }

    @Test
    fun `an unknown memory type fails the task`() = runTest {
        val handler = MemoryConsolidationTaskHandler(agent, taskStore, titlePort)
        val outcome = handler.execute(
            task(
                mapOf(
                    MemoryConsolidationTaskHandler.KEY_SCOPE to "global",
                    MemoryConsolidationTaskHandler.KEY_TYPE to "not_a_type"
                )
            )
        )
        assertTrue(outcome is TaskOutcome.Failure)
    }

    /**
     * The scheduler can only tell "the store grew" from "we just curated it, and merged entries still
     * count" if a finished run leaves a record behind. These are those records.
     */
    @Nested
    inner class Suppression {

        @Test
        fun `a run that archived nothing starts the delay and absorbs its own output into the baseline`() = runTest {
            val (task, _) = realTask(mergedWritten = 0)

            recordingHandler().execute(task)

            val state = recorded()
            assertEquals(1, state.zeroGainStreak)
            assertEquals(ConsolidationOutcome.NOTHING_TO_MERGE, state.lastOutcome)
            assertEquals(MemoryIndexEntry.fingerprint(listOf("doc-1", "doc-2")), state.activeFingerprint,
                "the set is measured after the run, so a merge never re-dirties its own slice")
            val days = ((state.nextEligibleAt!! - state.lastRunAt!!) / MILLIS_PER_DAY)
            assertEquals(7L, days, "first zero-gain run is delayed a week")
        }

        @Test
        fun `the delay doubles per zero-gain run and stops at the cap`() = runTest {
            listOf(1 to 14L, 2 to 28L, 3 to 30L, 8 to 30L).forEach { (previousStreak, expectedDays) ->
                val (task, _) = realTask(mergedWritten = 0, previousStreak = previousStreak)

                recordingHandler().execute(task)

                val state = recorded()
                assertEquals(previousStreak + 1, state.zeroGainStreak)
                assertEquals(
                    expectedDays,
                    (state.nextEligibleAt!! - state.lastRunAt!!) / MILLIS_PER_DAY,
                    "streak $previousStreak should delay $expectedDays days"
                )
            }
        }

        @Test
        fun `a run that merged clears the delay`() = runTest {
            val (task, _) = realTask(mergedWritten = 1, previousStreak = 3)

            recordingHandler().execute(task)

            val state = recorded()
            assertEquals(0, state.zeroGainStreak)
            assertEquals(ConsolidationOutcome.PRODUCTIVE, state.lastOutcome)
            kotlin.test.assertNull(state.nextEligibleAt, "work landed, so the next scan is free to ask again")
        }

        @Test
        fun `a preview records nothing`() = runTest {
            coEvery { agent.consolidate(any(), any(), any(), any(), any(), any()) } returns
                ConsolidationReport(
                    type = MemoryType.EXPERIENCE_LESSONS, scope = MemoryScope.GLOBAL, activeBefore = 20, dryRun = true
                )

            recordingHandler().execute(
                task(
                    mapOf(
                        MemoryConsolidationTaskHandler.KEY_SCOPE to "global",
                        MemoryConsolidationTaskHandler.KEY_USER_ID to "alice",
                        MemoryConsolidationTaskHandler.KEY_TYPE to "experience_lessons",
                        MemoryConsolidationTaskHandler.KEY_DRY_RUN to true
                    )
                )
            )

            coVerify(exactly = 0) { settingsStore.recordSliceState(any(), any(), any()) }
        }

        @Test
        fun `bookkeeping never fails a run that succeeded`() = runTest {
            coEvery { agent.consolidate(any(), any(), any(), any(), any(), any()) } returns
                ConsolidationReport(type = MemoryType.EXPERIENCE_LESSONS, scope = MemoryScope.GLOBAL, activeBefore = 2)
            coEvery { memoryStore.listActiveIndex(any(), any(), any()) } throws IllegalStateException("backend down")

            val outcome = recordingHandler().execute(
                task(
                    mapOf(
                        MemoryConsolidationTaskHandler.KEY_SCOPE to "global",
                        MemoryConsolidationTaskHandler.KEY_USER_ID to "alice",
                        MemoryConsolidationTaskHandler.KEY_TYPE to "experience_lessons"
                    )
                )
            )

            assertTrue(outcome is TaskOutcome.Success)
            coVerify(exactly = 0) { settingsStore.recordSliceState(any(), any(), any()) }
        }

        /**
         * What [block] threw, or null when it returned. `kotlin.test.assertFailsWith` takes a non-suspend
         * block, so a suspend call cannot be wrapped in it.
         */
        private suspend fun thrownBy(block: suspend () -> Any?): Throwable? = try {
            block()
            null
        } catch (e: Throwable) {
            e
        }

        @Test
        fun `a run that threw records a failed outcome on the short delay and rethrows`() = runTest {
            givenBookkeeping(previousStreak = 0)
            val boom = IllegalStateException("Memory curation model is not configured")
            coEvery { agent.consolidate(any(), any(), any(), any(), any(), any()) } throws boom

            val thrown = thrownBy { recordingHandler().execute(consolidationTask()) }

            assertSame(boom, thrown, "the runner needs the real failure, not a bookkeeping error")
            val state = recorded()
            assertEquals(ConsolidationOutcome.FAILED, state.lastOutcome)
            assertEquals(1, state.zeroGainStreak)
            assertEquals(
                1L,
                (state.nextEligibleAt!! - state.lastRunAt!!) / MILLIS_PER_DAY,
                "a run that never finished left the fingerprint at its previous value, so without a delay " +
                    "the next scan reads it as changed and burns the whole budget again"
            )
        }

        @Test
        fun `the failure delay doubles on the same streak and stops at the same cap`() = runTest {
            listOf(1 to 2L, 2 to 4L, 4 to 16L, 5 to 30L, 9 to 30L).forEach { (previousStreak, expectedDays) ->
                givenBookkeeping(previousStreak = previousStreak)
                coEvery { agent.consolidate(any(), any(), any(), any(), any(), any()) } throws
                    IllegalStateException("consolidation timed out")

                thrownBy { recordingHandler().execute(consolidationTask()) }

                val state = recorded()
                assertEquals(previousStreak + 1, state.zeroGainStreak)
                assertEquals(
                    expectedDays,
                    (state.nextEligibleAt!! - state.lastRunAt!!) / MILLIS_PER_DAY,
                    "streak $previousStreak should delay $expectedDays days"
                )
            }
        }

        @Test
        fun `a run that lost every prompt to the transport is retried in a day, not a week`() = runTest {
            givenBookkeeping(previousStreak = 0)
            coEvery { agent.consolidate(any(), any(), any(), any(), any(), any()) } returns
                ConsolidationReport(
                    type = MemoryType.EXPERIENCE_LESSONS,
                    scope = MemoryScope.GLOBAL,
                    activeBefore = 20,
                    mergedWritten = 0,
                    llmFailures = 3,
                    llmFailureReason = "connection reset"
                )

            recordingHandler().execute(consolidationTask())

            val state = recorded()
            assertEquals(ConsolidationOutcome.FAILED, state.lastOutcome)
            assertEquals(
                1L,
                (state.nextEligibleAt!! - state.lastRunAt!!) / MILLIS_PER_DAY,
                "an outage says nothing about whether the slice is mergeable"
            )
        }

        @Test
        fun `a run whose answers were all unusable is a failure, not an empty slice`() = runTest {
            givenBookkeeping(previousStreak = 0)
            coEvery { agent.consolidate(any(), any(), any(), any(), any(), any()) } returns
                ConsolidationReport(
                    type = MemoryType.EXPERIENCE_LESSONS,
                    scope = MemoryScope.GLOBAL,
                    activeBefore = 20,
                    mergedWritten = 0,
                    rejectedBatches = 2
                )

            recordingHandler().execute(consolidationTask())

            val state = recorded()
            assertEquals(ConsolidationOutcome.FAILED, state.lastOutcome)
            assertEquals(1L, (state.nextEligibleAt!! - state.lastRunAt!!) / MILLIS_PER_DAY)
        }

        @Test
        fun `a run that merged despite losing a prompt is still productive`() = runTest {
            givenBookkeeping(previousStreak = 3)
            coEvery { agent.consolidate(any(), any(), any(), any(), any(), any()) } returns
                ConsolidationReport(
                    type = MemoryType.EXPERIENCE_LESSONS,
                    scope = MemoryScope.GLOBAL,
                    activeBefore = 20,
                    mergedWritten = 1,
                    archived = 2,
                    llmFailures = 1
                )

            recordingHandler().execute(consolidationTask())

            val state = recorded()
            assertEquals(ConsolidationOutcome.PRODUCTIVE, state.lastOutcome)
            assertEquals(0, state.zeroGainStreak, "work landed, so a lost prompt is not worth delaying over")
            assertNull(state.nextEligibleAt)
        }

        @Test
        fun `a cancelled run records nothing and travels on as a cancellation`() = runTest {
            givenBookkeeping()
            coEvery { agent.consolidate(any(), any(), any(), any(), any(), any()) } throws
                CancellationException("user cancelled")

            val thrown = thrownBy { recordingHandler().execute(consolidationTask()) }

            assertIs<CancellationException>(thrown)
            assertTrue(
                recordedStates.isEmpty(),
                "a cancel is not a verdict about the slice; the runner parks the row INTERRUPTED instead"
            )
        }

        @Test
        fun `a timed-out run records the failed verdict before propagating`() = runTest {
            givenBookkeeping()
            coEvery { agent.consolidate(any(), any(), any(), any(), any(), any()) } coAnswers {
                delay(10_000)
                ConsolidationReport(type = MemoryType.EXPERIENCE_LESSONS, scope = MemoryScope.GLOBAL, activeBefore = 0)
            }

            val thrown = thrownBy {
                withTimeout(50) { recordingHandler().execute(consolidationTask()) }
            }

            assertIs<TimeoutCancellationException>(thrown)
            assertEquals(
                ConsolidationOutcome.FAILED,
                recorded().lastOutcome,
                "a run that outlived its budget owes the same backoff a failed run gets"
            )
        }

        @Test
        fun `a preview that threw records nothing`() = runTest {
            givenBookkeeping()
            coEvery { agent.consolidate(any(), any(), any(), any(), any(), any()) } throws
                IllegalStateException("preview timed out")

            thrownBy { recordingHandler().execute(consolidationTask(dryRun = true)) }

            coVerify(exactly = 0) { settingsStore.recordSliceState(any(), any(), any()) }
        }

        @Test
        fun `bookkeeping that fails on the error path does not replace the original error`() = runTest {
            givenBookkeeping()
            coEvery { settingsStore.recordSliceState(any(), any(), any()) } throws IllegalStateException("db down")
            val boom = IllegalStateException("consolidation timed out")
            coEvery { agent.consolidate(any(), any(), any(), any(), any(), any()) } throws boom

            val thrown = thrownBy { recordingHandler().execute(consolidationTask()) }

            assertSame(boom, thrown, "the timeout is what the task row must report")
        }

        @Test
        fun `a failed run keeps the last verdict's baseline so the delay governs the retry`() = runTest {
            // Re-measuring the fingerprint after a failure would make it match on the next scan, and the
            // fingerprint gate — not the delay — would then decide the retry: the slice would sit until
            // new memories happened to arrive, instead of being retried once the transport recovered.
            givenBookkeeping(previousStreak = 1, previousFingerprint = 999)
            coEvery { agent.consolidate(any(), any(), any(), any(), any(), any()) } throws
                IllegalStateException("consolidation timed out")

            thrownBy { recordingHandler().execute(consolidationTask()) }

            val state = recorded()
            assertEquals(999, state.activeFingerprint, "the run disposed of nothing, so the material stays due")
            coVerify(exactly = 0) { memoryStore.listActiveIndex(any(), any(), any()) }
        }

        @Test
        fun `the result map carries the two-pass numbers`() = runTest {
            coEvery { agent.consolidate(any(), any(), any(), any(), any(), any()) } returns
                ConsolidationReport(
                    type = MemoryType.EXPERIENCE_LESSONS,
                    scope = MemoryScope.GLOBAL,
                    activeBefore = 20,
                    clustersProposed = 6,
                    mergedWritten = 4,
                    archived = 9,
                    droppedGroups = 1,
                    overTarget = 2
                )

            val outcome = MemoryConsolidationTaskHandler(agent, taskStore, titlePort).execute(
                task(
                    mapOf(
                        MemoryConsolidationTaskHandler.KEY_SCOPE to "global",
                        MemoryConsolidationTaskHandler.KEY_USER_ID to "alice",
                        MemoryConsolidationTaskHandler.KEY_TYPE to "experience_lessons"
                    )
                )
            )

            val result = (outcome as TaskOutcome.Success).result
            assertEquals(6, result["clustersProposed"])
            assertEquals(1, result["droppedGroups"])
            assertEquals(2, result["overTarget"])
        }
    }
}
