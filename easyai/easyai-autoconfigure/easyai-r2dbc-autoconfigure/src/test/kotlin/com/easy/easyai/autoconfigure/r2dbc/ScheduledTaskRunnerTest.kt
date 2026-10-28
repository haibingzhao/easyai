package com.easy.easyai.autoconfigure.r2dbc

import com.easy.easyai.core.task.AsyncTask
import com.easy.easyai.core.task.AsyncTaskRunner
import com.easy.easyai.core.task.AsyncTaskStatus
import com.easy.easyai.core.task.AsyncTaskStore
import com.easy.easyai.core.task.AsyncTaskType
import com.easy.easyai.core.task.ScheduledTaskPlanContext
import com.easy.easyai.core.task.ScheduledTaskPlanner
import com.easy.easyai.core.task.ScheduledTaskSetting
import com.easy.easyai.core.task.ScheduledTaskSettingChanged
import com.easy.easyai.core.task.ScheduledTaskSettingStore
import com.easy.easyai.core.task.TaskDraft
import com.easy.easyai.core.task.TaskTrigger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.scheduling.annotation.Scheduled
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class ScheduledTaskRunnerTest {

    private val base = Instant.parse("2026-10-17T10:00:00Z")
    private val store = ScheduleStore()
    private val taskStore = mockk<AsyncTaskStore>()
    private val taskRunner = mockk<AsyncTaskRunner>()
    private val dispatched = mutableListOf<AsyncTask>()
    private val submitted = mutableListOf<AsyncTask>()

    init {
        coEvery { taskStore.listNonTerminal(any(), any(), any()) } returns emptyList()
        coEvery { taskRunner.awaitStartupRecovery() } returns Unit
        coEvery { taskRunner.submit(any()) } answers { firstArg<AsyncTask>().also { submitted += it } }
        every { taskRunner.dispatch(any()) } answers { dispatched += firstArg<AsyncTask>() }
    }

    private fun setting(
        userId: String = "alice",
        due: Instant = Instant.parse("2026-10-17T03:00:00Z")
    ) = ScheduledTaskSetting(
        userId = userId,
        taskType = AsyncTaskType.MEMORY_CONSOLIDATION,
        enabled = true,
        cron = "0 0 3 * * *",
        updatedAt = base.minusSeconds(3_600).toEpochMilli(),
        nextFireAt = due.toEpochMilli(),
        scheduleRevision = "revision-$userId",
        scheduleZone = "UTC"
    )

    private fun runner(
        test: TestScope,
        vararg planners: ScheduledTaskPlanner,
        startAt: Instant = base,
        clock: () -> Instant = { startAt.plusMillis(test.testScheduler.currentTime) }
    ) = ScheduledTaskRunner(store, taskStore, taskRunner, planners.toList(), clock, test.backgroundScope)

    private open class Probe(
        override val type: AsyncTaskType = AsyncTaskType.MEMORY_CONSOLIDATION,
        override val defaultCron: String? = "0 0 3 * * *",
        override val perUserPerTickLimit: Int = 5,
        private val onPlan: suspend (ScheduledTaskPlanContext) -> List<TaskDraft> = {
            listOf(TaskDraft(it.userId, it.setting.taskType, "planned", sessionId = "session-${it.userId}"))
        }
    ) : ScheduledTaskPlanner {
        val seen = mutableListOf<ScheduledTaskPlanContext>()

        override suspend fun plan(context: ScheduledTaskPlanContext): List<TaskDraft> {
            seen += context
            return onPlan(context)
        }
    }

    @Nested
    inner class Startup {

        @Test
        fun `startup immediately catches an overdue fire and repeated start does nothing`() = runTest {
            val row = setting()
            store.save(row)
            val probe = Probe()
            val scheduler = runner(this, probe)

            scheduler.start()
            runCurrent()
            scheduler.start()
            runCurrent()

            assertEquals(1, probe.seen.size)
            assertEquals(base, probe.seen.single().now)
            assertEquals(row.nextFireAt, dispatched.single().payload["scheduledFor"])
            assertEquals(Instant.parse("2026-10-18T03:00:00Z").toEpochMilli(), store.row().nextFireAt)
            assertEquals(1, store.queries.size)
        }

        @Test
        fun `several missed days coalesce into one plan rather than replaying each day`() = runTest {
            store.save(setting(due = base.minusSeconds(5 * 86_400)))
            val probe = Probe()
            val scheduler = runner(this, probe)

            scheduler.start()
            runCurrent()
            scheduler.preload()
            runCurrent()

            assertEquals(1, probe.seen.size)
            assertEquals(1, dispatched.size)
            assertEquals(Instant.parse("2026-10-18T03:00:00Z").toEpochMilli(), store.row().nextFireAt)
        }

        @Test
        fun `a successful firing is not replayed by a restarted runner`() {
            val probe = Probe()
            runTest {
                store.save(setting())
                val scheduler = runner(this, probe)
                scheduler.start()
                runCurrent()
                scheduler.destroy()
            }
            runTest {
                runner(this, probe, startAt = base.plusSeconds(60)).start()
                runCurrent()
                assertEquals(1, probe.seen.size)
                assertEquals(1, store.batches.size)
                assertEquals(1, dispatched.size)
            }
        }

        @Test
        fun `legacy uninitialized settings get one immediate catchup and a persisted zone`() = runTest {
            store.save(setting().copy(nextFireAt = null, scheduleRevision = null, scheduleZone = null))
            val probe = Probe()
            val scheduler = runner(this, probe)

            scheduler.start()
            runCurrent()
            scheduler.preload()
            runCurrent()

            assertEquals(base.toEpochMilli(), store.initialized.single().nextFireAt)
            assertEquals(ZoneId.systemDefault().id, store.row().scheduleZone)
            assertNotNull(store.row().scheduleRevision)
            assertTrue(assertNotNull(store.row().nextFireAt) > base.toEpochMilli())
            assertEquals(base.toEpochMilli(), dispatched.single().payload["scheduledFor"])
            assertEquals(1, probe.seen.size)
        }

        @Test
        fun `an explicit schedule zone governs initialization`() = runTest {
            store.save(setting().copy(nextFireAt = null, scheduleZone = null))
            val probe = Probe()
            val scheduler = ScheduledTaskRunner(
                store, taskStore, taskRunner, listOf(probe),
                { base.plusMillis(testScheduler.currentTime) }, backgroundScope,
                ZoneId.of("Asia/Shanghai")
            )

            scheduler.start()
            runCurrent()

            assertEquals("Asia/Shanghai", store.row().scheduleZone)
            // 09:00Z is 17:00 in Shanghai; the next 03:00 local fire is 19:00Z the same day.
            assertEquals(Instant.parse("2026-10-17T19:00:00Z").toEpochMilli(), store.row().nextFireAt)
        }

        @Test
        fun `fresh enable starts after updatedAt and never replays an earlier daily cron`() = runTest {
            val zone = ZoneId.systemDefault()
            val enabledAt = LocalDateTime.of(2026, 10, 17, 9, 0).atZone(zone).toInstant()
            store.save(setting().copy(
                updatedAt = enabledAt.toEpochMilli(), nextFireAt = null, scheduleZone = null
            ))
            val probe = Probe()

            runner(this, probe, startAt = enabledAt.plusSeconds(3_600)).start()
            runCurrent()

            val next = LocalDateTime.of(2026, 10, 18, 3, 0).atZone(zone).toInstant().toEpochMilli()
            assertEquals(next, store.row().nextFireAt)
            assertEquals(zone.id, store.row().scheduleZone)
            assertTrue(probe.seen.isEmpty())
            assertTrue(store.batches.isEmpty())
        }

        @Test
        fun `application readiness waits for recovery before enabling any scheduler work`() = runTest {
            store.save(setting())
            val recovered = CompletableDeferred<Unit>()
            coEvery { taskRunner.awaitStartupRecovery() } coAnswers { recovered.await() }
            val probe = Probe()
            val scheduler = runner(this, probe)

            scheduler.onApplicationReady()
            runCurrent()
            scheduler.tick()
            scheduler.preloadTick()
            runCurrent()
            coVerify(exactly = 1) { taskRunner.awaitStartupRecovery() }
            assertTrue(store.queries.isEmpty())
            assertTrue(probe.seen.isEmpty())

            recovered.complete(Unit)
            runCurrent()
            assertEquals(1, store.queries.size)
            assertEquals(1, dispatched.size)
        }
    }

    @Nested
    inner class Timers {

        @Test
        fun `a preloaded future fire runs at its exact due time without another preload`() = runTest {
            val due = base.plusSeconds(37 * 60)
            store.save(setting(due = due))
            val probe = Probe()
            runner(this, probe).start()
            runCurrent()
            val readsAfterPreload = store.reads

            advanceTimeBy(37 * 60_000L - 1)
            runCurrent()
            assertTrue(probe.seen.isEmpty())
            assertEquals(readsAfterPreload, store.reads)
            advanceTimeBy(1)
            runCurrent()

            assertEquals(due, probe.seen.single().now)
            assertEquals(due.toEpochMilli(), dispatched.single().payload["scheduledFor"])
            assertEquals(1, store.queries.size)
        }

        @Test
        fun `the next fire inside the horizon is armed without waiting for preload`() = runTest {
            store.save(setting(due = base.plusSeconds(60)).copy(cron = "0 * * * * *"))
            val probe = Probe()
            runner(this, probe).start()
            runCurrent()

            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, dispatched.size)
            advanceTimeBy(60_000)
            runCurrent()

            assertEquals(2, dispatched.size)
            assertEquals(base.plusSeconds(180).toEpochMilli(), store.row().nextFireAt)
            assertEquals(1, store.queries.size)
        }

        @Test
        fun `overlapping preloads page all users and include the horizon boundary only once`() = runTest {
            repeat(201) { store.save(setting("user-${it.toString().padStart(3, '0')}", base.plusSeconds(3_600))) }
            store.save(setting("outside", base.plusMillis(3_600_001)))
            store.save(setting("disabled", base).copy(enabled = false))
            val probe = Probe()
            val scheduler = runner(this, probe)

            scheduler.start()
            scheduler.preload()
            runCurrent()
            assertEquals(listOf(null, "user-199", null, "user-199"), store.queries.map { it.afterUserId })
            assertTrue(store.queries.all { it.horizon == base.plusSeconds(3_600).toEpochMilli() && it.limit == 200 })
            assertTrue(store.batches.isEmpty())
            advanceTimeBy(3_600_000)
            runCurrent()

            assertEquals(201, probe.seen.size)
            assertEquals(201, dispatched.map { it.userId }.toSet().size)
            assertEquals(201, dispatched.size)
            assertEquals(base.plusMillis(3_600_001).toEpochMilli(), store.row("outside").nextFireAt)
        }

        @Test
        fun `firing rereads params and timeout even when the schedule revision is unchanged`() = runTest {
            val row = setting(due = base.plusSeconds(60))
            store.save(row)
            val probe = Probe(onPlan = {
                listOf(TaskDraft(it.userId, it.setting.taskType, "updated", payload = it.setting.params))
            })
            runner(this, probe).start()
            runCurrent()
            val updated = row.copy(params = mapOf("threshold" to 9), timeoutMs = 45_000, updatedAt = base.toEpochMilli())
            store.save(updated)

            advanceTimeBy(60_000)
            runCurrent()

            assertEquals(updated, probe.seen.single().setting)
            assertEquals(9, dispatched.single().payload["threshold"])
            assertEquals(45_000L, dispatched.single().timeoutMs)
        }

        @Test
        fun `a setting change replaces the old timer with the new revision and due time`() = runTest {
            val row = setting(due = base.plusSeconds(60))
            store.save(row)
            val probe = Probe()
            val scheduler = runner(this, probe)
            scheduler.start()
            runCurrent()
            store.save(row.copy(scheduleRevision = "edited", nextFireAt = base.plusSeconds(120).toEpochMilli()))
            scheduler.onSettingChanged(ScheduledTaskSettingChanged(row.userId, row.taskType))
            runCurrent()

            advanceTimeBy(60_000)
            runCurrent()
            assertTrue(probe.seen.isEmpty())
            advanceTimeBy(60_000)
            runCurrent()

            assertEquals("edited", probe.seen.single().setting.scheduleRevision)
            assertEquals(base.plusSeconds(120).toEpochMilli(), dispatched.single().payload["scheduledFor"])
        }

        @Test
        fun `disable and delete events cancel their outstanding timers`() = runTest {
            val alice = setting(due = base.plusSeconds(60))
            val bob = setting("bob", base.plusSeconds(60))
            store.save(alice)
            store.save(bob)
            val probe = Probe()
            val scheduler = runner(this, probe)
            scheduler.start()
            runCurrent()

            store.save(alice.copy(enabled = false, scheduleRevision = "disabled"))
            store.delete(bob.userId, bob.taskType)
            scheduler.onSettingChanged(ScheduledTaskSettingChanged(alice.userId, alice.taskType))
            scheduler.onSettingChanged(ScheduledTaskSettingChanged(bob.userId, bob.taskType))
            runCurrent()
            val readsAfterRefresh = store.reads
            advanceTimeBy(120_000)
            runCurrent()

            assertEquals(readsAfterRefresh, store.reads)
            assertTrue(probe.seen.isEmpty())
            assertTrue(store.batches.isEmpty())
        }

        @Test
        fun `a remote revision change is rejected at firing even without a local event`() = runTest {
            val row = setting(due = base.plusSeconds(60))
            store.save(row)
            val probe = Probe()
            runner(this, probe).start()
            runCurrent()
            val remote = row.copy(scheduleRevision = "remote", nextFireAt = base.plusSeconds(7_200).toEpochMilli())
            store.save(remote)

            advanceTimeBy(60_000)
            runCurrent()

            assertTrue(probe.seen.isEmpty())
            assertTrue(store.batches.isEmpty())
            assertEquals(remote, store.row())
        }

        @Test
        fun `a wall clock jump is noticed on the next minute without querying schedules again`() = runTest {
            store.save(setting(due = base.plusSeconds(1_800)))
            var jump = 0L
            val probe = Probe()
            runner(this, probe, clock = { base.plusMillis(testScheduler.currentTime + jump) }).start()
            runCurrent()
            jump = 3_600_000

            advanceTimeBy(60_000)
            runCurrent()

            assertEquals(base.plusSeconds(3_660), probe.seen.single().now)
            assertEquals(1, dispatched.size)
            assertEquals(1, store.queries.size)
        }

        @Test
        fun `a slow preload does not block an immediate setting change`() = runTest {
            val probe = Probe()
            val scheduler = runner(this, probe)
            scheduler.start()
            val release = CompletableDeferred<Unit>()
            store.beforeQuery = { release.await() }
            backgroundScope.launch { scheduler.preload() }
            runCurrent()

            val row = setting(due = base.plusSeconds(60))
            store.save(row)
            scheduler.onSettingChanged(ScheduledTaskSettingChanged(row.userId, row.taskType))
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()

            assertEquals(1, dispatched.size)
            release.complete(Unit)
            runCurrent()
            assertEquals(1, dispatched.size)
        }

        @Test
        fun `a transient rearm read failure retries without waiting thirty minutes`() = runTest {
            store.save(setting(due = base).copy(cron = "0 * * * * *"))
            every { taskRunner.dispatch(any()) } answers {
                dispatched += firstArg<AsyncTask>()
                if (dispatched.size == 1) store.readFailure = IllegalStateException("rearm unavailable")
            }
            runner(this, Probe()).start()
            runCurrent()
            assertEquals(1, dispatched.size)

            store.readFailure = null
            advanceTimeBy(60_000)
            runCurrent()

            assertEquals(2, dispatched.size)
            assertEquals(1, store.queries.size)
        }

        @Test
        fun `planner cancellation preserves the fire and rearms with backoff`() = runTest {
            val row = setting()
            store.save(row)
            var attempts = 0
            val probe = Probe(onPlan = {
                if (attempts++ == 0) throw CancellationException("planner cancelled")
                listOf(TaskDraft(it.userId, it.setting.taskType, "recovered"))
            })
            runner(this, probe).start()
            runCurrent()
            assertEquals(1, attempts)
            assertEquals(row.nextFireAt, store.row().nextFireAt)
            advanceTimeBy(59_999)
            runCurrent()
            assertEquals(1, attempts)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, attempts)
            assertEquals(1, dispatched.size)
        }

        @Test
        fun `destroy cancels future firings and leaves the persisted deadline intact`() = runTest {
            val row = setting(due = base.plusSeconds(60))
            store.save(row)
            val probe = Probe()
            val scheduler = runner(this, probe)
            scheduler.start()
            runCurrent()
            val readsAfterPreload = store.reads

            scheduler.destroy()
            scheduler.preloadTick()
            scheduler.tick()
            scheduler.onSettingChanged(ScheduledTaskSettingChanged(row.userId, row.taskType))
            advanceTimeBy(3_600_000)
            runCurrent()

            assertEquals(row, store.row())
            assertTrue(probe.seen.isEmpty())
            assertEquals(1, store.queries.size)
            assertEquals(readsAfterPreload, store.reads)
        }
    }

    @Nested
    inner class AtomicSubmission {

        @Test
        fun `a failed plan leaves its fire due and retries after a minute without blocking another user`() = runTest {
            val alice = setting()
            store.save(alice)
            store.save(setting("bob"))
            var failing = true
            val probe = Probe(onPlan = {
                if (it.userId == "alice" && failing) error("planner unavailable")
                listOf(TaskDraft(it.userId, it.setting.taskType, "recovered"))
            })
            val scheduler = runner(this, probe)
            scheduler.start()
            runCurrent()
            assertEquals(alice.nextFireAt, store.row().nextFireAt)
            assertEquals(listOf("bob"), dispatched.map { it.userId })

            failing = false
            scheduler.preload()
            advanceTimeBy(59_999)
            runCurrent()
            assertEquals(2, probe.seen.size)
            advanceTimeBy(1)
            runCurrent()

            assertEquals(listOf("bob", "alice"), dispatched.map { it.userId })
            assertEquals(3, probe.seen.size)
        }

        @Test
        fun `a failed batch insert advances nothing and retries the whole batch after a minute`() = runTest {
            val row = setting()
            store.save(row)
            store.submitFailure = IllegalStateException("insert unavailable")
            val probe = Probe(onPlan = { context ->
                List(2) { TaskDraft(context.userId, context.setting.taskType, "draft-$it") }
            })
            runner(this, probe).start()
            runCurrent()
            assertEquals(row, store.row())
            assertTrue(store.batches.isEmpty())
            assertTrue(dispatched.isEmpty())

            store.submitFailure = null
            advanceTimeBy(59_999)
            runCurrent()
            assertEquals(1, store.submitAttempts)
            advanceTimeBy(1)
            runCurrent()

            assertEquals(2, store.submitAttempts)
            assertEquals(2, store.batches.single().size)
            assertEquals(store.batches.single(), dispatched)
            assertTrue(assertNotNull(store.row().nextFireAt) > base.toEpochMilli())
        }

        @Test
        fun `a failed setting reread leaves the fire due and retries without replanning blindly`() = runTest {
            val row = setting()
            store.save(row)
            val probe = Probe()
            runner(this, probe).start()
            store.readFailure = IllegalStateException("setting unavailable")
            runCurrent()
            assertEquals(row, store.row())
            assertTrue(probe.seen.isEmpty())
            assertTrue(store.batches.isEmpty())

            store.readFailure = null
            advanceTimeBy(59_999)
            runCurrent()
            assertTrue(probe.seen.isEmpty())
            advanceTimeBy(1)
            runCurrent()

            assertEquals(1, probe.seen.size)
            assertEquals(1, dispatched.size)
        }

        @Test
        fun `a failed pending task read preserves the deadline until the retry succeeds`() = runTest {
            val row = setting()
            store.save(row)
            coEvery { taskStore.listNonTerminal(any(), any(), any()) } throws IllegalStateException("tasks unavailable")
            val probe = Probe()
            runner(this, probe).start()
            runCurrent()
            assertEquals(row, store.row())
            assertTrue(probe.seen.isEmpty())
            assertTrue(store.batches.isEmpty())

            coEvery { taskStore.listNonTerminal(any(), any(), any()) } returns emptyList()
            advanceTimeBy(60_000)
            runCurrent()

            assertEquals(1, probe.seen.size)
            assertEquals(1, dispatched.size)
        }

        @Test
        fun `an empty successful plan advances its deadline without dispatching anything`() = runTest {
            store.save(setting())
            val probe = Probe(onPlan = { emptyList() })
            val scheduler = runner(this, probe)
            scheduler.start()
            runCurrent()
            scheduler.preload()
            runCurrent()

            assertEquals(1, probe.seen.size)
            assertEquals(listOf(emptyList()), store.batches)
            assertEquals(Instant.parse("2026-10-18T03:00:00Z").toEpochMilli(), store.row().nextFireAt)
            verify(exactly = 0) { taskRunner.dispatch(any()) }
            coVerify(exactly = 0) { taskRunner.submit(any()) }
        }

        @Test
        fun `two runners may plan the same firing but only the atomic winner dispatches`() = runTest {
            store.save(setting())
            val bothPlanning = CompletableDeferred<Unit>()
            var plans = 0
            val probe = Probe(onPlan = {
                if (++plans == 2) bothPlanning.complete(Unit)
                bothPlanning.await()
                listOf(TaskDraft(it.userId, it.setting.taskType, "competing"))
            })

            runner(this, probe).start()
            runner(this, probe).start()
            runCurrent()

            assertEquals(2, plans)
            assertEquals(2, store.submitAttempts)
            assertEquals(1, store.batches.size)
            assertEquals(store.batches.single(), dispatched)
            coVerify(exactly = 0) { taskRunner.submit(any()) }
        }

        @Test
        fun `per user capped batches carry pending input timeouts and persisted tasks to dispatch`() = runTest {
            store.save(setting().copy(timeoutMs = 60_000))
            store.save(setting("bob"))
            val pending = AsyncTask(
                "pending", "alice", AsyncTaskType.MEMORY_CONSOLIDATION, TaskTrigger.FRONTEND, "existing",
                status = AsyncTaskStatus.RUNNING
            )
            coEvery { taskStore.listNonTerminal("alice", AsyncTaskType.MEMORY_CONSOLIDATION, 200) } returns listOf(pending)
            val probe = Probe(perUserPerTickLimit = 2, onPlan = { context ->
                List(3) { index ->
                    TaskDraft(
                        context.userId, context.setting.taskType, "draft-$index",
                        payload = mapOf("slice" to index, "scheduledFor" to -1L), timeoutMs = 120_000,
                        sessionId = "session-${context.userId}-$index"
                    )
                }
            })
            runner(this, probe).start()
            runCurrent()

            assertEquals(listOf(pending), probe.seen.single { it.userId == "alice" }.pendingTasks)
            assertTrue(probe.seen.single { it.userId == "bob" }.pendingTasks.isEmpty())
            assertEquals(listOf(2, 2), store.batches.map { it.size })
            assertEquals(store.batches.flatten(), dispatched)
            assertEquals(4, dispatched.map { it.id }.toSet().size)
            assertTrue(dispatched.all { it.id.isNotBlank() })
            assertTrue(dispatched.all { it.trigger == TaskTrigger.SCHEDULE && it.status == AsyncTaskStatus.PENDING })
            assertTrue(dispatched.all { it.type == AsyncTaskType.MEMORY_CONSOLIDATION })
            assertTrue(dispatched.all { it.sessionId == "session-${it.userId}-${it.payload["slice"]}" })
            assertTrue(dispatched.all { it.payload["scheduledFor"] == setting().nextFireAt })
            assertEquals(listOf(60_000L, 60_000L), dispatched.filter { it.userId == "alice" }.map { it.timeoutMs })
            assertEquals(listOf(120_000L, 120_000L), dispatched.filter { it.userId == "bob" }.map { it.timeoutMs })
            coVerify(exactly = 0) { taskRunner.submit(any()) }
        }
    }

    @Nested
    inner class CronAndPolling {

        @Test
        fun `missing blank and invalid overrides use the default while valid overrides take precedence`() = runTest {
            listOf(null, " ", "not a cron", "0 0 12 * * *").forEachIndexed { index, cron ->
                store.save(setting("user-$index").copy(cron = cron))
            }
            runner(this, Probe()).start()
            runCurrent()

            repeat(3) {
                assertEquals(Instant.parse("2026-10-18T03:00:00Z").toEpochMilli(), store.row("user-$it").nextFireAt)
            }
            assertEquals(Instant.parse("2026-10-17T12:00:00Z").toEpochMilli(), store.row("user-3").nextFireAt)
            assertEquals(4, dispatched.size)
        }

        @Test
        fun `absent or invalid default cron initializes to no next fire instead of spinning`() = runTest {
            val probes = listOf(Probe(defaultCron = null), Probe(type = AsyncTaskType("invalid-default"), defaultCron = "bad"))
            for (probe in probes) {
                for ((index, cron) in listOf(null, "invalid").withIndex()) {
                    store.save(setting("${probe.type.key}-$index").copy(
                        taskType = probe.type, cron = cron, nextFireAt = null, scheduleRevision = null, scheduleZone = null
                    ))
                }
            }
            val scheduler = runner(this, *probes.toTypedArray())
            scheduler.start()
            runCurrent()
            scheduler.preload()
            runCurrent()

            assertEquals(4, store.initialized.size)
            assertTrue(store.initialized.all { it.scheduleZone != null && it.nextFireAt == null })
            assertTrue(probes.all { it.seen.isEmpty() })
            assertTrue(store.batches.isEmpty())
        }

        @Test
        fun `each persisted zone controls both planner local time and the next cron instant`() = runTest {
            store.save(setting("utc").copy(scheduleZone = "UTC"))
            store.save(setting("tokyo").copy(scheduleZone = "Asia/Tokyo"))
            val probe = Probe()
            runner(this, probe).start()
            runCurrent()

            assertEquals(base, probe.seen.single { it.userId == "utc" }.now)
            assertEquals(base, probe.seen.single { it.userId == "tokyo" }.now)
            assertEquals(Instant.parse("2026-10-18T03:00:00Z").toEpochMilli(), store.row("utc").nextFireAt)
            assertEquals(Instant.parse("2026-10-17T18:00:00Z").toEpochMilli(), store.row("tokyo").nextFireAt)
        }

        @Test
        fun `spring forward skips a nonexistent local cron time rather than adding 24 hours`() = runTest {
            val due = Instant.parse("2026-03-07T07:30:00Z")
            store.save(setting(due = due).copy(cron = "0 30 2 * * *", scheduleZone = "America/New_York"))
            val probe = Probe()
            runner(this, probe, startAt = due).start()
            runCurrent()

            assertEquals(due, probe.seen.single().now)
            assertEquals(Instant.parse("2026-03-09T06:30:00Z").toEpochMilli(), store.row().nextFireAt)
            assertEquals("America/New_York", store.row().scheduleZone)
        }

        @Test
        fun `fall back fires both distinct instants of an overlapping local cron time`() = runTest {
            val first = Instant.parse("2026-11-01T05:30:00Z")
            val second = Instant.parse("2026-11-01T06:30:00Z")
            store.save(setting(due = first).copy(cron = "0 30 1 * * *", scheduleZone = "America/New_York"))
            val probe = Probe()
            runner(this, probe, startAt = first).start()
            runCurrent()
            assertEquals(second.toEpochMilli(), store.row().nextFireAt)

            advanceTimeBy(3_600_000)
            runCurrent()

            assertEquals(listOf(first.toEpochMilli(), second.toEpochMilli()), dispatched.map { it.payload["scheduledFor"] })
            assertEquals(listOf(first, second), probe.seen.map { it.now })
            assertEquals(Instant.parse("2026-11-02T06:30:00Z").toEpochMilli(), store.row().nextFireAt)
            assertEquals(1, store.queries.size)
        }

        @Test
        fun `watch stays on minute polling and submits without touching global schedule state`() = runTest {
            val watch = object : Probe(type = AsyncTaskType.WATCH, defaultCron = "0 * * * * *") {
                override val usesPersistentSchedule = false
            }
            val memory = Probe()
            val watchRow = setting().copy(taskType = AsyncTaskType.WATCH, cron = null, nextFireAt = null, scheduleZone = null)
            store.save(watchRow)
            store.save(setting(due = base.plusSeconds(86_400)))
            val scheduler = runner(this, watch, memory)
            scheduler.start()
            scheduler.tick()
            runCurrent()
            scheduler.pollOnce(base)
            assertEquals(1, submitted.size)
            advanceTimeBy(60_000)
            scheduler.tick()
            runCurrent()

            assertEquals(2, watch.seen.size)
            assertTrue(memory.seen.isEmpty())
            assertEquals(2, submitted.size)
            assertTrue(submitted.all { it.type == AsyncTaskType.WATCH && it.trigger == TaskTrigger.SCHEDULE })
            assertEquals(watchRow, store.row(type = AsyncTaskType.WATCH))
            assertTrue(store.queries.all { it.type == AsyncTaskType.MEMORY_CONSOLIDATION })
            assertTrue(store.enabledQueries.all { it == AsyncTaskType.WATCH })
            assertTrue(store.initialized.isEmpty())
            assertEquals(0, store.submitAttempts)
            assertTrue(dispatched.isEmpty())
        }

        @Test
        fun `preload keeps its thirty minute delay and only polling planners use the minute tick`() {
            val preload = assertNotNull(ScheduledTaskRunner::class.java.getMethod("preloadTick").getAnnotation(Scheduled::class.java))
            val poll = assertNotNull(ScheduledTaskRunner::class.java.getMethod("tick").getAnnotation(Scheduled::class.java))
            assertEquals(1_800_000L, preload.fixedDelay)
            assertEquals("\${easyai.tasks.scheduled.scan-cron:0 * * * * *}", poll.cron)
            assertTrue(Probe().usesPersistentSchedule)
        }

        @Test
        fun `watch scheduler preloads every thirty minutes instead of polling due rows`() {
            val preload = assertNotNull(
                WatchTaskScheduler::class.java.getMethod("preloadTick").getAnnotation(Scheduled::class.java)
            )
            assertEquals(1_800_000L, preload.fixedDelay)
        }
    }

    @Nested
    inner class MultiRow {

        @Test
        fun `rows of one type keep independent timers and next-fire state`() = runTest {
            store.save(setting().copy(scheduleKey = "cn", scheduleRevision = "revision-cn"))
            store.save(
                setting().copy(
                    scheduleKey = "us",
                    cron = "0 30 3 * * *",
                    scheduleRevision = "revision-us",
                    nextFireAt = base.plusSeconds(1_800).toEpochMilli()
                )
            )
            val probe = Probe()
            val scheduler = runner(this, probe)

            scheduler.start()
            runCurrent()
            assertEquals(listOf("cn"), probe.seen.map { it.setting.scheduleKey })

            advanceTimeBy(1_800_000)
            runCurrent()
            assertEquals(listOf("cn", "us"), probe.seen.map { it.setting.scheduleKey })
            assertEquals(
                Instant.parse("2026-10-18T03:00:00Z").toEpochMilli(),
                store.row(scheduleKey = "cn").nextFireAt
            )
            assertEquals(
                Instant.parse("2026-10-18T03:30:00Z").toEpochMilli(),
                store.row(scheduleKey = "us").nextFireAt
            )
        }

        @Test
        fun `a firing re-reads its own row and skips when that row was deleted`() = runTest {
            store.save(setting().copy(scheduleKey = "cn", scheduleRevision = "revision-cn"))
            store.save(
                setting().copy(
                    scheduleKey = "us",
                    scheduleRevision = "revision-us",
                    nextFireAt = base.plusSeconds(60).toEpochMilli()
                )
            )
            val probe = Probe()
            val scheduler = runner(this, probe)

            scheduler.start()
            runCurrent()
            store.delete("alice", AsyncTaskType.MEMORY_CONSOLIDATION, "us")

            advanceTimeBy(60_000)
            runCurrent()

            assertEquals(listOf("cn"), probe.seen.map { it.setting.scheduleKey })
            assertEquals(1, dispatched.size)
        }

        @Test
        fun `a keyed change notification refreshes only that row`() = runTest {
            store.save(
                setting().copy(
                    scheduleKey = "cn",
                    scheduleRevision = "revision-cn",
                    nextFireAt = base.plusSeconds(60).toEpochMilli()
                )
            )
            store.save(
                setting().copy(
                    scheduleKey = "us",
                    enabled = false,
                    nextFireAt = null,
                    scheduleRevision = "revision-us"
                )
            )
            val probe = Probe()
            val scheduler = runner(this, probe)

            scheduler.start()
            runCurrent()
            store.save(
                setting().copy(
                    scheduleKey = "us",
                    scheduleRevision = "revision-us-edited",
                    nextFireAt = base.plusSeconds(120).toEpochMilli()
                )
            )
            scheduler.onSettingChanged(
                ScheduledTaskSettingChanged("alice", AsyncTaskType.MEMORY_CONSOLIDATION, "us")
            )
            runCurrent()

            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(listOf("cn"), probe.seen.map { it.setting.scheduleKey })

            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(listOf("cn", "us"), probe.seen.map { it.setting.scheduleKey })
        }
    }

    private class ScheduleStore : ScheduledTaskSettingStore {
        private val rows = mutableMapOf<Triple<String, AsyncTaskType, String>, ScheduledTaskSetting>()
        val queries = mutableListOf<Query>()
        val enabledQueries = mutableListOf<AsyncTaskType?>()
        val initialized = mutableListOf<ScheduledTaskSetting>()
        val batches = mutableListOf<List<AsyncTask>>()
        var reads = 0
        var submitAttempts = 0
        var readFailure: Exception? = null
        var submitFailure: Exception? = null
        var beforeQuery: suspend () -> Unit = {}

        fun row(
            userId: String = "alice",
            type: AsyncTaskType = AsyncTaskType.MEMORY_CONSOLIDATION,
            scheduleKey: String = ""
        ): ScheduledTaskSetting =
            requireNotNull(rows[Triple(userId, type, scheduleKey)])

        override suspend fun get(
            userId: String,
            taskType: AsyncTaskType,
            scheduleKey: String
        ): ScheduledTaskSetting? {
            reads++
            readFailure?.let { throw it }
            return rows[Triple(userId, taskType, scheduleKey)]
        }

        override suspend fun list(userId: String, taskType: AsyncTaskType): List<ScheduledTaskSetting> =
            rows.values.filter { it.userId == userId && it.taskType == taskType }.sortedBy { it.scheduleKey }

        override suspend fun save(setting: ScheduledTaskSetting) {
            rows[Triple(setting.userId, setting.taskType, setting.scheduleKey)] = setting
        }

        override suspend fun delete(userId: String, taskType: AsyncTaskType, scheduleKey: String): Boolean =
            rows.remove(Triple(userId, taskType, scheduleKey)) != null

        override suspend fun listEnabled(taskType: AsyncTaskType?): List<ScheduledTaskSetting> {
            enabledQueries += taskType
            return rows.values.filter { it.enabled && (taskType == null || it.taskType == taskType) }
        }

        override suspend fun listForScheduling(
            taskType: AsyncTaskType,
            horizon: Long,
            afterUserId: String?,
            limit: Int,
            afterScheduleKey: String?
        ): List<ScheduledTaskSetting> {
            queries += Query(taskType, horizon, afterUserId, limit, afterScheduleKey)
            beforeQuery()
            return rows.values.filter {
                it.enabled && it.taskType == taskType && (
                    afterUserId == null || it.userId > afterUserId ||
                        (it.userId == afterUserId && it.scheduleKey > (afterScheduleKey ?: ""))
                    ) &&
                    (it.scheduleZone == null || it.nextFireAt?.let { due -> due <= horizon } == true)
            }.sortedWith(compareBy({ it.userId }, { it.scheduleKey })).take(limit)
        }

        override suspend fun initializeSchedule(setting: ScheduledTaskSetting, nextFireAt: Long?, zone: String): Boolean {
            val key = Triple(setting.userId, setting.taskType, setting.scheduleKey)
            val current = rows[key] ?: return false
            if (!matches(current, setting) || current.scheduleZone != null) return false
            val updated = current.copy(
                nextFireAt = nextFireAt, scheduleZone = zone,
                scheduleRevision = current.scheduleRevision ?: "initialized-${current.userId}"
            )
            rows[key] = updated
            initialized += updated
            return true
        }

        override suspend fun submitScheduled(
            setting: ScheduledTaskSetting, nextFireAt: Long?, tasks: List<AsyncTask>
        ): List<AsyncTask>? {
            submitAttempts++
            submitFailure?.let { throw it }
            val key = Triple(setting.userId, setting.taskType, setting.scheduleKey)
            val current = rows[key] ?: return null
            if (!matches(current, setting)) return null
            // No suspension between the CAS check, advancement and insert on the single test scheduler.
            val persisted = tasks
            rows[key] = current.copy(nextFireAt = nextFireAt)
            batches += persisted
            return persisted
        }

        private fun matches(current: ScheduledTaskSetting, expected: ScheduledTaskSetting): Boolean =
            current.enabled && current.scheduleRevision == expected.scheduleRevision &&
                current.nextFireAt == expected.nextFireAt && current.scheduleZone == expected.scheduleZone

        data class Query(
            val type: AsyncTaskType,
            val horizon: Long,
            val afterUserId: String?,
            val limit: Int,
            val afterScheduleKey: String?
        )
    }
}
