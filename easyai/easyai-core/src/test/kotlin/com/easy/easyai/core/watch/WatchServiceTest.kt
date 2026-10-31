package com.easy.easyai.core.watch

import com.easy.easyai.core.model.ProjectKind
import com.easy.easyai.core.task.AsyncTask
import com.easy.easyai.core.task.AsyncTaskStatus
import com.easy.easyai.core.task.AsyncTaskStore
import com.easy.easyai.core.task.AsyncTaskType
import com.easy.easyai.core.task.ScheduledTaskSettingStore
import com.easy.easyai.core.task.TaskTrigger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The definition service is the only place that validates a watch, so these tests are about the
 * contract the tool and the page rely on: trigger forms get an actionable rejection, an edit bumps
 * the version, a finished watch is renewed only by a new schedule, and every manual operation
 * refuses to fight an in-flight run.
 */
class WatchServiceTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-25T00:00:00Z") // 08:00 in Shanghai

    private fun origin(modelConfigId: String? = "model-1", timeZone: ZoneId? = zone) = WatchOrigin(
        userId = "alice",
        sourceAgentId = "default-agent",
        modelConfigId = modelConfigId,
        projectId = "project-1",
        projectPath = "/tmp/project",
        projectKind = ProjectKind.USER,
        owners = setOf("alice"),
        originSessionId = "session-1",
        timeZone = timeZone
    )

    private fun input(
        title: String = "Price watch",
        condition: String = "Check whether the price crossed 100",
        schedule: WatchScheduleInput = WatchScheduleInput.In(Duration.ofHours(2)),
        until: LocalDate? = null,
        tags: List<WatchTag> = emptyList(),
        notifyTags: Set<String> = emptySet(),
        completeOnMet: Boolean? = null,
        timezone: ZoneId? = null,
        mode: WatchMode = WatchMode.CHECK,
        reminder: String? = null
    ) = WatchCreateInput(
        title = title,
        condition = condition,
        schedule = schedule,
        mode = mode,
        reminder = reminder,
        timezone = timezone,
        until = until,
        tags = tags,
        notifyTags = notifyTags,
        completeOnMet = completeOnMet
    )

    private fun service(
        store: WatchStore = FakeWatchStore(),
        properties: WatchProperties = WatchProperties(),
        settingStore: ScheduledTaskSettingStore? = null,
        taskStore: AsyncTaskStore? = null,
        gateway: WatchRunGateway? = null
    ) = WatchService(
        store = store,
        settingStore = settingStore,
        taskStore = taskStore,
        runGateway = gateway,
        properties = properties,
        clock = { now }
    )

    @Nested
    inner class `creation` {

        @Test
        fun `a watch without a saved model is refused with an actionable message`() = runTest {
            val error = assertThrows<WatchException> { service().create(origin(modelConfigId = null), input()) }
            assertTrue(error.message!!.contains("model configuration"))
        }

        @Test
        fun `the resolved schedule and next run are persisted`() = runTest {
            val store = FakeWatchStore()
            val result = service(store).create(origin(), input())
            val watch = store.findById(result.watch.id)!!
            assertEquals(WatchStatus.ACTIVE, watch.status)
            assertEquals(WatchPendingAction.CHECK, watch.pendingAction)
            assertEquals(now.plus(Duration.ofHours(2)), watch.nextDueAt)
            assertEquals(zone, watch.schedule.zone)
            assertEquals(1, watch.version)
            assertEquals("alice", watch.userId)
            assertTrue(result.created)
        }

        @Test
        fun `a recurring watch keeps monitoring after a met verdict by default`() = runTest {
            val result = service().create(
                origin(),
                input(schedule = WatchScheduleInput.Every(WatchScheduleKind.DAILY, LocalTime.of(8, 0)))
            )
            assertFalse(result.watch.completeOnMet)
        }

        @Test
        fun `a one-shot watch completes after a met verdict by default`() = runTest {
            val result = service().create(origin(), input())
            assertTrue(result.watch.completeOnMet)
        }

        @Test
        fun `an explicit completion policy overrides the recurring default`() = runTest {
            val result = service().create(
                origin(),
                input(
                    schedule = WatchScheduleInput.Every(WatchScheduleKind.DAILY, LocalTime.of(8, 0)),
                    completeOnMet = true
                )
            )
            assertTrue(result.watch.completeOnMet)
        }

        @Test
        fun `a missing request zone falls back to the server and says so`() = runTest {
            val result = service().create(origin(timeZone = null), input())
            assertEquals(ZoneId.systemDefault(), result.watch.schedule.zone)
            assertTrue(result.warnings.any { it.contains("time zone") })
        }

        @Test
        fun `until before the first run is refused`() = runTest {
            val error = assertThrows<WatchException> {
                service().create(
                    origin(),
                    input(schedule = WatchScheduleInput.In(Duration.ofDays(3)), until = LocalDate.parse("2026-10-25"))
                )
            }
            assertTrue(error.message!!.contains("'until'"))
        }

        @Test
        fun `an identical active condition is returned instead of duplicated`() = runTest {
            val store = FakeWatchStore()
            val svc = service(store)
            val first = svc.create(origin(), input(condition = "Check the  price"))
            val second = svc.create(origin(), input(condition = "  check   THE price "))
            assertFalse(second.created)
            assertEquals(first.watch.id, second.watch.id)
            assertEquals(1, store.findById(first.watch.id)!!.version)
        }

        @Test
        fun `the same condition at a different time is a new watch`() = runTest {
            val store = FakeWatchStore()
            val svc = service(store)
            val first = svc.create(
                origin(),
                input(
                    condition = "Take the pill",
                    mode = WatchMode.REMIND,
                    reminder = "Take the pill",
                    schedule = WatchScheduleInput.DayOffset(1, LocalTime.of(9, 0))
                )
            )
            val second = svc.create(
                origin(),
                input(
                    condition = "Take the pill",
                    mode = WatchMode.REMIND,
                    reminder = "Take the pill",
                    schedule = WatchScheduleInput.DayOffset(1, LocalTime.of(18, 0))
                )
            )
            assertTrue(second.created)
            assertNotEquals(first.watch.id, second.watch.id)
        }

        @Test
        fun `the active cap stops a new definition`() = runTest {
            val store = FakeWatchStore()
            val svc = service(store, WatchProperties(maxActivePerUser = 1))
            svc.create(origin(), input(condition = "one"))
            val error = assertThrows<WatchException> { svc.create(origin(), input(condition = "two")) }
            assertTrue(error.message!!.contains("active watches"))
        }

        @Test
        fun `a short lead time is refused`() = runTest {
            val error = assertThrows<WatchException> { service().create(origin(), input(schedule = WatchScheduleInput.In(Duration.ofSeconds(30)))) }
            assertTrue(error.message!!.contains("at least one minute"))
        }

        @Test
        fun `day offset zero with a time already passed today is refused`() = runTest {
            val error = assertThrows<WatchException> {
                service().create(
                    origin(),
                    input(schedule = WatchScheduleInput.DayOffset(0, LocalTime.of(7, 0)))
                )
            }
            assertTrue(error.message!!.contains("already passed"))
        }

        @Test
        fun `tag keys are normalized and must be unique and well formed`() = runTest {
            val store = FakeWatchStore()
            val watch = service(store).create(
                origin(),
                input(tags = listOf(WatchTag("Crossed", "已突破")))
            ).watch
            assertEquals("crossed", watch.tags.single().key)

            assertThrows<WatchException> {
                service(FakeWatchStore()).create(
                    origin(),
                    input(condition = "next", tags = listOf(WatchTag("no-good", "x")))
                )
            }
            assertThrows<WatchException> {
                service(FakeWatchStore()).create(
                    origin(),
                    input(condition = "next", tags = listOf(WatchTag("a", "1"), WatchTag("A", "2")))
                )
            }
        }

        @Test
        fun `notify tags must be part of the universe`() = runTest {
            val error = assertThrows<WatchException> {
                service().create(origin(), input(notifyTags = setOf("missing")))
            }
            assertTrue(error.message!!.contains("notify_tags"))
        }

        @Test
        fun `a remind watch needs its text`() = runTest {
            val error = assertThrows<WatchException> {
                service().create(origin(), input(mode = WatchMode.REMIND, condition = ""))
            }
            assertTrue(error.message!!.contains("reminder"))
        }

        @Test
        fun `a reminder needs no saved model because it never calls one`() = runTest {
            val result = service().create(
                origin(modelConfigId = null),
                input(mode = WatchMode.REMIND, condition = "", reminder = "Take the pill")
            )
            assertEquals(WatchMode.REMIND, result.watch.mode)
        }

        @Test
        fun `a temporary workspace is dropped with a warning`() = runTest {
            val result = service().create(origin().copy(projectKind = ProjectKind.TEMP), input())
            assertNull(result.watch.projectPath)
            assertTrue(result.warnings.any { it.contains("temporary") })
        }

        @Test
        fun `creating a watch enables the scan row without touching its schedule`() = runTest {
            val settings = mockk<ScheduledTaskSettingStore>(relaxed = true)
            coEvery { settings.get("alice", any()) } returns null
            service(settingStore = settings).create(origin(), input())
            coVerify {
                settings.save(
                    match { it.userId == "alice" && it.enabled && it.taskType.key == "watch" }
                )
            }
        }
    }

    @Nested
    inner class `editing` {

        private suspend fun created(
            store: FakeWatchStore,
            status: WatchStatus = WatchStatus.ACTIVE
        ): Watch {
            val watch = service(store).create(origin(), input()).watch
            return if (status == WatchStatus.ACTIVE) watch else store.setStatus(watch.id, status, "test")!!
        }

        @Test
        fun `a text-only edit keeps the next run and bumps the version`() = runTest {
            val store = FakeWatchStore()
            val before = created(store)
            val result = service(store).update(
                "alice",
                before.id,
                WatchUpdateInput(title = "Renamed", condition = "Something else")
            )
            assertEquals("Renamed", result.watch.title)
            assertEquals(before.nextDueAt, result.watch.nextDueAt)
            assertEquals(before.version + 1, result.watch.version)
        }

        @Test
        fun `an unchanged timezone does not move the schedule or clear a review`() = runTest {
            val store = FakeWatchStore()
            val watch = created(store)
            store.setStatus(watch.id, WatchStatus.ACTIVE, null, WatchPendingAction.EXPIRY_REVIEW)
            val before = store.findById(watch.id)!!
            val result = service(store).update(
                "alice",
                watch.id,
                WatchUpdateInput(title = "Renamed", timezone = zone)
            )
            assertEquals(before.nextDueAt, result.watch.nextDueAt)
            assertEquals(WatchPendingAction.EXPIRY_REVIEW, result.watch.pendingAction)
        }

        @Test
        fun `an unchanged until date does not clear a review`() = runTest {
            val store = FakeWatchStore()
            val watch = service(store)
                .create(origin(), input(until = LocalDate.parse("2026-12-31")))
                .watch
            store.setStatus(watch.id, WatchStatus.ACTIVE, null, WatchPendingAction.EXPIRY_REVIEW)
            val result = service(store).update(
                "alice",
                watch.id,
                WatchUpdateInput(title = "Renamed", until = LocalDate.parse("2026-12-31"))
            )
            assertEquals(WatchPendingAction.EXPIRY_REVIEW, result.watch.pendingAction)
            assertEquals(watch.untilAt, result.watch.untilAt)
        }

        @Test
        fun `a schedule edit recomputes the next run`() = runTest {
            val store = FakeWatchStore()
            val before = created(store)
            val result = service(store).update(
                "alice",
                before.id,
                WatchUpdateInput(schedule = WatchScheduleInput.Every(WatchScheduleKind.DAILY, LocalTime.of(9, 0)))
            )
            assertEquals(WatchScheduleKind.DAILY, result.watch.schedule.kind)
            assertEquals(Instant.parse("2026-10-25T01:00:00Z"), result.watch.nextDueAt)
        }

        @Test
        fun `moving the deadline clears a pending expiry review`() = runTest {
            val store = FakeWatchStore()
            val watch = created(store)
            store.setStatus(watch.id, WatchStatus.ACTIVE, null, WatchPendingAction.EXPIRY_REVIEW)
            val result = service(store).update(
                "alice",
                watch.id,
                WatchUpdateInput(until = LocalDate.parse("2026-12-31"))
            )
            assertEquals(WatchPendingAction.CHECK, result.watch.pendingAction)
        }

        @Test
        fun `a cancelled watch cannot be edited`() = runTest {
            val store = FakeWatchStore()
            val watch = created(store, WatchStatus.CANCELLED)
            val error = assertThrows<WatchException> {
                service(store).update("alice", watch.id, WatchUpdateInput(title = "x"))
            }
            assertTrue(error.message!!.contains("cancelled"))
        }

        @Test
        fun `a finished watch needs a schedule to be renewed`() = runTest {
            val store = FakeWatchStore()
            val watch = created(store, WatchStatus.EXPIRED)
            val error = assertThrows<WatchException> {
                service(store).update("alice", watch.id, WatchUpdateInput(title = "x"))
            }
            assertTrue(error.message!!.contains("schedule"))
        }

        @Test
        fun `renewing a one-shot as a recurring watch keeps monitoring after met`() = runTest {
            val store = FakeWatchStore()
            val watch = service(store).create(origin(), input()).watch
            assertTrue(watch.completeOnMet)
            store.forceStatus(watch.id, WatchStatus.COMPLETED)

            val result = service(store).update(
                "alice",
                watch.id,
                WatchUpdateInput(schedule = WatchScheduleInput.Every(WatchScheduleKind.DAILY, LocalTime.of(8, 0)))
            )

            // The one-shot lifecycle default does not survive a switch to a recurrence.
            assertFalse(result.watch.completeOnMet)
        }

        @Test
        fun `renewing a finished watch resets its lifecycle counters`() = runTest {
            val store = FakeWatchStore()
            val watch = created(store, WatchStatus.COMPLETED)
            val result = service(store).update(
                "alice",
                watch.id,
                WatchUpdateInput(schedule = WatchScheduleInput.In(Duration.ofDays(1)))
            )
            assertEquals(WatchStatus.ACTIVE, result.watch.status)
            assertEquals(0, result.watch.extensionCount)
            assertEquals(0, result.watch.consecutiveFailures)
            assertNull(result.watch.statusReason)
            assertEquals(now.plus(Duration.ofDays(1)), result.watch.nextDueAt)
        }

        @Test
        fun `clearing a review on a recurring watch restores the scheduled next run`() = runTest {
            val store = FakeWatchStore()
            val watch = service(store).create(
                origin(),
                input(schedule = WatchScheduleInput.Every(WatchScheduleKind.DAILY, LocalTime.of(9, 0)))
            ).watch
            store.setStatus(watch.id, WatchStatus.ACTIVE, null, WatchPendingAction.EXPIRY_REVIEW)
            // The review claimed the row and left its own short guard behind.
            store.forceNextDue(watch.id, now.plus(Duration.ofMinutes(10)))

            val result = service(store).update(
                "alice",
                watch.id,
                WatchUpdateInput(until = LocalDate.parse("2026-12-31"))
            )

            assertEquals(WatchPendingAction.CHECK, result.watch.pendingAction)
            assertEquals(Instant.parse("2026-10-25T01:00:00Z"), result.watch.nextDueAt)
        }

        @Test
        fun `renewing with the old until date clears it instead of refusing the save`() = runTest {
            val store = FakeWatchStore()
            val watch = service(store)
                .create(origin(), input(until = LocalDate.parse("2026-10-25")))
                .watch
            store.forceStatus(watch.id, WatchStatus.COMPLETED)
            val result = service(store).update(
                "alice",
                watch.id,
                WatchUpdateInput(
                    schedule = WatchScheduleInput.In(Duration.ofDays(2)),
                    until = LocalDate.parse("2026-10-25")
                )
            )
            assertEquals(WatchStatus.ACTIVE, result.watch.status)
            assertNull(result.watch.untilAt)
            assertTrue(result.warnings.any { it.contains("until") })
        }

        @Test
        fun `a save that changes nothing keeps the version so it cannot invalidate a run`() = runTest {
            val store = FakeWatchStore()
            val before = created(store)
            val result = service(store).update(
                "alice",
                before.id,
                WatchUpdateInput(title = before.title, condition = before.condition)
            )
            assertEquals(before, result.watch)
        }

        @Test
        fun `a stale version loses the edit instead of overwriting`() = runTest {
            val store = FakeWatchStore()
            val watch = created(store)
            // A concurrent edit lands between the service's read and its guarded write.
            store.beforeUpdateDefinition = { store.forceVersion(watch.id, watch.version + 5) }
            assertThrows<WatchConflictException> {
                service(store).update("alice", watch.id, WatchUpdateInput(title = "x"))
            }
        }

        @Test
        fun `an edit cannot revive a watch cancelled under it`() = runTest {
            val store = FakeWatchStore()
            val watch = created(store)
            // The cancel does not bump the version, so only the status guard can catch it.
            store.beforeUpdateDefinition = { store.forceStatus(watch.id, WatchStatus.CANCELLED) }
            assertThrows<WatchConflictException> {
                service(store).update("alice", watch.id, WatchUpdateInput(title = "x"))
            }
            assertEquals(WatchStatus.CANCELLED, store.findById(watch.id)!!.status)
        }
    }

    @Nested
    inner class `manual operation` {

        @Test
        fun `pause and resume move only active and paused rows`() = runTest {
            val store = FakeWatchStore()
            val svc = service(store)
            val watch = svc.create(origin(), input()).watch
            assertEquals(WatchStatus.PAUSED, svc.pause("alice", watch.id).status)
            assertEquals(WatchStatus.ACTIVE, svc.resume("alice", watch.id).status)
            assertThrows<WatchException> { svc.resume("alice", watch.id) }
        }

        @Test
        fun `an explicit resume clears the failure streak that paused the watch`() = runTest {
            val store = FakeWatchStore()
            val svc = service(store)
            val watch = svc.create(origin(), input()).watch
            store.forceStatus(watch.id, WatchStatus.PAUSED)
            store.forceFailures(watch.id, count = 3, error = "model unavailable")

            val resumed = svc.resume("alice", watch.id)

            assertEquals(WatchStatus.ACTIVE, resumed.status)
            assertEquals(0, resumed.consecutiveFailures)
            assertNull(resumed.lastError)
        }

        @Test
        fun `pause loses to a status change that landed first`() = runTest {
            val store = FakeWatchStore()
            val svc = service(store)
            val watch = svc.create(origin(), input()).watch
            store.beforeSetStatus = { store.forceStatus(watch.id, WatchStatus.COMPLETED) }
            assertThrows<WatchConflictException> { svc.pause("alice", watch.id) }
        }

        @Test
        fun `one user cannot administer another user's watch`() = runTest {
            val store = FakeWatchStore()
            val svc = service(store)
            val watch = svc.create(origin(), input()).watch
            assertThrows<WatchNotFoundException> { svc.pause("bob", watch.id) }
        }

        @Test
        fun `run now is refused while a review or another run is pending`() = runTest {
            val store = FakeWatchStore()
            val gateway = mockk<WatchRunGateway>(relaxed = true)
            val svc = service(store, gateway = gateway)
            val watch = svc.create(origin(), input()).watch
            store.setStatus(watch.id, WatchStatus.ACTIVE, null, WatchPendingAction.EXPIRY_REVIEW)
            assertThrows<WatchException> { svc.runNow("alice", watch.id) }
            store.setStatus(watch.id, WatchStatus.ACTIVE, null, WatchPendingAction.CHECK)
            store.tasks["task-1"] = watchTask(watch)
            assertThrows<WatchException> { svc.runNow("alice", watch.id) }
            assertTrue(store.admissions.isEmpty())
            verify(exactly = 0) { gateway.dispatch(any()) }
        }

        @Test
        fun `run now dispatches the committed check task once without moving the schedule`() = runTest {
            val store = FakeWatchStore()
            val taskStore = mockk<AsyncTaskStore>()
            val gateway = mockk<WatchRunGateway>(relaxed = true)
            every { gateway.dispatch(any()) } answers {
                val task = firstArg<AsyncTask>()
                assertEquals(task, store.tasks[task.id])
            }
            val svc = service(store, taskStore = taskStore, gateway = gateway)
            val watch = svc.create(origin(), input()).watch
            val task = svc.runNow("alice", watch.id)
            assertEquals(WatchRunKind.CHECK.name, task.payload[WatchTaskPayload.KEY_KIND])
            assertEquals(watch.id, task.payload[WatchTaskPayload.KEY_WATCH_ID])
            assertEquals(watch.runSessionId, task.sessionId)
            assertEquals(watch.nextDueAt, store.findById(watch.id)!!.nextDueAt)
            assertEquals(listOf(task), store.admissions)
            assertThrows<WatchException> { svc.runNow("alice", watch.id) }
            verify(exactly = 1) { gateway.dispatch(task) }
            coVerify(exactly = 0) { taskStore.create(any()) }
            coVerify(exactly = 0) { taskStore.listNonTerminal(any(), any()) }
        }

        @Test
        fun `automatic manual admission preserves its trigger and rejects a competing run`() = runTest {
            val store = FakeWatchStore()
            val gateway = mockk<WatchRunGateway>(relaxed = true)
            val svc = service(store, gateway = gateway)
            val watch = svc.create(origin(), input()).watch
            val task = svc.runNow("alice", watch.id, TaskTrigger.AUTO)
            assertEquals(TaskTrigger.AUTO, task.trigger)
            assertThrows<WatchException> { svc.runNow("alice", watch.id, TaskTrigger.FRONTEND) }
            assertEquals(1, store.admissions.size)
            verify(exactly = 1) { gateway.dispatch(task) }
        }

        @Test
        fun `a stale manual admission conflicts without dispatching`() = runTest {
            val store = mockk<WatchStore>()
            val watch = testWatch()
            coEvery { store.findById(watch.id) } returns watch
            coEvery { store.enqueueManual("alice", watch.id, now, any(), TaskTrigger.FRONTEND) } returns
                WatchEnqueueResult.Stale
            val gateway = mockk<WatchRunGateway>(relaxed = true)
            assertThrows<WatchConflictException> { service(store, gateway = gateway).runNow("alice", watch.id) }
            verify(exactly = 0) { gateway.dispatch(any()) }
        }

        @Test
        fun `run now cannot admit without a gateway or with an inactive watch`() = runTest {
            val store = FakeWatchStore()
            val watch = service(store).create(origin(), input()).watch
            assertThrows<WatchUnavailableException> { service(store).runNow("alice", watch.id) }
            store.forceStatus(watch.id, WatchStatus.PAUSED)
            assertThrows<WatchException> {
                service(store, gateway = mockk(relaxed = true)).runNow("alice", watch.id)
            }
            assertTrue(store.admissions.isEmpty())
        }

        @Test
        fun `caller cancellation cannot strand a committed manual task`() = runTest {
            val store = FakeWatchStore()
            val committed = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val delayedStore = object : WatchStore by store {
                override suspend fun enqueueManual(
                    userId: String, watchId: String, now: Instant, properties: WatchProperties, trigger: TaskTrigger
                ): WatchEnqueueResult {
                    val result = store.enqueueManual(userId, watchId, now, properties, trigger)
                    committed.complete(Unit)
                    release.await()
                    return result
                }
            }
            val gateway = mockk<WatchRunGateway>(relaxed = true)
            val svc = service(delayedStore, gateway = gateway)
            val watch = svc.create(origin(), input()).watch
            val request = launch { svc.runNow("alice", watch.id) }
            committed.await()
            request.cancel()
            release.complete(Unit)
            request.join()
            verify(exactly = 1) { gateway.dispatch(store.admissions.single()) }
        }

        @Test
        fun `cancel closes admission before cancelling only the exact watch tasks`() = runTest {
            val store = FakeWatchStore()
            val taskStore = mockk<AsyncTaskStore>()
            val gateway = mockk<WatchRunGateway>(relaxed = true)
            val svc = service(store, taskStore = taskStore, gateway = gateway)
            val watch = svc.create(origin(), input()).watch
            store.tasks["task-1"] = watchTask(watch)
            store.tasks["other"] = watchTask(watch.copy(id = "other-watch"), id = "other")
            coEvery { gateway.cancel("task-1") } coAnswers {
                assertEquals(WatchStatus.CANCELLED, store.findById(watch.id)!!.status)
                assertEquals(
                    WatchEnqueueResult.Stale,
                    store.enqueueManual("alice", watch.id, now, WatchProperties(), TaskTrigger.FRONTEND)
                )
                true
            }
            val cancelled = svc.cancel("alice", watch.id)
            assertEquals(WatchStatus.CANCELLED, cancelled.status)
            coVerify(exactly = 1) { gateway.cancel("task-1") }
            coVerify(exactly = 0) { gateway.cancel("other") }
            coVerify(exactly = 0) { taskStore.listNonTerminal(any(), any()) }
        }

        @Test
        fun `delete waits for cancellation after closing admission`() = runTest {
            val store = FakeWatchStore()
            val gateway = mockk<WatchRunGateway>(relaxed = true)
            val stopping = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val svc = service(store, gateway = gateway)
            val watch = svc.create(origin(), input()).watch
            store.tasks["task-1"] = watchTask(watch)
            coEvery { gateway.cancel("task-1") } coAnswers {
                stopping.complete(Unit)
                stopped.await()
                true
            }
            val deletion = launch { svc.delete("alice", watch.id) }
            stopping.await()
            assertEquals(WatchStatus.CANCELLED, store.findById(watch.id)!!.status)
            assertFalse(deletion.isCompleted)
            stopped.complete(Unit)
            deletion.join()
            assertNull(store.findById(watch.id))
        }

        @Test
        fun `cancel and delete never overwrite a concurrent status transition`() = runTest {
            for (delete in listOf(false, true)) {
                val store = FakeWatchStore()
                val gateway = mockk<WatchRunGateway>(relaxed = true)
                val svc = service(store, gateway = gateway)
                val watch = svc.create(origin(), input()).watch
                store.tasks["task-1"] = watchTask(watch)
                store.beforeSetStatus = { store.forceStatus(watch.id, WatchStatus.COMPLETED) }
                assertThrows<WatchConflictException> {
                    if (delete) svc.delete("alice", watch.id) else svc.cancel("alice", watch.id)
                }
                assertEquals(WatchStatus.COMPLETED, store.findById(watch.id)!!.status)
                coVerify(exactly = 0) { gateway.cancel(any()) }
            }
        }

        @Test
        fun `pause does not cancel a running task`() = runTest {
            val store = FakeWatchStore()
            val gateway = mockk<WatchRunGateway>(relaxed = true)
            val svc = service(store, gateway = gateway)
            val watch = svc.create(origin(), input()).watch
            store.tasks["task-1"] = watchTask(watch)
            svc.pause("alice", watch.id)
            coVerify(exactly = 0) { gateway.cancel(any()) }
        }
    }

    @Nested
    inner class `retry admission` {

        @Test
        fun `retry waits for the old worker before resetting the snapshot retry count`() = runTest {
            val store = FakeWatchStore()
            val watch = testWatch()
            store.seed(watch)
            val original = watchTask(watch, scheduledFor = now.minusSeconds(86_400)).copy(
                status = AsyncTaskStatus.FAILED, retryCount = 4, createdAt = 123,
                error = "failed", result = mapOf("old" to true)
            )
            store.tasks[original.id] = original
            val gateway = mockk<WatchRunGateway>(relaxed = true)
            val order = mutableListOf<String>()
            coEvery { gateway.awaitIdle(original.id) } coAnswers {
                assertEquals(original, store.tasks[original.id])
                assertTrue(store.expectedRetryCounts.isEmpty())
                order += "idle"
            }
            every { gateway.dispatch(any()) } answers {
                val reset = firstArg<AsyncTask>()
                assertEquals(reset, store.tasks[original.id])
                assertEquals(listOf(4), store.expectedRetryCounts)
                order += "dispatch"
            }
            val taskStore = mockk<AsyncTaskStore>()
            val reset = service(store, taskStore = taskStore, gateway = gateway).retryRun("alice", original)
            assertEquals(listOf("idle", "dispatch"), order)
            assertEquals(original.id, reset.id)
            assertEquals(original.payload, reset.payload)
            assertEquals(original.trigger, reset.trigger)
            assertEquals(original.createdAt, reset.createdAt)
            assertEquals(5, reset.retryCount)
            assertEquals(now.toEpochMilli(), reset.retriedAt)
            assertEquals(AsyncTaskStatus.PENDING, reset.status)
            assertNull(reset.error)
            assertNull(reset.result)
            assertEquals(watch.nextDueAt, store.findById(watch.id)!!.nextDueAt)
            coVerify(exactly = 0) { taskStore.retry(any()) }
        }

        @Test
        fun `retry conflicts with another active run and with an already consumed snapshot`() = runTest {
            val store = FakeWatchStore()
            val watch = testWatch()
            store.seed(watch)
            val original = watchTask(watch).copy(status = AsyncTaskStatus.FAILED, retryCount = 2)
            store.tasks[original.id] = original
            store.tasks["other"] = watchTask(watch, id = "other")
            val gateway = mockk<WatchRunGateway>(relaxed = true)
            val svc = service(store, gateway = gateway)
            assertThrows<WatchConflictException> { svc.retryRun("alice", original) }
            store.tasks.remove("other")
            store.tasks[original.id] = original.copy(retryCount = 3)
            assertThrows<WatchConflictException> { svc.retryRun("alice", original) }
            assertEquals(listOf(2, 2), store.expectedRetryCounts)
            assertTrue(store.admissions.isEmpty())
            verify(exactly = 0) { gateway.dispatch(any()) }
        }

        @Test
        fun `retry revalidates watch status after the old worker finishes`() = runTest {
            val store = FakeWatchStore()
            val watch = testWatch()
            store.seed(watch)
            val original = watchTask(watch).copy(status = AsyncTaskStatus.FAILED)
            store.tasks[original.id] = original
            val gateway = mockk<WatchRunGateway>(relaxed = true)
            coEvery { gateway.awaitIdle(original.id) } coAnswers {
                store.forceStatus(watch.id, WatchStatus.CANCELLED)
            }
            assertThrows<WatchConflictException> { service(store, gateway = gateway).retryRun("alice", original) }
            assertEquals(original, store.tasks[original.id])
            verify(exactly = 0) { gateway.dispatch(any()) }
        }

        @Test
        fun `retry validates task and watch ownership before waiting or admission`() = runTest {
            val store = FakeWatchStore()
            val watch = testWatch()
            store.seed(watch)
            val task = watchTask(watch).copy(status = AsyncTaskStatus.FAILED)
            val gateway = mockk<WatchRunGateway>(relaxed = true)
            val svc = service(store, gateway = gateway)
            assertThrows<WatchException> { svc.retryRun("alice", task.copy(type = AsyncTaskType("other"))) }
            assertThrows<WatchNotFoundException> { svc.retryRun("bob", task) }
            assertThrows<WatchException> { svc.retryRun("alice", task.copy(payload = emptyMap())) }
            assertThrows<WatchConflictException> { svc.retryRun("alice", task.copy(status = AsyncTaskStatus.RUNNING)) }
            store.seed(watch.copy(userId = "bob"))
            assertThrows<WatchNotFoundException> { svc.retryRun("alice", task) }
            assertTrue(store.admissions.isEmpty())
            coVerify(exactly = 0) { gateway.awaitIdle(any()) }
        }

        @Test
        fun `retry requires a gateway before touching the task`() = runTest {
            val store = FakeWatchStore()
            val watch = testWatch()
            store.seed(watch)
            assertThrows<WatchUnavailableException> {
                service(store).retryRun("alice", watchTask(watch).copy(status = AsyncTaskStatus.FAILED))
            }
            assertTrue(store.expectedRetryCounts.isEmpty())
        }

        @Test
        fun `retry wait is cancellable and cannot reset the task before idle`() = runTest {
            val store = FakeWatchStore()
            val watch = testWatch()
            store.seed(watch)
            val task = watchTask(watch).copy(status = AsyncTaskStatus.FAILED)
            store.tasks[task.id] = task
            val waiting = CompletableDeferred<Unit>()
            val idle = CompletableDeferred<Unit>()
            val gateway = mockk<WatchRunGateway>(relaxed = true)
            coEvery { gateway.awaitIdle(task.id) } coAnswers {
                waiting.complete(Unit)
                idle.await()
            }
            val request = launch { service(store, gateway = gateway).retryRun("alice", task) }
            waiting.await()
            request.cancel()
            request.join()
            assertTrue(store.expectedRetryCounts.isEmpty())
            assertEquals(task, store.tasks[task.id])
            verify(exactly = 0) { gateway.dispatch(any()) }
        }

        @Test
        fun `caller cancellation cannot strand a committed retry`() = runTest {
            val store = FakeWatchStore()
            val watch = testWatch()
            store.seed(watch)
            val task = watchTask(watch).copy(status = AsyncTaskStatus.FAILED)
            store.tasks[task.id] = task
            val committed = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val delayedStore = object : WatchStore by store {
                override suspend fun retryRun(
                    userId: String, watchId: String, taskId: String, expectedRetryCount: Int, now: Instant
                ): WatchEnqueueResult {
                    val result = store.retryRun(userId, watchId, taskId, expectedRetryCount, now)
                    committed.complete(Unit)
                    release.await()
                    return result
                }
            }
            val gateway = mockk<WatchRunGateway>(relaxed = true)
            val request = launch { service(delayedStore, gateway = gateway).retryRun("alice", task) }
            committed.await()
            request.cancel()
            release.complete(Unit)
            request.join()
            verify(exactly = 1) { gateway.dispatch(store.admissions.single()) }
        }
    }

}

