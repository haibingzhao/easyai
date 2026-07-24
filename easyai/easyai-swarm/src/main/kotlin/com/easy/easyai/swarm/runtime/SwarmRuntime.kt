package com.easy.easyai.swarm.runtime

import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.core.agent.AgentDefinition
import com.easy.easyai.swarm.dag.DagAlgorithms
import com.easy.easyai.swarm.event.SwarmEventBridge
import com.easy.easyai.swarm.model.*
import com.easy.easyai.swarm.store.SwarmRunStore
import com.easy.easyai.swarm.tool.UpdatableVariableGuard
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/**
 * Core DAG orchestration engine for multi-agent swarm execution.
 *
 * Executes swarm runs by:
 * 1. Validating the task DAG (cycle detection)
 * 2. Computing topological layers (Kahn's algorithm)
 * 3. Executing layers sequentially, tasks within a layer in parallel
 * 4. Routing to single-agent worker or multi-agent deliberation based on TaskType
 * 5. Passing upstream summaries to downstream tasks via template variable substitution
 *
 * Worker execution is delegated to [SwarmWorkerExecutor].
 * TEAM and DELIBERATION task execution is delegated to [TeamTaskExecutor] and
 * [DeliberationTaskExecutor] respectively, keeping this class focused on DAG orchestration.
 *
 * @param workerExecutor Executes individual swarm worker agents (resolve, run, collect)
 * @param agentResolver Resolves AgentDefinition + tools/skills/MCP from DB for batch loading
 * @param eventBridge Event bridge for emitting swarm/deliberation events
 * @param maxConcurrency Maximum concurrent worker executions (Semaphore permits)
 * @param store Optional run store for real-time task persistence (saveTask on completion/failure)
 */
