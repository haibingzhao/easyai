package com.easy.easyai.autoconfigure.r2dbc

import com.easy.easyai.core.host.HostIdentity
import com.easy.easyai.core.task.AsyncTask
import com.easy.easyai.core.task.AsyncTaskRunner
import com.easy.easyai.core.task.AsyncTaskStateChanged
import com.easy.easyai.core.task.AsyncTaskStatus
import com.easy.easyai.core.task.AsyncTaskType
import com.easy.easyai.core.task.ScheduledTaskSettingChanged
import com.easy.easyai.core.task.TaskTrigger
import com.easy.easyai.core.watch.WatchEnqueueResult
import com.easy.easyai.core.watch.WatchPendingAction
import com.easy.easyai.core.watch.WatchProperties
import com.easy.easyai.core.watch.WatchScheduleChanged
import com.easy.easyai.core.watch.WatchScheduleCursor
import com.easy.easyai.core.watch.WatchScheduleEntry
import com.easy.easyai.core.watch.WatchStore
import com.easy.easyai.core.watch.WatchTaskPayload
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Each watch owns a timer on its own `next_due_at`; preloading only arms them. These tests pin the
 * arming window, exact due firing, coalescing, the wake-ups (row change, terminal task, setting
 * change) and the per-user admission budget — none of which a minute scan can express.
 */
class WatchTaskSchedulerTest {

    private val base = Instant.parse("2026-10-25T04:00:00Z")
    private val host = HostIdentity(HOST)
    private val store = mockk<WatchStore>()
    private val runner = mockk<AsyncTaskRunner>()

    private val rows = linkedMapOf<String, WatchScheduleEntry>()
    private val enqueued = mutableListOf<WatchScheduleEntry>()
    private val dispatched = mutableListOf<AsyncTask>()
    private val pages = mutableListOf<Pair<Instant, WatchScheduleCursor?>>()
    private val scriptedResults = ArrayDeque<WatchEnqueueResult>()
    private var enqueueAttempts = 0

    init {
        coEvery { store.listUpcoming(any(), any(), any(), any()) } answers {
            val horizon = firstArg<Instant>()
            val cursor = secondArg<WatchScheduleCursor?>()
            val limit = thirdArg<Int>()
            val userId = arg<String?>(3)
            pages += horizon to cursor
            val result = rows.values
                .filter { it.nextDueAt <= horizon && (userId == null || it.userId == userId) }
                .sortedWith(compareBy({ it.nextDueAt }, { it.watchId }))
                .filter {
                    cursor == null || it.nextDueAt > cursor.nextDueAt ||
                        (it.nextDueAt == cursor.nextDueAt && it.watchId > cursor.watchId)
                }
                .take(limit)
            result
        }
        coEvery { store.findSchedule(any()) } answers { rows[firstArg<String>()] }
        coEvery { store.enqueueScheduled(any(), any(), any()) } answers {
            enqueueAttempts++
            val entry = firstArg<WatchScheduleEntry>()
            when (val scripted = scriptedResults.removeFirstOrNull()) {
                null -> accept(entry)
                else -> scripted
            }
        }
        coEvery { runner.awaitStartupRecovery() } returns Unit
        every { runner.dispatch(any()) } answers { dispatched += firstArg<AsyncTask>() }
    }

    private fun accept(entry: WatchScheduleEntry): WatchEnqueueResult.Enqueued {
        enqueued += entry
        rows[entry.watchId] = entry.copy(
            version = entry.version + 1,
            nextDueAt = entry.nextDueAt.plusSeconds(86_400)
        )
        return WatchEnqueueResult.Enqueued(taskFor(entry))
    }

    private fun entry(
        id: String = "watch-1",
        userId: String = "alice",
        due: Instant = base.minusSeconds(30),
        version: Int = 3,
        pendingAction: WatchPendingAction = WatchPendingAction.CHECK,
        ownerApp: String = HOST
    ) = WatchScheduleEntry(id, userId, ownerApp, version, due, pendingAction)

    private fun taskFor(entry: WatchScheduleEntry) = AsyncTask(
        id = "task-${entry.watchId}-${entry.version}",
        userId = entry.userId,
        type = AsyncTaskType.WATCH,
        trigger = TaskTrigger.SCHEDULE,
        title = "Watch · ${entry.watchId}",
        sessionId = "run-${entry.watchId}",
        ownerApp = entry.ownerApp,
        payload = mapOf(WatchTaskPayload.KEY_WATCH_ID to entry.watchId)
    )

