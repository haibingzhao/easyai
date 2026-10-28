package com.easy.easyai.repository.task

import com.easy.easyai.core.host.HostIdentity
import com.easy.easyai.core.task.AsyncTask
import com.easy.easyai.core.task.AsyncTaskStatus
import com.easy.easyai.core.task.AsyncTaskType
import com.easy.easyai.core.task.ScheduledTaskSetting
import com.easy.easyai.core.task.ScheduledTaskSettingChanged
import com.easy.easyai.core.task.ScheduledTaskSettingStore
import com.easy.easyai.core.task.TaskTrigger
import com.easy.easyai.repository.database.DatabaseMigration
import com.easy.easyai.repository.database.Tables.AsyncTaskTable
import com.easy.easyai.repository.database.Tables.ScheduledTaskSettingTable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for [R2dbcScheduledTaskSettingStore] over in-memory H2: the `(user, type)` composite
 * key, strict per-user isolation, opaque `params` JSON round-trip and the scan query's filters.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class R2dbcScheduledTaskSettingStoreTest {

    private lateinit var db: R2dbcDatabase

    @BeforeAll
    fun setupDb() = runTest {
        db = R2dbcDatabase.connect(
            url = "r2dbc:h2:mem:///scheduledsetting_test_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1",
            manager = { TransactionManager(it) }
        )
        DatabaseMigration.defaultTables().execute(db)
    }

    private fun createStore(appName: String = "host-a"): ScheduledTaskSettingStore =
        R2dbcScheduledTaskSettingStore(db, HostIdentity(appName))

    private fun setting(
        userId: String = "alice",
        taskType: AsyncTaskType = AsyncTaskType.MEMORY_CONSOLIDATION,
        enabled: Boolean = true,
        cron: String? = "0 0 3 * * *",
        params: Map<String, Any?> = mapOf("typeThresholds" to mapOf("experience_lessons" to 7)),
        timeoutMs: Long? = null
    ) = ScheduledTaskSetting(
        userId = userId,
        taskType = taskType,
        enabled = enabled,
        cron = cron,
        params = params,
        timeoutMs = timeoutMs
    )

    /** Write a sibling type key directly, bypassing the typed setting constructor. */
    private suspend fun insertRawType(userId: String, typeKey: String, enabled: Boolean) = suspendTransaction(db) {
        ScheduledTaskSettingTable.insert {
            it[ScheduledTaskSettingTable.userId] = userId
            it[ScheduledTaskSettingTable.taskType] = typeKey
            it[ScheduledTaskSettingTable.enabled] = enabled
            it[updatedAt] = System.currentTimeMillis()
        }
    }

    private suspend fun insertRawParams(userId: String, params: String) = suspendTransaction(db) {
        ScheduledTaskSettingTable.insert {
            it[ScheduledTaskSettingTable.userId] = userId
            it[ScheduledTaskSettingTable.taskType] = AsyncTaskType.MEMORY_CONSOLIDATION.key
            it[ScheduledTaskSettingTable.enabled] = true
            it[ScheduledTaskSettingTable.params] = params
            it[updatedAt] = System.currentTimeMillis()
        }
    }

    /** JSON comes back as whatever number type the parser chose, so compare numerically, not as `Int`. */
    private fun number(value: Any?): Int? = (value as? Number)?.toInt()

    @Nested
    inner class `save and read` {

        @Test
        fun `save round-trips cron and params for the composite key`() = runTest {
            val store = createStore()
            store.save(setting(userId = "roundtrip", cron = "0 15 4 * * *"))

            val loaded = assertNotNull(store.get("roundtrip", AsyncTaskType.MEMORY_CONSOLIDATION))
            assertEquals("0 15 4 * * *", loaded.cron)
            assertTrue(loaded.enabled)
            assertEquals(7, number((loaded.params["typeThresholds"] as? Map<*, *>)?.get("experience_lessons")))
        }

        @Test
        fun `a second save replaces the row instead of adding one`() = runTest {
            val store = createStore()
            val user = "upsert-user"
            store.save(setting(userId = user, enabled = true, params = mapOf("a" to 1)))
            store.save(setting(userId = user, enabled = false, params = mapOf("a" to 2)))

            val loaded = assertNotNull(store.get(user, AsyncTaskType.MEMORY_CONSOLIDATION))
            assertFalse(loaded.enabled)
            assertEquals(2, number(loaded.params["a"]))

            val rows = suspendTransaction(db) {
                ScheduledTaskSettingTable.selectAll()
                    .where { ScheduledTaskSettingTable.userId eq user }
                    .count()
            }
            assertEquals(1L, rows)
        }

        @Test
        fun `one user can hold a row per task type`() = runTest {
            val store = createStore()
            val user = "multi-type"
            store.save(setting(userId = user, params = mapOf("typeThresholds" to mapOf("experience_lessons" to 3))))
            // A second known type does not exist yet, so the sibling key is written raw: what is under
            // test is that the primary key is (user, type) and not user alone.
            insertRawType(user, "some_future_type", enabled = true)

            val loaded = assertNotNull(store.get(user, AsyncTaskType.MEMORY_CONSOLIDATION))
            assertEquals(
                3,
                number((loaded.params["typeThresholds"] as? Map<*, *>)?.get("experience_lessons")),
                "the consolidation row must survive a sibling row for another type"
            )
            assertEquals(2L, storeRowsFor(user))
        }

        @Test
        fun `save round-trips the timeout override`() = runTest {
            val store = createStore()
            store.save(setting(userId = "timeout-user", timeoutMs = 90_000))

            val loaded = assertNotNull(store.get("timeout-user", AsyncTaskType.MEMORY_CONSOLIDATION))
            assertEquals(90_000, loaded.timeoutMs)
        }

        @Test
        fun `empty params stores no JSON and reads back empty`() = runTest {
            val store = createStore()
            store.save(setting(userId = "no-params", params = emptyMap()))

            val loaded = assertNotNull(store.get("no-params", AsyncTaskType.MEMORY_CONSOLIDATION))
            assertEquals(emptyMap(), loaded.params)
        }

        @Test
        fun `get returns null for a user who never configured the type`() = runTest {
            assertNull(createStore().get("absent", AsyncTaskType.MEMORY_CONSOLIDATION))
        }
    }

    @Nested
    inner class `isolation` {

        @Test
        fun `get cannot read another user's row`() = runTest {
            val store = createStore()
            store.save(setting(userId = "owner-a"))

            assertNull(store.get("owner-b", AsyncTaskType.MEMORY_CONSOLIDATION))
        }

        @Test
        fun `delete only removes the named type of the named user`() = runTest {
            val store = createStore()
            val user = "delete-user"
            store.save(setting(userId = user))

            assertTrue(store.delete(user, AsyncTaskType.MEMORY_CONSOLIDATION))
            assertFalse(store.delete(user, AsyncTaskType.MEMORY_CONSOLIDATION))
            assertNull(store.get(user, AsyncTaskType.MEMORY_CONSOLIDATION))
        }
    }

    @Nested
    inner class `enabled scan` {

        @Test
        fun `listEnabled skips disabled rows`() = runTest {
            val store = createStore()
            store.save(setting(userId = "scan-on", enabled = true))
            store.save(setting(userId = "scan-off", enabled = false))

            val enabled = store.listEnabled()
            assertTrue(enabled.any { it.userId == "scan-on" })
            assertFalse(enabled.any { it.userId == "scan-off" })
        }

        @Test
        fun `listEnabled narrows to the requested type`() = runTest {
            val store = createStore()
            val user = "filter-user"
            store.save(setting(userId = user))
            // An enabled row for a type this build cannot name must not reach the filtered scan — and it
            // really is enabled, so an absent result means the filter worked rather than the row being off.
            insertRawType(user, "some_future_type", enabled = true)

            val filtered = store.listEnabled(AsyncTaskType.MEMORY_CONSOLIDATION)
            assertEquals(1, filtered.count { it.userId == user })
            assertTrue(filtered.all { it.taskType == AsyncTaskType.MEMORY_CONSOLIDATION })
        }

        @Test
        fun `a host-defined task type is returned like any other key`() = runTest {
            insertRawType("host-user", "DAILY_ANALYSIS", enabled = true)

            val store = createStore()
            assertTrue(store.listEnabled().any { it.userId == "host-user" })
            assertEquals(
                "DAILY_ANALYSIS",
                assertNotNull(store.get("host-user", AsyncTaskType("DAILY_ANALYSIS"))).taskType.key
            )
            assertNull(store.get("host-user", AsyncTaskType.MEMORY_CONSOLIDATION))
        }

        @Test
        fun `unparsable params degrade to empty rather than failing the scan`() = runTest {
            insertRawParams("broken-params", "{not json")

            val loaded = assertNotNull(createStore().get("broken-params", AsyncTaskType.MEMORY_CONSOLIDATION))
            assertEquals(emptyMap(), loaded.params)
        }
    }

    @Nested
    inner class `persisted schedules` {

        @Test
        fun `schedule survives recreation and is shared across hosts but not users or types`() = runTest {
            val configured = initialized(setting(userId = "schedule-restart"))
            val sibling = initialized(configured.copy(taskType = AsyncTaskType("schedule-sibling")), 300L)
            val otherUser = initialized(configured.copy(userId = "schedule-other"), 400L)
            for (store in listOf(createStore(), createStore("host-b"))) {
                assertEquals(configured, store.get(configured.userId, configured.taskType))
                assertEquals(sibling, store.get(sibling.userId, sibling.taskType))
                assertEquals(otherUser, store.get(otherUser.userId, otherUser.taskType))
            }
        }

        @Test
        fun `new saves ignore supplied runtime fields and mark even default cron for initialization`() = runTest {
            val store = createStore()
            val supplied = setting(userId = "schedule-new", cron = null).copy(
                nextFireAt = 10L, scheduleRevision = "caller-revision", scheduleZone = "UTC", updatedAt = 1L
            )
            val before = System.currentTimeMillis()
            store.save(supplied)
            val saved = assertNotNull(store.get(supplied.userId, supplied.taskType))
            assertNull(saved.nextFireAt)
            assertNull(saved.scheduleZone)
            assertNull(saved.cron)
            assertNotNull(saved.scheduleRevision)
            assertNotEquals(supplied.scheduleRevision, saved.scheduleRevision)
            assertTrue(saved.updatedAt >= before)
        }

        @Test
        fun `stale runtime and preference saves preserve authoritative schedule fields`() = runTest {
            val store = createStore()
            val configured = initialized(setting(userId = "schedule-preserve"))
            assertEquals(emptyList(), store.submitScheduled(configured, 200L, emptyList()))
            suspendTransaction(db) {
                ScheduledTaskSettingTable.update({ ScheduledTaskSettingTable.userId eq configured.userId }) {
                    it[updatedAt] = 42L
                }
            }
            val before = System.currentTimeMillis()
            createStore("host-b").save(configured.copy(
                params = mapOf("lastTaskId" to "done"), timeoutMs = 120_000L,
                scheduleRevision = "forged", scheduleZone = null, nextFireAt = 1L
            ))
            val saved = assertNotNull(store.get(configured.userId, configured.taskType))
            assertEquals(200L, saved.nextFireAt)
            assertEquals(configured.scheduleRevision, saved.scheduleRevision)
            assertEquals(configured.scheduleZone, saved.scheduleZone)
            assertEquals(mapOf("lastTaskId" to "done"), saved.params)
            assertEquals(120_000L, saved.timeoutMs)
            assertTrue(saved.updatedAt >= before)
        }

        @Test
        fun `cron edits disable and reenable invalidate only the affected schedule`() = runTest {
            val store = createStore()
            var current = initialized(setting(userId = "schedule-edits"))
            val sibling = initialized(current.copy(taskType = AsyncTaskType("schedule-edits-other")))
            val edits = listOf(
                current.copy(cron = "0 0 4 * * *"),
                current.copy(cron = null),
                current.copy(enabled = false),
                current.copy(enabled = true)
            )
            for (edit in edits) {
                store.save(edit)
                val saved = assertNotNull(store.get(edit.userId, edit.taskType))
                assertNotEquals(current.scheduleRevision, saved.scheduleRevision)
                assertNotNull(saved.scheduleRevision)
                assertNull(saved.nextFireAt)
                assertNull(saved.scheduleZone)
                assertFalse(store.initializeSchedule(current, 200L, "UTC"))
                assertNull(store.submitScheduled(current, 200L, emptyList()))
                if (saved.enabled) assertTrue(store.initializeSchedule(saved, 100L, "UTC"))
                current = assertNotNull(store.get(edit.userId, edit.taskType))
            }
            assertEquals(sibling, store.get(sibling.userId, sibling.taskType))
        }

        @Test
        fun `delete and recreate never reuses a revision`() = runTest {
            val store = createStore()
            val old = initialized(setting(userId = "schedule-recreated"))
            assertTrue(store.delete(old.userId, old.taskType))
            assertFalse(store.initializeSchedule(old.copy(scheduleZone = null), 200L, "UTC"))
            assertNull(store.submitScheduled(old, 200L, emptyList()))
            val recreated = initialized(old)
            assertNotEquals(old.scheduleRevision, recreated.scheduleRevision)
            assertNull(store.submitScheduled(old, 200L, emptyList()))
            assertEquals(recreated, store.get(old.userId, old.taskType))
        }
    }

    @Nested
    inner class `schedule initialization and paging` {

        @Test
        fun `legacy initialization assigns a revision without changing preferences or update time`() = runTest {
            insertRawParams("schedule-legacy", "{\"state\":\"kept\"}")
            val store = createStore()
            val legacy = assertNotNull(store.get("schedule-legacy", AsyncTaskType.MEMORY_CONSOLIDATION))
            assertNull(legacy.scheduleRevision)
            store.save(legacy.copy(timeoutMs = 50L))
            val current = assertNotNull(store.get(legacy.userId, legacy.taskType))
            assertNull(current.scheduleRevision)
            assertTrue(store.initializeSchedule(current, 100L, "Asia/Shanghai"))
            val initialized = assertNotNull(store.get(legacy.userId, legacy.taskType))
            assertNotNull(initialized.scheduleRevision)
            assertEquals(current.copy(
                nextFireAt = 100L, scheduleZone = "Asia/Shanghai", scheduleRevision = initialized.scheduleRevision
            ), initialized)
            assertFalse(createStore("host-b").initializeSchedule(legacy, 200L, "UTC"))
            assertEquals(initialized, store.get(legacy.userId, legacy.taskType))
        }

        @Test
        fun `initialize uses revision cron enabled and uninitialized state as CAS guards`() = runTest {
            val store = createStore()
            val configured = setting(userId = "schedule-init-cas")
            store.save(configured)
            val saved = assertNotNull(store.get(configured.userId, configured.taskType))
            assertFalse(store.initializeSchedule(saved.copy(scheduleRevision = "stale"), 100L, "UTC"))
            assertFalse(store.initializeSchedule(saved.copy(cron = null), 100L, "UTC"))
            assertFalse(store.initializeSchedule(saved.copy(userId = "schedule-init-missing"), 100L, "UTC"))
            assertTrue(store.initializeSchedule(saved, null, "UTC"))
            val initialized = assertNotNull(store.get(saved.userId, saved.taskType))
            assertEquals(saved.copy(scheduleZone = "UTC"), initialized)
            assertFalse(store.initializeSchedule(saved, 100L, "UTC"))
            store.save(initialized.copy(enabled = false))
            val disabled = assertNotNull(store.get(saved.userId, saved.taskType))
            assertFalse(store.initializeSchedule(disabled.copy(enabled = true), 100L, "UTC"))
            assertNull(store.get(saved.userId, saved.taskType)?.nextFireAt)
        }

        @Test
        fun `keyset pages include all overdue and uninitialized rows through inclusive horizon`() = runTest {
            val store = createStore()
            val type = AsyncTaskType("schedule-paging")
            initialized(setting("page-a", type), -1_000_000L)
            initialized(setting("page-b", type), 900L)
            initialized(setting("page-c", type), 1_000L)
            store.save(setting("page-d", type))
            suspendTransaction(db) {
                ScheduledTaskSettingTable.update({ ScheduledTaskSettingTable.userId eq "page-d" }) {
                    it[nextFireAt] = 10_000L
                }
            }
            insertRawType("page-e", type.key, true)
            initialized(setting("page-f", type), 1_001L)
            store.save(setting("page-g", type, enabled = false))
            initialized(setting("page-h", type), null)
            initialized(setting("page-a", AsyncTaskType("schedule-other-page")), 1L)
            val first = store.listForScheduling(type, 1_000L, limit = 2)
            val second = store.listForScheduling(type, 1_000L, first.last().userId, 2)
            val third = store.listForScheduling(type, 1_000L, second.last().userId, 2)
            assertEquals(listOf("page-a", "page-b"), first.map { it.userId })
            assertEquals(listOf("page-c", "page-d"), second.map { it.userId })
            assertEquals(listOf("page-e"), third.map { it.userId })
            assertTrue(store.listForScheduling(type, 1_000L, third.last().userId, 2).isEmpty())
            assertTrue((first + second + third).all { it.taskType == type })
            assertFailsWith<IllegalArgumentException> { store.listForScheduling(type, 1_000L, limit = 0) }
        }
    }

    @Nested
    inner class `atomic scheduled submission` {

        @Test
        fun `concurrent hosts have one winner and preserve the full host stamped batch`() = runTest {
            val configured = initialized(setting(userId = "schedule-race"), 1_700_000_000_000L)
            val next = 1_700_100_000_000L
            val ready = Channel<Unit>(2)
            val start = CompletableDeferred<Unit>()
            val hosts = listOf("host-a", "host-b")
            val batches = hosts.map { host ->
                listOf(task(configured, "$host-first"), task(configured, "$host-second"))
            }
            val attempts = hosts.mapIndexed { index, host ->
                async(Dispatchers.Default) {
                    ready.send(Unit)
                    start.await()
                    createStore(host).submitScheduled(configured, next, batches[index])
                }
            }
            repeat(2) { ready.receive() }
            start.complete(Unit)
            val results = attempts.awaitAll()
            assertEquals(1, results.count { it != null })
            val winner = results.indexOfFirst { it != null }
            val accepted = assertNotNull(results[winner])
            assertEquals(batches[winner].map { it.copy(ownerApp = hosts[winner]) }, accepted)
            val tasks = R2dbcAsyncTaskStore(db, HostIdentity("host-a"))
            for (task in accepted) assertEquals(task, tasks.findById(task.id))
            for (task in batches[1 - winner]) assertNull(tasks.findById(task.id))
            assertEquals(2L, taskRowsFor(configured.userId))
            assertEquals(configured.copy(nextFireAt = next), createStore().get(configured.userId, configured.taskType))
        }

        @Test
        fun `empty batch advances and can exhaust a schedule without changing preferences`() = runTest {
            val configured = initialized(setting(userId = "schedule-empty"))
            val store = createStore()
            assertEquals(emptyList(), store.submitScheduled(configured, null, emptyList()))
            assertEquals(configured.copy(nextFireAt = null), store.get(configured.userId, configured.taskType))
            assertEquals(0L, taskRowsFor(configured.userId))
            assertNull(store.submitScheduled(configured, 200L, emptyList()))
        }

        @Test
        fun `stale revision fire time and incomplete snapshots cannot enqueue`() = runTest {
            val configured = initialized(setting(userId = "schedule-stale-submit"))
            val store = createStore()
            for (stale in listOf(
                configured.copy(scheduleRevision = "stale"), configured.copy(nextFireAt = 99L),
                configured.copy(scheduleRevision = null), configured.copy(nextFireAt = null),
                configured.copy(scheduleZone = null), configured.copy(enabled = false)
            )) {
                assertNull(store.submitScheduled(stale, 200L, listOf(task(configured))))
            }
            assertEquals(configured, store.get(configured.userId, configured.taskType))
            assertEquals(0L, taskRowsFor(configured.userId))
        }

        @Test
        fun `mismatched user type trigger and status are rejected without advancing`() = runTest {
            val configured = initialized(setting(userId = "schedule-invalid-batch"))
            val valid = task(configured)
            val store = createStore()
            for (invalid in listOf(
                valid.copy(userId = "another-user"), valid.copy(type = AsyncTaskType.WATCH),
                valid.copy(trigger = TaskTrigger.FRONTEND), valid.copy(status = AsyncTaskStatus.RUNNING)
            )) {
                assertFailsWith<IllegalArgumentException> {
                    store.submitScheduled(configured, 200L, listOf(valid, invalid))
                }
                assertEquals(configured, store.get(configured.userId, configured.taskType))
                assertEquals(0L, taskRowsFor(configured.userId))
            }
        }

        @Test
        fun `duplicate insertion rolls back advance and every newly inserted task`() = runTest {
            val configured = initialized(setting(userId = "schedule-duplicate"))
            val store = createStore()
            val first = task(configured)
            assertFailsWith<Exception> {
                store.submitScheduled(configured, 200L, listOf(first, first.copy(title = "duplicate")))
            }
            assertEquals(configured, store.get(configured.userId, configured.taskType))
            assertEquals(0L, taskRowsFor(configured.userId))
            assertEquals(listOf(first.copy(ownerApp = "host-a")), store.submitScheduled(configured, 200L, listOf(first)))
        }

        @Test
        fun `invalid second row rolls back advance and first insert`() = runTest {
            val configured = initialized(setting(userId = "schedule-insert-failure"))
            val store = createStore()
            assertFailsWith<Exception> {
                store.submitScheduled(configured, 200L, listOf(task(configured), task(configured).copy(title = "x".repeat(256))))
            }
            assertEquals(configured, store.get(configured.userId, configured.taskType))
            assertEquals(0L, taskRowsFor(configured.userId))
        }
    }

    @Nested
    inner class `committed change notifications` {

        @Test
        fun `only definition changes and actual deletions publish`() = runTest {
            val events = mutableListOf<ScheduledTaskSettingChanged>()
            val store = R2dbcScheduledTaskSettingStore(db, HostIdentity("host-a"), onChanged = events::add)
            val configured = setting(userId = "schedule-events")
            val event = ScheduledTaskSettingChanged(configured.userId, configured.taskType)
            store.save(configured)
            assertEquals(listOf(event), events)
            val saved = assertNotNull(store.get(configured.userId, configured.taskType))
            store.save(saved.copy(params = mapOf("sliceState" to "done"), timeoutMs = 99L))
            assertTrue(store.initializeSchedule(saved, 100L, "UTC"))
            val due = assertNotNull(store.get(saved.userId, saved.taskType))
            assertNotNull(store.submitScheduled(due, 200L, emptyList()))
            assertEquals(listOf(event), events)
            store.save(due.copy(cron = "0 0 4 * * *"))
            store.save(due.copy(enabled = false))
            store.save(due.copy(enabled = false, params = emptyMap()))
            assertEquals(List(3) { event }, events)
            assertTrue(store.delete(due.userId, due.taskType))
            assertFalse(store.delete(due.userId, due.taskType))
            assertNull(store.get(due.userId, due.taskType))
            assertEquals(List(4) { event }, events)
        }

        @Test
        fun `nested saves publish only after outer commit with the row visible`() = runTest {
            var committed = false
            val observedCommit = mutableListOf<Boolean>()
            val store = R2dbcScheduledTaskSettingStore(db, HostIdentity("host-a"), onChanged = { observedCommit += committed })
            val configured = setting(userId = "schedule-event-commit")
            suspendTransaction(db) {
                registerInterceptor(object : SuspendStatementInterceptor {
                    override suspend fun afterCommit(transaction: R2dbcTransaction) {
                        committed = inTopLevelSuspendTransaction(db) {
                            ScheduledTaskSettingTable.selectAll()
                                .where { ScheduledTaskSettingTable.userId eq configured.userId }
                                .count() == 1L
                        }
                    }
                })
                store.save(configured)
                assertTrue(observedCommit.isEmpty())
            }
            assertEquals(listOf(true), observedCommit)
            assertNotNull(createStore("host-b").get(configured.userId, configured.taskType))
        }

        @Test
        fun `savepoints defer notifications and discard rolled back inner changes`() = runTest {
            val nestedDb = R2dbcDatabase.connect(manager = { TransactionManager(it) }) {
                setUrl("r2dbc:h2:mem:///schedule_savepoint_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1")
                useNestedTransactions = true
            }
            DatabaseMigration(arrayOf(ScheduledTaskSettingTable)).execute(nestedDb)
            val events = mutableListOf<ScheduledTaskSettingChanged>()
            val store = R2dbcScheduledTaskSettingStore(nestedDb, HostIdentity("host-a"), onChanged = events::add)
            val configured = setting(userId = "schedule-savepoint")
            suspendTransaction(nestedDb) {
                store.save(configured)
                assertTrue(events.isEmpty())
                assertFailsWith<IllegalStateException> {
                    suspendTransaction(nestedDb) {
                        store.save(configured.copy(userId = "schedule-inner-rollback"))
                        error("rollback inner savepoint")
                    }
                }
                assertTrue(events.isEmpty())
            }
            assertEquals(listOf(ScheduledTaskSettingChanged(configured.userId, configured.taskType)), events)
            assertNotNull(store.get(configured.userId, configured.taskType))
            assertNull(store.get("schedule-inner-rollback", configured.taskType))
            events.clear()
            assertFailsWith<IllegalStateException> {
                suspendTransaction(nestedDb) {
                    store.delete(configured.userId, configured.taskType)
                    error("rollback outer transaction")
                }
            }
            assertTrue(events.isEmpty())
            assertNotNull(store.get(configured.userId, configured.taskType))
        }

        @Test
        fun `rolled back saves and deletes never publish`() = runTest {
            val events = mutableListOf<ScheduledTaskSettingChanged>()
            val store = R2dbcScheduledTaskSettingStore(db, HostIdentity("host-a"), onChanged = events::add)
            val configured = setting(userId = "schedule-event-rollback")
            assertFailsWith<IllegalStateException> {
                suspendTransaction(db) {
                    store.save(configured)
                    error("rollback save")
                }
            }
            assertTrue(events.isEmpty())
            assertNull(store.get(configured.userId, configured.taskType))
            store.save(configured)
            events.clear()
            assertFailsWith<IllegalStateException> {
                suspendTransaction(db) {
                    assertTrue(store.delete(configured.userId, configured.taskType))
                    error("rollback delete")
                }
            }
            assertTrue(events.isEmpty())
            assertNotNull(store.get(configured.userId, configured.taskType))
        }
    }

    private suspend fun initialized(configured: ScheduledTaskSetting, due: Long? = 100L): ScheduledTaskSetting {
        val store = createStore()
        store.save(configured)
        val saved = assertNotNull(store.get(configured.userId, configured.taskType))
        assertTrue(store.initializeSchedule(saved, due, "UTC"))
        return assertNotNull(store.get(configured.userId, configured.taskType))
    }

    private fun task(configured: ScheduledTaskSetting, id: String = UUID.randomUUID().toString()) = AsyncTask(
        id = id,
        userId = configured.userId,
        type = configured.taskType,
        trigger = TaskTrigger.SCHEDULE,
        title = "Scheduled batch",
        ownerApp = "untrusted-owner",
        sessionId = "origin-session",
        timeoutMs = 90_000L,
        payload = mapOf("scheduledFor" to configured.nextFireAt, "slice" to "experience"),
        createdAt = 10L,
        updatedAt = 11L
    )

    private suspend fun taskRowsFor(userId: String): Long = suspendTransaction(db) {
        AsyncTaskTable.selectAll().where { AsyncTaskTable.userId eq userId }.count()
    }

    private suspend fun storeRowsFor(userId: String): Long = suspendTransaction(db) {
        ScheduledTaskSettingTable.selectAll()
            .where { ScheduledTaskSettingTable.userId eq userId }
            .mapNotNull { it[ScheduledTaskSettingTable.userId] }
            .toList()
            .size
            .toLong()
    }
}