/** In-memory [WatchStore] honouring the same version guard the SQL store uses. */
private class FakeWatchStore : WatchStore {

    private val rows = linkedMapOf<String, Watch>()
    val tasks = linkedMapOf<String, AsyncTask>()
    val admissions = mutableListOf<AsyncTask>()
    val expectedRetryCounts = mutableListOf<Int>()

    /** Runs immediately before a guarded definition write, so a test can simulate a lost race. */
    var beforeUpdateDefinition: (() -> Unit)? = null

    /** Runs immediately before a status write, so a test can simulate a concurrent transition. */
    var beforeSetStatus: (() -> Unit)? = null

    override suspend fun create(watch: Watch): Watch {
        rows[watch.id] = watch
        return watch
    }

    /** Insert a row exactly as given, bypassing the create path. */
    fun seed(watch: Watch) {
        rows[watch.id] = watch
    }

    override suspend fun findById(id: String): Watch? = rows[id]

    override suspend fun listByUser(userId: String, statuses: Set<WatchStatus>?, limit: Int): List<Watch> =
        rows.values.filter { it.userId == userId && (statuses == null || it.status in statuses) }
            .sortedByDescending { it.createdAt }
            .take(limit)

    override suspend fun countActive(userId: String): Int =
        rows.values.count { it.userId == userId && it.status == WatchStatus.ACTIVE }