    private fun scheduler(test: TestScope, properties: WatchProperties = WatchProperties()) =
        WatchTaskScheduler(
            store = store,
            runner = runner,
            host = host,
            properties = properties,
            clock = { base.plusMillis(test.testScheduler.currentTime) },
            scope = test.backgroundScope
        )

    @Nested
    inner class `arming` {

        @Test
        fun `an overdue watch is admitted on startup without waiting for a scan`() = runTest {
            rows["watch-1"] = entry()

            scheduler(this).start()
            runCurrent()

            assertEquals(1, enqueued.size)
            assertEquals(base.minusSeconds(30), enqueued.single().nextDueAt)
            assertEquals(1, dispatched.size)
            assertEquals("watch-1", WatchTaskPayload.watchIdOf(dispatched.single()))
        }

        @Test
        fun `a watch inside the horizon fires exactly at its due instant`() = runTest {
            rows["watch-1"] = entry(due = base.plusSeconds(37 * 60))

            scheduler(this).start()
            runCurrent()
            assertTrue(enqueued.isEmpty())

            advanceTimeBy(37 * 60_000L - 1)
            assertTrue(enqueued.isEmpty())

            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, enqueued.size)
            assertEquals(base.plusSeconds(37 * 60), enqueued.single().nextDueAt)
        }

        @Test
        fun `a watch beyond the horizon waits for the next preload`() = runTest {
            rows["watch-1"] = entry(due = base.plusSeconds(3_600 + 1))

            val scheduler = scheduler(this)
            scheduler.start()
            runCurrent()
            assertTrue(enqueued.isEmpty())

            advanceTimeBy(3_600_000)
            assertTrue(enqueued.isEmpty(), "no timer exists for a due beyond the arming window")

            scheduler.preload()
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(1, enqueued.size)
            assertEquals(1, dispatched.size)
        }

        @Test
        fun `repeated preloads do not execute the same firing twice`() = runTest {
            rows["watch-1"] = entry(due = base.plusSeconds(10 * 60))

            val scheduler = scheduler(this)
            scheduler.start()
            scheduler.preload()
            scheduler.preload()
            runCurrent()
            assertTrue(enqueued.isEmpty())

            advanceTimeBy(10 * 60_000)
            runCurrent()
            assertEquals(1, enqueued.size)
            assertEquals(1, enqueueAttempts)
        }

        @Test
        fun `overdue rows are paged with a keyset cursor instead of a single capped query`() = runTest {
            repeat(201) { index ->
                rows["watch-$index"] = entry(id = "watch-$index", userId = "user-$index")
            }

            scheduler(this).start()
            runCurrent()

            assertEquals(2, pages.size)
            assertEquals(201, enqueued.size)
            assertEquals(201, dispatched.size)
        }

