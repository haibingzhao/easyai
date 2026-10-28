package com.easy.easyai.core.task

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        override suspend fun markRunning(id: String): AsyncTask? {
            val current = rows[id] ?: return null
            if (current.status != AsyncTaskStatus.PENDING) return null
            return current.copy(status = AsyncTaskStatus.RUNNING, startedAt = now(), updatedAt = now())
                .also { rows[id] = it }
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
            rows.values.filter {
                it.userId == userId && (status == null || it.status == status)
            }
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
            // Cancellation between insert and dispatch makes markRunning return null. Ignoring the
            // lost claim would run side effects while the row remained CANCELLED forever.
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
        fun `repeated dispatch invokes one handler and keeps its cancellation tracked`() = runTest {
            val delegate = FakeStore()
            var claims = 0
            val store = object : AsyncTaskStore by delegate {
                override suspend fun markRunning(id: String): AsyncTask? {
                    claims++
                    return delegate.markRunning(id)
                }
            }
            val gate = CompletableDeferred<Unit>()
            var executions = 0
            var stopped = false
            val handler = object : TaskHandler {
                override val type = AsyncTaskType.MEMORY_CONSOLIDATION
                override suspend fun execute(task: AsyncTask): TaskOutcome {
                    executions++
                    try {
                        gate.await()
                        return TaskOutcome.Success()
                    } finally {
                        stopped = true
                    }
                }
            }
            val runner = AsyncTaskRunner(store, listOf(handler), CoroutineScope(StandardTestDispatcher(testScheduler)))
            val pending = assertNotNull(runner.submit(task(idSeq)))
            repeat(5) { runner.dispatch(pending) }
            runCurrent()
            repeat(5) { runner.dispatch(pending) }
            runCurrent()

            assertEquals(1, executions)
            assertEquals(1, claims, "local duplicates must not replace or cancel the registered worker")
            assertEquals(AsyncTaskStatus.RUNNING, delegate.rows[pending.id]?.status)
            assertTrue(runner.cancel(pending.id))
            advanceUntilIdle()
            assertTrue(stopped, "cancel must interrupt the handler, not merely change its row")
            assertEquals(AsyncTaskStatus.CANCELLED, delegate.rows[pending.id]?.status)
        }

        @Test
        fun `a second runner cannot execute or fail a task already claimed by the first`() = runTest {
            val store = FakeStore()
            val gate = CompletableDeferred<Unit>()
            var executions = 0
            val handler = object : TaskHandler {
                override val type = AsyncTaskType.MEMORY_CONSOLIDATION
                override suspend fun execute(task: AsyncTask): TaskOutcome {
                    executions++
                    gate.await()
                    return TaskOutcome.Success()
                }
            }
            val first = AsyncTaskRunner(store, listOf(handler), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            val second = AsyncTaskRunner(store, listOf(handler), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            val handlerless = AsyncTaskRunner(store, emptyList(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            val pending = assertNotNull(first.submit(task(idSeq)))
            second.dispatch(pending)
            handlerless.dispatch(pending)

            assertEquals(1, executions)
            assertEquals(AsyncTaskStatus.RUNNING, store.rows[pending.id]?.status)
            gate.complete(Unit)
            advanceUntilIdle()
            second.dispatch(pending)
            assertEquals(1, executions, "a terminal row cannot be dispatched again either")
            assertEquals(AsyncTaskStatus.COMPLETED, store.rows[pending.id]?.status)
        }

        @Test
        fun `dispatch uses the claimed row for handler selection payload and timeout`() = runTest {
            val store = FakeStore()
            val stale = task(idSeq)
            val authoritative = stale.copy(
                type = AsyncTaskType.WATCH,
                timeoutMs = 50,
                payload = mapOf("fresh" to true),
                sessionId = "persisted-session"
            )
            store.create(authoritative)
            var received: AsyncTask? = null
            val handler = object : TaskHandler {
                override val type = AsyncTaskType.WATCH
                override suspend fun execute(task: AsyncTask): TaskOutcome {
                    received = task
                    delay(10_000)
                    return TaskOutcome.Success()
                }
            }
            val runner = AsyncTaskRunner(store, listOf(handler), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            runner.dispatch(stale)
            val running = assertNotNull(store.rows[stale.id])
            assertEquals(AsyncTaskStatus.RUNNING, running.status)
            assertNotNull(running.startedAt)
            assertEquals(running, received)
            advanceUntilIdle()

            assertEquals(AsyncTaskStatus.TIMED_OUT, store.rows[stale.id]?.status)
            assertEquals("Timed out after 50ms", store.rows[stale.id]?.error)
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
    inner class `awaiting local completion` {

        @Test
        fun `awaitIdle waits for both execution and the terminal write without cancelling`() = runTest {
            val delegate = FakeStore()
            val executeGate = CompletableDeferred<Unit>()
            val writeGate = CompletableDeferred<Unit>()
            val store = object : AsyncTaskStore by delegate {
                override suspend fun complete(id: String, result: Map<String, Any?>?): AsyncTask? =
                    withContext(NonCancellable) {
                        writeGate.await()
                        delegate.complete(id, result)
                    }
            }
            val handler = object : TaskHandler {
                override val type = AsyncTaskType.MEMORY_CONSOLIDATION
                override suspend fun execute(task: AsyncTask): TaskOutcome {
                    executeGate.await()
                    return TaskOutcome.Success()
                }
            }
            val runner = AsyncTaskRunner(store, listOf(handler), CoroutineScope(StandardTestDispatcher(testScheduler)))
            val pending = assertNotNull(runner.submit(task(idSeq)))
            val waiting = async { runner.awaitIdle(pending.id) }
            runCurrent()
            assertFalse(waiting.isCompleted)
            assertEquals(AsyncTaskStatus.RUNNING, delegate.rows[pending.id]?.status)
            executeGate.complete(Unit)
            runCurrent()
            assertFalse(waiting.isCompleted, "returning from execute is not terminal cleanup")
            writeGate.complete(Unit)
            waiting.await()
            assertEquals(AsyncTaskStatus.COMPLETED, delegate.rows[pending.id]?.status)
            runner.awaitIdle(pending.id)
            runner.awaitIdle("missing")
        }

        @Test
        fun `cancelAndAwait and awaitIdle wait through noncancellable handler and store cleanup`() = runTest {
            val delegate = FakeStore()
            val cleanupGate = CompletableDeferred<Unit>()
            val writeGate = CompletableDeferred<Unit>()
            var cleanedUp = false
            var executions = 0
            val store = object : AsyncTaskStore by delegate {
                override suspend fun cancel(id: String): AsyncTask? {
                    writeGate.await()
                    return delegate.cancel(id)
                }
            }
            val handler = object : TaskHandler {
                override val type = AsyncTaskType.MEMORY_CONSOLIDATION
                override suspend fun execute(task: AsyncTask): TaskOutcome {
                    executions++
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            cleanupGate.await()
                            cleanedUp = true
                        }
                    }
                }
            }
            val runner = AsyncTaskRunner(store, listOf(handler), CoroutineScope(StandardTestDispatcher(testScheduler)))
            val pending = assertNotNull(runner.submit(task(idSeq)))
            runCurrent()
            val cancelling = async { runner.cancelAndAwait(pending.id) }
            val waiting = async { runner.awaitIdle(pending.id) }
            runCurrent()
            assertFalse(cancelling.isCompleted)
            assertFalse(waiting.isCompleted)
            assertFalse(cleanedUp)
            runner.dispatch(pending)
            cleanupGate.complete(Unit)
            runCurrent()
            assertTrue(cleanedUp)
            assertFalse(cancelling.isCompleted, "the terminal write is still suspended")
            assertFalse(waiting.isCompleted)
            assertEquals(AsyncTaskStatus.RUNNING, delegate.rows[pending.id]?.status)
            assertEquals(1, executions)
            writeGate.complete(Unit)
            assertTrue(cancelling.await())
            waiting.await()
            assertEquals(AsyncTaskStatus.CANCELLED, delegate.rows[pending.id]?.status)
            assertFalse(runner.cancelAndAwait(pending.id))
        }

        @Test
        fun `unstarted cancellation stays tracked until its separate settlement finishes`() = runTest {
            val delegate = FakeStore()
            val writeGate = CompletableDeferred<Unit>()
            var claims = 0
            val store = object : AsyncTaskStore by delegate {
                override suspend fun markRunning(id: String): AsyncTask? {
                    claims++
                    return delegate.markRunning(id)
                }
                override suspend fun cancel(id: String): AsyncTask? {
                    writeGate.await()
                    return delegate.cancel(id)
                }
            }
            val runner = AsyncTaskRunner(store, emptyList(), CoroutineScope(StandardTestDispatcher(testScheduler)))
            val pending = assertNotNull(runner.submit(task(idSeq)))
            val cancelling = async(start = CoroutineStart.UNDISPATCHED) { runner.cancelAndAwait(pending.id) }
            runCurrent()
            val waiting = async { runner.awaitIdle(pending.id) }
            runner.dispatch(pending)
            runCurrent()
            assertFalse(cancelling.isCompleted)
            assertFalse(waiting.isCompleted)
            assertEquals(1, claims, "a pending cleanup must still suppress duplicate dispatch")
            writeGate.complete(Unit)
            assertTrue(cancelling.await())
            waiting.await()
            assertEquals(AsyncTaskStatus.CANCELLED, delegate.rows[pending.id]?.status)
        }

        @Test
        fun `cancelAndAwait of an unstarted loser does not cancel another runners claim`() = runTest {
            val store = FakeStore()
            val executeGate = CompletableDeferred<Unit>()
            val handler = object : TaskHandler {
                override val type = AsyncTaskType.MEMORY_CONSOLIDATION
                override suspend fun execute(task: AsyncTask): TaskOutcome {
                    executeGate.await()
                    return TaskOutcome.Success()
                }
            }
            val first = AsyncTaskRunner(store, listOf(handler), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            val second = AsyncTaskRunner(store, emptyList(), CoroutineScope(StandardTestDispatcher(testScheduler)))
            val pending = assertNotNull(first.submit(task(idSeq)))
            second.dispatch(pending)
            assertTrue(second.cancelAndAwait(pending.id))
            assertEquals(AsyncTaskStatus.RUNNING, store.rows[pending.id]?.status)
            executeGate.complete(Unit)
            first.awaitIdle(pending.id)
            assertEquals(AsyncTaskStatus.COMPLETED, store.rows[pending.id]?.status)
        }

        @Test
        fun `cancelAndAwait settles store-only rows and rejects absent or terminal rows`() = runTest {
            val store = FakeStore()
            val runner = AsyncTaskRunner(store)
            for (status in listOf(AsyncTaskStatus.PENDING, AsyncTaskStatus.RUNNING)) {
                val pending = store.create(task(idSeq).copy(status = status))
                assertTrue(runner.cancelAndAwait(pending.id))
                assertEquals(AsyncTaskStatus.CANCELLED, store.rows[pending.id]?.status)
                assertFalse(runner.cancelAndAwait(pending.id))
            }
            assertFalse(runner.cancelAndAwait("missing"))
            val noStore = AsyncTaskRunner(null)
            assertFalse(noStore.cancelAndAwait("missing"))
            noStore.awaitIdle("missing")
            runner.shutdown()
            noStore.shutdown()
        }

        @Test
        fun `a worker cannot await itself even inside timeout and noncancellable contexts`() = runTest {
            val store = FakeStore()
            lateinit var runner: AsyncTaskRunner
            var guarded = false
            val handler = object : TaskHandler {
                override val type = AsyncTaskType.MEMORY_CONSOLIDATION
                override suspend fun execute(task: AsyncTask): TaskOutcome {
                    withContext(NonCancellable) {
                        assertFailsWith<IllegalStateException> { runner.awaitIdle(task.id) }
                        assertFailsWith<IllegalStateException> { runner.cancelAndAwait(task.id) }
                    }
                    guarded = true
                    return TaskOutcome.Success()
                }
            }
            runner = AsyncTaskRunner(store, listOf(handler), CoroutineScope(StandardTestDispatcher(testScheduler)))
            val pending = assertNotNull(runner.submit(task(idSeq).copy(timeoutMs = 1_000)))
            runner.awaitIdle(pending.id)
            assertTrue(guarded)
            assertEquals(AsyncTaskStatus.COMPLETED, store.rows[pending.id]?.status)
        }

        @Test
        fun `awaitIdle after shutdown still waits for interruption cleanup`() = runTest {
            val delegate = FakeStore()
            val writeGate = CompletableDeferred<Unit>()
            val store = object : AsyncTaskStore by delegate {
                override suspend fun markInterrupted(id: String): AsyncTask? {
                    writeGate.await()
                    return delegate.markInterrupted(id)
                }
            }
            val handler = object : TaskHandler {
                override val type = AsyncTaskType.MEMORY_CONSOLIDATION
                override suspend fun execute(task: AsyncTask): TaskOutcome = awaitCancellation()
            }
            val runner = AsyncTaskRunner(store, listOf(handler), CoroutineScope(StandardTestDispatcher(testScheduler)))
            val pending = assertNotNull(runner.submit(task(idSeq)))
            runCurrent()
            runner.shutdown()
            val waiting = async { runner.awaitIdle(pending.id) }
            runCurrent()
            assertFalse(waiting.isCompleted)
            writeGate.complete(Unit)
            waiting.await()
            assertEquals(AsyncTaskStatus.INTERRUPTED, delegate.rows[pending.id]?.status)
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
        fun `cancelling before the worker starts settles PENDING without running the handler`() = runTest {
            val store = FakeStore()
            var executions = 0
            val handler = object : TaskHandler {
                override val type = AsyncTaskType.MEMORY_CONSOLIDATION
                override suspend fun execute(task: AsyncTask): TaskOutcome {
                    executions++
                    return TaskOutcome.Success()
                }
            }
            val runner = AsyncTaskRunner(store, listOf(handler), CoroutineScope(StandardTestDispatcher(testScheduler)))
            val pending = assertNotNull(runner.submit(task(idSeq)))
            runner.dispatch(pending)
            assertTrue(runner.cancel(pending.id))
            advanceUntilIdle()

            assertEquals(0, executions)
            assertEquals(AsyncTaskStatus.CANCELLED, store.rows[pending.id]?.status)
        }

        @Test
        fun `cancelling an unstarted competing dispatch never cancels another runners claim`() = runTest {
            val store = FakeStore()
            val gate = CompletableDeferred<Unit>()
            var executions = 0
            val handler = object : TaskHandler {
                override val type = AsyncTaskType.MEMORY_CONSOLIDATION
                override suspend fun execute(task: AsyncTask): TaskOutcome {
                    executions++
                    gate.await()
                    return TaskOutcome.Success()
                }
            }
            val first = AsyncTaskRunner(store, listOf(handler), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            val second = AsyncTaskRunner(store, listOf(handler), CoroutineScope(StandardTestDispatcher(testScheduler)))
            val pending = assertNotNull(first.submit(task(idSeq)))
            second.dispatch(pending)
            assertTrue(second.cancel(pending.id))
            advanceUntilIdle()

            assertEquals(1, executions)
            assertEquals(AsyncTaskStatus.RUNNING, store.rows[pending.id]?.status)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(AsyncTaskStatus.COMPLETED, store.rows[pending.id]?.status)
        }

        @Test
        fun `cancellation during a successful claim settles the row without invoking the handler`() = runTest {
            val delegate = FakeStore()
            val claimFinished = CompletableDeferred<Unit>()
            val store = object : AsyncTaskStore by delegate {
                override suspend fun markRunning(id: String): AsyncTask? {
                    val running = delegate.markRunning(id)
                    claimFinished.await()
                    return running
                }
            }
            var executions = 0
            val handler = object : TaskHandler {
                override val type = AsyncTaskType.MEMORY_CONSOLIDATION
                override suspend fun execute(task: AsyncTask): TaskOutcome {
                    executions++
                    return TaskOutcome.Success()
                }
            }
            val runner = AsyncTaskRunner(store, listOf(handler), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            val pending = assertNotNull(runner.submit(task(idSeq)))
            assertEquals(AsyncTaskStatus.RUNNING, delegate.rows[pending.id]?.status)
            assertTrue(runner.cancel(pending.id))
            claimFinished.complete(Unit)
            advanceUntilIdle()

            assertEquals(0, executions)
            assertEquals(AsyncTaskStatus.CANCELLED, delegate.rows[pending.id]?.status)
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
        fun `recovery dispatches only snapshot scheduled PENDING after all other orphans are parked`() = runTest {
            val delegate = FakeStore()
            val pending = task(idSeq).copy(trigger = TaskTrigger.SCHEDULE)
            val running = task(idSeq).copy(trigger = TaskTrigger.SCHEDULE, status = AsyncTaskStatus.RUNNING)
            val automatic = task(idSeq)
            val manual = task(idSeq).copy(trigger = TaskTrigger.FRONTEND)
            val late = task(idSeq).copy(trigger = TaskTrigger.SCHEDULE)
            // The resumable row is first in the snapshot: dispatch inside the parking loop is unsafe.
            listOf(pending, running, automatic, manual).forEach { delegate.create(it) }
            val store = object : AsyncTaskStore by delegate {
                override suspend fun markInterrupted(id: String): AsyncTask? {
                    delegate.create(late)
                    return delegate.markInterrupted(id)
                }
            }
            val executed = mutableListOf<String>()
            val handler = object : TaskHandler {
                override val type = AsyncTaskType.MEMORY_CONSOLIDATION
                override suspend fun execute(task: AsyncTask): TaskOutcome {
                    executed += task.id
                    assertTrue(listOf(running, automatic, manual).all {
                        delegate.rows[it.id]?.status == AsyncTaskStatus.INTERRUPTED
                    }, "every unsafe orphan must be parked before any recovered handler starts")
                    return TaskOutcome.Success()
                }
            }
            val runner = AsyncTaskRunner(store, listOf(handler), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            runner.reconcileOrphans()
            runner.awaitStartupRecovery()
            advanceUntilIdle()

            assertEquals(listOf(pending.id), executed, "SCHEDULE RUNNING must not be replayed")
            assertEquals(AsyncTaskStatus.COMPLETED, delegate.rows[pending.id]?.status)
            assertEquals(AsyncTaskStatus.PENDING, delegate.rows[late.id]?.status, "no second orphan query after parking")
        }

        @Test
        fun `startup readiness waits for parking and dispatch but not recovered handlers`() = runTest {
            val delegate = FakeStore()
            val pending = task(idSeq).copy(trigger = TaskTrigger.SCHEDULE)
            val running = task(idSeq).copy(status = AsyncTaskStatus.RUNNING)
            delegate.create(pending)
            delegate.create(running)
            val parking = CompletableDeferred<Unit>()
            val store = object : AsyncTaskStore by delegate {
                override suspend fun markInterrupted(id: String): AsyncTask? {
                    parking.await()
                    return delegate.markInterrupted(id)
                }
            }
            val handlerGate = CompletableDeferred<Unit>()
            val handler = object : TaskHandler {
                override val type = AsyncTaskType.MEMORY_CONSOLIDATION
                override suspend fun execute(task: AsyncTask): TaskOutcome {
                    handlerGate.await()
                    return TaskOutcome.Success()
                }
            }
            val runner = AsyncTaskRunner(store, listOf(handler), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            var ready = false
            val waiter = launch { runner.awaitStartupRecovery(); ready = true }
            runCurrent()
            assertFalse(ready, "having a store requires a startup recovery attempt")
            val recovery = launch { runner.reconcileOrphans() }
            runCurrent()
            assertFalse(ready, "parking has not finished")
            assertEquals(AsyncTaskStatus.PENDING, delegate.rows[pending.id]?.status)
            parking.complete(Unit)
            recovery.join()
            waiter.join()

            assertTrue(ready)
            assertEquals(AsyncTaskStatus.INTERRUPTED, delegate.rows[running.id]?.status)
            assertEquals(AsyncTaskStatus.RUNNING, delegate.rows[pending.id]?.status)
            handlerGate.complete(Unit)
            advanceUntilIdle()
        }

        @Test
        fun `startup readiness without a store does not need a lifecycle invocation`() = runTest {
            val runner = AsyncTaskRunner(null, emptyList(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            runner.awaitStartupRecovery()
            runner.reconcileOrphans()
            runner.awaitStartupRecovery()
        }

        @Test
        fun `startup readiness completes when orphan lookup fails`() = runTest {
            val store = object : AsyncTaskStore by FakeStore() {
                override suspend fun findOrphans(): List<AsyncTask> = throw IllegalStateException("database is down")
            }
            val runner = AsyncTaskRunner(store, emptyList(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            val waiter = launch { runner.awaitStartupRecovery() }
            runCurrent()
            assertFalse(waiter.isCompleted)
            runner.reconcileOrphans()
            waiter.join()
            assertTrue(waiter.isCompleted)
        }

        @Test
        fun `startup readiness completes when recovery is cancelled`() = runTest {
            val gate = CompletableDeferred<Unit>()
            val store = object : AsyncTaskStore by FakeStore() {
                override suspend fun findOrphans(): List<AsyncTask> {
                    gate.await()
                    return emptyList()
                }
            }
            val runner = AsyncTaskRunner(store, emptyList(), CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
            val recovery = launch { runner.reconcileOrphans() }
            runCurrent()
            recovery.cancel()
            recovery.join()
            runner.awaitStartupRecovery()
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