    override suspend fun listUpcoming(
        horizon: Instant,
        cursor: WatchScheduleCursor?,
        limit: Int,
        userId: String?
    ): List<WatchScheduleEntry> = rows.values.mapNotNull { findSchedule(it.id) }
        .filter {
            it.nextDueAt <= horizon && (userId == null || it.userId == userId) &&
                (cursor == null || it.nextDueAt > cursor.nextDueAt ||
                    (it.nextDueAt == cursor.nextDueAt && it.watchId > cursor.watchId))
        }.sortedWith(compareBy({ it.nextDueAt }, { it.watchId })).take(limit)

    override suspend fun findSchedule(watchId: String): WatchScheduleEntry? = rows[watchId]
        ?.takeIf { it.status == WatchStatus.ACTIVE }
        ?.let { WatchScheduleEntry(it.id, it.userId, it.version, it.nextDueAt, it.pendingAction) }

    override suspend fun enqueueScheduled(
        expected: WatchScheduleEntry,
        now: Instant,
        properties: WatchProperties
    ): WatchEnqueueResult {
        if (findSchedule(expected.watchId) != expected || expected.nextDueAt > now) return WatchEnqueueResult.Stale
        listInFlight(expected.userId, expected.watchId).firstOrNull()?.let { return WatchEnqueueResult.Busy(it.id) }
        val watch = rows.getValue(expected.watchId)
        val decision = WatchClaimPolicy.decide(watch, now, properties) ?: return WatchEnqueueResult.Stale
        val task = watchTask(watch, decision.kind, watch.nextDueAt, "task-${tasks.size + 1}")
            .copy(timeoutMs = properties.runTimeoutMs)
        rows[watch.id] = watch.copy(nextDueAt = decision.newNextDueAt, pendingAction = decision.pendingAction)
        tasks[task.id] = task
        admissions += task
        return WatchEnqueueResult.Enqueued(task)
    }

