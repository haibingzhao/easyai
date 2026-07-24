package com.easy.easyai.swarm.runtime

import com.easy.easyai.common.textio.template.TemplateRenderer
import com.easy.easyai.core.agent.*
import com.easy.easyai.core.event.MessageEndEvent
import com.easy.easyai.core.event.TurnEndEvent
import com.easy.easyai.core.model.Usage
import com.easy.easyai.core.prompt.PromptContext
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.swarm.event.SwarmEvent
import com.easy.easyai.swarm.event.SwarmEventBridge
import com.easy.easyai.swarm.model.*
import com.easy.easyai.swarm.runtime.SwarmRuntime.Companion.MAX_SUMMARY_LENGTH
import com.easy.easyai.swarm.tool.TaskCompletionReportCheck
import com.easy.easyai.swarm.tool.TaskCompletionReportTool
import com.easy.easyai.swarm.tool.UpdateVariableTool
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

/**
 * Executes individual swarm worker agents using [AgentRunner].
 *
 * Responsibilities:
 * - Resolve agent context (tools/skills/MCP) via [SwarmAgentResolver]
 * - Create persisted sessions via [SwarmSessionManager]
 * - Inject runtime variable update tools
 * - Run the ReAct loop with timeout and event forwarding
 * - Render Jinja2 prompt templates for SINGLE tasks
 *
 * Extracted from [SwarmRuntime] to isolate worker execution from DAG orchestration.
 *
 * @param agentServiceProvider Lazy provider for shared AgentService
 * @param agentResolver Resolves AgentDefinition + tools/skills/MCP from DB for swarm workers
 * @param templateRenderer Jinja2 template renderer for prompt variable substitution
 * @param eventBridge Event bridge for emitting worker events
 * @param sessionManager Manages session creation and message listener lifecycle. null when no persistence.
 * @param eventVerbosity Controls which worker events are forwarded ("task", "tool", "all")
 */
