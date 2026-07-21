package com.easy.easyai.swarm.runtime

import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.swarm.event.SwarmEventBridge
import com.easy.easyai.swarm.model.*
import com.easy.easyai.swarm.tool.EscalationCompletionCheck
import com.easy.easyai.swarm.tool.EscalationResult
import com.easy.easyai.swarm.tool.EscalationTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

/**
 * Executes TEAM tasks: Leader-Member reactive event loop.
 *
 * Flow:
 * 1. Leader auto-generates planning prompt from contextTemplate
 * 2. Leader analyzes the task and delegates work to members
 * 3. Members execute assignments in parallel, results flow into a Channel
 * 4. Main loop receives results with debounce/drain aggregation
 * 5. Leader reviews progress (trigger events + running members) and decides next steps
 * 6. Repeats until complete, stalled, or max iterations reached
 *
 * Extracted from [SwarmRuntime] to reduce its size and isolate TEAM coordination logic.
 */
internal class TeamTaskExecutor(
    private val workerExecutor: SwarmWorkerExecutor,
    private val eventBridge: SwarmEventBridge,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        /**
         * JSON Schema for Leader decision output (planning + coordination).
         * Compatible with OpenAI response_format=json_schema strict mode.
         */
        val LEADER_DECISION_SCHEMA = """
{
  "type": "object",
  "properties": {
    "analysis": { "type": "string" },
    "newTasks": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "memberId": { "type": "string" },
          "assignment": { "type": "string" }
        },
        "required": ["memberId", "assignment"],
        "additionalProperties": false
      }
    },
    "reassignments": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "fromMemberId": { "type": "string" },
          "toMemberId": { "type": "string" },
          "reason": { "type": "string" }
        },
        "required": ["fromMemberId", "toMemberId", "reason"],
        "additionalProperties": false
      }
    },
    "isComplete": { "type": "boolean" }
  },
  "required": ["analysis", "newTasks", "reassignments", "isComplete"],
  "additionalProperties": false
}
        """.trimIndent()
    }

    /**
     * Execute a TEAM task: Leader-Member reactive event loop.
     *
     * Resume degradation: TEAM tasks cannot be resumed mid-execution.
     * If the run is in RESUMING state, the task is marked FAILED.
     */
    suspend fun runTeam(
        task: SwarmTask,
        run: SwarmRun,
        taskSummaries: MutableMap<String, String>,
        abortSignal: () -> Boolean,
        runContext: RunContext
    ): WorkerResult {
        val teamSpec = task.team
            ?: return WorkerResult(SwarmTaskStatus.FAILED, "", error = "No team spec for TEAM task")

        // Resume degradation: TEAM tasks cannot be resumed mid-execution
        if (run.status == SwarmRunStatus.RESUMING) {
            logger.warn("TEAM task '{}' cannot be resumed; marking FAILED", task.id)
            return WorkerResult(SwarmTaskStatus.FAILED, "", error = "TEAM tasks do not support resume")
        }

        val leaderSpec = run.agents.find { it.id == teamSpec.leader }
            ?: return WorkerResult(SwarmTaskStatus.FAILED, "", error = "Leader '${teamSpec.leader}' not found in run agents")

        val state = TeamExecutionState()
        val resultChannel = Channel<MemberExecutionResult>(Channel.UNLIMITED)
        val memberTimeoutMs = resolveMemberTimeoutMs(teamSpec)
        val inputFromVars = workerExecutor.resolveInputFrom(task, taskSummaries)

        eventBridge.onTeamStarted(run, task, teamSpec)

        supervisorScope {
            try {
                // Phase 1: Leader initial planning
                val initialDecision = invokeLeaderPlanning(
                    teamSpec, leaderSpec, run, task, taskSummaries, inputFromVars, runContext, state
                )

                launchMembers(
                    initialDecision, resultChannel, state, memberTimeoutMs,
                    run, task, taskSummaries, inputFromVars, abortSignal, runContext
                )

                // Phase 2: Reactive Loop
                while (state.iterations < teamSpec.maxIterations && !abortSignal()) {
                    val first = withTimeoutOrNull((teamSpec.roundTimeoutSeconds * 1000L).milliseconds) {
                        resultChannel.receive()
                    }
                    if (first == null) {
                        logger.warn("Team task '{}' iteration {} timed out ({}s limit)",
                            task.id, state.iterations + 1, teamSpec.roundTimeoutSeconds)
                        state.escalationHistory.add(EscalationEntry(
                            memberId = teamSpec.leader,
                            round = state.iterations + 1,
                            reason = "Iteration ${state.iterations + 1} timed out after ${teamSpec.roundTimeoutSeconds}s",
                            resolution = "Iteration timeout — proceeding with completed results"
                        ))
                        break
                    }

                    // Drain: wait brief window to aggregate more results
                    delay(2000)
                    val batch = mutableListOf(first)
                    while (true) {
                        val next = resultChannel.tryReceive().getOrNull() ?: break
                        batch.add(next)
                    }

                    // Process batch: update state, emit events, update taskSummaries
                    processResultBatch(batch, state, run, task, taskSummaries)

                    state.iterations++
                    eventBridge.onTeamRoundStarted(run, task, state.iterations)

                    val decision = invokeLeaderCoordination(
                        batch, state, teamSpec, leaderSpec, run, task,
                        taskSummaries, inputFromVars, runContext
                    )

                    // Build and persist iteration record
                    val record = buildIterationRecord(state.iterations, decision, batch, state)
                    state.roundRecords.add(record)
                    eventBridge.onTeamRoundCompleted(run, task, state.iterations, record)

                    if (decision.isComplete) {
                        logger.info("Team task '{}' completed at iteration {} (leader signaled complete)",
                            task.id, state.iterations)
                        break
                    }
                    if (state.runningMemberIds.isEmpty() && decision.newTasks.isEmpty() && decision.reassignments.isEmpty()) {
                        logger.info("Team task '{}' stalled at iteration {} (no running members, no new tasks)",
                            task.id, state.iterations)
                        break
                    }

                    launchMembers(
                        decision, resultChannel, state, memberTimeoutMs,
                        run, task, taskSummaries, inputFromVars, abortSignal, runContext
                    )
                }
            } finally {
                // Phase 3: Cancel running members and close channel
                state.runningJobs.values.forEach { it.cancel() }
                resultChannel.close()
            }
        }

        // Final summary
        val finalSummary = buildTeamFinalSummary(state.memberExecutions, state.roundRecords)
        eventBridge.onTeamCompleted(run, task, finalSummary)

        val finalStatus = when {
            state.roundRecords.isEmpty() -> SwarmTaskStatus.FAILED
            state.escalationHistory.any { it.resolution == null } -> SwarmTaskStatus.FAILED
            else -> SwarmTaskStatus.COMPLETED
        }

        return WorkerResult(
            status = finalStatus,
            summary = finalSummary,
            iterations = state.iterations,
            inputTokens = state.total.input,
            outputTokens = state.total.output,
            cacheReadTokens = state.total.cacheRead,
            cacheWriteTokens = state.total.cacheWrite,
            durationMs = state.total.duration,
            error = if (finalStatus == SwarmTaskStatus.FAILED) "Team task had unresolved escalations" else null,
            escalationHistory = state.escalationHistory,
            memberExecutions = state.memberExecutions,
            roundRecords = state.roundRecords,
        )
    }

    // --- Leader Invocation ---

    /**
     * Invoke Leader for initial planning (iteration 1).
     */
    private suspend fun invokeLeaderPlanning(
        teamSpec: TeamSpec,
        leaderSpec: SwarmAgentSpec,
        run: SwarmRun,
        task: SwarmTask,
        taskSummaries: MutableMap<String, String>,
        inputFromVars: Map<String, String>,
        runContext: RunContext,
        state: TeamExecutionState,
    ): LeaderDecision {
        val teamContext = workerExecutor.renderPrompt(
            teamSpec.contextTemplate, taskSummaries, run.userVars, inputFromVars, null
        )
        val memberProfiles = buildMemberProfiles(teamSpec, runContext, run)
        val leaderPrompt = buildLeaderPlanningPrompt(teamContext, memberProfiles, run)

        val leaderResult = workerExecutor.executeWorker(
            leaderSpec, leaderPrompt, run, task, runContext, { false },
            outputSchemaOverride = LEADER_DECISION_SCHEMA
        )
        state.total += leaderResult

        val decision = LeaderDecisionParser.parse(leaderResult.summary)
        state.leaderPrompts.add(leaderPrompt)
        state.leaderAnalyses.add("Iteration 1:\n${decision.analysis.take(500)}")
        for (dt in decision.newTasks) {
            state.delegationHistory.add(DelegationRecord(1, dt.memberId, dt.assignment.take(500)))
        }
        return decision
    }

    /**
     * Invoke Leader for coordination after receiving member results.
     */
    private suspend fun invokeLeaderCoordination(
        batch: List<MemberExecutionResult>,
        state: TeamExecutionState,
        teamSpec: TeamSpec,
        leaderSpec: SwarmAgentSpec,
        run: SwarmRun,
        task: SwarmTask,
        taskSummaries: MutableMap<String, String>,
        inputFromVars: Map<String, String>,
        runContext: RunContext,
    ): LeaderDecision {
        val teamContext = workerExecutor.renderPrompt(
            teamSpec.contextTemplate, taskSummaries, run.userVars, inputFromVars, null
        )
        val leaderPrompt = buildLeaderReactivePrompt(
            teamContext, state.iterations + 1, batch, state
        )

        val leaderResult = workerExecutor.executeWorker(
            leaderSpec, leaderPrompt, run, task, runContext, { false },
            outputSchemaOverride = LEADER_DECISION_SCHEMA
        )
        state.total += leaderResult

        val decision = LeaderDecisionParser.parse(leaderResult.summary)
        val iteration = state.iterations + 1
        state.leaderPrompts.add(leaderPrompt)
        state.leaderAnalyses.add("Iteration $iteration:\n${decision.analysis.take(500)}")
        for (dt in decision.newTasks) {
            state.delegationHistory.add(DelegationRecord(iteration, dt.memberId, dt.assignment.take(500)))
        }
        for (ra in decision.reassignments) {
            state.delegationHistory.add(DelegationRecord(iteration, ra.toMemberId,
                "Reassigned from ${ra.fromMemberId}: ${ra.reason.take(500)}"))
        }
        return decision
    }

    // --- Member Launch & Processing ---

    /**
     * Launch member coroutines for the given decision. Results are sent to the channel.
     * Must be called within a [supervisorScope] — launched jobs are children of that scope.
     */
    private suspend fun CoroutineScope.launchMembers(
        decision: LeaderDecision,
        channel: Channel<MemberExecutionResult>,
        state: TeamExecutionState,
        memberTimeoutMs: Long,
        run: SwarmRun,
        task: SwarmTask,
        taskSummaries: MutableMap<String, String>,
        inputFromVars: Map<String, String>,
        abortSignal: () -> Boolean,
        runContext: RunContext,
    ) {
        val iteration = state.iterations + 1

        // Emit delegation events and launch new tasks
        for (dynamicTask in decision.newTasks) {
            eventBridge.onTeamDelegated(run, task, dynamicTask.memberId, dynamicTask.assignment)
            val job = launch {
                val result = withTimeoutOrNull(memberTimeoutMs) {
                    executeTeamMember(
                        dynamicTask.memberId, dynamicTask.assignment,
                        iteration, run, task, taskSummaries, inputFromVars,
                        abortSignal, runContext
                    )
                } ?: timeoutMemberResult(dynamicTask.memberId, iteration, memberTimeoutMs)
                state.runningJobs.remove(dynamicTask.memberId)
                state.runningMemberIds.remove(dynamicTask.memberId)
                channel.send(result)
            }
            state.runningJobs[dynamicTask.memberId] = job
            state.runningMemberIds.add(dynamicTask.memberId)
        }

        // Handle reassignments
        for (reassignment in decision.reassignments) {
            logger.info("Team task '{}': reassignment from '{}' to '{}': {}",
                task.id, reassignment.fromMemberId, reassignment.toMemberId, reassignment.reason)
            state.escalationHistory.add(EscalationEntry(
                memberId = reassignment.fromMemberId,
                round = iteration,
                reason = reassignment.reason.take(2000),
                reassignedTo = reassignment.toMemberId,
            ))
            eventBridge.onTeamEscalated(run, task, reassignment.fromMemberId, reassignment.reason)
            eventBridge.onTeamDelegated(run, task, reassignment.toMemberId,
                "Reassigned from ${reassignment.fromMemberId}: ${reassignment.reason}")

            val reassignAssignment = "Reassigned from ${reassignment.fromMemberId}: ${reassignment.reason}"
            val job = launch {
                val result = withTimeoutOrNull(memberTimeoutMs) {
                    executeTeamMember(
                        reassignment.toMemberId, reassignAssignment,
                        iteration, run, task, taskSummaries, inputFromVars,
                        abortSignal, runContext
                    )
                } ?: timeoutMemberResult(reassignment.toMemberId, iteration, memberTimeoutMs)
                state.runningJobs.remove(reassignment.toMemberId)
                state.runningMemberIds.remove(reassignment.toMemberId)
                channel.send(result)
            }
            state.runningJobs[reassignment.toMemberId] = job
            state.runningMemberIds.add(reassignment.toMemberId)
        }
    }

    /**
     * Process a batch of member execution results: update state, emit events, update task summaries.
     */
    private suspend fun processResultBatch(
        batch: List<MemberExecutionResult>,
        state: TeamExecutionState,
        run: SwarmRun,
        task: SwarmTask,
        taskSummaries: MutableMap<String, String>,
    ) {
        for (result in batch) {
            val (memberId, memberExec) = result
            state.total += result.tokens.snapshot()
            state.memberExecutions.add(memberExec)

            if (memberExec.status == MemberStatus.COMPLETED) {
                eventBridge.onTeamMemberCompleted(run, task, memberId, memberExec.summary ?: "")
                if (memberExec.summary != null) {
                    taskSummaries["${task.id}.$memberId"] = memberExec.summary
                }
            } else if (memberExec.status == MemberStatus.ESCALATED) {
                state.escalationHistory.add(EscalationEntry(
                    memberId = memberId,
                    round = memberExec.round,
                    reason = (memberExec.escalationReason ?: "Unknown").take(2000),
                ))
                eventBridge.onTeamEscalated(run, task, memberId, memberExec.escalationReason ?: "Unknown")
            }
        }
    }

    // --- Member Execution ---

    /**
     * Execute a single team member assignment.
     * Returns (memberId, TeamMemberExecution, inputTokens, outputTokens).
     */
    private suspend fun executeTeamMember(
        memberId: String,
        assignment: String,
        round: Int,
        run: SwarmRun,
        task: SwarmTask,
        taskSummaries: MutableMap<String, String>,
        inputFromVars: Map<String, String>,
        abortSignal: () -> Boolean,
        runContext: RunContext
    ): MemberExecutionResult {
        val memberSpec = run.agents.find { it.id == memberId }
        if (memberSpec == null) {
            logger.warn("Team task '{}': member '{}' not found in run agents", task.id, memberId)
            return MemberExecutionResult(
                memberId = memberId,
                execution = TeamMemberExecution(
                    memberId = memberId, round = round, assignment = assignment,
                    status = MemberStatus.ESCALATED,
                    escalationReason = "Member '$memberId' not found in run agents"
                ),
            )
        }

        val memberAgentDef = runContext.agentDefCache[memberSpec.cacheKey]
        val memberPrompt = workerExecutor.renderPrompt(
            assignment, taskSummaries, run.userVars,
            inputFromVars + mapOf("round" to round.toString()),
            memberAgentDef
        )

        // Inject EscalationTool + EscalationCompletionCheck for explicit escalation signaling
        val escalationRef = AtomicReference<EscalationResult?>(null)
        val escalationTool = EscalationTool(
            metadata = ToolMetadata(
                name = "escalate",
                description = EscalationTool.DESCRIPTION,
                permissionCategory = "swarm",
                isDefaultTool = false
            ),
            escalationRef = escalationRef
        )
        val completionCheck = EscalationCompletionCheck(escalationRef)

        val memberResult = workerExecutor.executeWorker(
            memberSpec, memberPrompt, run, task, runContext, abortSignal,
            additionalTools = listOf(escalationTool),
            additionalCompletionChecks = listOf(completionCheck)
        )

        // Check if member explicitly escalated via tool call
        val escalationReason = escalationRef.get()?.reason

        val status = if (escalationReason != null || memberResult.status == SwarmTaskStatus.FAILED) {
            MemberStatus.ESCALATED
        } else {
            MemberStatus.COMPLETED
        }

        return MemberExecutionResult(
            memberId = memberId,
            execution = TeamMemberExecution(
                memberId = memberId,
                round = round,
                assignment = assignment,
                status = status,
                summary = if (status == MemberStatus.COMPLETED) memberResult.summary else null,
                escalationReason = escalationReason ?: memberResult.error,
                inputTokens = memberResult.inputTokens,
                outputTokens = memberResult.outputTokens,
            ),
            tokens = TokenCounters.from(memberResult),
        )
    }

    /**
     * Build a timeout result for a member that exceeded its execution time limit.
     */
    private fun timeoutMemberResult(
        memberId: String,
        iteration: Int,
        memberTimeoutMs: Long,
    ): MemberExecutionResult {
        return MemberExecutionResult(
            memberId = memberId,
            execution = TeamMemberExecution(
                memberId = memberId,
                round = iteration,
                assignment = "",
                status = MemberStatus.ESCALATED,
                escalationReason = "Timed out after ${memberTimeoutMs / 1000}s",
            ),
        )
    }

    // --- Iteration Record ---

    /**
     * Build a [TeamRoundRecord] for a completed iteration.
     */
    private fun buildIterationRecord(
        iteration: Int,
        decision: LeaderDecision,
        batch: List<MemberExecutionResult>,
        state: TeamExecutionState,
    ): TeamRoundRecord {
        val delegatedMembers = decision.newTasks.map { it.memberId } +
            decision.reassignments.map { it.toMemberId }
        val completedMembers = batch
            .filter { it.execution.status == MemberStatus.COMPLETED }
            .map { it.memberId }
        val escalations = batch
            .filter { it.execution.status == MemberStatus.ESCALATED }
            .map { it.memberId }

        return TeamRoundRecord(
            round = iteration,
            leaderAnalysis = decision.analysis.take(500),
            delegatedMembers = delegatedMembers,
            completedMembers = completedMembers,
            escalations = escalations,
            leaderPrompt = state.leaderPrompts.getOrNull(iteration - 1),
        )
    }

    // --- Timeout Resolution ---

    /**
     * Resolve per-member execution timeout in milliseconds.
     * If [TeamSpec.memberTimeoutSeconds] > 0, use it directly.
     * Otherwise, auto-calculate as max(roundTimeout/2, 30) seconds.
     */
    private fun resolveMemberTimeoutMs(teamSpec: TeamSpec): Long {
        if (teamSpec.memberTimeoutSeconds > 0) return teamSpec.memberTimeoutSeconds * 1000L
        return maxOf(teamSpec.roundTimeoutSeconds / 2, 30) * 1000L
    }

    // --- Leader Prompt Generation ---

    /**
     * Build Round 1 planning prompt for the Leader.
     * Pure string concatenation — zero extra LLM calls.
     */
    private fun buildLeaderPlanningPrompt(
        teamContext: String,
        memberProfiles: String,
        run: SwarmRun,
    ): String = buildString {
        appendLine("## Team Coordination — Planning Phase")
        appendLine()
        if (teamContext.isNotBlank()) {
            appendLine("### Task Context")
            appendLine(teamContext)
            appendLine()
        }
        appendLine("### User Input")
        appendLine(run.userVars["user_input"] ?: "(none)")
        appendLine()
        appendLine("### Available Members")
        appendLine(memberProfiles)
        appendLine()
        appendLine("### Your Role")
        appendLine("Analyze the task context above, create a plan, and delegate work to available members.")
        appendLine("Consider each member's expertise when assigning tasks.")
        appendLine()
        appendLine("### Output Format")
        appendLine("Respond with a JSON object:")
        appendLine("```json")
        appendLine("{")
        appendLine("  \"analysis\": \"Your analysis of the task and plan\",")
        appendLine("  \"newTasks\": [")
        appendLine("    { \"memberId\": \"member-id\", \"assignment\": \"Clear task description for this member\" }")
        appendLine("  ],")
        appendLine("  \"reassignments\": [],")
        appendLine("  \"isComplete\": false")
        appendLine("}")
        appendLine("```")
        appendLine()
        appendLine("Output ONLY the JSON. No explanation outside the JSON.")
    }

    /**
     * Build reactive coordination prompt for the Leader (iteration 2+).
     * Includes trigger events (just-completed results), currently running members,
     * and capped history to prevent prompt growth.
     */
    private fun buildLeaderReactivePrompt(
        teamContext: String,
        iteration: Int,
        triggerBatch: List<MemberExecutionResult>,
        state: TeamExecutionState,
    ): String = buildString {
        appendLine("## Team Coordination — Iteration $iteration Reactive Decision")
        appendLine()
        if (teamContext.isNotBlank()) {
            appendLine("### Original Task Context")
            appendLine(teamContext)
            appendLine()
        }

        // Trigger Events: members that just completed
        appendLine("### Trigger Events (just completed)")
        if (triggerBatch.isNotEmpty()) {
            appendLine("<trigger_events>")
            for (result in triggerBatch) {
                val exec = result.execution
                val statusLabel = if (exec.status == MemberStatus.COMPLETED) "COMPLETED" else "ESCALATED"
                val detail = if (exec.status == MemberStatus.COMPLETED) {
                    exec.summary?.take(800) ?: "(no summary)"
                } else {
                    exec.escalationReason ?: "Unknown"
                }
                appendLine("  <event member=\"${exec.memberId}\" status=\"$statusLabel\">")
                appendLine(detail)
                appendLine("  </event>")
            }
            appendLine("</trigger_events>")
        } else {
            appendLine("(No trigger events)")
        }
        appendLine()

        // Currently Running Members
        appendLine("### Currently Running Members")
        if (state.runningMemberIds.isNotEmpty()) {
            for (memberId in state.runningMemberIds) {
                appendLine("- **$memberId** (still executing)")
            }
        } else {
            appendLine("(No members currently running)")
        }
        appendLine()

        // Completed Tasks (capped at 10)
        appendLine("### Completed Tasks (recent 10)")
        val completed = state.memberExecutions
            .filter { it.status == MemberStatus.COMPLETED }
            .takeLast(10)
        if (completed.isNotEmpty()) {
            appendLine("<completed_tasks>")
            for (exec in completed) {
                appendLine("  <task member=\"${exec.memberId}\" iteration=\"${exec.round}\">")
                appendLine(exec.summary?.take(800) ?: "(no summary)")
                appendLine("  </task>")
            }
            appendLine("</completed_tasks>")
        } else {
            appendLine("(No completed tasks yet)")
        }
        appendLine()

        // Escalation History (capped at 5)
        appendLine("### Escalation History (recent 5)")
        val recentEscalations = state.escalationHistory.takeLast(5)
        appendLine(formatEscalationHistory(recentEscalations))
        appendLine()

        // Previous Iteration Analyses (capped at 5)
        appendLine("### Previous Iteration Analyses (recent 5)")
        val recentAnalyses = state.leaderAnalyses.takeLast(5)
        if (recentAnalyses.isNotEmpty()) {
            appendLine("<previous_analyses>")
            for ((index, analysis) in recentAnalyses.withIndex()) {
                appendLine("  <analysis index=\"${index + 1}\">")
                appendLine(analysis)
                appendLine("  </analysis>")
            }
            appendLine("</previous_analyses>")
        } else {
            appendLine("(First coordination iteration)")
        }
        appendLine()

        // Delegation History (capped at 10)
        appendLine("### Delegation History (recent 10)")
        val recentDelegations = state.delegationHistory.takeLast(10)
        if (recentDelegations.isNotEmpty()) {
            appendLine("<delegation_history>")
            for (delegation in recentDelegations) {
                appendLine("  <delegation iteration=\"${delegation.round}\" member=\"${delegation.memberId}\">")
                appendLine(delegation.assignment)
                appendLine("  </delegation>")
            }
            appendLine("</delegation_history>")
        } else {
            appendLine("(No prior delegations)")
        }
        appendLine()

        appendLine("### Your Decision")
        appendLine("Review the trigger events and current state above. Handle any escalations,")
        appendLine("reassign work if needed, and decide whether the team has completed all objectives.")
        appendLine("You may choose to wait for running members or proceed with new assignments.")
        appendLine()
        appendLine("Respond with a JSON object:")
        appendLine("```json")
        appendLine("{")
        appendLine("  \"analysis\": \"Your assessment of current progress\",")
        appendLine("  \"newTasks\": [")
        appendLine("    { \"memberId\": \"member-id\", \"assignment\": \"Task description\" }")
        appendLine("  ],")
        appendLine("  \"reassignments\": [")
        appendLine("    { \"fromMemberId\": \"blocked-member\", \"toMemberId\": \"new-member\", \"reason\": \"Why\" }")
        appendLine("  ],")
        appendLine("  \"isComplete\": true")
        appendLine("}")
        appendLine("```")
        appendLine()
        appendLine("Set \"isComplete\": true when all objectives are met. Output ONLY the JSON.")
    }

    /**
     * Build member profiles string for the Leader, listing each member's capabilities.
     * Referenced from [DeliberationTaskExecutor.buildParticipantProfiles].
     */
    private fun buildMemberProfiles(
        teamSpec: TeamSpec,
        runContext: RunContext,
        run: SwarmRun,
    ): String = buildString {
        for (memberId in teamSpec.members) {
            val spec = run.agents.find { it.id == memberId } ?: continue
            val agentDef = runContext.agentDefCache[spec.cacheKey]
            appendLine("- **${spec.id}** (${spec.role})")
            agentDef?.description?.let { appendLine("  Description: $it") }
        }
        if (teamSpec.members.isEmpty()) {
            appendLine("(No members configured)")
        }
    }

    /**
     * Format escalation history for injection into Leader prompts.
     */
    private fun formatEscalationHistory(history: List<EscalationEntry>): String {
        if (history.isEmpty()) return "(No escalations yet)"
        return history.joinToString("\n") { entry ->
            "[Round ${entry.round}] ${entry.memberId}: ${entry.reason}" +
                (entry.reassignedTo?.let { " → reassigned to $it" } ?: "") +
                (entry.resolution?.let { " (resolved: $it)" } ?: " (unresolved)")
        }
    }

    /**
     * Build final summary for a team task from all member executions.
     */
    private fun buildTeamFinalSummary(
        memberExecutions: List<TeamMemberExecution>,
        roundRecords: List<TeamRoundRecord>
    ): String {
        val completedExecs = memberExecutions.filter { it.status == MemberStatus.COMPLETED }
        val escalatedExecs = memberExecutions.filter { it.status == MemberStatus.ESCALATED }

        val sb = StringBuilder()
        sb.append("Team coordination completed in ${roundRecords.size} round(s).\n\n")

        if (completedExecs.isNotEmpty()) {
            sb.append("## Completed Tasks\n")
            for (exec in completedExecs) {
                sb.append("- **${exec.memberId}** (round ${exec.round}): ${exec.summary?.take(300) ?: "(no summary)"}\n")
            }
        }

        if (escalatedExecs.isNotEmpty()) {
            sb.append("\n## Escalated Issues\n")
            for (exec in escalatedExecs) {
                sb.append("- **${exec.memberId}** (round ${exec.round}): ${exec.escalationReason ?: "Unknown"}\n")
            }
        }

        return sb.toString()
    }

}