    override suspend fun enqueueManual(
        userId: String,
        watchId: String,
        now: Instant,
        properties: WatchProperties,
        trigger: TaskTrigger
    ): WatchEnqueueResult {
        val watch = rows[watchId] ?: return WatchEnqueueResult.Stale
        if (watch.userId != userId || findSchedule(watchId) == null ||
            watch.pendingAction != WatchPendingAction.CHECK
        ) return WatchEnqueueResult.Stale
        listInFlight(userId, watchId).firstOrNull()?.let { return WatchEnqueueResult.Busy(it.id) }
        val task = watchTask(watch, scheduledFor = now, id = "task-${tasks.size + 1}")
            .copy(trigger = trigger, timeoutMs = properties.runTimeoutMs)
        tasks[task.id] = task
        admissions += task
        return WatchEnqueueResult.Enqueued(task)
    }

    override suspend fun retryRun(
        userId: String,
        watchId: String,
        taskId: String,
        expectedRetryCount: Int,
        now: Instant
    ): WatchEnqueueResult {
        expectedRetryCounts += expectedRetryCount
        val watch = rows[watchId] ?: return WatchEnqueueResult.Stale
        val task = tasks[taskId] ?: return WatchEnqueueResult.Stale
        if (watch.userId != userId || findSchedule(watchId) == null ||
            task.userId != userId || WatchTaskPayload.watchIdOf(task) != watchId ||
            !task.status.isTerminal || task.retryCount != expectedRetryCount ||
            (WatchTaskPayload.kindOf(task) == WatchRunKind.EXPIRY_REVIEW &&
                watch.pendingAction != WatchPendingAction.EXPIRY_REVIEW)
        ) return WatchEnqueueResult.Stale
        listInFlight(userId, watchId).firstOrNull()?.let { return WatchEnqueueResult.Busy(it.id) }
        val reset = task.copy(
            status = AsyncTaskStatus.PENDING, retryCount = expectedRetryCount + 1,
            retriedAt = now.toEpochMilli(), error = null, result = null, startedAt = null, completedAt = null
        )
        tasks[task.id] = reset
        admissions += reset
        return WatchEnqueueResult.Enqueued(reset)
    }

