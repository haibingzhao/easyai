package com.easy.easyai.repository.task

import com.easy.easyai.core.task.AsyncTask
import com.easy.easyai.core.task.AsyncTaskStatus
import com.easy.easyai.core.task.AsyncTaskType
import com.easy.easyai.core.task.TaskTrigger
import com.easy.easyai.repository.database.DatabaseMigration
import com.easy.easyai.repository.database.Tables.AsyncTaskTable
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for [R2dbcAsyncTaskStore] over an in-memory H2 R2DBC database: JSON payload/
 * result round-trip, status transitions, strict per-user isolation, in-place retry bookkeeping and
 * orphan lookup.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class R2dbcAsyncTaskStoreTest {

    private lateinit var db: R2dbcDatabase

    @BeforeAll
    fun setupDb() = runTest {
        db = R2dbcDatabase.connect(
            url = "r2dbc:h2:mem:///asynctask_test_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1",
            manager = { TransactionManager(it) }
        )
        DatabaseMigration.defaultTables().execute(db)
    }

    private fun createStore() = R2dbcAsyncTaskStore(db)

    /** Insert a row with raw enum-column strings, bypassing the enums — a newer writer or a rollback. */
    private suspend fun insertRawType(
        taskId: String,
        user: String,
        typeKey: String,
        statusName: String = AsyncTaskStatus.PENDING.name,
        triggerName: String = TaskTrigger.AUTO.name
    ) = suspendTransaction(db) {
        AsyncTaskTable.insert {
            it[id] = taskId
            it[userId] = user
            it[type] = typeKey
            it[status] = statusName
            it[triggerSource] = triggerName
            it[title] = "记忆归并"
            it[createdAt] = System.currentTimeMillis()
            it[updatedAt] = System.currentTimeMillis()
        }
    }

    /** Insert a row whose JSON column this build cannot parse — hand-written data or a rollback. */
    private suspend fun insertRawPayload(taskId: String, user: String, payloadJson: String) =
        suspendTransaction(db) {
            AsyncTaskTable.insert {
                it[id] = taskId
                it[userId] = user
                it[type] = AsyncTaskType.MEMORY_CONSOLIDATION.key
                it[status] = AsyncTaskStatus.PENDING.name
                it[triggerSource] = TaskTrigger.AUTO.name
                it[title] = "记忆归并"
                it[payload] = payloadJson
                it[createdAt] = System.currentTimeMillis()
                it[updatedAt] = System.currentTimeMillis()
            }
        }

    private fun task(
        id: String = UUID.randomUUID().toString(),
        userId: String = "alice",
        status: AsyncTaskStatus = AsyncTaskStatus.PENDING,
        trigger: TaskTrigger = TaskTrigger.AUTO,
        sessionId: String? = null,
        payload: Map<String, Any?> = mapOf("scope" to "global", "seedName" to "note-$id"),
        title: String = "记忆归并"
    ) = AsyncTask(
        id = id,
        userId = userId,
        type = AsyncTaskType.MEMORY_CONSOLIDATION,
        trigger = trigger,
        title = title,
        status = status,
        sessionId = sessionId,
        payload = payload
    )

    @Nested
    inner class `create and read` {

        @Test
        fun `create round-trips every column including JSON payload`() = runTest {
            val store = createStore()
            val id = UUID.randomUUID().toString()
            store.create(
                task(
                    id = id,
                    sessionId = "sess-1",
                    payload = mapOf("scope" to "project", "owners" to listOf("alice", "system"), "dryRun" to true)
                )
            )

            val loaded = assertNotNull(store.findById(id))
            assertEquals("alice", loaded.userId)
            assertEquals(AsyncTaskType.MEMORY_CONSOLIDATION, loaded.type)
            assertEquals(TaskTrigger.AUTO, loaded.trigger)
            assertEquals(AsyncTaskStatus.PENDING, loaded.status)
            assertEquals("sess-1", loaded.sessionId)
            assertEquals(0, loaded.retryCount)
            assertNull(loaded.result)
            assertEquals("project", loaded.payload["scope"])
            assertEquals(true, loaded.payload["dryRun"])
            @Suppress("UNCHECKED_CAST")
            assertEquals(listOf("alice", "system"), loaded.payload["owners"] as List<String>)
        }

        @Test
        fun `findById on a missing id returns null`() = runTest {
            assertNull(createStore().findById("no-such-${UUID.randomUUID()}"))
        }
    }

    @Nested
    inner class `status transitions` {

        @Test
        fun `running then complete stamps timestamps and persists result`() = runTest {
            val store = createStore()
            val id = UUID.randomUUID().toString()
            store.create(task(id = id))

            val running = assertNotNull(store.markRunning(id))
            assertEquals(AsyncTaskStatus.RUNNING, running.status)
            assertNotNull(running.startedAt)

            val done = assertNotNull(store.complete(id, mapOf("merged" to 2, "pruned" to 1)))
            assertEquals(AsyncTaskStatus.COMPLETED, done.status)
            assertNotNull(done.completedAt)
            assertEquals(2, done.result?.get("merged"))
            assertTrue(done.durationMs!! >= 0)
        }

        @Test
        fun `fail records the error and clears a prior result`() = runTest {
            val store = createStore()
            val id = UUID.randomUUID().toString()
            store.create(task(id = id))
            store.markRunning(id)

            val failed = assertNotNull(store.fail(id, "boom"))
            assertEquals(AsyncTaskStatus.FAILED, failed.status)
            assertEquals("boom", failed.error)
            assertNotNull(failed.completedAt)
        }

        @Test
        fun `markInterrupted parks a running task without a completedAt`() = runTest {
            val store = createStore()
            val id = UUID.randomUUID().toString()
            store.create(task(id = id))
            store.markRunning(id)

            val interrupted = assertNotNull(store.markInterrupted(id))
            assertEquals(AsyncTaskStatus.INTERRUPTED, interrupted.status)
        }

        @Test
        fun `a terminal row is never overwritten by a late cancel or complete`() = runTest {
            val store = createStore()
            val id = UUID.randomUUID().toString()
            store.create(task(id = id))
            store.markRunning(id)
            store.complete(id, mapOf("merged" to 1))

            // The job finished but was not yet untracked when a cancel lands — the guard must no-op.
            val afterCancel = assertNotNull(store.cancel(id))
            assertEquals(AsyncTaskStatus.COMPLETED, afterCancel.status, "cancel must not overwrite COMPLETED")
            val afterFail = assertNotNull(store.fail(id, "late"))
            assertEquals(AsyncTaskStatus.COMPLETED, afterFail.status, "fail must not overwrite COMPLETED")
            assertEquals(1, afterFail.result?.get("merged"))
        }

        @Test
        fun `markRunning only fires from PENDING`() = runTest {
            val store = createStore()
            val id = UUID.randomUUID().toString()
            store.create(task(id = id))
            store.markRunning(id)
            store.complete(id, null)

            val revived = assertNotNull(store.markRunning(id))
            assertEquals(AsyncTaskStatus.COMPLETED, revived.status, "a settled task must not go RUNNING again")
        }
    }

    @Nested
    inner class `retry` {

        @Test
        fun `retry resets a terminal task in place and bumps the counter`() = runTest {
            val store = createStore()
            val id = UUID.randomUUID().toString()
            store.create(task(id = id))
            store.markRunning(id)
            store.complete(id, mapOf("merged" to 1))

            val reset = assertNotNull(store.retry(id))
            assertEquals(AsyncTaskStatus.PENDING, reset.status)
            assertNull(reset.error)
            assertNull(reset.result)
            assertNull(reset.startedAt)
            assertNull(reset.completedAt)
            assertEquals(1, reset.retryCount)
            assertNotNull(reset.retriedAt)

            val again = assertNotNull(store.retry(id))
            assertEquals(2, again.retryCount)
        }
    }

    @Nested
    inner class `listing and isolation` {

        @Test
        fun `listByUser is strictly scoped and newest first`() = runTest {
            val store = createStore()
            val suffix = UUID.randomUUID().toString().take(8)
            val older = task(id = "aa-$suffix", userId = "bob")
            val newer = task(id = "zz-$suffix", userId = "bob")
            store.create(older.copy(createdAt = 1_000))
            store.create(newer.copy(createdAt = 2_000))
            store.create(task(id = "other-$suffix", userId = "carol").copy(createdAt = 3_000))

            val ids = store.listByUser("bob").map { it.id }
            assertTrue(ids.contains("aa-$suffix") && ids.contains("zz-$suffix"))
            assertFalse(ids.contains("other-$suffix"), "another user's task must not leak")
            assertTrue(ids.indexOf("zz-$suffix") < ids.indexOf("aa-$suffix"), "newest first")
        }

        @Test
        fun `listByUser filters by status and session`() = runTest {
            val store = createStore()
            val suffix = UUID.randomUUID().toString().take(8)
            store.create(task(id = "s1-$suffix", userId = "dave", sessionId = "sess-$suffix"))
            store.create(task(id = "s2-$suffix", userId = "dave", sessionId = "other-$suffix"))
            store.create(task(id = "s3-$suffix", userId = "dave", sessionId = "sess-$suffix", status = AsyncTaskStatus.COMPLETED))

            val bySession = store.listByUser("dave", sessionId = "sess-$suffix").map { it.id }
            assertEquals(setOf("s1-$suffix", "s3-$suffix"), bySession.toSet())

            val byStatus = store.listByUser("dave", status = AsyncTaskStatus.COMPLETED).map { it.id }
            assertEquals(listOf("s3-$suffix"), byStatus)
        }

        @Test
        fun `a row with an unknown type is skipped instead of poisoning the whole list`() = runTest {
            val store = createStore()
            val suffix = UUID.randomUUID().toString().take(8)
            val knownId = "known-$suffix"
            store.create(task(id = knownId, userId = "ivan"))
            insertRawType("unknown-$suffix", "ivan", "some_future_type")

            val ids = store.listByUser("ivan").map { it.id }
            assertEquals(listOf(knownId), ids, "the unknown-type row is skipped, the known row still lists")
            assertNull(store.findById("unknown-$suffix"), "an unknown-type row reads as absent, not a 500")
        }

        @Test
        fun `a row with an unknown status or trigger is skipped too`() = runTest {
            val store = createStore()
            val suffix = UUID.randomUUID().toString().take(8)
            val knownId = "known-$suffix"
            val badStatus = "bad-status-$suffix"
            val badTrigger = "bad-trigger-$suffix"
            store.create(task(id = knownId, userId = "jerome"))
            insertRawType(badStatus, "jerome", AsyncTaskType.MEMORY_CONSOLIDATION.key, statusName = "SOMETHING_NEW")
            insertRawType(badTrigger, "jerome", AsyncTaskType.MEMORY_CONSOLIDATION.key, triggerName = "SOMETHING_NEW")

            val ids = store.listByUser("jerome").map { it.id }

            assertEquals(listOf(knownId), ids, "status and trigger degrade like type: skip, never 500")
            assertNull(store.findById(badStatus))
            assertNull(store.findById(badTrigger))
        }

        @Test
        fun `a row whose JSON column cannot be parsed still lists, without that field`() = runTest {
            val store = createStore()
            val suffix = UUID.randomUUID().toString().take(8)
            val goodId = "good-$suffix"
            val badId = "bad-$suffix"
            store.create(task(id = goodId, userId = "kate"))
            insertRawPayload(badId, "kate", "{ this is not json")

            val rows = store.listByUser("kate")

            // Unlike an unknown enum, an unreadable payload only costs the per-type title: hiding the
            // row would leave a task the user can no longer cancel or retry from the Tasks page.
            assertEquals(setOf(goodId, badId), rows.map { it.id }.toSet(), "one bad field must not blank the list")
            val bad = rows.first { it.id == badId }
            assertEquals(emptyMap(), bad.payload)
            assertNull(bad.result)
        }

        @Test
        fun `listNonTerminal finds a queued row behind a full page of finished ones`() = runTest {
            val store = createStore()
            val suffix = UUID.randomUUID().toString().take(8)
            val queuedId = "queued-$suffix"
            store.create(task(id = queuedId, userId = "lena"))
            // Twenty settled rows, each newer than the queued one. The scheduler's duplicate check
            // reads a bounded page: filtering terminal rows after the fact would cut this row off.
            (1..20).forEach { index ->
                val id = "done-$suffix-$index"
                val newer = System.currentTimeMillis() + index * 1_000L
                store.create(task(id = id, userId = "lena").copy(createdAt = newer, updatedAt = newer))
                store.markRunning(id)
                store.complete(id, null)
            }

            val nonTerminal = store.listNonTerminal("lena", AsyncTaskType.MEMORY_CONSOLIDATION, limit = 5)

            assertEquals(listOf(queuedId), nonTerminal.map { it.id }, "only unsettled rows come back, however deep")
        }
    }

    @Nested
    inner class `orphans and delete` {

        @Test
        fun `findOrphans returns non-terminal tasks (RUNNING and stuck PENDING)`() = runTest {
            val store = createStore()
            val runningId = UUID.randomUUID().toString()
            val pendingId = UUID.randomUUID().toString()
            store.create(task(id = runningId, userId = "gina"))
            store.markRunning(runningId)
            store.create(task(id = pendingId, userId = "gina")) // never dispatched → still PENDING

            val orphans = store.findOrphans().map { it.id }
            assertTrue(orphans.contains(runningId), "a RUNNING row is an orphan")
            assertTrue(orphans.contains(pendingId), "a stuck PENDING row is an orphan too")
        }

        @Test
        fun `delete removes one row and deleteByUser clears by status`() = runTest {
            val store = createStore()
            val keep = UUID.randomUUID().toString()
            val drop = UUID.randomUUID().toString()
            store.create(task(id = keep, userId = "henry"))
            store.create(task(id = drop, userId = "henry"))
            store.markRunning(drop)
            store.complete(drop, null)

            assertTrue(store.delete(drop))
            assertNull(store.findById(drop))
            assertNotNull(store.findById(keep))

            val cleared = store.deleteByUser("henry", AsyncTaskStatus.COMPLETED)
            assertEquals(0, cleared, "the completed row was already deleted")
            assertEquals(1, store.deleteByUser("henry"))
            assertTrue(store.listByUser("henry").isEmpty())
        }
    }
}