/**
 * Encapsulates all mutable state for a single TEAM task execution.
 * Eliminates parameter drilling between runTeam() and helper methods.
 */
internal class TeamExecutionState {
    val escalationHistory = mutableListOf<EscalationEntry>()
    val memberExecutions = mutableListOf<TeamMemberExecution>()
    val roundRecords = mutableListOf<TeamRoundRecord>()
    val leaderAnalyses = mutableListOf<String>()
    /** Full prompts sent to the Leader per iteration — index 0 = iteration 1 (planning), 1+ = coordination. */
    val leaderPrompts = mutableListOf<String>()
    val delegationHistory = mutableListOf<DelegationRecord>()
    val total = TokenCounters()
    var iterations = 0
    val runningJobs = mutableMapOf<String, Job>()
    val runningMemberIds = mutableSetOf<String>()
}

/**
 * Mutable token counter accumulator. Eliminates repetitive field-by-field
 * token summation across leader, member, and round result aggregation.
 */
internal class TokenCounters {
    var input: Long = 0L
    var output: Long = 0L
    var cacheRead: Long = 0L
    var cacheWrite: Long = 0L
    var duration: Long = 0L

    operator fun plusAssign(result: WorkerResult) {
        input += result.inputTokens
        output += result.outputTokens
        cacheRead += result.cacheReadTokens
        cacheWrite += result.cacheWriteTokens
        duration += result.durationMs
    }

    operator fun plusAssign(s: Snapshot) {
        input += s.input
        output += s.output
        cacheRead += s.cacheRead
        cacheWrite += s.cacheWrite
        duration += s.duration
    }

    fun snapshot() = Snapshot(input, output, cacheRead, cacheWrite, duration)

    data class Snapshot(
        val input: Long,
        val output: Long,
        val cacheRead: Long,
        val cacheWrite: Long,
        val duration: Long,
    )

    companion object {
        fun from(result: WorkerResult) = TokenCounters().apply {
            input = result.inputTokens
            output = result.outputTokens
            cacheRead = result.cacheReadTokens
            cacheWrite = result.cacheWriteTokens
            duration = result.durationMs
        }
    }
}

/**
 * Internal result of executing one team member's assignment.
 */
internal data class MemberExecutionResult(
    val memberId: String,
    val execution: TeamMemberExecution,
    val tokens: TokenCounters = TokenCounters(),
)

/**
 * Records a single task delegation or reassignment made by the Leader in a specific round.
 * Used to build the `delegation_history` template variable for Leader cross-round memory.
 */
internal data class DelegationRecord(
    val round: Int,
    val memberId: String,
    val assignment: String,
)
