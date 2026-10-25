package com.easy.easyai.autoconfigure.r2dbc

import com.easy.easyai.core.task.AsyncTask
import com.easy.easyai.core.task.AsyncTaskRunner
import com.easy.easyai.core.task.AsyncTaskStatus
import com.easy.easyai.core.task.AsyncTaskStore
import com.easy.easyai.core.task.AsyncTaskType
import com.easy.easyai.core.task.ScheduledTaskPlanContext
import com.easy.easyai.core.task.ScheduledTaskPlanner
import com.easy.easyai.core.task.ScheduledTaskSetting
import com.easy.easyai.core.task.ScheduledTaskSettingStore
import com.easy.easyai.core.task.TaskDraft
import com.easy.easyai.core.task.TaskTrigger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Stand-in draft; file-level so the probe below can use it as a default argument. */
private fun draftFor(user: String, index: Int = 0) = TaskDraft(
    userId = user,
    type = AsyncTaskType.MEMORY_CONSOLIDATION,
    title = "",
    sessionId = "session-$user-$index",
    payload = mapOf("scope" to "global", "type" to "experience_lessons")
)

/**
 * Tests the generic scan: cron due-ness and its fallback, dispatch to the right planner, the per-user
 * cap, duplicate-suppression input, and that one failing user cannot cost the others their tick.
 */
class ScheduledTaskRunnerTest {

    private val settingStore = mockk<ScheduledTaskSettingStore>()
    private val taskStore = mockk<AsyncTaskStore>()
    private val taskRunner = mockk<AsyncTaskRunner>()

    private val threeAm = LocalDateTime.of(2026, 10, 17, 3, 0)

    /** Stands in for a real planner: records every context it was handed, proposes whatever it is told. */
    private class Probe(
        override val defaultCron: String? = "0 0 3 * * *",
        override val perUserPerTickLimit: Int = 5,
        val onPlan: (ScheduledTaskPlanContext) -> List<TaskDraft> = { listOf(draftFor(it.userId)) }
    ) : ScheduledTaskPlanner {
        override val type: AsyncTaskType = AsyncTaskType.MEMORY_CONSOLIDATION
        val seen = mutableListOf<ScheduledTaskPlanContext>()

        override suspend fun plan(context: ScheduledTaskPlanContext): List<TaskDraft> {
            seen += context
            return onPlan(context)
        }
    }

    private fun setting(
        userId: String = "alice",
        cron: String? = "0 0 3 * * *",
        enabled: Boolean = true,
        timeoutMs: Long? = null
    ) = ScheduledTaskSetting(
        userId = userId,
        taskType = AsyncTaskType.MEMORY_CONSOLIDATION,
        enabled = enabled,
        cron = cron,
        params = mapOf("typeThresholds" to mapOf("experience_lessons" to 2)),
        timeoutMs = timeoutMs
    )

    private fun runner(planners: List<ScheduledTaskPlanner>) = ScheduledTaskRunner(
        settingStore = settingStore,
        taskStore = taskStore,
        taskRunner = taskRunner,
        planners = planners,
        clock = { threeAm }
    )

    private fun givenSettings(vararg settings: ScheduledTaskSetting) {
        coEvery { settingStore.listEnabled(any()) } returns settings.toList()
        coEvery { taskStore.listNonTerminal(any(), any(), any()) } returns emptyList()
        coEvery { taskRunner.submit(any()) } answers { firstArg() }
    }

    @Test
    fun `a due row is planned and submitted as a schedule-triggered task`() = runBlocking {
        givenSettings(setting())
        val submitted = slot<AsyncTask>()
        coEvery { taskRunner.submit(capture(submitted)) } answers { submitted.captured }

        runner(listOf(Probe())).scanOnce()

        val task = submitted.captured
        assertEquals(TaskTrigger.SCHEDULE, task.trigger)
        assertEquals(AsyncTaskType.MEMORY_CONSOLIDATION, task.type)
        assertEquals("alice", task.userId)
        assertNotNull(task.id, "the driver must mint the id a planner leaves blank")
        assertEquals("session-alice-0", task.sessionId)
    }

    @Test
    fun `a draft timeout is carried onto the submitted task`() = runBlocking {
        givenSettings(setting())
        val submitted = slot<AsyncTask>()
        coEvery { taskRunner.submit(capture(submitted)) } answers { submitted.captured }
        val probe = Probe(onPlan = { ctx -> listOf(draftFor(ctx.userId).copy(timeoutMs = 120_000)) })

        runner(listOf(probe)).scanOnce()

        assertEquals(120_000, submitted.captured.timeoutMs)
    }

    @Test
    fun `a setting timeout overrides the draft timeout`() = runBlocking {
        givenSettings(setting(timeoutMs = 60_000))
        val submitted = slot<AsyncTask>()
        coEvery { taskRunner.submit(capture(submitted)) } answers { submitted.captured }
        val probe = Probe(onPlan = { ctx -> listOf(draftFor(ctx.userId).copy(timeoutMs = 120_000)) })

        runner(listOf(probe)).scanOnce()

        assertEquals(60_000, submitted.captured.timeoutMs)
    }