        @Test
        fun `a watch owned by another application is never armed`() = runTest {
            rows["watch-1"] = entry(due = base.minusSeconds(30), ownerApp = "other-app")

            scheduler(this).start()
            runCurrent()
            assertTrue(enqueued.isEmpty())
            assertEquals(0, enqueueAttempts)
        }
    }

    @Nested
    inner class `wake-ups` {

        @Test
        fun `a definition change moves the waiting timer to the new due instant`() = runTest {
            rows["watch-1"] = entry(due = base.plusSeconds(50 * 60))

            val scheduler = scheduler(this)
            scheduler.start()
            runCurrent()
            advanceTimeBy(60_000)

            rows["watch-1"] = entry(due = base.plusSeconds(60), version = 4)
            scheduler.onWatchChanged(WatchScheduleChanged(HOST, "watch-1", "alice"))
            runCurrent()

            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, enqueued.size)
            assertEquals(4, enqueued.single().version)
            assertEquals(base.plusSeconds(60), enqueued.single().nextDueAt)
            assertEquals(1, enqueueAttempts)
        }

        @Test
        fun `a schedule moved beyond the horizon retires the old timer`() = runTest {
            rows["watch-1"] = entry(due = base.plusSeconds(50 * 60))

            val scheduler = scheduler(this)
            scheduler.start()
            runCurrent()

            rows["watch-1"] = entry(due = base.plusSeconds(3 * 3_600), version = 5)
            scheduler.onWatchChanged(WatchScheduleChanged(HOST, "watch-1", "alice"))
            runCurrent()

            advanceTimeBy(2 * 3_600_000L)
            runCurrent()
            assertTrue(enqueued.isEmpty())
        }

        @Test
        fun `a busy admission retries as soon as the running task reaches a terminal state`() = runTest {
            rows["watch-1"] = entry()
            scriptedResults += WatchEnqueueResult.Busy("task-running")

            val scheduler = scheduler(this)
            scheduler.start()
            runCurrent()
            assertTrue(enqueued.isEmpty())
            assertEquals(1, enqueueAttempts)

            // The watch write-back lands before the task row settles; only the terminal signal may
            // release the waiting timer, not the unchanged definition snapshot.
            scheduler.onTaskChanged(
                AsyncTaskStateChanged(taskFor(entry()).copy(status = AsyncTaskStatus.COMPLETED))
            )
            runCurrent()

            assertEquals(1, enqueued.size)
            assertEquals(2, enqueueAttempts)
        }

        @Test
        fun `an unrelated task or another host's watch event wakes nothing`() = runTest {
            rows["watch-1"] = entry(due = base.plusSeconds(50 * 60))

            val scheduler = scheduler(this)
            scheduler.start()
            runCurrent()

            scheduler.onTaskChanged(
                AsyncTaskStateChanged(
                    taskFor(entry()).copy(id = "other", type = AsyncTaskType.MEMORY_CONSOLIDATION)
                )
            )
            scheduler.onWatchChanged(WatchScheduleChanged("other-app", "watch-1", "alice"))
            runCurrent()

            advanceTimeBy(50 * 60_000L)
            runCurrent()
            assertEquals(1, enqueued.size, "the timer still fired at its own due instant")
            assertEquals(1, enqueueAttempts)
        }

        @Test
        fun `enabling the watch setting loads that user's watches without waiting for preload`() = runTest {
            val scheduler = scheduler(this)
            scheduler.start()
            runCurrent()

            rows["watch-1"] = entry(due = base.plusSeconds(5 * 60))
            scheduler.onSettingChanged(ScheduledTaskSettingChanged("alice", AsyncTaskType.WATCH))
            runCurrent()

            advanceTimeBy(5 * 60_000L)
            runCurrent()
            assertEquals(1, enqueued.size)
        }

        @Test
        fun `destroy cancels waiting timers without touching the persisted deadline`() = runTest {
            rows["watch-1"] = entry(due = base.plusSeconds(5 * 60))

            val scheduler = scheduler(this)
            scheduler.start()
            runCurrent()
            scheduler.destroy()

            advanceTimeBy(10 * 60_000L)
            runCurrent()
            assertTrue(enqueued.isEmpty())
            assertEquals(base.plusSeconds(5 * 60), rows["watch-1"]?.nextDueAt)
        }
    }

    @Nested
    inner class `admission limits` {

        @Test
        fun `automatic admissions are capped per user per minute and resume on the next minute`() = runTest {
            repeat(21) { index ->
                rows["watch-$index"] = entry(id = "watch-$index", userId = "alice")
            }

            scheduler(this).start()
            runCurrent()
            assertEquals(20, enqueued.size)
            assertEquals(20, enqueueAttempts)

            advanceTimeBy(59_999)
            runCurrent()
            assertEquals(20, enqueued.size)

            advanceTimeBy(1)
            runCurrent()
            assertEquals(21, enqueued.size)
        }

        @Test
        fun `a stale admission refreshes instead of spinning on the same snapshot`() = runTest {
            rows["watch-1"] = entry()
            scriptedResults += WatchEnqueueResult.Stale

            scheduler(this).start()
            runCurrent()
            assertEquals(1, enqueueAttempts)
            assertTrue(enqueued.isEmpty())

            advanceTimeBy(59_999)
            runCurrent()
            assertEquals(1, enqueueAttempts)

            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, enqueueAttempts)
            assertEquals(1, enqueued.size)
        }
    }

    private companion object {
        const val HOST = "agent-api"
    }
}