class SwarmRuntime(
    private val workerExecutor: SwarmWorkerExecutor,
    private val agentResolver: SwarmAgentResolver,
    private val eventBridge: SwarmEventBridge = SwarmEventBridge(),
    private val maxConcurrency: Int = 4,
    private val store: SwarmRunStore? = null,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val semaphore = Semaphore(maxConcurrency)
    private val dagMutex = Mutex()

    private val teamExecutor = TeamTaskExecutor(
        workerExecutor = workerExecutor,
        eventBridge = eventBridge,
    )

    private val deliberationExecutor = DeliberationTaskExecutor(
        workerExecutor = workerExecutor,
        eventBridge = eventBridge,
        store = store,
    )

    /**
     * Execute a swarm run to completion.
     *
     * @param run The swarm run to execute (mutated in place with results)
     * @param abortSignal Signal that returns true when cancellation is requested
     * @param pauseRequested Flag that when set to true, pauses after the current layer completes
     * @param modelConfigId Optional model config ID override for all workers in this run
     * @return The completed, failed, cancelled, or paused run
     */
    suspend fun execute(
        run: SwarmRun,
        abortSignal: () -> Boolean = { false },
        pauseRequested: AtomicBoolean? = null,
        modelConfigId: String? = null
    ): SwarmRun {
        logger.info("Starting swarm run '{}': {} agents, {} tasks", run.id, run.agents.size, run.tasks.size)

        // Validate DAG
        DagAlgorithms.validateDag(run.tasks)

        run.startedAt = Instant.now()
        run.status = SwarmRunStatus.RUNNING

        // Compute execution layers
        val layers = DagAlgorithms.topologicalLayers(run.tasks)
        logger.info("Run '{}' has {} layers: {}", run.id, layers.size, layers)

        // Announce run start immediately so frontend can begin SSE subscription
        val runContext = initializeRunContext(run, modelConfigId)

        try {
            val taskSummaries = ConcurrentHashMap<String, String>()

            for ((layerIndex, layer) in layers.withIndex()) {
                // Check abort signal before each layer
                if (abortSignal()) {
                    logger.info("Abort signal received before layer {} in run '{}'", layerIndex, run.id)
                    markRemainingTasksCancelled(run)
                    run.status = SwarmRunStatus.CANCELLED
                    run.error = "Run cancelled by abort signal"
                    withContext(NonCancellable) {
                        persistRunSafely(run)
                        eventBridge.onRunCancelled(run.id)
                    }
                    break
                }

                // Check pause request before each layer (pause = finish current layer, then stop)
                if (pauseRequested != null && pauseRequested.get() && layerIndex > 0) {
                    logger.info("Pause requested before layer {} in run '{}'", layerIndex, run.id)
                    run.status = SwarmRunStatus.PAUSED
                    withContext(NonCancellable) {
                        persistRunSafely(run)
                    }
                    eventBridge.onRunPaused(run.id)
                    break
                }

                logger.info("Layer {}: executing {} tasks in parallel: {}", layerIndex, layer.size, layer)
                eventBridge.onLayerStarted(run, layerIndex, layer)

                coroutineScope {
                    layer.map { taskId ->
                        async {
                            val task = run.tasks.find { it.id == taskId }!!
                            executeTask(task, run, taskSummaries, layerIndex, abortSignal, runContext)
                        }
                    }.awaitAll()
                }

                // Check for blocked tasks (upstream failures)
                val blockedCount = run.tasks.count { it.status == SwarmTaskStatus.BLOCKED }
                if (blockedCount > 0) {
                    logger.warn("Layer {}: {} tasks blocked due to upstream failures", layerIndex, blockedCount)
                }
            }

            resolveRunStatus(run)
        } catch (e: CancellationException) {
            run.status = SwarmRunStatus.CANCELLED
            run.error = "Run cancelled"
            logger.warn("Swarm run '{}' cancelled", run.id)
            withContext(NonCancellable) {
                eventBridge.onRunCancelled(run.id)
            }
            throw e
        } catch (e: Exception) {
            run.status = SwarmRunStatus.FAILED
            run.error = e.message
            logger.error("Swarm run '{}' failed: {}", run.id, e.message, e)
            eventBridge.onRunFailed(run.id, e.message ?: "Unknown error")
        } finally {
            finalizeRun(run)
        }

        return run
    }

    /**
     * Resume a paused swarm run from the point of interruption.
     *
     * Recovers task summaries from the DB, finds the first incomplete layer,
     * and continues execution from there.
     *
     * @param run The paused run to resume (must have status PAUSED)
     * @param abortSignal Signal that returns true when cancellation is requested
     * @param pauseRequested Flag that when set to true, pauses after the current layer completes
     * @return The completed (or failed) run
     */
    suspend fun resume(
        run: SwarmRun,
        abortSignal: () -> Boolean = { false },
        pauseRequested: AtomicBoolean? = null,
        modelConfigId: String? = null
    ): SwarmRun {
        logger.info("Resuming swarm run '{}' from status {}", run.id, run.status)

        run.status = SwarmRunStatus.RESUMING

        // Load persisted tasks to recover task summaries and states
        val persistedTasks = store?.getTasks(run.id) ?: emptyList()
        val taskSummaries = ConcurrentHashMap<String, String>()
        for (persisted in persistedTasks) {
            val inMemory = run.tasks.find { it.id == persisted.id }
            if (inMemory != null) {
                // Restore status and summary from persisted state
                inMemory.status = persisted.status
                inMemory.summary = persisted.summary
                inMemory.error = persisted.error
                inMemory.workerIterations = persisted.workerIterations
                inMemory.inputTokens = persisted.inputTokens
                inMemory.outputTokens = persisted.outputTokens
                inMemory.startedAt = persisted.startedAt
                inMemory.completedAt = persisted.completedAt
                if (persisted.status == SwarmTaskStatus.COMPLETED && persisted.summary != null) {
                    taskSummaries[persisted.id] = persisted.summary!!
                }
            }
        }

        // Compute layers and find the first incomplete layer
        val layers = DagAlgorithms.topologicalLayers(run.tasks)
        var resumeFromLayer = 0
        for ((layerIndex, layer) in layers.withIndex()) {
            val allDone = layer.all { taskId ->
                val task = run.tasks.find { it.id == taskId }
                task != null && (task.status == SwarmTaskStatus.COMPLETED ||
                    task.status == SwarmTaskStatus.FAILED ||
                    task.status == SwarmTaskStatus.BLOCKED)
            }
            if (!allDone) {
                resumeFromLayer = layerIndex
                break
            }
        }

        logger.info("Resuming run '{}' from layer {} of {}", run.id, resumeFromLayer, layers.size)
        run.status = SwarmRunStatus.RUNNING
        run.startedAt = Instant.now()

        val runContext = initializeRunContext(run, modelConfigId)

        try {
            for (layerIndex in resumeFromLayer until layers.size) {
                val layer = layers[layerIndex]

                // Check abort signal before each layer
                if (abortSignal()) {
                    logger.info("Abort signal received during resume before layer {} in run '{}'", layerIndex, run.id)
                    markRemainingTasksCancelled(run)
                    run.status = SwarmRunStatus.CANCELLED
                    run.error = "Run cancelled during resume"
                    withContext(NonCancellable) {
                        persistRunSafely(run)
                        eventBridge.onRunCancelled(run.id)
                    }
                    break
                }

                // Check pause request before each layer (pause = finish current layer, then stop)
                if (pauseRequested != null && pauseRequested.get() && layerIndex > resumeFromLayer) {
                    logger.info("Pause requested during resume before layer {} in run '{}'", layerIndex, run.id)
                    run.status = SwarmRunStatus.PAUSED
                    withContext(NonCancellable) {
                        persistRunSafely(run)
                    }
                    eventBridge.onRunPaused(run.id)
                    break
                }

                logger.info("Resume Layer {}: executing {} tasks: {}", layerIndex, layer.size, layer)
                eventBridge.onLayerStarted(run, layerIndex, layer)

                coroutineScope {
                    layer.map { taskId ->
                        async {
                            val task = run.tasks.find { it.id == taskId }!!
                            // Skip already-completed/failed/blocked tasks
                            if (task.status == SwarmTaskStatus.COMPLETED ||
                                task.status == SwarmTaskStatus.FAILED ||
                                task.status == SwarmTaskStatus.BLOCKED) {
                                return@async
                            }
                            executeTask(task, run, taskSummaries, layerIndex, abortSignal, runContext)
                        }
                    }.awaitAll()
                }
            }

            resolveRunStatus(run)
        } catch (e: CancellationException) {
            run.status = SwarmRunStatus.CANCELLED
            run.error = "Run cancelled during resume"
            logger.warn("Swarm run '{}' cancelled during resume", run.id)
            withContext(NonCancellable) {
                eventBridge.onRunCancelled(run.id)
            }
            throw e
        } catch (e: Exception) {
            run.status = SwarmRunStatus.FAILED
            run.error = e.message
            logger.error("Swarm run '{}' failed during resume: {}", run.id, e.message, e)
            eventBridge.onRunFailed(run.id, e.message ?: "Unknown error")
        } finally {
            finalizeRun(run)
        }

        return run
    }

    /**
     * Initialize run context: emit start event, load caches, and build [RunContext].
     * Shared between [execute] and [resume].
     */
    private suspend fun initializeRunContext(run: SwarmRun, modelConfigId: String?): RunContext {
        withContext(NonCancellable) {
            eventBridge.onRunStarted(run)
        }

        val agentDefCache = agentResolver.batchLoadAgentDefinitions(run.agents, run.userId)
        val modelConfigCache = agentResolver.loadModelConfigCache(run.userId)

        // Ensure userVars is a thread-safe ConcurrentHashMap for runtime variable updates
        if (run.userVars !is ConcurrentHashMap) {
            run.userVars = ConcurrentHashMap(run.userVars)
        }

        // Initialize variable guard for runtime-updatable variables
        val taskPermissions = run.tasks
            .filter { it.updatableVariables.isNotEmpty() }
            .associate { it.id to it.updatableVariables.toSet() }
        val updatableVarNames = run.variableDefinitions
            .filter { it.updatable }
            .map { it.name }
            .toSet()
        val variableGuard = if (taskPermissions.isNotEmpty() && updatableVarNames.isNotEmpty()) {
            UpdatableVariableGuard(taskPermissions, updatableVarNames)
        } else null

        return RunContext(modelConfigId, agentDefCache, modelConfigCache, variableGuard, run.language)
    }

    /**
     * Execute a single task (with retry and timeout).
     */
    private suspend fun executeTask(
        task: SwarmTask,
        run: SwarmRun,
        taskSummaries: MutableMap<String, String>,
        layerIndex: Int,
        abortSignal: () -> Boolean,
        runContext: RunContext
    ) {
        // Skip tasks blocked by upstream failures
        if (task.status == SwarmTaskStatus.BLOCKED) {
            logger.info("Skipping blocked task '{}' on layer {}", task.id, layerIndex)
            eventBridge.onTaskBlocked(run, task)
            return
        }

        // Skip already completed tasks (e.g., during resume)
        if (task.status == SwarmTaskStatus.COMPLETED) {
            return
        }

        semaphore.withPermit {
            task.status = SwarmTaskStatus.IN_PROGRESS
            task.startedAt = Instant.now()

            // Emit task_started event and persist IN_PROGRESS status to DB
            eventBridge.onTaskStarted(run, task)
            store?.saveTask(run.id, task)

            val maxRetries = task.maxRetries
            var lastError: String? = null
            var completed = false

            for (attempt in 0..maxRetries) {
                // Check abort signal before each retry
                if (abortSignal()) {
                    task.status = SwarmTaskStatus.CANCELLED
                    task.error = "Cancelled by abort signal"
                    task.completedAt = Instant.now()
                    withContext(NonCancellable) {
                        store?.saveTask(run.id, task)
                        eventBridge.onTaskCancelled(run.id, task)
                    }
                    return
                }

                try {
                    if (attempt > 0) {
                        val backoffMs = (1000L * (1L shl (attempt - 1))).coerceAtMost(30_000L)
                        logger.info("Retrying task '{}' (attempt {}/{}) after {}ms",
                            task.id, attempt + 1, maxRetries + 1, backoffMs)
                        delay(backoffMs.milliseconds)
                    }

                    val result = when (task.type) {
                        TaskType.SINGLE -> workerExecutor.runSingleWorker(task, run, taskSummaries, abortSignal, runContext)
                        TaskType.DELIBERATION -> deliberationExecutor.runDeliberation(task, run, taskSummaries, abortSignal, runContext)
                        TaskType.TEAM -> teamExecutor.runTeam(task, run, taskSummaries, abortSignal, runContext)
                    }

                    task.applyResult(result)
                    task.completedAt = Instant.now()

                    // Store deliberation history on the task for later persistence
                    if (task.type == TaskType.DELIBERATION && result.deliberationHistory.isNotEmpty()) {
                        task.deliberationHistory = result.deliberationHistory
                        task.verdictPrompt = result.verdictPrompt
                        task.verdictResponse = result.verdictResponse
                    }

                    // Store TEAM runtime records on the task for later persistence
                    if (task.type == TaskType.TEAM) {
                        if (result.escalationHistory.isNotEmpty()) {
                            task.escalationHistory = result.escalationHistory
                        }
                        if (result.memberExecutions.isNotEmpty()) {
                            task.memberExecutions = result.memberExecutions
                        }
                        if (result.roundRecords.isNotEmpty()) {
                            task.roundRecords = result.roundRecords
                        }
                    }

                    if (result.status == SwarmTaskStatus.COMPLETED) {
                        // Check task completion report: if agent self-reported FAILED, degrade and block downstream
                        val report = result.taskReport
                        if (report != null && report.status == TaskReportStatus.FAILED) {
                            task.status = SwarmTaskStatus.FAILED
                            task.error = "Task self-reported failure: ${report.reason}"
                            task.completedAt = Instant.now()
                            dagMutex.withLock {
                                DagAlgorithms.resolveDependencies(run.tasks, task.id, failed = true)
                            }
                            store?.saveTask(run.id, task)
                            eventBridge.onTaskFailed(run, task)
                            logger.warn("Task '{}' self-reported failure: {}", task.id, report.reason)
                            completed = true
                            break
                        }

                        taskSummaries[task.id] = result.summary
                        dagMutex.withLock {
                            DagAlgorithms.resolveDependencies(run.tasks, task.id, failed = false)
                        }
                        // Persist task result immediately
                        store?.saveTask(run.id, task)
                        // Persist userVars if runtime variable updates are enabled
                        if (runContext.variableGuard != null) {
                            store?.updateRun(run, run.userId)
                        }
                        eventBridge.onTaskCompleted(run, task)
                        logger.info("Task '{}' completed: {} chars, {} input/{} output tokens",
                            task.id, result.summary.length, result.inputTokens, result.outputTokens)
                        completed = true
                        break
                    }

                    lastError = result.error
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e.message
                    logger.warn("Task '{}' attempt {} failed: {}", task.id, attempt + 1, e.message)
                }
            }

            // All retries exhausted
            if (!completed) {
                task.status = SwarmTaskStatus.FAILED
                task.error = lastError
                task.completedAt = Instant.now()
                dagMutex.withLock {
                    DagAlgorithms.resolveDependencies(run.tasks, task.id, failed = true)
                }
                // Persist failed task
                store?.saveTask(run.id, task)
                eventBridge.onTaskFailed(run, task)
                logger.error("Task '{}' failed after {} attempts: {}", task.id, maxRetries + 1, lastError)
            }
        }
    }

    /**
     * Determine overall run status from task statuses (only if still RUNNING).
     */
    private fun resolveRunStatus(run: SwarmRun) {
        if (run.status != SwarmRunStatus.RUNNING) return
        run.status = when {
            run.tasks.all { it.status == SwarmTaskStatus.COMPLETED } -> SwarmRunStatus.COMPLETED
            run.tasks.any { it.status == SwarmTaskStatus.FAILED } -> SwarmRunStatus.FAILED
            run.tasks.any { it.status == SwarmTaskStatus.BLOCKED } -> SwarmRunStatus.FAILED
            else -> SwarmRunStatus.FAILED
        }
    }

    /**
     * Mark all non-terminal tasks as CANCELLED (used when abort signal fires).
     */
    private fun markRemainingTasksCancelled(run: SwarmRun) {
        for (task in run.tasks) {
            if (task.status != SwarmTaskStatus.COMPLETED &&
                task.status != SwarmTaskStatus.FAILED &&
                task.status != SwarmTaskStatus.BLOCKED) {
                task.status = SwarmTaskStatus.CANCELLED
                task.error = "Cancelled by abort signal"
                task.completedAt = Instant.now()
            }
        }
    }

    /**
     * Finalize a run: record completion time, aggregate tokens, persist, and emit terminal event.
     */
    private suspend fun finalizeRun(run: SwarmRun) {
        run.completedAt = Instant.now()
        run.totalInputTokens = run.tasks.sumOf { it.inputTokens }
        run.totalOutputTokens = run.tasks.sumOf { it.outputTokens }
        run.totalCacheReadTokens = run.tasks.sumOf { it.cacheReadTokens }
        run.totalCacheWriteTokens = run.tasks.sumOf { it.cacheWriteTokens }
        run.totalDurationMs = run.tasks.sumOf { it.durationMs }
        withContext(NonCancellable) {
            persistRunSafely(run)
            if (run.status == SwarmRunStatus.COMPLETED) {
                eventBridge.onRunCompleted(run)
            }
        }
    }

    /**
     * Persist run results safely, ignoring exceptions during NonCancellable blocks.
     */
    private suspend fun persistRunSafely(run: SwarmRun) {
        try {
            store?.persistRunResults(run, run.userId)
        } catch (e: Exception) {
            logger.warn("Failed to persist run '{}' results: {}", run.id, e.message)
        }
    }

    /**
     * Create an ephemeral runtime variant for dry-run execution.
     * Same executors and event bridge, but with all persistence disabled
     * (no run/task/session writes to DB).
     */
    fun forDryRun(): SwarmRuntime = SwarmRuntime(
        workerExecutor = workerExecutor.withoutSession(),
        agentResolver = agentResolver,
        eventBridge = eventBridge,
        maxConcurrency = maxConcurrency,
        store = null,
    )

    companion object {
        /** Maximum characters for worker summaries to prevent context overflow. */
        const val MAX_SUMMARY_LENGTH = 10_000
    }
}

/**
 * Run-level cached context: agent definitions, model configs, and variable guard loaded once per run.
 */
internal data class RunContext(
    val modelConfigId: String?,
    val agentDefCache: Map<String, AgentDefinition>,
    val modelConfigCache: List<ModelProviderConfig>?,
    val variableGuard: UpdatableVariableGuard? = null,
    /** Preferred language for all LLM responses. Empty = no language enforcement. */
    val language: String = "",
)