    override suspend fun listInFlight(userId: String, watchId: String): List<AsyncTask> =
        tasks.values.filter { it.userId == userId && WatchTaskPayload.watchIdOf(it) == watchId && !it.status.isTerminal }

    override suspend fun claimRunSlot(task: AsyncTask, expectedVersion: Int, at: Instant): Boolean {
        val row = rows[WatchTaskPayload.watchIdOf(task)] ?: return false
        if (row.version != expectedVersion || row.status != WatchStatus.ACTIVE) return false
        rows[row.id] = row.copy(lastTaskId = task.id, lastRunAt = at.toEpochMilli())
        return true
    }

    override suspend fun applyFinding(
        id: String,
        expectedVersion: Int,
        expectedStatus: WatchStatus,
        status: WatchStatus,
        pendingAction: WatchPendingAction,
        nextDueAt: Long,
        finding: WatchFinding,
        taskId: String,
        at: Long
    ): Watch? {
        val row = rows[id] ?: return null
        if (row.version != expectedVersion || row.status != expectedStatus) return null
        val updated = row.copy(
            status = status,
            pendingAction = pendingAction,
            nextDueAt = Instant.ofEpochMilli(nextDueAt),
            lastFinding = finding,
            lastTaskId = taskId,
            lastRunAt = at,
            consecutiveFailures = 0,
            lastError = null,
            updatedAt = at
        )
        rows[id] = updated
        return updated
    }

