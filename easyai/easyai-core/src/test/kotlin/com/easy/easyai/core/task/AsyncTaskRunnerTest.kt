package com.easy.easyai.core.task

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [AsyncTaskRunner] against an in-memory [AsyncTaskStore] stub: it drives status
 * transitions through the store, dispatches by type, cancels live work, and parks orphans on
 * reconcile — all independent of any session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AsyncTaskRunnerTest {

    /** In-memory [AsyncTaskStore] stub mirroring the R2DBC store's transition semantics. */
    private class FakeStore : AsyncTaskStore {
        val rows = mutableMapOf<String, AsyncTask>()
        override suspend fun create(task: AsyncTask): AsyncTask { rows[task.id] = task; return task }
        override suspend fun markRunning(id: String) = mutateIf(id, { it.status == AsyncTaskStatus.PENDING }) {
            it.copy(status = AsyncTaskStatus.RUNNING, startedAt = System.currentTimeMillis(), updatedAt = now())
        }
        override suspend fun complete(id: String, result: Map<String, Any?>?) = mutateIf(id, { !it.status.isTerminal }) {
            it.copy(status = AsyncTaskStatus.COMPLETED, result = result, error = null, completedAt = now(), updatedAt = now())
        }
        override suspend fun fail(id: String, error: String) = mutateIf(id, { !it.status.isTerminal }) {
            it.copy(status = AsyncTaskStatus.FAILED, error = error, completedAt = now(), updatedAt = now())
        }
        override suspend fun markTimedOut(id: String, error: String) = mutateIf(id, { !it.status.isTerminal }) {
            it.copy(status = AsyncTaskStatus.TIMED_OUT, error = error, completedAt = now(), updatedAt = now())
        }
        override suspend fun cancel(id: String) = mutateIf(id, { !it.status.isTerminal }) {
            it.copy(status = AsyncTaskStatus.CANCELLED, completedAt = now(), updatedAt = now())
        }
        override suspend fun markInterrupted(id: String) = mutateIf(id, { !it.status.isTerminal }) {
            it.copy(status = AsyncTaskStatus.INTERRUPTED, updatedAt = now())
        }
        override suspend fun retry(id: String) = mutate(id) {
            it.copy(status = AsyncTaskStatus.PENDING, error = null, result = null, startedAt = null, completedAt = null, retryCount = it.retryCount + 1, retriedAt = now(), updatedAt = now())
        }
        override suspend fun findById(id: String) = rows[id]
        override suspend fun listByUser(userId: String, type: AsyncTaskType?, status: AsyncTaskStatus?, sessionId: String?, limit: Int) =
            rows.values.filter { it.userId == userId }.toList()
        override suspend fun listNonTerminal(userId: String, type: AsyncTaskType?, limit: Int) =
            rows.values.filter { it.userId == userId && !it.status.isTerminal }.toList()
        override suspend fun delete(id: String) = rows.remove(id) != null
        override suspend fun deleteByUser(userId: String, status: AsyncTaskStatus?) =
            rows.values.filter { it.userId == userId && (status == null || it.status == status) }
                .also { matched -> matched.forEach { rows.remove(it.id) } }.size
        override suspend fun findOrphans() = rows.values.filter { !it.status.isTerminal }.toList()
        override suspend fun updateSessionId(id: String, sessionId: String) = mutateIf(id, { !it.status.isTerminal }) {
            it.copy(sessionId = sessionId, updatedAt = now())
        }

        private fun now() = System.currentTimeMillis()
        private suspend fun mutate(id: String, f: (AsyncTask) -> AsyncTask): AsyncTask? {
            val current = rows[id] ?: return null
            val next = f(current)
            rows[id] = next
            return next
        }
        /** Guarded transition: when [guard] rejects the current row, leave it untouched and return it as-is. */
        private suspend fun mutateIf(id: String, guard: (AsyncTask) -> Boolean, f: (AsyncTask) -> AsyncTask): AsyncTask? {
            val current = rows[id] ?: return null
            if (!guard(current)) return current
            val next = f(current)
            rows[id] = next
            return next
        }
    }

    private fun task(id: String) = AsyncTask(
        id = id,
        userId = "alice",
        type = AsyncTaskType.MEMORY_CONSOLIDATION,
        trigger = TaskTrigger.AUTO,
        title = "记忆归并"
    )

    private var seq = 0
    private val idSeq: String get() = "t-${seq++}"

    @Nested
    inner class `execution` {

        @Test
        fun `submit persists then runs to COMPLETED`() = runTest {
            val store = FakeStore()
            val handler = object : TaskHandler {
                override val type = AsyncTaskType.MEMORY_CONSOLIDATION
                override suspend fun execute(task: AsyncTask) =
                    TaskOutcome.Success(mapOf("merged" to 3))
            }
            val runner = AsyncTaskRunner(store, listOf(handler), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

            val id = idSeq
            val submitted = runner.submit(task(id))
            assertNotNull(submitted)
            advanceUntilIdle()

            assertEquals(AsyncTaskStatus.COMPLETED, store.rows[id]?.status)
            assertEquals(3, store.rows[id]?.result?.get("merged"))
        }

        @Test
        fun `a row settled before dispatch never runs its handler`() = runTest {
            // A queued task can be cancelled in the moment between its insert and its first dispatch.
            // markRunning is a guarded write that returns the row as it now stands, so ignoring that
            // result runs the work anyway and leaves a row that reads CANCELLED forever while its side
            // effects — consolidated memories, for instance — are live.
            val delegate = FakeStore()
            var executed = false
            val handler = object : TaskHandler {
                override val type = AsyncTaskType.MEMORY_CONSOLIDATION
                override suspend fun execute(task: AsyncTask): TaskOutcome {
                    executed = true
                    return TaskOutcome.Success(mapOf("merged" to 1))
                }
            }
            val store = object : AsyncTaskStore by delegate {
                override suspend fun markRunning(id: String): AsyncTask? {
                    delegate.cancel(id)
                    return delegate.markRunning(id)
                }
            }
            val runner = AsyncTaskRunner(store, listOf(handler), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

            val id = idSeq
            runner.submit(task(id))
            advanceUntilIdle()

            assertFalse(executed, "the row was already terminal, so nothing should have run")
            assertEquals(AsyncTaskStatus.CANCELLED, delegate.rows[id]?.status)
        }

        @Test
        fun `handler failure marks the task FAILED with the error`() = runTest {
            val store = FakeStore()
            val handler = object : TaskHandler {
                override val type = AsyncTaskType.MEMORY_CONSOLIDATION
                override suspend fun execute(task: AsyncTask) =
                    TaskOutcome.Failure("no curation model")
            }
            val runner = AsyncTaskRunner(store, listOf(handler), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

            val id = idSeq
            runner.submit(task(id))
            advanceUntilIdle()

            assertEquals(AsyncTaskStatus.FAILED, store.rows[id]?.status)
            assertEquals("no curation model", store.rows[id]?.error)
        }

        @Test
        fun `a failure write that itself throws does not escape the worker`() = runTest {
            // The store hiccup that stops a task being recorded as FAILED must not turn into an
            // uncaught exception on the runner's scope — that is what left the row stuck RUNNING.
            val delegate = FakeStore()
            val store = object : AsyncTaskStore by delegate {
                override suspend fun fail(id: String, error: String): AsyncTask? =
                    throw IllegalStateException("database is down")
            }
            val handler = object : TaskHandler {
                override val type = AsyncTaskType.MEMORY_CONSOLIDATION
                override suspend fun execute(task: AsyncTask): TaskOutcome = throw IllegalStateException("kaboom")
            }
            val runner = AsyncTaskRunner(store, listOf(handler), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

            val id = idSeq
            runner.submit(task(id))
            advanceUntilIdle()

            assertEquals(AsyncTaskStatus.RUNNING, delegate.rows[id]?.status, "the guarded write failed, so the row stayed where it was")
            assertTrue(runner.cancel(id), "the runner is still serving commands after the failed write")
            assertEquals(AsyncTaskStatus.CANCELLED, delegate.rows[id]?.status)
        }

        @Test
        fun `a thrown exception is captured as FAILED`() = runTest {
            val store = FakeStore()
            val handler = object : TaskHandler {
                override val type = AsyncTaskType.MEMORY_CONSOLIDATION
                override suspend fun execute(task: AsyncTask): TaskOutcome =
                    throw IllegalStateException("kaboom")
            }
            val runner = AsyncTaskRunner(store, listOf(handler), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

            val id = idSeq
            runner.submit(task(id))
            advanceUntilIdle()

            assertEquals(AsyncTaskStatus.FAILED, store.rows[id]?.status)
            assertEquals("kaboom", store.rows[id]?.error)
        }

        @Test
        fun `a run past its timeout is parked as TIMED_OUT`() = runTest {
            val store = FakeStore()
            val handler = object : TaskHandler {
                override val type = AsyncTaskType.MEMORY_CONSOLIDATION
                override suspend fun execute(task: AsyncTask): TaskOutcome {
                    delay(10_000)
                    return TaskOutcome.Success()
                }
            }
            val runner = AsyncTaskRunner(store, listOf(handler), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

            val id = idSeq
            runner.submit(task(id).copy(timeoutMs = 50))
            advanceUntilIdle()

            assertEquals(AsyncTaskStatus.TIMED_OUT, store.rows[id]?.status)
            assertTrue(store.rows[id]?.error?.contains("Timed out after 50ms") == true)
        }

        @Test
        fun `no handler for the type fails the task`() = runTest {
            val store = FakeStore()
            val runner = AsyncTaskRunner(store, emptyList(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

            val id = idSeq
            runner.submit(task(id))
            advanceUntilIdle()

            assertEquals(AsyncTaskStatus.FAILED, store.rows[id]?.status)
            assertTrue(store.rows[id]?.error?.contains("No handler") == true)
        }

        @Test
        fun `submit without a store is a no-op`() = runTest {
            val runner = AsyncTaskRunner(null, emptyList(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            assertNull(runner.submit(task(idSeq)))
        }
    }

    @Nested
    inner class `cancellation and reconciliation` {

        @Test
        fun `cancel interrupts a live worker and persists CANCELLED`() = runTest {
            val store = FakeStore()
            val gate = CompletableDeferred<Unit>()
            val handler = object : TaskHandler {
                override val type = AsyncTaskType.MEMORY_CONSOLIDATION
                override suspend fun execute(task: AsyncTask): TaskOutcome {
                    gate.await()
                    return TaskOutcome.Success()
                }
            }
            val runner = AsyncTaskRunner(store, listOf(handler), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

            val id = idSeq
            runner.submit(task(id))
            advanceUntilIdle()
            assertEquals(AsyncTaskStatus.RUNNING, store.rows[id]?.status)

            assertTrue(runner.cancel(id))
            advanceUntilIdle()
            assertEquals(AsyncTaskStatus.CANCELLED, store.rows[id]?.status)
        }

        @Test
        fun `reconcileOrphans parks RUNNING tasks as INTERRUPTED`() = runTest {
            val store = FakeStore()
            val runner = AsyncTaskRunner(store, emptyList(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            val orphan = task(idSeq)
            store.create(orphan)
            store.markRunning(orphan.id)

            runner.reconcileOrphans()

            assertEquals(AsyncTaskStatus.INTERRUPTED, store.rows[orphan.id]?.status)
        }

        @Test
        fun `reconcileOrphans parks a PENDING task the process never dispatched as INTERRUPTED`() = runTest {
            val store = FakeStore()
            val runner = AsyncTaskRunner(store, emptyList(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            val orphan = task(idSeq)
            store.create(orphan) // died between create and dispatch → still PENDING

            runner.reconcileOrphans()

            assertEquals(AsyncTaskStatus.INTERRUPTED, store.rows[orphan.id]?.status)
        }

        @Test
        fun `shutdown parks an in-flight worker as INTERRUPTED, not CANCELLED`() = runTest {
            val store = FakeStore()
            val gate = CompletableDeferred<Unit>()
            val handler = object : TaskHandler {
                override val type = AsyncTaskType.MEMORY_CONSOLIDATION
                override suspend fun execute(task: AsyncTask): TaskOutcome {
                    gate.await()
                    return TaskOutcome.Success()
                }
            }
            val runner = AsyncTaskRunner(store, listOf(handler), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

            val id = idSeq
            runner.submit(task(id))
            advanceUntilIdle()
            assertEquals(AsyncTaskStatus.RUNNING, store.rows[id]?.status)

            runner.shutdown()
            advanceUntilIdle()

            assertEquals(AsyncTaskStatus.INTERRUPTED, store.rows[id]?.status)
        }

        @Test
        fun `a submit that races shutdown writes no orphan row`() = runTest {
            val store = FakeStore()
            val handler = object : TaskHandler {
                override val type = AsyncTaskType.MEMORY_CONSOLIDATION
                override suspend fun execute(task: AsyncTask) = TaskOutcome.Success()
            }
            val runner = AsyncTaskRunner(store, listOf(handler), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            runner.shutdown()

            // Launching into the cancelled scope would leave a PENDING row nobody can run or settle
            // until the next start's orphan sweep, and the caller would hold a task id for nothing.
            assertNull(runner.submit(task(idSeq)))
            assertTrue(store.rows.isEmpty(), "a shutting-down runner must not persist work it cannot start")
        }
    }
}
