package com.easy.easyai.core.task

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * App-scoped executor for [AsyncTask]s. Owns a single [CoroutineScope] (SupervisorJob +
 * Dispatchers.Default) that is independent of any chat session, so a task keeps running when its
 * originating session closes, aborts, or the client refreshes; state lives in [AsyncTaskStore].
 *
 * One runner serves every [AsyncTaskType]: it dispatches to the matching [TaskHandler], drives
 * PENDING→RUNNING→COMPLETED/FAILED, mirrors each transition to the store. The store is the only
 * state sink — clients read it over REST, so there is no per-transition push path.
 *
 * The store is optional so a deployment without the R2DBC store degrades to a no-op instead of
 * failing to start.
 */
class AsyncTaskRunner(
    private val store: AsyncTaskStore?,
    handlers: List<TaskHandler> = emptyList(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val handlersByType: Map<AsyncTaskType, TaskHandler> =
        handlers.associateBy { it.type }.also { byType ->
            // "Add a task type, add a handler bean" is the whole registration contract, so two beans
            // for one type is a wiring mistake; saying so beats silently dropping one of them.
            handlers.groupBy { it.type }
                .filterValues { it.size > 1 }
                .forEach { (type, duplicates) ->
                    logger.warn(
                        "{} handlers registered for type {} ({}); '{}' dispatches, the rest are dead beans",
                        duplicates.size, type.key,
                        duplicates.joinToString(", ") { it.javaClass.simpleName },
                        byType[type]?.javaClass?.simpleName
                    )
                }
        }
    private class LocalTask(val job: Job, val settled: CompletableDeferred<Unit> = CompletableDeferred())

    private class WorkerContext(val runner: AsyncTaskRunner, val taskId: String) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<WorkerContext>
    }

    private val jobs = ConcurrentHashMap<String, LocalTask>()
    private val startupRecovery = CompletableDeferred<Unit>().also {
        if (store == null) it.complete(Unit)
    }

    /**
     * Set once [shutdown] begins. A cancellation observed while this is true is the process going
     * away, not a user action, so the worker parks its row as INTERRUPTED (retryable) instead of
     * CANCELLED (terminal). Volatile: written on the shutdown thread, read on worker threads.
     */
    @Volatile
    private var shuttingDown = false

    /**
     * Persist [task] (status PENDING) and start it. Returns the persisted task, or null when no
     * store is configured (nothing to track, so nothing runs).
     */
    suspend fun submit(task: AsyncTask): AsyncTask? {
        val taskStore = store
        if (taskStore == null) {
            logger.warn("AsyncTask submit ignored (no store configured): type={}", task.type)
            return null
        }
        if (shuttingDown) {
            // The scope is already cancelled: the row would be written, the launch would never run,
            // and nothing would settle it until the next start's orphan sweep.
            logger.warn("AsyncTask submit ignored (runner is shutting down): type={}", task.type)
            return null
        }
        val persisted = runCatching { taskStore.create(task) }
            .onFailure { logger.error("Failed to persist async task {}: {}", task.id, it.message) }
            .getOrNull() ?: return null
        dispatch(persisted)
        return persisted
    }

    /**
     * Start an already-persisted task (e.g. after [AsyncTaskStore.retry] reset it to PENDING).
     * Does not write the row again.
     */
    fun dispatch(task: AsyncTask) {
        val taskStore = store ?: return
        if (shuttingDown) return
        val bodyStarted = AtomicBoolean(false)
        val job = scope.launch(WorkerContext(this, task.id), start = CoroutineStart.LAZY) {
            bodyStarted.set(true)
            var claimedTask: AsyncTask? = null
            try {
                // Finish the claim even when cancellation races it, retaining ownership before the
                // cancellation handler runs. A losing dispatch must never settle the winner's row.
                withContext(NonCancellable) {
                    claimedTask = taskStore.markRunning(task.id)
                }
                val running = claimedTask
                if (running == null) {
                    logger.info("Async task {} not started: PENDING claim was not won", task.id)
                    return@launch
                }
                ensureActive()
                val handler = handlersByType[running.type]
                if (handler == null) {
                    settleFailure(taskStore, running, "No handler registered for task type '${running.type.key}'")
                    return@launch
                }
                when (val outcome = executeWithTimeout(running, handler)) {
                    is TaskOutcome.Success -> {
                        taskStore.complete(running.id, outcome.result)
                        logger.info("Async task completed: id={}, type={}", running.id, running.type)
                    }
                    is TaskOutcome.Failure -> {
                        settleFailure(taskStore, running, outcome.error)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                // Only a claimed row belongs to this worker; a timeout in the store is not a claim.
                val running = claimedTask ?: throw e
                val message = running.timeoutMs?.let { "Timed out after ${it}ms" } ?: "Timed out"
                withContext(NonCancellable) {
                    runCatching {
                        taskStore.markTimedOut(running.id, message)
                    }.onFailure {
                        logger.warn("Failed to record timeout for async task {}: {}", running.id, it.message)
                    }
                }
                logger.warn("Async task timed out: id={}, type={}, {}", running.id, running.type, message)
            } catch (e: CancellationException) {
                val running = claimedTask ?: throw e
                val shutdown = shuttingDown
                // The coroutine is cancelled; allow its owned row's terminal write to land.
                withContext(NonCancellable) {
                    runCatching {
                        if (shutdown) {
                            taskStore.markInterrupted(running.id)
                        } else {
                            taskStore.cancel(running.id)
                        }
                    }.onFailure {
                        logger.warn(
                            "Failed to record {} for async task {}: {}",
                            if (shutdown) "interruption" else "cancellation", running.id, it.message
                        )
                    }
                }
                logger.info(
                    "Async task {}: id={}, type={}",
                    if (shutdown) "interrupted by shutdown" else "cancelled", running.id, running.type
                )
                throw e
            } catch (e: Exception) {
                val running = claimedTask
                if (running != null) {
                    settleFailure(taskStore, running, e.message ?: "Unknown error", e)
                } else {
                    logger.warn("Failed to claim async task {}: {}", task.id, e.message, e)
                }
            }
        }
        // Register before starting, including with an unconfined dispatcher. A duplicate must neither
        // overwrite the live worker nor install an unstarted-job callback that could cancel it.
        val local = LocalTask(job)
        if (jobs.putIfAbsent(task.id, local) != null) {
            job.cancel()
            return
        }
        job.invokeOnCompletion {
            val cleanup = if (bodyStarted.get()) null else settleUnstartedTask(taskStore, task.id)
            if (cleanup == null) finishLocalTask(task.id, local)
            else cleanup.invokeOnCompletion { finishLocalTask(task.id, local) }
        }
        job.start()
    }

    private fun finishLocalTask(id: String, local: LocalTask) {
        jobs.remove(id, local)
        local.settled.complete(Unit)
    }

    /**
     * Run the handler under the task's timeout when one is set. `> 0` is required so a malformed row
     * cannot turn every dispatch into an immediate timeout.
     */
    private suspend fun executeWithTimeout(task: AsyncTask, handler: TaskHandler): TaskOutcome =
        task.timeoutMs?.takeIf { it > 0 }?.let { timeoutMs ->
            withTimeout(timeoutMs) { handler.execute(task) }
        } ?: handler.execute(task)

    /**
     * Park a failed task. The write itself must never escape: on the generic failure path it runs
     * inside the handler's own `catch`, and a store hiccup there would replace a recorded failure
     * with an uncaught exception on a supervisor-managed coroutine and leave the row RUNNING.
     */
    private suspend fun settleFailure(
        taskStore: AsyncTaskStore,
        task: AsyncTask,
        error: String,
        cause: Throwable? = null
    ) {
        runCatching { taskStore.fail(task.id, error) }
            .onSuccess { logger.warn("Async task failed: id={}, type={}, error={}", task.id, task.type, error) }
            .onFailure {
                logger.error("Failed to record failure for async task {}: {}", task.id, it.message, it)
                if (cause != null) {
                    logger.error("Original async task {} (type {}) error: {}", task.id, task.type, cause.message, cause)
                }
            }
    }

    /**
     * Settle a row whose worker coroutine never began running. A task cancelled in the moment between
     * its insert and its first dispatch reaches none of the in-coroutine terminal writes, so without
     * this it would stay PENDING after the caller was told it was cancelled.
     */
    private fun settleUnstartedTask(taskStore: AsyncTaskStore, id: String): Job? {
        if (shuttingDown) {
            // Scheduled PENDING rows can be dispatched on restart; no handler has run yet.
            logger.info("Async task {} never started before shutdown; left for orphan reconciliation", id)
            return null
        }
        return scope.launch(NonCancellable + WorkerContext(this, id)) {
            runCatching {
                // Another runner may already own RUNNING. Never cancel it just because this
                // local dispatch was cancelled before its body could attempt the claim.
                if (taskStore.markRunning(id) != null) {
                    if (shuttingDown) taskStore.markInterrupted(id) else taskStore.cancel(id)
                }
            }.onFailure { logger.warn("Failed to park never-started async task {}: {}", id, it.message) }
        }
    }

    /**
     * Cancel a task. A live worker is interrupted (its coroutine persists CANCELLED); a task that
     * is only PENDING in the store — not yet picked up in this process — is marked CANCELLED here.
     * @return true when a live job was cancelled or a row was transitioned.
     */
    suspend fun cancel(id: String): Boolean {
        val local = jobs[id]
        if (local != null) {
            local.job.cancel()
            return true
        }
        return cancelStoredTask(id)
    }

    /** Wait for the current local worker and its terminal cleanup. External callers only. */
    suspend fun awaitIdle(id: String) {
        checkExternalCaller(id)
        val local = jobs[id] ?: return
        local.job.join()
        local.settled.await()
    }

    /** Cancel the captured worker and await its cleanup, or settle a store-only row. External callers only. */
    suspend fun cancelAndAwait(id: String): Boolean {
        checkExternalCaller(id)
        val local = jobs[id] ?: return cancelStoredTask(id)
        local.job.cancel()
        local.job.join()
        local.settled.await()
        return true
    }

    private suspend fun checkExternalCaller(id: String) {
        val worker = currentCoroutineContext()[WorkerContext]
        check(worker == null || worker.runner !== this || worker.taskId != id) {
            "A task worker cannot await its own completion"
        }
    }

    private suspend fun cancelStoredTask(id: String): Boolean {
        if (store == null) return false
        val current = store.findById(id) ?: return false
        if (current.status.isTerminal) return false
        return store.cancel(id)?.status == AsyncTaskStatus.CANCELLED
    }

    /**
     * Wait until the startup recovery attempt ends, not until recovered handlers finish. A missing
     * store is immediately ready; a failed recovery is logged and releases waiters as well.
     */
    suspend fun awaitStartupRecovery() {
        startupRecovery.await()
    }

    /**
     * Recover this application's initial orphan snapshot. First park all RUNNING and non-SCHEDULE
     * PENDING rows as INTERRUPTED; then dispatch only SCHEDULE PENDING rows. Their enqueue committed
     * before the crash but no handler claimed them, whereas replaying RUNNING could repeat effects.
     *
     * Called once by the startup lifecycle. [AsyncTaskStore.findOrphans] excludes other applications'
     * accounts and users with no account row (e.g. `system`). Scheduler preload must wait for
     * [awaitStartupRecovery] so newly enqueued work cannot be mistaken for an orphan.
     */
    suspend fun reconcileOrphans() {
        try {
            val taskStore = store ?: return
            val orphans = taskStore.findOrphans()
            val resumable = orphans.filter {
                it.status == AsyncTaskStatus.PENDING && it.trigger == TaskTrigger.SCHEDULE
            }
            for (task in orphans) {
                if (task.status != AsyncTaskStatus.RUNNING &&
                    !(task.status == AsyncTaskStatus.PENDING && task.trigger != TaskTrigger.SCHEDULE)
                ) continue
                try {
                    taskStore.markInterrupted(task.id)
                    logger.info("Marked orphaned async task INTERRUPTED: id={}, type={}", task.id, task.type)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("Failed to park orphaned async task {}: {}", task.id, e.message)
                }
            }
            resumable.forEach { dispatch(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Orphan task reconciliation failed: {}", e.message)
        } finally {
            startupRecovery.complete(Unit)
        }
    }

    /**
     * Cancel the executor scope (application shutdown). In-flight workers observe [shuttingDown] and
     * park their rows as INTERRUPTED; anything the process did not reach is caught by [reconcileOrphans]
     * on the next start.
     */
    fun shutdown() {
        logger.info("Shutting down AsyncTaskRunner ({} in-flight task(s))", jobs.size)
        shuttingDown = true
        scope.coroutineContext[Job]?.cancel(CancellationException("application context is closing"))
    }
}
