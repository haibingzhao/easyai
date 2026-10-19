package com.easy.easyai.core.task

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
    private val jobs = ConcurrentHashMap<String, Job>()

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
        val handler = handlersByType[task.type]
        if (handler == null) {
            logger.warn("No TaskHandler for type {}; marking task {} FAILED", task.type, task.id)
            scope.launch {
                runCatching {
                    taskStore.fail(task.id, "No handler registered for task type '${task.type.key}'")
                }.onFailure {
                    logger.warn("Failed to park handler-less async task {}: {}", task.id, it.message)
                }
            }
            return
        }

        val bodyStarted = AtomicBoolean(false)
        val job = scope.launch {
            bodyStarted.set(true)
            try {
                // The guarded PENDING→RUNNING write returns the row as it now stands, so a row another
                // path already settled (cancelled from the Tasks page, interrupted by a shutdown that
                // has since restarted) comes back non-RUNNING. Running the handler anyway would do work
                // nobody asked for and then have every terminal write guarded out — the row would read
                // "cancelled" forever while its side effects, such as consolidated memories, are live.
                val running = taskStore.markRunning(task.id)
                if (running == null || running.status != AsyncTaskStatus.RUNNING) {
                    logger.info(
                        "Async task {} not started: row is {}, not PENDING",
                        task.id, running?.status ?: "missing"
                    )
                    return@launch
                }
                when (val outcome = handler.execute(task)) {
                    is TaskOutcome.Success -> {
                        taskStore.complete(task.id, outcome.result)
                        logger.info("Async task completed: id={}, type={}", task.id, task.type)
                    }
                    is TaskOutcome.Failure -> {
                        settleFailure(taskStore, task, outcome.error)
                    }
                }
            } catch (e: CancellationException) {
                // The coroutine is already cancelled, so a plain suspend call would throw before the
                // UPDATE runs and the row would be stuck RUNNING. NonCancellable lets the terminal
                // write land before we propagate the cancellation.
                val shutdown = shuttingDown
                withContext(NonCancellable) {
                    runCatching {
                        if (shutdown) {
                            // Process is going away: park as INTERRUPTED so the next start (or a manual
                            // retry) can pick it up. CANCELLED would be terminal and lose the run.
                            taskStore.markInterrupted(task.id)
                        } else {
                            taskStore.cancel(task.id)
                        }
                    }.onFailure {
                        // The store can already be gone during shutdown. A failed terminal write must not
                        // replace the cancellation this coroutine is propagating.
                        logger.warn(
                            "Failed to record {} for async task {}: {}",
                            if (shutdown) "interruption" else "cancellation", task.id, it.message
                        )
                    }
                }
                logger.info(
                    "Async task {}: id={}, type={}",
                    if (shutdown) "interrupted by shutdown" else "cancelled", task.id, task.type
                )
                throw e
            } catch (e: Exception) {
                settleFailure(taskStore, task, e.message ?: "Unknown error", e)
            }
        }
        // Track after launch, but clean up via invokeOnCompletion: it fires immediately if the job
        // already finished (racing the assignment above), so no stale entry is ever left behind. The
        // two-arg remove never drops a newer job that reused the same id.
        jobs[task.id] = job
        job.invokeOnCompletion {
            jobs.remove(task.id, job)
            if (!bodyStarted.get()) settleUnstartedTask(taskStore, task.id)
        }
    }

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
    private fun settleUnstartedTask(taskStore: AsyncTaskStore, id: String) {
        if (shuttingDown) {
            // The scope is gone; [reconcileOrphans] parks the row as INTERRUPTED on the next start.
            logger.info("Async task {} never started before shutdown; left for orphan reconciliation", id)
            return
        }
        scope.launch {
            runCatching { taskStore.cancel(id) }
                .onFailure { logger.warn("Failed to park never-started async task {}: {}", id, it.message) }
        }
    }

    /**
     * Cancel a task. A live worker is interrupted (its coroutine persists CANCELLED); a task that
     * is only PENDING in the store — not yet picked up in this process — is marked CANCELLED here.
     * @return true when a live job was cancelled or a row was transitioned.
     */
    suspend fun cancel(id: String): Boolean {
        val job = jobs[id]
        if (job != null) {
            job.cancel()
            return true
        }
        if (store == null) return false
        val current = store.findById(id) ?: return false
        if (current.status.isTerminal) return false
        store.cancel(id)
        return true
    }

    /**
     * Park tasks a previous process left non-terminal (RUNNING, or PENDING when it died between
     * [AsyncTaskStore.create] and dispatch) as INTERRUPTED, so they can be retried manually.
     *
     * SINGLE-INSTANCE ASSUMPTION: this treats every non-terminal row as an orphan, which is only
     * correct when one process owns the database (the desktop backend, or a single web-server). In a
     * multi-instance deployment sharing a DB it would wrongly park tasks another live instance is
     * still running. Scaling out therefore needs an ownership/instance-id column to filter on before
     * this runs — until then, do not enable the async-task subsystem on a shared-DB multi-instance
     * topology.
     */
    suspend fun reconcileOrphans() {
        val taskStore = store ?: return
        val orphans = runCatching { taskStore.findOrphans() }.getOrElse {
            logger.warn("Orphan task reconciliation failed: {}", it.message)
            return
        }
        for (task in orphans) {
            runCatching { taskStore.markInterrupted(task.id) }
                .onSuccess { logger.info("Marked orphaned async task INTERRUPTED: id={}, type={}", task.id, task.type) }
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
        jobs.clear()
    }
}
