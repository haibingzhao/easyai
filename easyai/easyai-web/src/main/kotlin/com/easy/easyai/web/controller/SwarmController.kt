package com.easy.easyai.web.controller

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.team.TeamMemberExecution
import com.easy.easyai.repository.session.AsyncSessionStore
import com.easy.easyai.swarm.event.SwarmEvent
import com.easy.easyai.swarm.event.SwarmEventBridge
import com.easy.easyai.swarm.model.*
import com.easy.easyai.swarm.preset.SwarmPresetStore
import com.easy.easyai.swarm.runtime.SwarmRuntime
import com.easy.easyai.swarm.store.SwarmRunStore
import com.easy.easyai.web.model.SessionDetail
import com.easy.easyai.web.model.SessionMessagesAfterResponse
import com.easy.easyai.web.security.getCurrentUserId
import com.easy.easyai.web.service.SessionService
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.reactor.asFlux
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * REST API for swarm orchestration.
 *
 * Endpoints:
 * - POST /api/swarm/runs — Launch a new swarm run
 * - GET /api/swarm/runs — List recent runs
 * - GET /api/swarm/runs/{id} — Get run details
 * - POST /api/swarm/runs/{id}/cancel — Cancel a running swarm
 * - POST /api/swarm/runs/{id}/pause — Pause after current layer
 * - POST /api/swarm/runs/{id}/resume — Resume a paused run
 * - GET /api/swarm/presets — List available presets
 * - GET /api/swarm/presets/{name} — Get preset details
 * - POST /api/swarm/presets — Create a preset
 * - PUT /api/swarm/presets/{name} — Update a preset
 * - DELETE /api/swarm/presets/{name} — Delete a preset
 * - DELETE /api/swarm/runs/{id} — Delete a run and all associated data
 * - GET /api/swarm/runs/{runId}/tasks/{taskId}/session — Get task session detail
 */