    @Test
    fun `ids differ across drafts so no submission can overwrite another`() = runBlocking {
        givenSettings(setting())
        val probe = Probe(onPlan = { ctx -> listOf(draftFor(ctx.userId, 1), draftFor(ctx.userId, 2)) })
        val ids = mutableListOf<String>()
        coEvery { taskRunner.submit(any()) } answers { firstArg<AsyncTask>().also { ids += it.id } }

        runner(listOf(probe)).scanOnce()

        assertEquals(2, ids.size)
        assertEquals(2, ids.toSet().size, "minted ids must be unique")
    }

    @Test
    fun `a row whose cron has not fired since the previous scan is never planned`() = runBlocking {
        givenSettings(setting(cron = "0 30 4 * * *"))
        val probe = Probe()

        runner(listOf(probe)).scanOnce()

        assertTrue(probe.seen.isEmpty(), "a not-yet-due row must not even reach the planner")
        coVerify(exactly = 0) { taskRunner.submit(any()) }
    }

    @Test
    fun `a scan period longer than a minute still catches the fires inside it`() = runBlocking {
        givenSettings(setting(cron = "0 30 3 * * *"))
        val probe = Probe()
        val scheduledRunner = runner(listOf(probe))

        scheduledRunner.scanOnce(LocalDateTime.of(2026, 10, 17, 3, 0))
        coVerify(exactly = 0) { taskRunner.submit(any()) }

        // A 30-minute scan period: the 03:30 fire lands inside this scan's window, not inside a
        // hardcoded minute, otherwise any cron misaligned with the tick would never fire.
        scheduledRunner.scanOnce(LocalDateTime.of(2026, 10, 17, 3, 30))

        coVerify(exactly = 1) { taskRunner.submit(any()) }
    }

    @Test
    fun `one cron fire makes exactly one scan due`() = runBlocking {
        givenSettings(setting(cron = "0 30 3 * * *"))
        val scheduledRunner = runner(listOf(Probe()))

        scheduledRunner.scanOnce(LocalDateTime.of(2026, 10, 17, 3, 30))
        scheduledRunner.scanOnce(LocalDateTime.of(2026, 10, 17, 4, 0))

        coVerify(exactly = 1) { taskRunner.submit(any()) }
    }

    @Test
    fun `a row without a cron follows the planner default`() = runBlocking {
        givenSettings(setting(cron = null))

        runner(listOf(Probe())).scanOnce()

        coVerify(exactly = 1) { taskRunner.submit(any()) }
    }

    @Test
    fun `an invalid per-user cron falls back to the planner default`() = runBlocking {
        givenSettings(setting(cron = "not a cron"))

        runner(listOf(Probe())).scanOnce()

        // The default is 03:00 and the clock reads 03:00, so the fallback makes this row due.
        coVerify(exactly = 1) { taskRunner.submit(any()) }
    }

    @Test
    fun `a row with no planner registered is skipped`() = runBlocking {
        givenSettings(setting())

        runner(emptyList()).scanOnce()

        coVerify(exactly = 0) { taskRunner.submit(any()) }
    }

    @Test
    fun `drafts past the planner cap are dropped for that user only`() = runBlocking {
        givenSettings(setting(userId = "alice"), setting(userId = "bob"))
        val probe = Probe(perUserPerTickLimit = 1, onPlan = { ctx ->
            listOf(draftFor(ctx.userId, 1), draftFor(ctx.userId, 2), draftFor(ctx.userId, 3))
        })

        runner(listOf(probe)).scanOnce()

        coVerify(exactly = 2) { taskRunner.submit(any()) }
    }

    @Test
    fun `pending tasks reach the planner for duplicate suppression`() = runBlocking {
        val existing = AsyncTask(
            id = "existing",
            userId = "alice",
            type = AsyncTaskType.MEMORY_CONSOLIDATION,
            trigger = TaskTrigger.FRONTEND,
            title = "running",
            status = AsyncTaskStatus.RUNNING,
            sessionId = "session-running"
        )
        givenSettings(setting())
        coEvery { taskStore.listNonTerminal("alice", any(), any()) } returns listOf(existing)
        val probe = Probe()

        runner(listOf(probe)).scanOnce()

        assertEquals(listOf(existing), probe.seen.single().pendingTasks)
    }

    @Test
    fun `a planner that throws costs only its own user the tick`() = runBlocking {
        givenSettings(setting(userId = "alice"), setting(userId = "bob"))
        val probe = Probe(onPlan = { ctx ->
            if (ctx.userId == "alice") error("slice query blew up") else listOf(draftFor(ctx.userId))
        })

        runner(listOf(probe)).scanOnce()

        coVerify(exactly = 1) { taskRunner.submit(match { it.userId == "bob" }) }
    }

    @Test
    fun `no setting store means the scan does nothing`() = runBlocking {
        val probe = Probe()

        ScheduledTaskRunner(null, taskStore, taskRunner, listOf(probe), { threeAm }).scanOnce()

        assertTrue(probe.seen.isEmpty())
        coVerify(exactly = 0) { taskRunner.submit(any()) }
    }

    @Test
    fun `a draft with nowhere to go is dropped without failing the scan`() = runBlocking {
        givenSettings(setting())
        val probe = Probe()

        ScheduledTaskRunner(settingStore, taskStore, null, listOf(probe), { threeAm }).scanOnce()

        // The planner still ran; only submission was lost, and the scan returned instead of throwing.
        assertEquals(1, probe.seen.size)
        coVerify(exactly = 0) { taskRunner.submit(any()) }
    }
}
