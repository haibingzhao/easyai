package com.easy.easyai.autoconfigure.r2dbc

import com.easy.easyai.core.model.ProjectKind
import com.easy.easyai.core.task.AsyncTask
import com.easy.easyai.core.task.AsyncTaskType
import com.easy.easyai.core.task.ScheduledTaskPlanContext
import com.easy.easyai.core.task.ScheduledTaskSetting
import com.easy.easyai.core.task.TaskTrigger
import com.easy.easyai.core.watch.Watch
import com.easy.easyai.core.watch.WatchClaimPolicy
import com.easy.easyai.core.watch.WatchMode
import com.easy.easyai.core.watch.WatchNotifyPolicy
import com.easy.easyai.core.watch.WatchPendingAction
import com.easy.easyai.core.watch.WatchProperties
import com.easy.easyai.core.watch.WatchRunKind
import com.easy.easyai.core.watch.WatchSchedule
import com.easy.easyai.core.watch.WatchScheduleKind
import com.easy.easyai.core.watch.WatchStatus
import com.easy.easyai.core.watch.WatchStore
import com.easy.easyai.core.watch.WatchTaskPayload
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The planner is the one scheduled type that writes while planning: a due row is claimed before its
 * task exists, which is what stops two ticks from enqueueing the same firing. These tests pin the
 * claim, the two skips (an unsettled run, a lost race) and the check-vs-review decision.
 */
class WatchTaskPlannerTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-25T04:00:00Z")
    private val store = mockk<WatchStore>(relaxed = true)

    private fun watch(
        id: String = "watch-1",
        nextDueAt: Instant = now.minusSeconds(30),
        schedule: WatchSchedule = WatchSchedule(WatchScheduleKind.DAILY, zone, localTime = LocalTime.of(9, 0)),
        mode: WatchMode = WatchMode.CHECK,
        untilAt: Instant? = null,
        pendingAction: WatchPendingAction = WatchPendingAction.CHECK,
        version: Int = 4
    ) = Watch(
        id = id,
        userId = "alice",
        title = "Price watch",
        condition = "Check the price",
        mode = mode,
        reminderText = null,
        schedule = schedule,
        untilAt = untilAt,
        nextDueAt = nextDueAt,
        status = WatchStatus.ACTIVE,
        pendingAction = pendingAction,
        version = version,
        extensionCount = 0,
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
        runSessionId = "run-session-1",
        allowedTools = emptyList(),
        sourceHints = null,
        createdAt = 0,
        updatedAt = 0
    )

    private fun planner(properties: WatchProperties = WatchProperties(), clock: () -> Instant = { now }) =
        WatchTaskPlanner(store, properties, clock)

    private fun context(pending: List<AsyncTask> = emptyList()) = ScheduledTaskPlanContext(
        userId = "alice",
        setting = ScheduledTaskSetting(userId = "alice", taskType = AsyncTaskType.WATCH, enabled = true),
        now = now.atZone(zone).toLocalDateTime(),
        pendingTasks = pending
    )

    private fun pendingTask(watchId: String) = AsyncTask(
        id = "pending-1",
        userId = "alice",
        type = AsyncTaskType.WATCH,
        trigger = TaskTrigger.SCHEDULE,
        title = "Watch",
        payload = mapOf(WatchTaskPayload.KEY_WATCH_ID to watchId)
    )

    @Nested
    inner class `due rows` {

        @Test
        fun `a disabled planner proposes nothing`() = runBlocking {
            coEvery { store.listDue(any(), any(), any()) } returns listOf(watch())
            val drafts = planner(WatchProperties(enabled = false)).plan(context())
            assertTrue(drafts.isEmpty())
            coVerify(exactly = 0) { store.listDue(any(), any(), any()) }
        }

        @Test
        fun `a due daily watch becomes a check with the claimed next occurrence`() = runBlocking {
            val watch = watch()
            coEvery { store.listDue("alice", now.toEpochMilli(), any()) } returns listOf(watch)
            coEvery { store.claim(any(), any(), any(), any(), any(), any()) } returns true

            val drafts = planner().plan(context())

            assertEquals(1, drafts.size)
            val draft = drafts.single()
            assertEquals(AsyncTaskType.WATCH, draft.type)
            assertEquals("watch-1", draft.payload[WatchTaskPayload.KEY_WATCH_ID])
            assertEquals(WatchRunKind.CHECK.name, draft.payload[WatchTaskPayload.KEY_KIND])
            assertEquals(watch.nextDueAt.toEpochMilli(), draft.payload[WatchTaskPayload.KEY_SCHEDULED_FOR])
            assertEquals(4, draft.payload[WatchTaskPayload.KEY_DEFINITION_VERSION])
            assertEquals("Price watch", draft.payload[WatchTaskPayload.KEY_TITLE])
            assertEquals("run-session-1", draft.sessionId)
            assertEquals(WatchProperties().runTimeoutMs, draft.timeoutMs)
            assertEquals("Watch · Price watch", draft.title)

            val expectedNext = WatchClaimPolicy.decide(watch, now, WatchProperties())!!.newNextDueAt.toEpochMilli()
            coVerify {
                store.claim(
                    id = "watch-1",
                    expectedNextDueAt = watch.nextDueAt.toEpochMilli(),
                    newNextDueAt = expectedNext,
                    pendingAction = WatchPendingAction.CHECK,
                    statusReason = null
                )
            }
        }

        @Test
        fun `a watch past its until date becomes an expiry review claim`() = runBlocking {
            val watch = watch(untilAt = now.minusSeconds(3_600))
            coEvery { store.listDue(any(), any(), any()) } returns listOf(watch)
            coEvery { store.claim(any(), any(), any(), any(), any(), any()) } returns true

            val draft = planner().plan(context()).single()

            assertEquals(WatchRunKind.EXPIRY_REVIEW.name, draft.payload[WatchTaskPayload.KEY_KIND])
            coVerify {
                store.claim(
                    id = "watch-1",
                    expectedNextDueAt = any(),
                    newNextDueAt = any(),
                    pendingAction = WatchPendingAction.EXPIRY_REVIEW,
                    statusReason = any()
                )
            }
        }

        @Test
        fun `an overdue reminder is still a normal handler run`() = runBlocking {
            val watch = watch(mode = WatchMode.REMIND)
            coEvery { store.listDue(any(), any(), any()) } returns listOf(watch)
            coEvery { store.claim(any(), any(), any(), any(), any(), any()) } returns true

            val draft = planner().plan(context()).single()

            assertEquals(WatchRunKind.CHECK.name, draft.payload[WatchTaskPayload.KEY_KIND])
        }

        @Test
        fun `never claims more firings than the runner will submit`() = runBlocking {
            val due = (1..25).map { index -> watch(id = "watch-$index") }
            coEvery { store.listDue(any(), any(), any()) } returns due
            coEvery { store.claim(any(), any(), any(), any(), any(), any()) } returns true

            val drafts = planner(WatchProperties(perUserPerTickLimit = 20)).plan(context())

            assertEquals(20, drafts.size)
            coVerify(exactly = 20) { store.claim(any(), any(), any(), any(), any(), any()) }
        }
    }

    @Nested
    inner class `skips` {

        @Test
        fun `a watch with an unsettled run is skipped for this tick`() = runBlocking {
            coEvery { store.listDue(any(), any(), any()) } returns listOf(watch())
            val drafts = planner().plan(context(pending = listOf(pendingTask("watch-1"))))
            assertTrue(drafts.isEmpty())
            coVerify(exactly = 0) { store.claim(any(), any(), any(), any(), any(), any()) }
        }

        @Test
        fun `a lost claim proposes nothing`() = runBlocking {
            coEvery { store.listDue(any(), any(), any()) } returns listOf(watch())
            coEvery { store.claim(any(), any(), any(), any(), any(), any()) } returns false
            assertTrue(planner().plan(context()).isEmpty())
        }

        @Test
        fun `claims are attempted beyond the submission cap so one loss does not starve the rest`() = runBlocking {
            val properties = WatchProperties(perUserPerTickLimit = 2)
            planner(properties).plan(context())
            coVerify {
                store.listDue("alice", now.toEpochMilli(), 2 * 4)
            }
        }
    }
}