@RestController
@RequestMapping("/api/swarm")
@ConditionalOnProperty(prefix = "easyai.swarm", name = ["enabled"], havingValue = "true")
class SwarmController(
    private val runtime: SwarmRuntime,
    private val eventBridge: SwarmEventBridge,
    private val serverStartupTime: Instant,
    @param:Autowired(required = false) private val presetStore: SwarmPresetStore? = null,
    @param:Autowired(required = false) private val store: SwarmRunStore? = null,
    @param:Autowired(required = false) private val sessionService: SessionService? = null,
    @param:Autowired(required = false) private val sessionStore: AsyncSessionStore? = null,
    @param:Autowired(required = false) private val agentStore: com.easy.easyai.core.agent.AsyncAgentStore? = null
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val supervisorJob = SupervisorJob()
    private val backgroundScope = CoroutineScope(Dispatchers.Default + supervisorJob +
        CoroutineExceptionHandler { _, e -> logger.error("Swarm background task failed", e) })
    private val activeJobs = ConcurrentHashMap<String, ActiveRunState>()

    @PreDestroy
    fun shutdown() {
        supervisorJob.cancel()
    }

    /**
     * Tracks the state of an active swarm run for cancel/pause/resume control.
     */
    data class ActiveRunState(
        val job: Job,
        val abortSignal: AtomicBoolean,
        val pauseRequested: AtomicBoolean,
        val runtime: SwarmRuntime,
        val run: SwarmRun,
        val userId: String,
        val modelConfigId: String? = null,
        /** Dry runs are ephemeral: never persisted to DB, kept in memory only for status queries. */
        val dryRun: Boolean = false
    )

    data class LaunchRequest(
        val presetName: String,
        val variables: Map<String, String> = emptyMap(),
        val modelConfigId: String? = null,
        /** Optional language override for this run. Empty/null = use preset default. */
        val language: String? = null,
        /** When true, the run executes without any DB persistence and never appears in run history. */
        val dryRun: Boolean = false
    )

    data class ResumeRequest(
        val modelConfigId: String? = null
    )

    data class PresetInfo(
        val name: String,
        val title: String,
        val description: String,
        val agents: List<AgentBrief> = emptyList(),
        val tasks: List<TaskBrief> = emptyList(),
        val variables: List<VariableBrief> = emptyList(),
        val language: String = ""
    )

    data class AgentBrief(
        val id: String,
        val role: String,
        val description: String?
    )

    data class TaskBrief(
        val id: String,
        val agentId: String,
        val type: String,
        val dependsOn: List<String>,
        val promptTemplate: String = "",
        val deliberation: DeliberationBrief? = null,
        val team: TeamBrief? = null
    )

    data class DeliberationBrief(
        val participants: List<String>,
        val judge: String
    )

    data class TeamBrief(
        val leader: String,
        val members: List<String>
    )

    data class VariableBrief(
        val name: String,
        val description: String,
        val required: Boolean = false,
        val defaultValue: String? = null,
        val updatable: Boolean = false
    )

    data class RunSummary(
        val id: String,
        val presetName: String,
        val title: String,
        val status: String,
        val totalInputTokens: Long,
        val totalOutputTokens: Long,
        val totalCacheReadTokens: Long,
        val totalCacheWriteTokens: Long,
        val totalDurationMs: Long,
        val error: String?,
        val createdAt: Long,
        val language: String = ""
    )

    data class PresetDetail(
        val name: String,
        val title: String,
        val description: String,
        val agents: List<SwarmAgentSpec>,
        val tasks: List<TaskEditDto>,
        val variables: List<SwarmVariable>,
        val language: String = ""
    )

    /** Schema-only DTO for editing tasks — excludes mutable runtime state from SwarmTask. */
    data class TaskEditDto(
        val id: String,
        val agentId: String,
        val promptTemplate: String,
        val dependsOn: List<String>,
        val inputFrom: Map<String, String>,
        val type: TaskType,
        val deliberation: DeliberationSpec?,
        val team: TeamSpec?,
        val maxRetries: Int,
        val updatableVariables: List<String> = emptyList(),
        val agentPromptEnabled: Boolean = true,
        val systemPromptTemplate: String = "",
        val reportEnabled: Boolean = false,
    )

    private fun SwarmTask.toEditDto() = TaskEditDto(
        id = id,
        agentId = agentId,
        promptTemplate = promptTemplate,
        dependsOn = dependsOn,
        inputFrom = inputFrom,
        type = type,
        deliberation = deliberation,
        team = team,
        maxRetries = maxRetries,
        updatableVariables = updatableVariables,
        agentPromptEnabled = agentPromptEnabled,
        systemPromptTemplate = systemPromptTemplate,
        reportEnabled = reportEnabled,
    )

    data class PresetRequest(
        val name: String,
        val title: String,
        val description: String = "",
        val agents: List<SwarmAgentSpec>,
        val tasks: List<SwarmTask>,
        val variables: List<SwarmVariable> = emptyList(),
        val language: String = ""
    )

    /**
     * Launch a new swarm run. Returns immediately with the run ID;
     * execution proceeds asynchronously.
     *
     * When [LaunchRequest.dryRun] is true, the run executes with all persistence
     * disabled (no run/task/session writes) and never appears in run history.
     */
    @PostMapping("/runs")
    fun launchRun(@RequestBody request: LaunchRequest): Mono<Map<String, String>> {
        return mono {
            val userId = getCurrentUserId()
            val presetStore = presetStore
                ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Preset store not available")
            val run = presetStore.buildRun(request.presetName, userId, request.variables)
            // Apply language override from request if provided
            val effectiveRun = if (!request.language.isNullOrBlank()) {
                run.copy(language = request.language)
            } else run

            // Dry run: use ephemeral runtime with no store/session persistence
            val effectiveRuntime = if (request.dryRun) {
                cleanupCompletedDryRuns()
                logger.info("Launching dry run '{}' for preset '{}' — no DB persistence", effectiveRun.id, request.presetName)
                runtime.forDryRun()
            } else {
                store?.saveRun(effectiveRun, userId)
                runtime
            }

            val abortFlag = AtomicBoolean(false)
            val pauseFlag = AtomicBoolean(false)

            // Launch in background
            val job = backgroundScope.launch {
                try {
                    effectiveRuntime.execute(
                        effectiveRun,
                        abortSignal = { abortFlag.get() },
                        pauseRequested = pauseFlag,
                        modelConfigId = request.modelConfigId
                    )
                } catch (_: CancellationException) {
                    // Execution was cancelled — SwarmRuntime finally block handles persistence
                } finally {
                    // Dry runs stay in activeJobs so their final state remains queryable
                    if (!request.dryRun) {
                        activeJobs.remove(effectiveRun.id)
                    }
                }
            }
            activeJobs[effectiveRun.id] = ActiveRunState(
                job = job,
                abortSignal = abortFlag,
                pauseRequested = pauseFlag,
                runtime = effectiveRuntime,
                run = effectiveRun,
                userId = userId,
                modelConfigId = request.modelConfigId,
                dryRun = request.dryRun
            )

            mapOf("runId" to effectiveRun.id, "status" to effectiveRun.status.name)
        }
    }

    /** Remove completed dry-run entries from activeJobs to bound memory usage. */
    private fun cleanupCompletedDryRuns() {
        activeJobs.entries.removeIf { (_, state) ->
            state.dryRun && state.job.isCompleted
        }
    }

    /**
     * List recent swarm runs.
     */
    @GetMapping("/runs")
    fun listRuns(
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int
    ): Mono<List<RunSummary>> {
        return mono {
            val userId = getCurrentUserId()
            val safeLimit = limit.coerceIn(1, 100)
            val safeOffset = offset.coerceAtLeast(0)
            val runs = store?.listRuns(safeLimit, safeOffset, userId) ?: emptyList()
            runs.map { resolveEffectiveStatus(it).toSummary() }
        }
    }

    /**
     * Get detailed info about a specific run including all task results.
     * Dry runs (not persisted) are served from in-memory state while active.
     */
    @GetMapping("/runs/{id}")
    fun getRun(@PathVariable id: String): Mono<RunDetailResponse> {
        return mono {
            val userId = getCurrentUserId()
            val run = store?.getRun(id, userId)
            if (run != null) {
                val effectiveRun = resolveEffectiveStatus(run)
                val isStale = isStaleRunning(run)
                val tasks = store.getTasks(id)
                val effectiveTasks = resolveStaleTaskStatuses(isStale, tasks)
                RunDetailResponse(
                    id = effectiveRun.id,
                    presetName = effectiveRun.presetName,
                    title = effectiveRun.title,
                    status = effectiveRun.status.name,
                    totalInputTokens = effectiveRun.totalInputTokens,
                    totalOutputTokens = effectiveRun.totalOutputTokens,
                    totalCacheReadTokens = effectiveRun.totalCacheReadTokens,
                    totalCacheWriteTokens = effectiveRun.totalCacheWriteTokens,
                    totalDurationMs = effectiveRun.totalDurationMs,
                    error = effectiveRun.error,
                    tasks = effectiveTasks.map { it.toSummary() },
                    language = effectiveRun.language
                )
            } else {
                // Dry runs are ephemeral — serve from in-memory state
                val state = activeJobs[id]
                if (state != null && state.dryRun && state.userId == userId) {
                    val memRun = state.run
                    RunDetailResponse(
                        id = memRun.id,
                        presetName = memRun.presetName,
                        title = memRun.title,
                        status = memRun.status.name,
                        totalInputTokens = memRun.tasks.sumOf { it.inputTokens },
                        totalOutputTokens = memRun.tasks.sumOf { it.outputTokens },
                        totalCacheReadTokens = memRun.tasks.sumOf { it.cacheReadTokens },
                        totalCacheWriteTokens = memRun.tasks.sumOf { it.cacheWriteTokens },
                        totalDurationMs = memRun.tasks.sumOf { it.durationMs },
                        error = memRun.error,
                        tasks = memRun.tasks.map { it.toSummary() },
                        language = memRun.language,
                        dryRun = true
                    )
                } else {
                    throw ResponseStatusException(HttpStatus.NOT_FOUND, "Swarm run not found: $id")
                }
            }
        }
    }

    /**
     * Cancel a running swarm.
     * For RUNNING/PENDING/RESUMING: sets the abort signal flag first so workers
     * can gracefully stop, then cancels the coroutine job.
     * For PAUSED: directly updates DB status to CANCELLED and marks remaining
     * non-terminal tasks as CANCELLED (no active coroutine to cancel).
     */
    @PostMapping("/runs/{id}/cancel")
    fun cancelRun(@PathVariable id: String): Mono<Map<String, String>> {
        return mono {
            val userId = getCurrentUserId()
            val run = store?.getRun(id, userId)

            if (run == null) {
                // Dry runs are not in the store — cancel from in-memory state
                val state = activeJobs[id]
                if (state != null && state.dryRun && state.userId == userId) {
                    state.abortSignal.set(true)
                    state.job.cancel()
                    activeJobs.remove(id)
                    eventBridge.onRunCancelled(id)
                    logger.info("Cancelled dry run '{}'", id)
                    mapOf("status" to "CANCELLED")
                } else {
                    mapOf("error" to "Run not found")
                }
            } else if (run.status != SwarmRunStatus.RUNNING &&
                run.status != SwarmRunStatus.PENDING &&
                run.status != SwarmRunStatus.RESUMING &&
                run.status != SwarmRunStatus.PAUSED) {
                mapOf("error" to "Run is not in a cancellable state (current: ${run.status})")
            } else {
                val state = activeJobs[id]
                if (state != null) {
                    // Active run: set abort signal first for graceful shutdown
                    state.abortSignal.set(true)
                    // Then cancel the coroutine (belt-and-suspenders)
                    state.job.cancel()
                    activeJobs.remove(id)
                } else if (run.status == SwarmRunStatus.PAUSED) {
                    // Paused run: no active coroutine — update DB directly
                    val tasks = store.getTasks(id) ?: emptyList()
                    val now = Instant.now()
                    for (task in tasks) {
                        if (task.status != SwarmTaskStatus.COMPLETED &&
                            task.status != SwarmTaskStatus.FAILED &&
                            task.status != SwarmTaskStatus.BLOCKED) {
                            task.status = SwarmTaskStatus.CANCELLED
                            task.error = "Cancelled by user (was PAUSED)"
                            task.completedAt = now
                        }
                    }
                    run.status = SwarmRunStatus.CANCELLED
                    run.error = "Cancelled by user"
                    run.completedAt = now
                    store.updateRun(run, userId)
                    store.saveTasks(id, tasks)
                    eventBridge.onRunCancelled(id)
                    logger.info("Cancelled paused swarm run '{}'", id)
                }
                mapOf("status" to "CANCELLED")
            }
        }
    }

    /**
     * Pause a running swarm after the current layer completes.
     * Does not interrupt in-progress workers — they finish naturally.
     */
    @PostMapping("/runs/{id}/pause")
    fun pauseRun(@PathVariable id: String): Mono<Map<String, String>> {
        return mono {
            val userId = getCurrentUserId()
            val run = store?.getRun(id, userId)
            if (run == null) {
                mapOf("error" to "Run not found")
            } else if (run.status != SwarmRunStatus.RUNNING) {
                mapOf("error" to "Run is not in RUNNING state (current: ${run.status})")
            } else {
                val state = activeJobs[id]
                if (state != null) {
                    state.pauseRequested.set(true)
                    logger.info("Pause requested for swarm run '{}'", id)
                    mapOf("status" to "PAUSING")
                } else {
                    mapOf("error" to "Run is not actively executing")
                }
            }
        }
    }

    /**
     * Resume a paused swarm run from the point of interruption.
     */
    @PostMapping("/runs/{id}/resume")
    fun resumeRun(
        @PathVariable id: String,
        @RequestBody(required = false) body: ResumeRequest?
    ): Mono<Map<String, String>> {
        return mono {
            val userId = getCurrentUserId()
            val run = store?.getRun(id, userId)
            if (run == null) {
                mapOf("error" to "Run not found")
            } else if (isStaleRunning(run)) {
                // Lazy recovery: persist PAUSED state before resuming
                logger.info("Lazy-recovering stale RUNNING swarm run '{}' before resume", id)
                run.status = SwarmRunStatus.PAUSED
                run.error = "Service restarted — run paused for manual resume"
                store.updateRun(run, userId)
                // Now proceed with normal resume
                doResume(run, id, userId, body)
            } else if (run.status != SwarmRunStatus.PAUSED) {
                mapOf("error" to "Run is not in PAUSED state (current: ${run.status})")
            } else {
                doResume(run, id, userId, body)
            }
        }
    }

    private suspend fun doResume(
        run: SwarmRun,
        id: String,
        userId: String,
        body: ResumeRequest?
    ): Map<String, String> {
        // Load tasks from DB (runtime state: status, summary, tokens, etc.)
        val dbTasks = store!!.getTasks(id)
        // Merge DB runtime state with original task schema (updatableVariables, promptTemplate, etc.)
        // DB-loaded tasks lose schema fields because swarm_task table only stores runtime columns.
        val mergedTasks = dbTasks.map { dbTask ->
            val original = run.tasks.find { it.id == dbTask.id }
            if (original != null) {
                dbTask.copy(
                    agentId = original.agentId,
                    updatableVariables = original.updatableVariables,
                    promptTemplate = original.promptTemplate,
                    dependsOn = original.dependsOn,
                    inputFrom = original.inputFrom,
                    deliberation = original.deliberation,
                    team = original.team,
                    maxRetries = original.maxRetries,
                )
            } else {
                dbTask
            }
        }

        // Reload variableDefinitions from preset (it's @JsonIgnore, not persisted in DB)
        val preset = presetStore?.findByName(run.presetName, userId)
        val runWithTasks = run.copy(
            tasks = mergedTasks,
            variableDefinitions = preset?.variables ?: emptyList()
        )

        val abortFlag = AtomicBoolean(false)
        val pauseFlag = AtomicBoolean(false)
        val modelConfigId = body?.modelConfigId

        val job = backgroundScope.launch {
            try {
                runtime.resume(
                    runWithTasks,
                    abortSignal = { abortFlag.get() },
                    pauseRequested = pauseFlag,
                    modelConfigId = modelConfigId
                )
            } catch (_: CancellationException) {
                // SwarmRuntime finally block handles persistence
            } finally {
                activeJobs.remove(id)
            }
        }
        activeJobs[id] = ActiveRunState(
            job = job,
            abortSignal = abortFlag,
            pauseRequested = pauseFlag,
            runtime = runtime,
            run = runWithTasks,
            userId = userId,
            modelConfigId = modelConfigId
        )

        return mapOf("status" to "RESUMING", "runId" to id)
    }

    /**
     * Delete a swarm run and all associated data (tasks, deliberation history,
     * escalation history, team records, sessions, and messages).
     * If the run is currently active, it will be cancelled first.
     */
    @DeleteMapping("/runs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteRun(@PathVariable id: String): Mono<Void> {
        return mono {
            val userId = getCurrentUserId()
            val store = store
                ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Run store not available")
            store.getRun(id, userId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Swarm run not found: $id")

            // Cancel active run if still executing
            val state = activeJobs[id]
            if (state != null) {
                state.abortSignal.set(true)
                state.job.cancel()
                activeJobs.remove(id)
                logger.info("Cancelled active swarm run '{}' before deletion", id)
            }

            store.deleteRun(id, userId)
            null
        }
    }

    /**
     * List all available swarm presets with full DAG info.
     */
    @GetMapping("/presets")
    fun listPresets(): Mono<List<PresetInfo>> {
        return mono {
            val userId = getCurrentUserId()
            val presets = presetStore?.findAll(userId) ?: emptyList()
            presets.map { preset -> preset.toInfo() }
        }
    }

    /**
     * Get detailed info about a specific preset.
     */
    @GetMapping("/presets/{name}")
    fun getPreset(@PathVariable name: String): Mono<PresetInfo> {
        return mono {
            val userId = getCurrentUserId()
            val preset = presetStore?.findByName(name, userId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Swarm preset not found: $name")
            preset.toInfo()
        }
    }

    /**
     * Get full preset detail including complete agent specs, task definitions, and variables.
     * Used by the preset editor for loading existing presets for editing.
     */
    @GetMapping("/presets/{name}/detail")
    fun getPresetDetail(@PathVariable name: String): Mono<PresetDetail> {
        return mono {
            val userId = getCurrentUserId()
            val preset = presetStore?.findByName(name, userId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Swarm preset not found: $name")
            PresetDetail(
                name = preset.name,
                title = preset.title,
                description = preset.description,
                agents = preset.agents,
                tasks = preset.tasks.map { it.toEditDto() },
                variables = preset.variables,
                language = preset.language
            )
        }
    }

    /**
     * Create a new swarm preset.
     */
    @PostMapping("/presets")
    @ResponseStatus(HttpStatus.CREATED)
    fun createPreset(@RequestBody request: PresetRequest): Mono<PresetInfo> {
        return mono {
            val userId = getCurrentUserId()
            val presetStore = presetStore
                ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Preset store not available")
            val preset = SwarmPreset(
                name = request.name,
                title = request.title,
                description = request.description,
                agents = request.agents,
                tasks = request.tasks,
                variables = request.variables,
                language = request.language
            )
            presetStore.save(preset, userId)
            preset.toInfo()
        }
    }

    /**
     * Update an existing swarm preset.
     */
    @PutMapping("/presets/{name}")
    fun updatePreset(
        @PathVariable name: String,
        @RequestBody request: PresetRequest
    ): Mono<PresetInfo> {
        return mono {
            val userId = getCurrentUserId()
            val presetStore = presetStore
                ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Preset store not available")
            val existing = presetStore.findByName(name, userId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Swarm preset not found: $name")
            val updated = existing.copy(
                title = request.title,
                description = request.description,
                agents = request.agents,
                tasks = request.tasks,
                variables = request.variables,
                language = request.language
            )
            presetStore.update(updated, userId)
            updated.toInfo()
        }
    }

    /**
     * Delete a swarm preset.
     */
    @DeleteMapping("/presets/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deletePreset(@PathVariable name: String): Mono<Void> {
        return mono {
            val userId = getCurrentUserId()
            val presetStore = presetStore
                ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Preset store not available")
            presetStore.delete(name, userId)
            null
        }
    }

    /**
     * Export a swarm preset as a self-contained JSON file.
     * Global agent references are expanded to inline format so the exported preset
     * is fully portable and does not depend on the target environment's global agents.
     */
    @GetMapping("/presets/{name}/export")
    fun exportPreset(@PathVariable name: String): Mono<ResponseEntity<String>> {
        return mono {
            val userId = getCurrentUserId()
            val preset = presetStore?.findByName(name, userId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Swarm preset not found: $name")

            // Expand global agent references to inline format
            val expandedAgents = preset.agents.map { spec ->
                if (spec.isInline) {
                    spec
                } else {
                    val agentDef = agentStore?.findById(spec.agentDefinitionId, userId)
                    if (agentDef != null) {
                        // Query MCP configs from DB and convert to SwarmMcpBinding
                        val mcpBindings = agentStore.getAgentMcpConfigs(spec.agentDefinitionId)
                            .map { config -> config.toSwarmMcpBinding() }
                        spec.copy(
                            agentDefinitionId = "",
                            name = agentDef.name,
                            description = agentDef.description ?: "",
                            systemPrompt = agentDef.promptTemplate ?: "",
                            toolNames = agentDef.toolNames,
                            mcpConfigs = mcpBindings,
                        )
                    } else {
                        logger.warn("AgentDefinition '{}' not found during export, keeping reference", spec.agentDefinitionId)
                        spec
                    }
                }
            }

            val exportDto = mapOf(
                "formatVersion" to 1,
                "name" to preset.name,
                "title" to preset.title,
                "description" to preset.description,
                "agents" to expandedAgents,
                "tasks" to preset.tasks.map { it.toEditDto() },
                "variables" to preset.variables,
                "language" to preset.language,
            )
            val json = SharedObjectMapper.instance.writeValueAsString(exportDto)
            val safeName = preset.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"${safeName}.swarm.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
        }
    }

    /**
     * Import a swarm preset from JSON. All agents in the imported preset are expected
     * to be in inline format (as produced by the export endpoint).
     * Returns 409 Conflict if a preset with the same name already exists.
     */
    @PostMapping("/presets/import")
    @ResponseStatus(HttpStatus.CREATED)
    fun importPreset(@RequestBody request: PresetRequest): Mono<PresetInfo> {
        return mono {
            val userId = getCurrentUserId()
            val presetStore = presetStore
                ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Preset store not available")

            // Validate: all imported agents must be inline (self-contained)
            val nonInlineAgents = request.agents.filter { !it.isInline }
            if (nonInlineAgents.isNotEmpty()) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Imported agents must be inline (custom). Non-inline agents: ${nonInlineAgents.joinToString { it.id }}"
                )
            }

            val existing = presetStore.findByName(request.name, userId)
            if (existing != null) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Preset '${request.name}' already exists")
            }
            val preset = SwarmPreset(
                name = request.name,
                title = request.title,
                description = request.description,
                agents = request.agents,
                tasks = request.tasks,
                variables = request.variables,
                language = request.language,
            )
            try {
                presetStore.save(preset, userId)
            } catch (e: Exception) {
                if (e.message?.contains("Unique index", ignoreCase = true) == true ||
                    e.message?.contains("duplicate", ignoreCase = true) == true) {
                    throw ResponseStatusException(HttpStatus.CONFLICT, "Preset '${request.name}' already exists")
                }
                throw e
            }
            preset.toInfo()
        }
    }

    /**
     * Get the list of prompt variables available in Swarm context.
     * Static endpoint (no DB query) — returns variables that are actually populated
     * during Swarm task prompt rendering.
     */
    @GetMapping("/prompt-variables")
    fun getPromptVariables(): Mono<List<PromptVariableGroup>> {
        return mono {
            listOf(
                PromptVariableGroup(
                    label = "Agent Context",
                    vars = listOf(
                        PromptVariableInfo("agent", "Agent info (access: agent.id, agent.name, agent.description)"),
                        PromptVariableInfo("custom_instructions", "Agent custom instructions text"),
                        PromptVariableInfo("model_id", "Active model identifier"),
                        PromptVariableInfo("os", "Operating system name"),
                    )
                ),
            )
        }
    }

    data class PromptVariableGroup(val label: String, val vars: List<PromptVariableInfo>)
    data class PromptVariableInfo(val name: String, val description: String)

    /**
     * Get session detail for a swarm task (messages from the worker's agent run).
     */
    @GetMapping("/runs/{runId}/tasks/{taskId}/session")
    fun getTaskSession(
        @PathVariable runId: String,
        @PathVariable taskId: String
    ): Mono<SessionDetail> {
        return mono {
            val userId = getCurrentUserId()
            // Verify the current user owns this run before accessing session data
            store?.getRun(runId, userId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Swarm run not found: $runId")
            val sessionId = sessionStore?.findSessionIdBySwarmTask(runId, taskId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found for task: $taskId")
            sessionService?.getSessionDetail(sessionId, userId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Session detail not available")
        }
    }

    /**
     * Incremental message fetch for a swarm task session.
     * Returns only messages created after [after] timestamp, plus compaction metadata.
     */
    @GetMapping("/runs/{runId}/tasks/{taskId}/session/messages")
    fun getTaskSessionMessagesAfter(
        @PathVariable runId: String,
        @PathVariable taskId: String,
        @RequestParam after: Long
    ): Mono<SessionMessagesAfterResponse> {
        return mono {
            val userId = getCurrentUserId()
            store?.getRun(runId, userId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Swarm run not found: $runId")
            val sessionId = sessionStore?.findSessionIdBySwarmTask(runId, taskId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found for task: $taskId")
            sessionService?.getSessionMessagesAfter(sessionId, after, userId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Session messages not available")
        }
    }

    /**
     * Get team history for a TEAM-type task: escalation entries, member executions, and round records.
     */
    @GetMapping("/runs/{runId}/tasks/{taskId}/team-history")
    suspend fun getTeamHistory(
        @PathVariable runId: String,
        @PathVariable taskId: String,
    ): ResponseEntity<TeamHistoryResponse> {
        val store = this.store ?: return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
        val userId = getCurrentUserId()
        store.getRun(runId, userId)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        val escalationHistory = store.getEscalationHistory(runId, taskId)
        val (memberExecutions, roundRecords) = store.getTeamHistory(runId, taskId)
        return ResponseEntity.ok(
            TeamHistoryResponse(
                escalationHistory = escalationHistory,
                memberExecutions = memberExecutions,
                roundRecords = roundRecords
            )
        )
    }

    /**
     * Get deliberation history for a DELIBERATION-type task: participant responses per round,
     * plus the Judge's verdict prompt and response.
     */
    @GetMapping("/runs/{runId}/tasks/{taskId}/deliberation-history")
    suspend fun getDeliberationHistory(
        @PathVariable runId: String,
        @PathVariable taskId: String,
    ): ResponseEntity<DeliberationHistoryResponse> {
        val store = this.store ?: return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
        val userId = getCurrentUserId()
        store.getRun(runId, userId)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        val history = store.getDeliberationHistory(runId, taskId)
        val verdict = store.getDeliberationVerdict(runId, taskId)
        return ResponseEntity.ok(
            DeliberationHistoryResponse(
                entries = history,
                verdictPrompt = verdict?.first,
                verdictResponse = verdict?.second,
            )
        )
    }

    // --- Team Consultation Endpoints ---

    data class ConsultationAnswerRequest(
        val memberId: String,
        val answer: String,
    )

    data class ConsultationRejectRequest(
        val memberId: String,
    )

    /**
     * Answer a pending team consultation.
     * Completes the CompletableDeferred in TeamTaskExecutor, triggering member resume.
     */
    @PostMapping("/runs/{runId}/tasks/{taskId}/consultation/answer")
    suspend fun answerConsultation(
        @PathVariable runId: String,
        @PathVariable taskId: String,
        @RequestBody request: ConsultationAnswerRequest,
    ): ResponseEntity<Map<String, Any>> {
        val userId = getCurrentUserId()
        // Verify run ownership
        store?.getRun(runId, userId)
            ?: activeJobs[runId]?.takeIf { it.userId == userId }?.run
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(mapOf("error" to "Swarm run not found: $runId"))

        val answered = runtime.consultationRegistry.answer(runId, taskId, request.memberId, request.answer)
        return if (answered) {
            ResponseEntity.ok(mapOf("status" to "answered", "memberId" to request.memberId))
        } else {
            ResponseEntity.status(HttpStatus.GONE)
                .body(mapOf("error" to "Consultation not found or already completed", "memberId" to request.memberId))
        }
    }

    /**
     * Reject/skip a pending team consultation.
     * The member will be marked as ESCALATED and Leader will re-decide next round.
     */
    @PostMapping("/runs/{runId}/tasks/{taskId}/consultation/reject")
    suspend fun rejectConsultation(
        @PathVariable runId: String,
        @PathVariable taskId: String,
        @RequestBody request: ConsultationRejectRequest,
    ): ResponseEntity<Map<String, Any>> {
        val userId = getCurrentUserId()
        // Verify run ownership
        store?.getRun(runId, userId)
            ?: activeJobs[runId]?.takeIf { it.userId == userId }?.run
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(mapOf("error" to "Swarm run not found: $runId"))

        val rejected = runtime.consultationRegistry.reject(runId, taskId, request.memberId)
        return if (rejected) {
            ResponseEntity.ok(mapOf("status" to "rejected", "memberId" to request.memberId))
        } else {
            ResponseEntity.status(HttpStatus.GONE)
                .body(mapOf("error" to "Consultation not found or already completed", "memberId" to request.memberId))
        }
    }

    /**
     * SSE event stream for a specific swarm run.
     *
     * Emits a synthetic `swarm_run_snapshot` event first so late subscribers
     * (who missed `run_started` due to SharedFlow replay=0) receive immediate
     * context about the current run state. Then merges live progress and
     * terminal events for the run.
     * Stream completes automatically after a terminal event is emitted.
     */
    @GetMapping("/runs/{id}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamRunEvents(
        @PathVariable id: String,
        @RequestParam(required = false) token: String?
    ): Flux<ServerSentEvent<String>> {
        return mono {
            val userId = getCurrentUserId()
            // Verify run exists and belongs to the current user (store or in-memory dry run)
            store?.getRun(id, userId)
                ?: activeJobs[id]?.takeIf { it.dryRun && it.userId == userId }?.run
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Swarm run not found: $id")
        }.flatMapMany { swarmRun ->
            val objectMapper = SharedObjectMapper.instance

            // Emit a synthetic snapshot event first so late subscribers
            // (who missed run_started due to replay=0) get immediate context.
            val effectiveRun = resolveEffectiveStatus(swarmRun)
            val snapshotEvent = SwarmEvent(
                type = "swarm_run_snapshot",
                runId = effectiveRun.id,
                data = mapOf(
                    "presetName" to effectiveRun.presetName,
                    "title" to effectiveRun.title,
                    "status" to effectiveRun.status.name,
                    "taskCount" to effectiveRun.tasks.size,
                    "agentCount" to effectiveRun.agents.size,
                    "totalInputTokens" to effectiveRun.totalInputTokens,
                    "totalOutputTokens" to effectiveRun.totalOutputTokens,
                )
            )
            val snapshotFlux = Flux.just(
                ServerSentEvent.builder<String>()
                    .data(objectMapper.writeValueAsString(snapshotEvent))
                    .build()
            )

            // eventsForRun already merges main + terminal events filtered by runId.
            // transformWhile emits each event and stops after the terminal event is sent.
            val liveFlux = eventBridge.eventsForRun(id)
                .transformWhile { event ->
                    emit(event)
                    // Return false on terminal events to stop the flow after emitting them
                    event.type != "swarm_run_completed" &&
                    event.type != "swarm_run_failed" &&
                    event.type != "swarm_run_paused" &&
                    event.type != "swarm_run_cancelled"
                }
                .map { event ->
                    val json = objectMapper.writeValueAsString(event)
                    ServerSentEvent.builder<String>()
                        .data(json)
                        .build()
                }
                .asFlux()
                .timeout(Duration.ofMinutes(30))

            snapshotFlux.concatWith(liveFlux)
        }
    }

    // --- Lazy recovery helpers ---

    /**
     * Returns true if the run is in RUNNING state but was started before the server
     * restarted — meaning no executing coroutine exists for it.
     */
    private fun isStaleRunning(run: SwarmRun): Boolean {
        return (run.status == SwarmRunStatus.RUNNING || run.status == SwarmRunStatus.RESUMING) &&
            (run.startedAt == null || run.startedAt!! < serverStartupTime)
    }

    /**
     * Resolves the effective status of a run for API responses.
     * Stale RUNNING runs (started before server restart) are reported as PAUSED
     * without modifying the database.
     */
    private fun resolveEffectiveStatus(run: SwarmRun): SwarmRun {
        if (isStaleRunning(run)) {
            return run.copy(
                status = SwarmRunStatus.PAUSED,
                error = "Service restarted — run paused for manual resume"
            )
        }
        return run
    }

    /**
     * For stale-running runs, fix task statuses in API responses:
     * IN_PROGRESS tasks are reported as PENDING because they were interrupted
     * by a service restart and will be re-executed on resume.
     * Does not modify the database — only affects the response payload.
     */
    private fun resolveStaleTaskStatuses(isStale: Boolean, tasks: List<SwarmTask>): List<SwarmTask> {
        if (!isStale) return tasks
        return tasks.map { task ->
            if (task.status == SwarmTaskStatus.IN_PROGRESS) {
                task.copy(status = SwarmTaskStatus.PENDING, error = "Interrupted by service restart")
            } else task
        }
    }

    // --- Response DTOs ---

    data class RunDetailResponse(
        val id: String,
        val presetName: String,
        val title: String,
        val status: String,
        val totalInputTokens: Long,
        val totalOutputTokens: Long,
        val totalCacheReadTokens: Long,
        val totalCacheWriteTokens: Long,
        val totalDurationMs: Long,
        val error: String?,
        val tasks: List<TaskSummary>,
        val language: String = "",
        val dryRun: Boolean = false
    )

    data class TaskSummary(
        val id: String,
        val agentId: String,
        val type: String,
        val status: String,
        val summary: String?,
        val error: String?,
        val workerIterations: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        val cacheReadTokens: Long,
        val cacheWriteTokens: Long,
        val durationMs: Long,
    )

    data class TeamHistoryResponse(
        val escalationHistory: List<EscalationEntry>,
        val memberExecutions: List<TeamMemberExecution>,
        val roundRecords: List<TeamRoundRecord>,
    )

    private fun SwarmRun.toSummary() = RunSummary(
        id = id,
        presetName = presetName,
        title = title,
        status = status.name,
        totalInputTokens = totalInputTokens,
        totalOutputTokens = totalOutputTokens,
        totalCacheReadTokens = totalCacheReadTokens,
        totalCacheWriteTokens = totalCacheWriteTokens,
        totalDurationMs = totalDurationMs,
        error = error,
        createdAt = createdAt.toEpochMilli(),
        language = language
    )

    private fun SwarmTask.toSummary() = TaskSummary(
        id = id,
        agentId = agentId,
        type = type.name,
        status = status.name,
        summary = summary,
        error = error,
        workerIterations = workerIterations,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        cacheReadTokens = cacheReadTokens,
        cacheWriteTokens = cacheWriteTokens,
        durationMs = durationMs,
    )

    private fun SwarmPreset.toInfo() = PresetInfo(
        name = name,
        title = title,
        description = description,
        agents = agents.map { a -> AgentBrief(id = a.id, role = a.role, description = null) },
        tasks = tasks.map { t ->
            TaskBrief(
                id = t.id,
                agentId = t.agentId,
                type = t.type.name,
                dependsOn = t.dependsOn,
                promptTemplate = t.promptTemplate,
                deliberation = t.deliberation?.let { DeliberationBrief(participants = it.participants, judge = it.judge) },
                team = t.team?.let { TeamBrief(leader = it.leader, members = it.members) }
            )
        },
        variables = variables.map { v -> VariableBrief(name = v.name, description = v.description, required = v.required, defaultValue = v.defaultValue, updatable = v.updatable) },
        language = language
    )

    /** Convert an [AgentToolConfig] (targetType=MCP) to a [SwarmMcpBinding] for export. */
    private fun com.easy.easyai.core.agent.AgentToolConfig.toSwarmMcpBinding(): SwarmMcpBinding {
        val (toolNames, promptNames) = metadata?.let { meta ->
            try {
                val node = SharedObjectMapper.instance.readTree(meta)
                if (node.isArray) {
                    val names = mutableListOf<String>()
                    for (el in node) { names.add(el.asText()) }
                    names to emptyList()
                } else {
                    val tools = mutableListOf<String>()
                    val toolNode = node.get("toolNames")
                    if (toolNode != null && toolNode.isArray) { for (el in toolNode) { tools.add(el.asText()) } }
                    val prompts = mutableListOf<String>()
                    val promptNode = node.get("promptNames")
                    if (promptNode != null && promptNode.isArray) { for (el in promptNode) { prompts.add(el.asText()) } }
                    tools to prompts
                }
            } catch (_: Exception) {
                emptyList<String>() to emptyList<String>()
            }
        } ?: (emptyList<String>() to emptyList<String>())
        return SwarmMcpBinding(serverName = targetName, toolNames = toolNames, promptNames = promptNames)
    }
}