class SwarmWorkerExecutor(
    agentServiceProvider: () -> AgentService,
    private val agentResolver: SwarmAgentResolver,
    private val templateRenderer: TemplateRenderer,
    private val eventBridge: SwarmEventBridge,
    private val sessionManager: SwarmSessionManager? = null,
    private val eventVerbosity: String = "task"
) {
    private val agentService: AgentService by lazy { agentServiceProvider() }
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Execute a SINGLE agent task using AgentRunner.
     */
    internal suspend fun runSingleWorker(
        task: SwarmTask,
        run: SwarmRun,
        taskSummaries: Map<String, String>,
        abortSignal: () -> Boolean,
        runContext: RunContext
    ): WorkerResult {
        val agentSpec = run.agents.find { it.id == task.agentId }
            ?: return WorkerResult(
                SwarmTaskStatus.FAILED, "",
                error = "Agent '${task.agentId}' not found in preset"
            )

        // When the task has no promptTemplate, fall back to user_input regardless of agentContext.
        // This avoids sending an empty UserMessage to the LLM for non-CHAT agents.
        val agentDef = runContext.agentDefCache[agentSpec.cacheKey]
        val inputFromVars = resolveInputFrom(task, taskSummaries)
        val prompt = if (task.promptTemplate.isBlank()) {
            run.userVars["user_input"] ?: ""
        } else {
            renderPrompt(task.promptTemplate, taskSummaries, run.userVars, extraVars = inputFromVars, agentDef = agentDef)
        }
        return executeWorker(agentSpec, prompt, run, task, runContext, abortSignal, inputFromVars = inputFromVars)
    }

    /**
     * Execute a single worker agent using AgentRunner.
     *
     * Uses [SwarmAgentResolver] to load AgentDefinition from DB and resolve
     * tools/skills/MCP via SubAgentContextResolver.
     * Uses CompletableDeferred<Job> to track the runner job for cancellation (SubAgentTool pattern).
     */
    internal suspend fun executeWorker(
        agentSpec: SwarmAgentSpec,
        prompt: String,
        run: SwarmRun,
        task: SwarmTask,
        runContext: RunContext,
        abortSignal: () -> Boolean = { false },
        additionalTools: List<ToolDefinition> = emptyList(),
        additionalCompletionChecks: List<AgentCompletionCheck> = emptyList(),
        inputFromVars: Map<String, String> = emptyMap(),
        outputSchemaOverride: String? = null,
        /** Override the Agent's SystemMessage prompt. Used by Deliberation orchestrator phases
         *  (Opening/Round) to inject a neutral orchestrator prompt instead of the Judge's own promptTemplate. */
        systemPromptOverride: String? = null,
    ): WorkerResult {
        // Resolve agent context via SwarmAgentResolver (DB load + tool/skill/MCP resolution)
        val (baseContext, resolvedTools) = try {
            agentResolver.resolve(
                spec = agentSpec,
                run = run,
                task = task,
                modelConfigId = runContext.modelConfigId,
                agentDefCache = runContext.agentDefCache,
                modelConfigCache = runContext.modelConfigCache,
                inputFromVars = inputFromVars,
                outputSchemaOverride = outputSchemaOverride,
            )
        } catch (e: Exception) {
            logger.error("Failed to resolve agent '{}': {}", agentSpec.id, e.message, e)
            return WorkerResult(
                SwarmTaskStatus.FAILED, "",
                error = "Failed to resolve agent '${agentSpec.id}': ${e.message}"
            )
        }

        // Create a persisted session for this swarm worker (enables message persistence)
        val sessionId = try {
            sessionManager?.createSession(agentSpec.id, run.id, task.id, run.userId)
        } catch (e: Exception) {
            logger.warn("Failed to create swarm session for task '{}': {}", task.id, e.message)
            null
        }

        // Inject update_variable tool if this task has updatable variables configured
        val toolsWithVarUpdate = if (runContext.variableGuard != null && task.updatableVariables.isNotEmpty()) {
            resolvedTools + UpdateVariableTool(
                metadata = ToolMetadata(
                    name = "update_variable",
                    description = UpdateVariableTool.buildDescription(task.updatableVariables),
                    permissionCategory = "swarm",
                    isDefaultTool = false
                ),
                userVars = run.userVars,
                guard = runContext.variableGuard,
                taskId = task.id
            ) + additionalTools
        } else {
            resolvedTools + additionalTools
        }

        // Inject report_task_result tool + completion check when reportEnabled
        val reportRef = AtomicReference<TaskReportResult>()
        val toolsWithReport = if (task.reportEnabled) {
            toolsWithVarUpdate + TaskCompletionReportTool(
                metadata = ToolMetadata(
                    name = TaskCompletionReportTool.TOOL_NAME,
                    description = TaskCompletionReportTool.buildDescription(),
                    permissionCategory = "swarm",
                    isDefaultTool = false
                ),
                reportRef = reportRef,
            )
        } else toolsWithVarUpdate

        val effectiveCompletionChecks = if (task.reportEnabled) {
            additionalCompletionChecks + TaskCompletionReportCheck(reportRef)
        } else additionalCompletionChecks

        // Merge inputFrom vars into inputVariables so the system prompt can also render them
        val mergedInputVariables = if (inputFromVars.isNotEmpty()) {
            baseContext.inputVariables + inputFromVars
        } else {
            baseContext.inputVariables
        }
        val context = baseContext.copy(
            tools = toolsWithReport,
            sessionId = sessionId,
            abortSignal = abortSignal,
            inputVariables = mergedInputVariables,
        ).let { ctx ->
            if (systemPromptOverride != null) ctx.copy(promptTemplate = systemPromptOverride) else ctx
        }

        // Wrap agentService with message listener for real-time persistence
        val workerService = wrapServiceWithListener(
            agentService,
            if (sessionId != null && sessionManager != null) sessionManager.createMessageListener(sessionId, context) else null
        )

        val finalService = wrapServiceWithCompletionChecks(workerService, effectiveCompletionChecks)

        // Track accumulated usage for task_progress events
        var progressUsage = Usage()

        val output = executeAgentWithProtection(
            agent = Agent(context, finalService),
            prompt = prompt,
            timeoutMs = agentSpec.timeoutSeconds * 1000L,
            abortSignal = abortSignal,
            onEvent = { event ->
                // Accumulate usage from MessageEndEvent for progress tracking
                if (event is MessageEndEvent) {
                    event.usage?.let { u ->
                        progressUsage = Usage(
                            inputTokens = progressUsage.inputTokens + u.inputTokens,
                            outputTokens = progressUsage.outputTokens + u.outputTokens,
                            cacheReadTokens = progressUsage.cacheReadTokens + u.cacheReadTokens,
                            cacheWriteTokens = progressUsage.cacheWriteTokens + u.cacheWriteTokens,
                        )
                    }
                }

                // Emit task_progress on each ReAct iteration end
                if (event is TurnEndEvent) {
                    eventBridge.onTaskProgress(
                        run = run,
                        task = task,
                        iteration = event.turnId,
                        inputTokens = progressUsage.inputTokens.toLong(),
                        outputTokens = progressUsage.outputTokens.toLong()
                    )
                }

                // Forward worker internal events based on eventVerbosity
                if (eventVerbosity != "task") {
                    val shouldForward = eventVerbosity == "all" ||
                        (eventVerbosity == "tool" && event.type.startsWith("tool_"))
                    if (shouldForward) {
                        eventBridge.onWorkerEvent(
                            SwarmEvent(
                                type = "worker_${event.type}",
                                runId = run.id,
                                taskId = task.id,
                                data = mapOf("agentEventType" to event.type)
                            )
                        )
                    }
                }
            },
            maxSummaryLength = MAX_SUMMARY_LENGTH,
            label = "Worker '${agentSpec.id}'",
        )

        return when (output.status) {
            ExecutionStatus.COMPLETED -> WorkerResult(
                status = SwarmTaskStatus.COMPLETED,
                summary = output.summary,
                inputTokens = output.usage.inputTokens.toLong(),
                outputTokens = output.usage.outputTokens.toLong(),
                cacheReadTokens = output.usage.cacheReadTokens.toLong(),
                cacheWriteTokens = output.usage.cacheWriteTokens.toLong(),
                durationMs = output.usage.durationMs,
                sessionId = sessionId,
                taskReport = reportRef.get(),
            )
            ExecutionStatus.TIMEOUT -> WorkerResult(
                SwarmTaskStatus.FAILED, "",
                inputTokens = output.usage.inputTokens.toLong(),
                outputTokens = output.usage.outputTokens.toLong(),
                cacheReadTokens = output.usage.cacheReadTokens.toLong(),
                cacheWriteTokens = output.usage.cacheWriteTokens.toLong(),
                durationMs = output.usage.durationMs,
                sessionId = sessionId,
                error = "Worker '${agentSpec.id}' timed out after ${agentSpec.timeoutSeconds}s"
            )
            ExecutionStatus.FAILED -> WorkerResult(
                SwarmTaskStatus.FAILED, "",
                inputTokens = output.usage.inputTokens.toLong(),
                outputTokens = output.usage.outputTokens.toLong(),
                cacheReadTokens = output.usage.cacheReadTokens.toLong(),
                cacheWriteTokens = output.usage.cacheWriteTokens.toLong(),
                durationMs = output.usage.durationMs,
                sessionId = sessionId,
                error = output.error ?: "Worker '${agentSpec.id}' failed"
            )
        }
    }

    /**
     * Resume a previously suspended worker from its persisted session.
     *
     * Loads historical messages from the session, appends [resumeMessage] as a new UserMessage,
     * and continues execution with the same agent configuration.
     *
     * @param agentSpec The agent spec for the suspended member
     * @param sessionId The session ID from the original execution
     * @param resumeMessage Message to inject (e.g., resolution info or user answer)
     */
    internal suspend fun resumeWorker(
        agentSpec: SwarmAgentSpec,
        sessionId: String,
        resumeMessage: String,
        run: SwarmRun,
        task: SwarmTask,
        runContext: RunContext,
        abortSignal: () -> Boolean = { false },
        additionalTools: List<ToolDefinition> = emptyList(),
        additionalCompletionChecks: List<AgentCompletionCheck> = emptyList(),
    ): WorkerResult {
        // Resolve agent context (same as executeWorker)
        val (baseContext, resolvedTools) = try {
            agentResolver.resolve(
                spec = agentSpec,
                run = run,
                task = task,
                modelConfigId = runContext.modelConfigId,
                agentDefCache = runContext.agentDefCache,
                modelConfigCache = runContext.modelConfigCache,
            )
        } catch (e: Exception) {
            logger.error("Failed to resolve agent '{}' for resume: {}", agentSpec.id, e.message, e)
            return WorkerResult(
                SwarmTaskStatus.FAILED, "",
                error = "Failed to resolve agent '${agentSpec.id}' for resume: ${e.message}"
            )
        }

        // Load historical messages from the persisted session
        val historyMessages = try {
            sessionManager?.loadMessages(sessionId) ?: emptyList()
        } catch (e: Exception) {
            logger.warn("Failed to load session messages for '{}': {}", sessionId, e.message)
            emptyList()
        }

        if (historyMessages.isEmpty()) {
            logger.warn("No history found for session '{}', falling back to fresh execution", sessionId)
        }

        val toolsWithAdditional = resolvedTools + additionalTools
        val context = baseContext.copy(
            tools = toolsWithAdditional,
            sessionId = sessionId,
            abortSignal = abortSignal,
        )

        val workerService = wrapServiceWithListener(
            agentService,
            sessionManager?.createMessageListener(sessionId, context)
        )
        val finalService = wrapServiceWithCompletionChecks(workerService, additionalCompletionChecks)

        val output = executeAgentWithProtection(
            agent = Agent(context, finalService),
            prompt = resumeMessage,
            timeoutMs = agentSpec.timeoutSeconds * 1000L,
            abortSignal = abortSignal,
            onEvent = { event ->
                if (eventVerbosity != "task") {
                    val shouldForward = eventVerbosity == "all" ||
                        (eventVerbosity == "tool" && event.type.startsWith("tool_"))
                    if (shouldForward) {
                        eventBridge.onWorkerEvent(
                            SwarmEvent(
                                type = "worker_${event.type}",
                                runId = run.id,
                                taskId = task.id,
                                data = mapOf("agentEventType" to event.type)
                            )
                        )
                    }
                }
            },
            maxSummaryLength = MAX_SUMMARY_LENGTH,
            label = "Resumed Worker '${agentSpec.id}'",
            initialMessages = historyMessages,
        )

        return when (output.status) {
            ExecutionStatus.COMPLETED -> WorkerResult(
                status = SwarmTaskStatus.COMPLETED,
                summary = output.summary,
                inputTokens = output.usage.inputTokens.toLong(),
                outputTokens = output.usage.outputTokens.toLong(),
                cacheReadTokens = output.usage.cacheReadTokens.toLong(),
                cacheWriteTokens = output.usage.cacheWriteTokens.toLong(),
                durationMs = output.usage.durationMs,
                sessionId = sessionId,
            )
            ExecutionStatus.TIMEOUT -> WorkerResult(
                SwarmTaskStatus.FAILED, "",
                inputTokens = output.usage.inputTokens.toLong(),
                outputTokens = output.usage.outputTokens.toLong(),
                cacheReadTokens = output.usage.cacheReadTokens.toLong(),
                cacheWriteTokens = output.usage.cacheWriteTokens.toLong(),
                durationMs = output.usage.durationMs,
                sessionId = sessionId,
                error = "Resumed worker '${agentSpec.id}' timed out after ${agentSpec.timeoutSeconds}s"
            )
            ExecutionStatus.FAILED -> WorkerResult(
                SwarmTaskStatus.FAILED, "",
                inputTokens = output.usage.inputTokens.toLong(),
                outputTokens = output.usage.outputTokens.toLong(),
                cacheReadTokens = output.usage.cacheReadTokens.toLong(),
                cacheWriteTokens = output.usage.cacheWriteTokens.toLong(),
                durationMs = output.usage.durationMs,
                sessionId = sessionId,
                error = output.error ?: "Resumed worker '${agentSpec.id}' failed"
            )
        }
    }

    /**
     * Render a prompt template using Jinja2 engine.
     *
     * Variables are merged into a single model map and rendered via [TemplateRenderer.renderLiteralTemplate].
     * Task summaries are truncated to [MAX_SUMMARY_LENGTH] to prevent context overflow.
     * On rendering failure, returns the raw template so callers can detect the error.
     */
    fun renderPrompt(
        template: String,
        taskSummaries: Map<String, String>,
        userVars: Map<String, String>,
        extraVars: Map<String, String> = emptyMap(),
        agentDef: AgentDefinition? = null
    ): String {
        if (template.isBlank()) {
            logger.debug("renderPrompt called with blank template, returning empty string")
            return ""
        }
        // Build PromptContext with agent-specific info when available
        val promptContext = if (agentDef != null) {
            PromptContext(
                agent = mapOf(
                    "id" to agentDef.id,
                    "name" to agentDef.name,
                    "description" to (agentDef.description ?: "")
                ),
                customInstructions = agentDef.customInstructions,
                outputSchema = agentDef.outputSchema
            )
        } else {
            PromptContext()
        }
        val model = promptContext.toModel().toMutableMap()
        // Swarm-specific variables override base context (priority: extraVars > taskSummaries > userVars > PromptContext)
        userVars.forEach { (k, v) -> model[k] = v }
        taskSummaries.forEach { (k, v) ->
            model[k] = if (v.length > MAX_SUMMARY_LENGTH) v.take(MAX_SUMMARY_LENGTH) + "\n[Truncated]" else v
        }
        extraVars.forEach { (k, v) -> model[k] = v }
        return try {
            templateRenderer.renderLiteralTemplate(template, model)
        } catch (e: Exception) {
            logger.error("Jinja2 render failed for template: {}", e.message)
            template
        }
    }

    /**
     * Resolve inputFrom variable aliases to actual upstream summary values.
     */
    fun resolveInputFrom(
        task: SwarmTask,
        taskSummaries: Map<String, String>
    ): Map<String, String> {
        val resolved = mutableMapOf<String, String>()
        for ((varName, sourceTaskId) in task.inputFrom) {
            taskSummaries[sourceTaskId]?.let { resolved[varName] = it }
        }
        return resolved
    }

    /**
     * Create a copy of this executor without session persistence (for dry-run mode).
     */
    internal fun withoutSession(): SwarmWorkerExecutor = SwarmWorkerExecutor(
        agentServiceProvider = { agentService },
        agentResolver = agentResolver,
        templateRenderer = templateRenderer,
        eventBridge = eventBridge,
        sessionManager = null,
        eventVerbosity = eventVerbosity,
    )
}

