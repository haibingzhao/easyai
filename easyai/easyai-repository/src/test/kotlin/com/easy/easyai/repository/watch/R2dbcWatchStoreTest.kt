package com.easy.easyai.repository.watch

import com.easy.easyai.core.host.HostIdentity
import com.easy.easyai.core.model.ProjectKind
import com.easy.easyai.core.task.AsyncTask
import com.easy.easyai.core.task.AsyncTaskStatus
import com.easy.easyai.core.task.AsyncTaskType
import com.easy.easyai.core.task.ScheduledTaskSetting
import com.easy.easyai.core.task.TaskTrigger
import com.easy.easyai.core.watch.Watch
import com.easy.easyai.core.watch.WatchClaimPolicy
import com.easy.easyai.core.watch.WatchEnqueueResult
import com.easy.easyai.core.watch.WatchFinding
import com.easy.easyai.core.watch.WatchFoundTag
import com.easy.easyai.core.watch.WatchMode
import com.easy.easyai.core.watch.WatchNotifyPolicy
import com.easy.easyai.core.watch.WatchPendingAction
import com.easy.easyai.core.watch.WatchProperties
import com.easy.easyai.core.watch.WatchRunKind
import com.easy.easyai.core.watch.WatchSchedule
import com.easy.easyai.core.watch.WatchScheduleChanged
import com.easy.easyai.core.watch.WatchScheduleCursor
import com.easy.easyai.core.watch.WatchScheduleEntry
import com.easy.easyai.core.watch.WatchScheduleKind
import com.easy.easyai.core.watch.WatchSource
import com.easy.easyai.core.watch.WatchStatus
import com.easy.easyai.core.watch.WatchTag
import com.easy.easyai.core.watch.WatchTaskPayload
import com.easy.easyai.core.watch.WatchVerdict
import com.easy.easyai.repository.database.DatabaseMigration
import com.easy.easyai.repository.database.Tables.AsyncTaskTable
import com.easy.easyai.repository.database.Tables.ScheduledTaskSettingTable
import com.easy.easyai.repository.database.Tables.WatchTable
import com.easy.easyai.repository.task.R2dbcAsyncTaskStore
import com.easy.easyai.repository.task.R2dbcScheduledTaskSettingStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.statements.SuspendStatementInterceptor
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.inTopLevelSuspendTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** H2 integration coverage for Watch admission, run slots, guarded writes and commit notifications. */
private const val SELF = "agent-api"

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class R2dbcWatchStoreTest {

    private lateinit var db: R2dbcDatabase

    @BeforeAll
    fun setupDb() = runTest {
        db = R2dbcDatabase.connect(
            url = "r2dbc:h2:mem:///watch_test_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1",
            manager = { TransactionManager(it) }
        )
        DatabaseMigration.defaultTables().execute(db)
    }

    private fun store(onChanged: (WatchScheduleChanged) -> Unit = {}) =
        R2dbcWatchStore(db, HostIdentity(SELF), onChanged = onChanged)

    private fun tasks() = R2dbcAsyncTaskStore(db, HostIdentity(SELF))

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-25T04:00:00Z")
    private val properties = WatchProperties()

    private fun watch(
        id: String = UUID.randomUUID().toString(),
        userId: String = "user-${UUID.randomUUID()}",
        title: String = "Price watch",
        status: WatchStatus = WatchStatus.ACTIVE,
        pendingAction: WatchPendingAction = WatchPendingAction.CHECK,
        version: Int = 1,
        nextDueAt: Instant = Instant.parse("2026-10-25T01:00:00Z"),
        createdAt: Long = 1_000,
        schedule: WatchSchedule = WatchSchedule(
            kind = WatchScheduleKind.WEEKLY,
            zone = zone,
            localTime = LocalTime.of(9, 0),
            weekday = DayOfWeek.MONDAY
        ),
        lastFinding: WatchFinding? = null
    ) = Watch(
        id = id,
        userId = userId,
        title = title,
        condition = "Check whether the price crossed 100",
        mode = WatchMode.CHECK,
        reminderText = null,
        schedule = schedule,
        untilAt = Instant.parse("2026-12-31T15:59:59Z"),
        nextDueAt = nextDueAt,
        status = status,
        pendingAction = pendingAction,
        version = version,
        extensionCount = 2,
        tags = listOf(WatchTag("crossed", "已突破", "Price above the line")),
        completeOnMet = true,
        notifyPolicy = WatchNotifyPolicy.ON_CHANGE,
        notifyTags = setOf("crossed"),
        sourceAgentId = "default-agent",
        modelConfigId = "model-1",
        projectId = "project-1",
        projectPath = "/tmp/project",
        projectKind = ProjectKind.USER,
        owners = setOf("alice", "group-1"),
        originSessionId = "origin-session",
        runSessionId = "run-$id",
        allowedTools = listOf("webfetch", "memory_search"),
        sourceHints = "look at the quote page",
        lastTaskId = "task-0",
        lastRunAt = 900,
        lastFinding = lastFinding,
        consecutiveFailures = 1,
        lastError = "previous failure",
        statusReason = "because",
        createdAt = createdAt,
        updatedAt = 999
    )

    private suspend fun setting(userId: String, enabled: Boolean = true, timeoutMs: Long? = null) {
        R2dbcScheduledTaskSettingStore(db, HostIdentity(SELF)).save(
            ScheduledTaskSetting(userId = userId, taskType = AsyncTaskType.WATCH, enabled = enabled, timeoutMs = timeoutMs)
        )
    }

    private fun snapshot(watch: Watch) = WatchScheduleEntry(
        watch.id, watch.userId, watch.ownerApp ?: SELF, watch.version, watch.nextDueAt, watch.pendingAction
    )

    private fun task(watch: Watch, status: AsyncTaskStatus = AsyncTaskStatus.PENDING) = AsyncTask(
        id = UUID.randomUUID().toString(),
        userId = watch.userId,
        type = AsyncTaskType.WATCH,
        trigger = TaskTrigger.SCHEDULE,
        title = watch.title,
        status = status,
        sessionId = watch.runSessionId,
        ownerApp = SELF,
        payload = mapOf(
            WatchTaskPayload.KEY_WATCH_ID to watch.id,
            WatchTaskPayload.KEY_KIND to WatchRunKind.CHECK.name,
            WatchTaskPayload.KEY_SCHEDULED_FOR to watch.nextDueAt.toEpochMilli(),
            WatchTaskPayload.KEY_DEFINITION_VERSION to watch.version,
            WatchTaskPayload.KEY_TITLE to watch.title
        ),
        createdAt = now.toEpochMilli(),
        updatedAt = now.toEpochMilli()
    )

    /** Arrange the existing handler-CAS tests independently of admission and execution. */
    private suspend fun setLastTaskId(watchId: String, taskId: String) = suspendTransaction(db) {
        WatchTable.update({ WatchTable.id eq watchId }) { it[lastTaskId] = taskId }
    }

    private suspend fun taskCount(watch: Watch): Long = suspendTransaction(db) {
        AsyncTaskTable.selectAll().where {
            (AsyncTaskTable.userId eq watch.userId) and (AsyncTaskTable.sessionId eq watch.runSessionId)
        }.count()
    }

    private fun finding() = WatchFinding(
        conclusion = "Price crossed 100",
        verdict = WatchVerdict.MET,
        tags = listOf(WatchFoundTag("crossed", "已突破")),
        sources = listOf(WatchSource("Quote", "https://example.com/q")),
        definitionVersion = 1,
        changed = true,
        notified = true,
        scheduledFor = Instant.parse("2026-10-25T01:00:00Z").toEpochMilli(),
        lateByMs = 5,
        applied = true,
        stale = false
    )

    @Nested
    inner class `round trip` {

        @Test
        fun `every field survives a write and read`() = runTest {
            val store = store()
            val original = watch(lastFinding = finding())
            store.create(original)
            val loaded = store.findById(original.id)!!

            assertEquals(original.title, loaded.title)
            assertEquals(original.condition, loaded.condition)
            assertEquals(original.mode, loaded.mode)
            assertEquals(original.schedule.kind, loaded.schedule.kind)
            assertEquals(original.schedule.zone, loaded.schedule.zone)
            assertEquals(original.schedule.localTime, loaded.schedule.localTime)
            assertEquals(original.schedule.weekday, loaded.schedule.weekday)
            assertEquals(original.untilAt, loaded.untilAt)
            assertEquals(original.nextDueAt, loaded.nextDueAt)
            assertEquals(original.pendingAction, loaded.pendingAction)
            assertEquals(original.extensionCount, loaded.extensionCount)
            assertEquals(original.tags, loaded.tags)
            assertEquals(original.notifyTags, loaded.notifyTags)
            assertEquals(original.owners, loaded.owners)
            assertEquals(original.allowedTools, loaded.allowedTools)
            assertEquals(original.sourceHints, loaded.sourceHints)
            assertEquals(original.consecutiveFailures, loaded.consecutiveFailures)
            assertEquals(original.statusReason, loaded.statusReason)
            assertEquals(original.lastFinding, loaded.lastFinding)
        }
    }

    @Nested
    inner class `listing` {

        @Test
        fun `reads are strictly scoped to the owner`() = runTest {
            val store = store()
            store.create(watch(userId = "alice"))
            store.create(watch(userId = "bob"))
            assertEquals(1, store.listByUser("alice").size)
            assertEquals(1, store.countActive("alice"))
            assertEquals(0, store.listByUser("carol").size)
        }

        @Test
        fun `create stamps the owning application`() = runTest {
            val created = store().create(watch(userId = "owner-${UUID.randomUUID()}"))
            assertEquals(SELF, created.ownerApp)
            assertEquals(SELF, store().findById(created.id)?.ownerApp)
        }

        @Test
        fun `another application's watch is neither listed nor scheduled, but is readable by id`() = runTest {
            val user = "host-${UUID.randomUUID()}"
            val now = Instant.parse("2026-10-26T00:00:00Z")
            val mine = store().create(watch(userId = user))
            val theirs = R2dbcWatchStore(db, HostIdentity("trading-api")).create(watch(userId = user))
            setting(user)

            assertEquals(listOf(mine.id), store().listUpcoming(now, userId = user).map { it.watchId })
            assertEquals(listOf(mine.id), store().listByUser(user).map { it.id })
            assertEquals(1, store().countActive(user))
            // The handler reads by id so it can say *why* it refuses to run the row.
            assertEquals("trading-api", store().findById(theirs.id)?.ownerApp)
        }

        @Test
        fun `an unclaimed watch is not scheduled`() = runTest {
            val store = store()
            val user = "legacy-${UUID.randomUUID()}"
            val created = store.create(watch(userId = user))
            // A row written before owner_app existed: the column is null and nothing claims it.
            suspendTransaction(db) {
                WatchTable.update({ WatchTable.id eq created.id }) { it[WatchTable.ownerApp] = null }
            }

            setting(user)
            assertEquals(emptyList(), store.listUpcoming(Instant.parse("2026-10-26T00:00:00Z"), userId = user))
            assertNull(store.findSchedule(created.id))
            assertEquals(emptyList(), store.listByUser(user))
            assertNull(store.findById(created.id)?.ownerApp)
        }

        @Test
        fun `statuses filter the listing`() = runTest {
            val store = store()
            val active = watch(userId = "dana")
            store.create(active)
            store.create(watch(userId = "dana", status = WatchStatus.PAUSED))
            assertEquals(1, store.listByUser("dana", setOf(WatchStatus.ACTIVE)).size)
            assertEquals(2, store.listByUser("dana").size)
        }

        @Test
        fun `due rows are active and ordered by their due instant`() = runTest {
            val store = store()
            val now = Instant.parse("2026-10-25T04:00:00Z").toEpochMilli()
            store.create(watch(userId = "erin", nextDueAt = Instant.ofEpochMilli(now - 500), createdAt = 1))
            store.create(watch(userId = "erin", nextDueAt = Instant.ofEpochMilli(now - 1000), createdAt = 2))
            store.create(watch(userId = "erin", nextDueAt = Instant.ofEpochMilli(now + 500), createdAt = 3))
            store.create(watch(userId = "erin", status = WatchStatus.PAUSED, nextDueAt = Instant.ofEpochMilli(now - 200), createdAt = 4))

            setting("erin")
            val due = store.listUpcoming(Instant.ofEpochMilli(now), userId = "erin")
            assertEquals(2, due.size)
            assertTrue(due[0].nextDueAt < due[1].nextDueAt)
        }

        @Test
        fun `an unreadable enum row is skipped rather than failing the list`() = runTest {
            val store = store()
            val healthy = watch(userId = "frank")
            store.create(healthy)
            suspendTransaction(db) {
                WatchTable.insert {
                    it[id] = "broken"
                    it[userId] = "frank"
                    it[title] = "Broken"
                    it[conditionText] = "x"
                    it[mode] = "NOT_A_MODE"
                    it[scheduleKind] = WatchScheduleKind.DAILY.name
                    it[timezone] = zone.id
                    it[nextDueAt] = 0
                    it[status] = WatchStatus.ACTIVE.name
                    it[pendingAction] = WatchPendingAction.CHECK.name
                    it[version] = 1
                    it[extensionCount] = 0
                    it[tags] = "[]"
                    it[completeOnMet] = true
                    it[notifyPolicy] = WatchNotifyPolicy.ON_CHANGE.name
                    it[notifyTags] = "[]"
                    it[sourceAgentId] = "default-agent"
                    it[modelConfigId] = "model-1"
                    it[projectKind] = ProjectKind.USER.name
                    it[owners] = "[]"
                    it[runSessionId] = "run"
                    it[allowedTools] = "[]"
                    it[consecutiveFailures] = 0
                    it[createdAt] = 1
                    it[updatedAt] = 1
                }
            }
            val rows = store.listByUser("frank")
            assertEquals(listOf(healthy.id), rows.map { it.id })
        }
    }

    @Nested
    inner class `guarded writes` {

        @Test
        fun `an automatic admission advances the due and inserts the task exactly once`() = runTest {
            val store = store()
            val original = watch()
            store.create(original)
            setting(original.userId)
            val snapshot = snapshot(store.findById(original.id)!!)

            val first = assertIs<WatchEnqueueResult.Enqueued>(
                store.enqueueScheduled(snapshot, now, properties)
            )
            val scheduledFor = (first.task.payload[WatchTaskPayload.KEY_SCHEDULED_FOR] as Number).toLong()
            assertEquals(original.nextDueAt.toEpochMilli(), scheduledFor)

            assertIs<WatchEnqueueResult.Stale>(store.enqueueScheduled(snapshot, now, properties))

            val updated = store.findById(original.id)!!
            val decision = assertNotNull(WatchClaimPolicy.decide(original, now, properties))
            assertEquals(decision.newNextDueAt, updated.nextDueAt)
            assertEquals(first.task.id, updated.lastTaskId)
            assertEquals(1, taskCount(original))
        }

        @Test
        fun `an automatic admission can route the row into an expiry review`() = runTest {
            val store = store()
            val original = watch().copy(untilAt = now.minusSeconds(3_600))
            store.create(original)
            setting(original.userId)

            val result = store.enqueueScheduled(snapshot(store.findById(original.id)!!), now, properties)

            val task = assertIs<WatchEnqueueResult.Enqueued>(result).task
            assertEquals(WatchRunKind.EXPIRY_REVIEW.name, task.payload[WatchTaskPayload.KEY_KIND])
            val updated = store.findById(original.id)!!
            assertEquals(WatchPendingAction.EXPIRY_REVIEW, updated.pendingAction)
            assertNotNull(updated.statusReason)
        }

        @Test
        fun `a finding is refused after the definition changed`() = runTest {
            val store = store()
            val original = watch()
            store.create(original)
            setLastTaskId(original.id, "task-1")

            assertNull(
                store.applyFinding(
                    id = original.id,
                    expectedVersion = original.version + 1,
                    expectedStatus = WatchStatus.ACTIVE,
                    status = WatchStatus.COMPLETED,
                    pendingAction = WatchPendingAction.CHECK,
                    nextDueAt = 5,
                    finding = finding(),
                    taskId = "task-1",
                    at = 10
                )
            )
            assertNotNull(
                store.applyFinding(
                    id = original.id,
                    expectedVersion = original.version,
                    expectedStatus = WatchStatus.ACTIVE,
                    status = WatchStatus.COMPLETED,
                    pendingAction = WatchPendingAction.CHECK,
                    nextDueAt = 5,
                    finding = finding(),
                    taskId = "task-1",
                    at = 10
                )
            )
            val updated = store.findById(original.id)!!
            assertEquals(WatchStatus.COMPLETED, updated.status)
            assertEquals(finding(), updated.lastFinding)
            assertEquals(0, updated.consecutiveFailures)
            assertNull(updated.lastError)
            assertNull(updated.statusReason)
        }

        @Test
        fun `a finding from a run that lost the task slot is refused`() = runTest {
            val store = store()
            val original = watch()
            store.create(original)
            setLastTaskId(original.id, "task-1")
            setLastTaskId(original.id, "task-2")

            assertNull(
                store.applyFinding(
                    id = original.id,
                    expectedVersion = original.version,
                    expectedStatus = WatchStatus.ACTIVE,
                    status = WatchStatus.ACTIVE,
                    pendingAction = WatchPendingAction.CHECK,
                    nextDueAt = 5,
                    finding = finding(),
                    taskId = "task-1",
                    at = 10
                )
            )
            assertNotNull(
                store.applyFinding(
                    id = original.id,
                    expectedVersion = original.version,
                    expectedStatus = WatchStatus.ACTIVE,
                    status = WatchStatus.ACTIVE,
                    pendingAction = WatchPendingAction.CHECK,
                    nextDueAt = 5,
                    finding = finding(),
                    taskId = "task-2",
                    at = 10
                )
            )
        }

        @Test
        fun `a failure records the streak and can pause the row`() = runTest {
            val store = store()
            val original = watch()
            store.create(original)
            setLastTaskId(original.id, "task-1")
            val updated = store.recordFailure(
                id = original.id,
                expectedVersion = original.version,
                expectedStatus = WatchStatus.ACTIVE,
                error = "model unavailable",
                nextDueAt = 123,
                status = WatchStatus.PAUSED,
                consecutiveFailures = 3,
                statusReason = "Paused after 3 consecutive failures",
                taskId = "task-1",
                at = 99
            )!!
            assertEquals(WatchStatus.PAUSED, updated.status)
            assertEquals(3, updated.consecutiveFailures)
            assertEquals("model unavailable", updated.lastError)
            assertEquals(123, updated.nextDueAt.toEpochMilli())
        }

        @Test
        fun `a failure from a superseded task is refused`() = runTest {
            val store = store()
            val original = watch()
            store.create(original)
            setLastTaskId(original.id, "task-2")

            assertNull(
                store.recordFailure(
                    id = original.id,
                    expectedVersion = original.version,
                    expectedStatus = WatchStatus.ACTIVE,
                    error = "model unavailable",
                    nextDueAt = 123,
                    status = WatchStatus.PAUSED,
                    consecutiveFailures = 3,
                    statusReason = "Paused after 3 consecutive failures",
                    taskId = "task-1",
                    at = 99
                )
            )
        }

        @Test
        fun `an expiry review requires the pending marker it read`() = runTest {
            val store = store()
            val original = watch(pendingAction = WatchPendingAction.EXPIRY_REVIEW)
            store.create(original)
            setLastTaskId(original.id, "task-1")

            assertNull(
                store.applyExpiryReview(
                    id = original.id,
                    expectedVersion = original.version,
                    expectedPendingAction = WatchPendingAction.CHECK,
                    status = WatchStatus.ACTIVE,
                    pendingAction = WatchPendingAction.CHECK,
                    untilAt = 500,
                    anchorAt = null,
                    nextDueAt = 600,
                    extensionCount = 3,
                    statusReason = "renewed",
                    taskId = "task-1",
                    at = 1
                )
            )
            val renewed = store.applyExpiryReview(
                id = original.id,
                expectedVersion = original.version,
                expectedPendingAction = WatchPendingAction.EXPIRY_REVIEW,
                status = WatchStatus.ACTIVE,
                pendingAction = WatchPendingAction.CHECK,
                untilAt = 500,
                anchorAt = null,
                nextDueAt = 600,
                extensionCount = 3,
                statusReason = "renewed",
                taskId = "task-1",
                at = 1
            )!!
            assertEquals(WatchPendingAction.CHECK, renewed.pendingAction)
            assertEquals(3, renewed.extensionCount)
            assertEquals(500, renewed.untilAt!!.toEpochMilli())
            assertEquals(600, renewed.nextDueAt.toEpochMilli())
        }

        @Test
        fun `an edit is guarded by the version the caller read`() = runTest {
            val store = store()
            val original = watch()
            store.create(original)
            val edited = original.copy(title = "Renamed", version = original.version + 1, updatedAt = 50)

            assertNull(
                store.updateDefinition(
                    edited,
                    expectedVersion = original.version + 1,
                    expectedStatus = WatchStatus.ACTIVE
                )
            )
            val saved = store.updateDefinition(
                edited,
                expectedVersion = original.version,
                expectedStatus = WatchStatus.ACTIVE
            )!!
            assertEquals("Renamed", saved.title)
            assertEquals(original.version + 1, saved.version)
        }

        @Test
        fun `an edit cannot resurrect a status the caller no longer sees`() = runTest {
            val store = store()
            val original = watch()
            store.create(original)
            store.setStatus(original.id, WatchStatus.CANCELLED, "cancelled", expectedStatus = WatchStatus.ACTIVE)
            val edited = original.copy(title = "Renamed", version = original.version + 1, updatedAt = 50)

            assertNull(
                store.updateDefinition(
                    edited,
                    expectedVersion = original.version,
                    expectedStatus = WatchStatus.ACTIVE
                )
            )
            assertEquals(WatchStatus.CANCELLED, store.findById(original.id)!!.status)
        }

        @Test
        fun `pause resume and delete`() = runTest {
            val store = store()
            val original = watch()
            store.create(original)
            assertEquals(WatchStatus.PAUSED, store.setStatus(original.id, WatchStatus.PAUSED, "paused")!!.status)
            assertEquals(WatchStatus.ACTIVE, store.setStatus(original.id, WatchStatus.ACTIVE, null)!!.status)
            assertTrue(store.delete(original.id))
            assertNull(store.findById(original.id))
        }

        @Test
        fun `a resume can clear the failure streak`() = runTest {
            val store = store()
            val original = watch(status = WatchStatus.PAUSED)
            store.create(original)

            val resumed = store.setStatus(
                original.id,
                WatchStatus.ACTIVE,
                null,
                expectedStatus = WatchStatus.PAUSED,
                clearFailures = true
            )!!

            assertEquals(0, resumed.consecutiveFailures)
            assertNull(resumed.lastError)
        }

        @Test
        fun `a status transition is guarded by the state the caller read`() = runTest {
            val store = store()
            val original = watch()
            store.create(original)
            assertNull(store.setStatus(original.id, WatchStatus.PAUSED, "paused", expectedStatus = WatchStatus.COMPLETED))
            assertEquals(WatchStatus.ACTIVE, store.findById(original.id)!!.status)
            assertNotNull(store.setStatus(original.id, WatchStatus.PAUSED, "paused", expectedStatus = WatchStatus.ACTIVE))
        }

        @Test
        fun `a run slot is reserved for a live task without touching the schedule`() = runTest {
            val store = store()
            val original = watch()
            store.create(original)
            val running = tasks().create(task(original, AsyncTaskStatus.RUNNING))

            assertTrue(store.claimRunSlot(running, original.version, Instant.ofEpochMilli(700)))

            val updated = store.findById(original.id)!!
            assertEquals(running.id, updated.lastTaskId)
            assertEquals(original.nextDueAt, updated.nextDueAt)

            // A second live run has no free slot, even though the watch row itself is untouched.
            val other = tasks().create(task(original, AsyncTaskStatus.RUNNING))
            assertFalse(store.claimRunSlot(other, original.version, Instant.ofEpochMilli(800)))
            assertEquals(running.id, store.findById(original.id)!!.lastTaskId)
        }
    }

    @Nested
    inner class `manual and retry admission` {

        @Test
        fun `an in-flight run blocks both admission paths without moving the due`() = runTest {
            val store = store()
            val original = watch()
            store.create(original)
            setting(original.userId)
            val running = tasks().create(task(original, AsyncTaskStatus.RUNNING))

            val scheduled = store.enqueueScheduled(snapshot(store.findById(original.id)!!), now, properties)
            assertEquals(running.id, assertIs<WatchEnqueueResult.Busy>(scheduled).taskId)

            assertIs<WatchEnqueueResult.Busy>(
                store.enqueueManual(original.userId, original.id, now, properties)
            )
            assertEquals(original.nextDueAt, store.findById(original.id)!!.nextDueAt)
            assertEquals(listOf(running.id), store.listInFlight(original.userId, original.id).map { it.id })
            assertEquals(1, taskCount(original))
        }

        @Test
        fun `a manual run is inserted without consuming the schedule`() = runTest {
            val store = store()
            val original = watch()
            store.create(original)

            val task = assertIs<WatchEnqueueResult.Enqueued>(
                store.enqueueManual(original.userId, original.id, now, properties)
            ).task

            assertEquals(TaskTrigger.FRONTEND, task.trigger)
            assertEquals(WatchRunKind.CHECK.name, task.payload[WatchTaskPayload.KEY_KIND])
            // A manual run reports its own request instant as the firing, not the stored due.
            assertEquals(
                now.toEpochMilli(),
                (task.payload[WatchTaskPayload.KEY_SCHEDULED_FOR] as Number).toLong()
            )
            val updated = store.findById(original.id)!!
            assertEquals(original.nextDueAt, updated.nextDueAt)
            assertEquals(task.id, updated.lastTaskId)
        }

        @Test
        fun `a disabled setting stops automatic admission but not manual runs`() = runTest {
            val store = store()
            val original = watch()
            store.create(original)
            setting(original.userId, enabled = false)

            assertIs<WatchEnqueueResult.Stale>(
                store.enqueueScheduled(snapshot(store.findById(original.id)!!), now, properties)
            )
            assertTrue(
                store.listUpcoming(now.plusSeconds(3_600), userId = original.userId).isEmpty()
            )
            assertIs<WatchEnqueueResult.Enqueued>(
                store.enqueueManual(original.userId, original.id, now, properties)
            )
        }

        @Test
        fun `another user's enabled setting does not schedule this watch`() = runTest {
            val store = store()
            val original = watch()
            store.create(original)
            setting(original.userId, enabled = false)
            setting("someone-else", enabled = true)

            assertTrue(
                store.listUpcoming(now.plusSeconds(3_600), userId = original.userId).isEmpty()
            )
        }

        @Test
        fun `a committed admission publishes exactly one schedule change`() = runTest {
            val events = mutableListOf<WatchScheduleChanged>()
            val store = store { events += it }
            val original = watch()
            store.create(original)
            setting(original.userId)
            events.clear()

            store.enqueueScheduled(snapshot(store.findById(original.id)!!), now, properties)

            val event = events.single()
            assertEquals(SELF, event.ownerApp)
            assertEquals(original.id, event.watchId)
            assertEquals(original.userId, event.userId)
        }

        @Test
        fun `a stale admission publishes no schedule change`() = runTest {
            val events = mutableListOf<WatchScheduleChanged>()
            val store = store { events += it }
            val original = watch()
            store.create(original)
            setting(original.userId)
            val snapshot = snapshot(store.findById(original.id)!!)
            store.enqueueScheduled(snapshot, now, properties)
            events.clear()

            assertIs<WatchEnqueueResult.Stale>(store.enqueueScheduled(snapshot, now, properties))
            assertTrue(events.isEmpty())
        }

        @Test
        fun `a retry reuses the finished row and keeps its original firing`() = runTest {
            val store = store()
            val original = watch()
            store.create(original)
            setting(original.userId)
            val first = assertIs<WatchEnqueueResult.Enqueued>(
                store.enqueueScheduled(snapshot(store.findById(original.id)!!), now, properties)
            ).task
            tasks().complete(first.id, mapOf("ok" to true))

            val retried = assertIs<WatchEnqueueResult.Enqueued>(
                store.retryRun(original.userId, original.id, first.id, 0, now.plusSeconds(60))
            )

            assertEquals(first.id, retried.task.id)
            assertEquals(AsyncTaskStatus.PENDING, retried.task.status)
            assertEquals(1, retried.task.retryCount)
            assertEquals(
                (first.payload[WatchTaskPayload.KEY_SCHEDULED_FOR] as Number).toLong(),
                (retried.task.payload[WatchTaskPayload.KEY_SCHEDULED_FOR] as Number).toLong()
            )
            assertEquals(first.id, store.findById(original.id)!!.lastTaskId)
            assertEquals(1, taskCount(original))
        }
    }
}