    override suspend fun recordFailure(
        id: String,
        expectedVersion: Int,
        expectedStatus: WatchStatus,
        error: String,
        nextDueAt: Long,
        status: WatchStatus,
        consecutiveFailures: Int,
        statusReason: String?,
        taskId: String?,
        at: Long
    ): Watch? {
        val row = rows[id] ?: return null
        if (row.version != expectedVersion || row.status != expectedStatus) return null
        val updated = row.copy(
            status = status,
            nextDueAt = Instant.ofEpochMilli(nextDueAt),
            consecutiveFailures = consecutiveFailures,
            lastError = error,
            statusReason = statusReason,
            lastTaskId = taskId ?: row.lastTaskId,
            updatedAt = at
        )
        rows[id] = updated
        return updated
    }

    override suspend fun applyExpiryReview(
        id: String,
        expectedVersion: Int,
        expectedPendingAction: WatchPendingAction,
        status: WatchStatus,
        pendingAction: WatchPendingAction,
        untilAt: Long?,
        anchorAt: Long?,
        nextDueAt: Long,
        extensionCount: Int,
        statusReason: String?,
        taskId: String,
        at: Long
    ): Watch? {
        val row = rows[id] ?: return null
        if (row.version != expectedVersion || row.pendingAction != expectedPendingAction) return null
        val updated = row.copy(
            status = status,
            pendingAction = pendingAction,
            untilAt = untilAt?.let(Instant::ofEpochMilli),
            schedule = row.schedule.copy(anchorAt = anchorAt?.let(Instant::ofEpochMilli)),
            nextDueAt = Instant.ofEpochMilli(nextDueAt),
            extensionCount = extensionCount,
            statusReason = statusReason,
            lastTaskId = taskId,
            updatedAt = at
        )
        rows[id] = updated
        return updated
    }

