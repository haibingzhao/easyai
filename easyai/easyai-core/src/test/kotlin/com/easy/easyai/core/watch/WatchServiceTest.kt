package com.easy.easyai.core.watch

import com.easy.easyai.core.model.ProjectKind
import com.easy.easyai.core.task.AsyncTask
import com.easy.easyai.core.task.AsyncTaskStore
import com.easy.easyai.core.task.AsyncTaskType
import com.easy.easyai.core.task.ScheduledTaskSettingStore
import com.easy.easyai.core.task.TaskTrigger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
        notifyTags = notifyTags
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
            val taskStore = mockk<AsyncTaskStore>(relaxed = true)
            val gateway = mockk<WatchRunGateway>(relaxed = true)
            coEvery { taskStore.listNonTerminal(any(), any()) } returns emptyList()
            val svc = service(store, taskStore = taskStore, gateway = gateway)
            val watch = svc.create(origin(), input()).watch

            store.setStatus(watch.id, WatchStatus.ACTIVE, null, WatchPendingAction.EXPIRY_REVIEW)
            assertThrows<WatchException> { svc.runNow("alice", watch.id) }
            store.setStatus(watch.id, WatchStatus.ACTIVE, null, WatchPendingAction.CHECK)

            coEvery { taskStore.listNonTerminal(any(), any()) } returns listOf(
                AsyncTask(
                    id = "task-1",
                    userId = "alice",
                    type = AsyncTaskType.WATCH,
                    trigger = TaskTrigger.FRONTEND,
                    title = "Watch",
                    sessionId = watch.runSessionId,
                    payload = mapOf(WatchTaskPayload.KEY_WATCH_ID to watch.id)
                )
            )
            assertThrows<WatchException> { svc.runNow("alice", watch.id) }
        }

        @Test
        fun `run now creates a check task without moving the schedule`() = runTest {
            val store = FakeWatchStore()
            val taskStore = mockk<AsyncTaskStore>(relaxed = true)
            val gateway = mockk<WatchRunGateway>(relaxed = true)
            coEvery { taskStore.listNonTerminal(any(), any()) } returns emptyList()
            coEvery { gateway.launch(any()) } answers { firstArg() }
            val svc = service(store, taskStore = taskStore, gateway = gateway)
            val watch = svc.create(origin(), input()).watch
            val task = svc.runNow("alice", watch.id)
            assertEquals(WatchRunKind.CHECK.name, task.payload[WatchTaskPayload.KEY_KIND])
            assertEquals(watch.id, task.payload[WatchTaskPayload.KEY_WATCH_ID])
            assertEquals(watch.runSessionId, task.sessionId)
            assertEquals(watch.nextDueAt, store.findById(watch.id)!!.nextDueAt)
        }

        @Test
        fun `cancel stops the in-flight task before flipping the status`() = runTest {
            val store = FakeWatchStore()
            val taskStore = mockk<AsyncTaskStore>(relaxed = true)
            val gateway = mockk<WatchRunGateway>(relaxed = true)
            val svc = service(store, taskStore = taskStore, gateway = gateway)
            val watch = svc.create(origin(), input()).watch
            coEvery { taskStore.listNonTerminal(any(), any()) } returns listOf(
                AsyncTask(
                    id = "task-1",
                    userId = "alice",
                    type = AsyncTaskType.WATCH,
                    trigger = TaskTrigger.FRONTEND,
                    title = "Watch",
                    payload = mapOf(WatchTaskPayload.KEY_WATCH_ID to watch.id)
                )
            )
            coEvery { gateway.cancel("task-1") } returns true
            val cancelled = svc.cancel("alice", watch.id)
            assertEquals(WatchStatus.CANCELLED, cancelled.status)
            coVerify { gateway.cancel("task-1") }
        }
    }

}

/** In-memory [WatchStore] honouring the same version guard the SQL store uses. */
private class FakeWatchStore : WatchStore {

    private val rows = linkedMapOf<String, Watch>()

    /** Runs immediately before a guarded definition write, so a test can simulate a lost race. */
    var beforeUpdateDefinition: (() -> Unit)? = null

    /** Runs immediately before a status write, so a test can simulate a concurrent transition. */
    var beforeSetStatus: (() -> Unit)? = null

    override suspend fun create(watch: Watch): Watch {
        rows[watch.id] = watch
        return watch
    }

    override suspend fun findById(id: String): Watch? = rows[id]

    override suspend fun listByUser(userId: String, statuses: Set<WatchStatus>?, limit: Int): List<Watch> =
        rows.values.filter { it.userId == userId && (statuses == null || it.status in statuses) }
            .sortedByDescending { it.createdAt }
            .take(limit)

    override suspend fun countActive(userId: String): Int =
        rows.values.count { it.userId == userId && it.status == WatchStatus.ACTIVE }

    override suspend fun listDue(userId: String, now: Long, limit: Int): List<Watch> =
        rows.values.filter { it.userId == userId && it.status == WatchStatus.ACTIVE && it.nextDueAt.toEpochMilli() <= now }
            .sortedBy { it.nextDueAt }
            .take(limit)

    override suspend fun claim(
        id: String,
        expectedNextDueAt: Long,
        newNextDueAt: Long,
        pendingAction: WatchPendingAction,
        newStatus: WatchStatus?,
        statusReason: String?
    ): Boolean {
        val row = rows[id] ?: return false
        if (row.nextDueAt.toEpochMilli() != expectedNextDueAt) return false
        rows[id] = row.copy(
            nextDueAt = Instant.ofEpochMilli(newNextDueAt),
            pendingAction = pendingAction,
            status = newStatus ?: row.status,
            statusReason = statusReason ?: row.statusReason
        )
        return true
    }

    override suspend fun markRunStarted(id: String, taskId: String, at: Long): Boolean {
        val row = rows[id] ?: return false
        rows[id] = row.copy(lastTaskId = taskId, lastRunAt = at)
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
        expectedStatus: WatchStatus?
    ): Watch? {
        beforeSetStatus?.invoke()
        beforeSetStatus = null
        val row = rows[id] ?: return null
        if (expectedStatus != null && row.status != expectedStatus) return null
        val updated = row.copy(
            status = status,
            statusReason = statusReason,
            pendingAction = pendingAction ?: row.pendingAction,
            nextDueAt = nextDueAt?.let(Instant::ofEpochMilli) ?: row.nextDueAt
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
}
