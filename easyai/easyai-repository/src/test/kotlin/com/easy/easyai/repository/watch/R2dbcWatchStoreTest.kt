package com.easy.easyai.repository.watch

import com.easy.easyai.core.model.ProjectKind
import com.easy.easyai.core.watch.Watch
import com.easy.easyai.core.watch.WatchFinding
import com.easy.easyai.core.watch.WatchFoundTag
import com.easy.easyai.core.watch.WatchMode
import com.easy.easyai.core.watch.WatchNotifyPolicy
import com.easy.easyai.core.watch.WatchPendingAction
import com.easy.easyai.core.watch.WatchSchedule
import com.easy.easyai.core.watch.WatchScheduleKind
import com.easy.easyai.core.watch.WatchSource
import com.easy.easyai.core.watch.WatchStatus
import com.easy.easyai.core.watch.WatchTag
import com.easy.easyai.core.watch.WatchVerdict
import com.easy.easyai.repository.database.DatabaseMigration
import com.easy.easyai.repository.database.Tables.WatchTable
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for [R2dbcWatchStore] over in-memory H2: JSON round-trips, strict per-user reads,
 * the due listing, the guarded claim that makes the planner single-writer, and every version /
 * pending-action guard the handler depends on.
 */
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

    private fun store() = R2dbcWatchStore(db)

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun watch(
        id: String = UUID.randomUUID().toString(),
        userId: String = "alice",
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
        runSessionId = "run-session",
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

            val due = store.listDue("erin", now)
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
        fun `a claim advances the row exactly once`() = runTest {
            val store = store()
            val original = watch()
            store.create(original)
            val due = original.nextDueAt.toEpochMilli()

            assertTrue(store.claim(original.id, due, due + 60_000, WatchPendingAction.CHECK))
            assertFalse(store.claim(original.id, due, due + 120_000, WatchPendingAction.CHECK))

            val updated = store.findById(original.id)!!
            assertEquals(due + 60_000, updated.nextDueAt.toEpochMilli())
        }

        @Test
        fun `a claim can route the row into an expiry review`() = runTest {
            val store = store()
            val original = watch()
            store.create(original)
            val due = original.nextDueAt.toEpochMilli()
            assertTrue(
                store.claim(
                    original.id,
                    due,
                    due + 600_000,
                    WatchPendingAction.EXPIRY_REVIEW,
                    statusReason = "until passed"
                )
            )
            val updated = store.findById(original.id)!!
            assertEquals(WatchPendingAction.EXPIRY_REVIEW, updated.pendingAction)
            assertEquals("until passed", updated.statusReason)
        }

        @Test
        fun `a finding is refused after the definition changed`() = runTest {
            val store = store()
            val original = watch()
            store.create(original)
            store.markRunStarted(original.id, "task-1", 1)

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
            store.markRunStarted(original.id, "task-1", 1)
            store.markRunStarted(original.id, "task-2", 2)

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
        fun `an expiry review requires the pending marker it read`() = runTest {
            val store = store()
            val original = watch(pendingAction = WatchPendingAction.EXPIRY_REVIEW)
            store.create(original)
            store.markRunStarted(original.id, "task-1", 1)

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
        fun `a status transition is guarded by the state the caller read`() = runTest {
            val store = store()
            val original = watch()
            store.create(original)
            assertNull(store.setStatus(original.id, WatchStatus.PAUSED, "paused", expectedStatus = WatchStatus.COMPLETED))
            assertEquals(WatchStatus.ACTIVE, store.findById(original.id)!!.status)
            assertNotNull(store.setStatus(original.id, WatchStatus.PAUSED, "paused", expectedStatus = WatchStatus.ACTIVE))
        }

        @Test
        fun `run start stamps the task without touching the schedule`() = runTest {
            val store = store()
            val original = watch()
            store.create(original)
            assertTrue(store.markRunStarted(original.id, "task-7", 700))
            val updated = store.findById(original.id)!!
            assertEquals("task-7", updated.lastTaskId)
            assertEquals(original.nextDueAt, updated.nextDueAt)
        }
    }
}