    override suspend fun updateDefinition(
        watch: Watch,
        expectedVersion: Int,
        expectedStatus: WatchStatus
    ): Watch? {
        beforeUpdateDefinition?.invoke()
        beforeUpdateDefinition = null
        val row = rows[watch.id] ?: return null
        if (row.version != expectedVersion || row.status != expectedStatus) return null
        rows[watch.id] = watch
        return watch
    }

    override suspend fun setStatus(
        id: String,
        status: WatchStatus,
        statusReason: String?,
        pendingAction: WatchPendingAction?,
        nextDueAt: Long?,
        expectedStatus: WatchStatus?,
        clearFailures: Boolean
    ): Watch? {
        beforeSetStatus?.invoke()
        beforeSetStatus = null
        val row = rows[id] ?: return null
        if (expectedStatus != null && row.status != expectedStatus) return null
        val updated = row.copy(
            status = status,
            statusReason = statusReason,
            pendingAction = pendingAction ?: row.pendingAction,
            nextDueAt = nextDueAt?.let(Instant::ofEpochMilli) ?: row.nextDueAt,
            consecutiveFailures = if (clearFailures) 0 else row.consecutiveFailures,
            lastError = if (clearFailures) null else row.lastError
        )
        rows[id] = updated
        return updated
    }

    override suspend fun delete(id: String): Boolean = rows.remove(id) != null

    fun forceVersion(id: String, version: Int) {
        rows[id] = rows.getValue(id).copy(version = version)
    }

    fun forceStatus(id: String, status: WatchStatus) {
        rows[id] = rows.getValue(id).copy(status = status)
    }

    fun forceFailures(id: String, count: Int, error: String?) {
        rows[id] = rows.getValue(id).copy(consecutiveFailures = count, lastError = error)
    }

    fun forceNextDue(id: String, nextDueAt: Instant) {
        rows[id] = rows.getValue(id).copy(nextDueAt = nextDueAt)
    }
}
