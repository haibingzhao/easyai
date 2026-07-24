package com.easy.easyai.swarm.runtime

import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.swarm.event.SwarmEventBridge
import com.easy.easyai.swarm.model.*
import com.easy.easyai.swarm.store.SwarmRunStore
import com.easy.easyai.swarm.tool.EscalationCompletionCheck
import com.easy.easyai.swarm.tool.EscalationResult
import com.easy.easyai.swarm.tool.EscalationTool
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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
    val consultationRegistry: TeamConsultationRegistry = TeamConsultationRegistry(),
    private val store: SwarmRunStore? = null,
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
    "suspendAndAssist": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "blockedMemberId": { "type": "string" },
          "helperMemberId": { "type": "string" },
          "assistTask": { "type": "string" }
        },
        "required": ["blockedMemberId", "helperMemberId", "assistTask"],
        "additionalProperties": false
      }
    },
    "suspendAndConsultUser": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "blockedMemberId": { "type": "string" },
          "question": { "type": "string" },
          "options": { "type": "array", "items": { "type": "string" } }
        },
        "required": ["blockedMemberId", "question"],
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
     * Resume support: When task.roundRecords is non-empty (loaded by SwarmRuntime.resume()),
     * rebuilds state from history and continues from the last completed round.
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

        val leaderSpec = run.agents.find { it.id == teamSpec.leader }
            ?: return WorkerResult(SwarmTaskStatus.FAILED, "", error = "Leader '${teamSpec.leader}' not found in run agents")

        // Detect resume: roundRecords populated by SwarmRuntime.resume() from DB
        val isResume = task.roundRecords.isNotEmpty()
        val state = TeamExecutionState()
        if (isResume) {
            rebuildStateFromHistory(state, task)
            // Restore taskSummaries from completed member executions for downstream tasks
            for (exec in task.memberExecutions.filter { it.status == MemberStatus.COMPLETED && it.summary != null }) {
                taskSummaries["${task.id}.${exec.memberId}"] = exec.summary!!
            }
            logger.info("TEAM task '{}' resuming from round {} ({} member executions restored)",
                task.id, state.iterations, state.memberExecutions.size)
        }

        val resultChannel = Channel<MemberExecutionResult>(Channel.UNLIMITED)
        val memberTimeoutMs = resolveMemberTimeoutMs(teamSpec)
        val inputFromVars = workerExecutor.resolveInputFrom(task, taskSummaries)

        eventBridge.onTeamStarted(run, task, teamSpec)

        supervisorScope {
            try {
                var resumeCompleted = false
                if (!isResume) {
                    // Phase 1: Leader initial planning (skip on resume)
                    val initialDecision = invokeLeaderPlanning(
                        teamSpec, leaderSpec, run, task, taskSummaries, inputFromVars, runContext, state
                    )

                    launchMembers(
                        initialDecision, resultChannel, state, memberTimeoutMs,
                        run, task, taskSummaries, inputFromVars, abortSignal, runContext, teamSpec
                    )
                } else {
                    // Resume: invoke Leader coordination with empty batch to re-evaluate and continue
                    val resumeDecision = invokeLeaderCoordination(
                        emptyList(), state, teamSpec, leaderSpec, run, task,
                        taskSummaries, inputFromVars, runContext
                    )
                    val resumeRound = state.iterations + 1
                    val record = buildIterationRecord(resumeRound, resumeDecision, emptyList(), state)
                    state.roundRecords.add(record)
                    persistTeamHistorySafely(run, task, state)

                    if (resumeDecision.isComplete) {
                        logger.info("Team task '{}' completed on resume (leader confirmed all objectives met)", task.id)
                        resumeCompleted = true
                    } else {
                        launchMembers(
                            resumeDecision, resultChannel, state, memberTimeoutMs,
                            run, task, taskSummaries, inputFromVars, abortSignal, runContext, teamSpec,
                            iteration = resumeRound + 1
                        )
                    }
                    state.iterations = resumeRound
                }

                // Phase 2: Reactive Loop (skipped when resume leader signals immediate completion)
                while (!resumeCompleted && state.iterations < teamSpec.maxIterations && !abortSignal()) {
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
                    delay(2000.milliseconds)
                    val batch = mutableListOf(first)
                    while (true) {
                        val next = resultChannel.tryReceive().getOrNull() ?: break
                        batch.add(next)
                    }

                    // Process batch: update state, emit events, update taskSummaries
                    processResultBatch(batch, state, run, task, taskSummaries,
                        resultChannel, runContext, abortSignal
                    )

                    val decision = invokeLeaderCoordination(
                        batch, state, teamSpec, leaderSpec, run, task,
                        taskSummaries, inputFromVars, runContext
                    )

                    // Build and persist iteration record (round = iterations + 1, matching launchMembers)
                    val roundNumber = state.iterations + 1
                    val record = buildIterationRecord(roundNumber, decision, batch, state)
                    state.roundRecords.add(record)
                    eventBridge.onTeamRoundCompleted(run, task, roundNumber, record)

                    // Incremental persistence: save team history after each round for resume support
                    persistTeamHistorySafely(run, task, state)

                    if (decision.isComplete) {
                        logger.info("Team task '{}' completed at iteration {} (leader signaled complete)",
                            task.id, roundNumber)
                        break
                    }
                    if (state.runningMemberIds.isEmpty() && decision.newTasks.isEmpty() &&
                        decision.reassignments.isEmpty() && state.suspendedMembers.isEmpty() &&
                        state.pendingConsultations.isEmpty()) {
                        logger.info("Team task '{}' stalled at iteration {} (no running members, no new tasks, no suspended)",
                            task.id, roundNumber)
                        break
                    }

                    launchMembers(
                        decision, resultChannel, state, memberTimeoutMs,
                        run, task, taskSummaries, inputFromVars, abortSignal, runContext, teamSpec,
                        iteration = roundNumber + 1
                    )
                    state.iterations = roundNumber
                }
            } finally {
                // Phase 3: Cancel running members, pending consultations, and close channel
                state.runningJobs.values.forEach { it.cancel() }
                // Cancel pending consultation deferreds so watcher coroutines exit promptly
                state.pendingConsultations.forEach { (memberId, deferred) ->
                    deferred.cancel()
                    consultationRegistry.remove(run.id, task.id, memberId)
                }
                state.pendingConsultations.clear()
                resultChannel.close()
                // Final persistence: ensure team history is saved even on cancellation/abnormal exit
                withContext(NonCancellable) {
                    persistTeamHistorySafely(run, task, state)
                }
            }
        }

        // Final summary
        val finalSummary = buildTeamFinalSummary(state.memberExecutions, state.roundRecords)
        eventBridge.onTeamCompleted(run, task, finalSummary)

        val finalStatus = when {
            state.roundRecords.isEmpty() -> SwarmTaskStatus.FAILED
            state.escalationHistory.any { it.resolution == null && it.reassignedTo == null } -> SwarmTaskStatus.FAILED
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
        teamSpec: TeamSpec? = null,
        iteration: Int = state.iterations + 1,
    ) {

        // Emit delegation events and launch new tasks
        for (dynamicTask in decision.newTasks) {
            eventBridge.onTeamDelegated(run, task, dynamicTask.memberId, dynamicTask.assignment)
            val job = launch {
                val result = executeMemberWithTimeout(
                    dynamicTask.memberId, dynamicTask.assignment, iteration, memberTimeoutMs,
                    run, task, taskSummaries, inputFromVars, abortSignal, runContext, state
                )
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
                val result = executeMemberWithTimeout(
                    reassignment.toMemberId, reassignAssignment, iteration, memberTimeoutMs,
                    run, task, taskSummaries, inputFromVars, abortSignal, runContext, state
                )
                channel.send(result)
            }
            state.runningJobs[reassignment.toMemberId] = job
            state.runningMemberIds.add(reassignment.toMemberId)
        }

        // Handle SUSPEND_AND_ASSIST: suspend blocked member, launch helper
        for (spec in decision.suspendAndAssist) {
            if (state.suspendedMembers.containsKey(spec.blockedMemberId)) {
                logger.info("Team task '{}': member '{}' already suspended, skipping", task.id, spec.blockedMemberId)
                continue
            }
            // Verify sessionId exists BEFORE cancelling the job (avoid losing the member)
            val sessionId = state.memberSessionIds[spec.blockedMemberId]
            if (sessionId == null) {
                logger.warn("Team task '{}': no sessionId for blocked member '{}', cannot suspend",
                    task.id, spec.blockedMemberId)
                continue
            }
            // Suspend the blocked member (cancel job, record state, emit event)
            val originalAssignment = suspendBlockedMember(
                state, spec.blockedMemberId, sessionId, spec.assistTask, iteration, run, task
            )
            state.memberExecutions.add(TeamMemberExecution(
                memberId = spec.blockedMemberId,
                round = iteration,
                assignment = originalAssignment,
                status = MemberStatus.SUSPENDED,
                escalationReason = spec.assistTask,
            ))

            // Launch helper member to resolve the blocking issue
            eventBridge.onTeamDelegated(run, task, spec.helperMemberId, spec.assistTask)
            val helperJob = launch {
                val helperResult = executeMemberWithTimeout(
                    spec.helperMemberId, spec.assistTask, iteration, memberTimeoutMs,
                    run, task, taskSummaries, inputFromVars, abortSignal, runContext, state
                )
                // Tag result so processResultBatch knows to trigger resume
                channel.send(helperResult.copy(assistForMemberId = spec.blockedMemberId))
            }
            state.runningJobs[spec.helperMemberId] = helperJob
            state.runningMemberIds.add(spec.helperMemberId)
        }

        // Handle SUSPEND_AND_CONSULT_USER: suspend blocked member, wait for user answer
        for (spec in decision.suspendAndConsultUser) {
            if (state.suspendedMembers.containsKey(spec.blockedMemberId)) {
                logger.info("Team task '{}': member '{}' already suspended, skipping", task.id, spec.blockedMemberId)
                continue
            }
            // Verify sessionId exists BEFORE cancelling the job (avoid losing the member)
            val sessionId = state.memberSessionIds[spec.blockedMemberId]
            if (sessionId == null) {
                logger.warn("Team task '{}': no sessionId for blocked member '{}', cannot suspend for consultation",
                    task.id, spec.blockedMemberId)
                continue
            }
            // Suspend the blocked member (cancel job, record state, emit event)
            val originalAssignment = suspendBlockedMember(
                state, spec.blockedMemberId, sessionId, spec.question, iteration, run, task
            )
            eventBridge.onTeamUserConsultationNeeded(run, task, spec.blockedMemberId, spec.question, spec.options)
            state.memberExecutions.add(TeamMemberExecution(
                memberId = spec.blockedMemberId,
                round = iteration,
                assignment = originalAssignment,
                status = MemberStatus.SUSPENDED,
                escalationReason = spec.question,
            ))

            // Create deferred for user answer and launch watcher coroutine
            val deferred = CompletableDeferred<String>()
            state.pendingConsultations[spec.blockedMemberId] = deferred
            consultationRegistry.register(run.id, task.id, spec.blockedMemberId, deferred)

            val consultTimeoutMs = ((teamSpec?.consultationTimeoutSeconds ?: 0).takeIf { it > 0 } ?: 300) * 1000L
            val watcherJob = launch {
                val answer = withTimeoutOrNull(consultTimeoutMs.milliseconds) { deferred.await() }
                state.pendingConsultations.remove(spec.blockedMemberId)
                consultationRegistry.remove(run.id, task.id, spec.blockedMemberId)
                val suspended = state.suspendedMembers[spec.blockedMemberId] ?: return@launch

                if (answer != null && answer != TeamConsultationRegistry.REJECT_MARKER) {
                    // User answered — resume the member
                    val resumeResult = resumeSuspendedMember(
                        suspended, answer, run, task, runContext, abortSignal, state
                    )
                    trySendToChannel(channel, resumeResult)
                } else {
                    // Timeout or rejected — mark as escalated for Leader to re-decide
                    val reason = if (answer == TeamConsultationRegistry.REJECT_MARKER) {
                        "User rejected/skipped the consultation"
                    } else {
                        "User consultation timed out after ${consultTimeoutMs / 1000}s"
                    }
                    logger.warn("Team task '{}': consultation for member '{}' ended: {}",
                        task.id, spec.blockedMemberId, reason)
                    state.suspendedMembers.remove(spec.blockedMemberId)
                    trySendToChannel(channel, MemberExecutionResult(
                        memberId = spec.blockedMemberId,
                        execution = TeamMemberExecution(
                            memberId = spec.blockedMemberId,
                            round = iteration,
                            assignment = originalAssignment,
                            status = MemberStatus.ESCALATED,
                            escalationReason = reason,
                        ),
                    ))
                }
            }
            state.runningJobs["consultation:${spec.blockedMemberId}"] = watcherJob
        }
    }

    /**
     * Process a batch of member execution results: update state, emit events, update task summaries.
     * Also handles assist-triggered resume: when a helper completes for a suspended member,
     * launches a coroutine to resume the suspended member and send result to channel.
     */
    private suspend fun CoroutineScope.processResultBatch(
        batch: List<MemberExecutionResult>,
        state: TeamExecutionState,
        run: SwarmRun,
        task: SwarmTask,
        taskSummaries: MutableMap<String, String>,
        channel: Channel<MemberExecutionResult>? = null,
        runContext: RunContext? = null,
        abortSignal: (() -> Boolean)? = null,
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

                // If this was a helper for a suspended member, trigger resume
                val assistFor = result.assistForMemberId
                if (assistFor != null && channel != null && runContext != null && abortSignal != null) {
                    val suspended = state.suspendedMembers[assistFor]
                    if (suspended != null) {
                        val resolutionInfo = memberExec.summary ?: "Helper completed the assist task."
                        val resumeJob = launch {
                            val resumeResult = resumeSuspendedMember(
                                suspended, resolutionInfo, run, task, runContext, abortSignal,
                                state
                            )
                            trySendToChannel(channel, resumeResult)
                        }
                        state.runningJobs["resume:$assistFor"] = resumeJob
                    }
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
        runContext: RunContext,
        state: TeamExecutionState? = null,
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

        // Track session info for potential suspend/resume
        memberResult.sessionId?.let { sid -> state?.memberSessionIds?.put(memberId, sid) }
        state?.memberAssignments?.put(memberId, assignment)

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

    /**
     * Execute a team member with timeout and clean up running state afterwards.
     * Combines [executeTeamMember] + [timeoutMemberResult] fallback + running state removal.
     */
    private suspend fun executeMemberWithTimeout(
        memberId: String,
        assignment: String,
        iteration: Int,
        memberTimeoutMs: Long,
        run: SwarmRun,
        task: SwarmTask,
        taskSummaries: MutableMap<String, String>,
        inputFromVars: Map<String, String>,
        abortSignal: () -> Boolean,
        runContext: RunContext,
        state: TeamExecutionState,
    ): MemberExecutionResult {
        val result = withTimeoutOrNull(memberTimeoutMs.milliseconds) {
            executeTeamMember(
                memberId, assignment, iteration, run, task,
                taskSummaries, inputFromVars, abortSignal, runContext, state
            )
        } ?: timeoutMemberResult(memberId, iteration, memberTimeoutMs)
        state.runningJobs.remove(memberId)
        state.runningMemberIds.remove(memberId)
        return result
    }

    /**
     * Resume a suspended member by loading its session history and continuing execution.
     * Called after a helper completes (SUSPEND_AND_ASSIST) or user answers (SUSPEND_AND_CONSULT_USER).
     */
    private suspend fun resumeSuspendedMember(
        suspended: SuspendedMemberInfo,
        resolutionInfo: String,
        run: SwarmRun,
        task: SwarmTask,
        runContext: RunContext,
        abortSignal: () -> Boolean,
        state: TeamExecutionState? = null,
    ): MemberExecutionResult {
        val memberSpec = run.agents.find { it.id == suspended.memberId }
        if (memberSpec == null) {
            logger.warn("Team task '{}': cannot resume member '{}', not found in agents", task.id, suspended.memberId)
            state?.suspendedMembers?.remove(suspended.memberId)
            return MemberExecutionResult(
                memberId = suspended.memberId,
                execution = TeamMemberExecution(
                    memberId = suspended.memberId,
                    round = suspended.suspendedAtRound,
                    assignment = suspended.originalAssignment,
                    status = MemberStatus.ESCALATED,
                    escalationReason = "Cannot resume: member '${suspended.memberId}' not found",
                ),
            )
        }

        val resumeMessage = buildString {
            appendLine("Your blocking issue has been resolved.")
            appendLine("Resolution: $resolutionInfo")
            appendLine()
            appendLine("Please continue your original task: ${suspended.originalAssignment}")
        }

        // Inject EscalationTool for the resumed execution
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

        val resumeResult = workerExecutor.resumeWorker(
            agentSpec = memberSpec,
            sessionId = suspended.sessionId,
            resumeMessage = resumeMessage,
            run = run,
            task = task,
            runContext = runContext,
            abortSignal = abortSignal,
            additionalTools = listOf(escalationTool),
            additionalCompletionChecks = listOf(completionCheck),
        )

        // Remove from suspended
        state?.suspendedMembers?.remove(suspended.memberId)

        val escalationReason = escalationRef.get()?.reason
        val status = if (escalationReason != null || resumeResult.status == SwarmTaskStatus.FAILED) {
            MemberStatus.ESCALATED
        } else {
            MemberStatus.COMPLETED
        }

        eventBridge.onTeamMemberResumed(run, task, suspended.memberId,
            if (escalationReason != null) "escalated_after_resume" else "completed")

        return MemberExecutionResult(
            memberId = suspended.memberId,
            execution = TeamMemberExecution(
                memberId = suspended.memberId,
                round = suspended.suspendedAtRound,
                assignment = suspended.originalAssignment,
                status = status,
                summary = if (status == MemberStatus.COMPLETED) resumeResult.summary else null,
                escalationReason = escalationReason ?: resumeResult.error,
                inputTokens = resumeResult.inputTokens,
                outputTokens = resumeResult.outputTokens,
            ),
            tokens = TokenCounters.from(resumeResult),
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
        appendLine("  \"suspendAndAssist\": [],")
        appendLine("  \"suspendAndConsultUser\": [],")
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
        } else if (state.iterations > 0) {
            appendLine("(Resumed session — previous execution was interrupted. Review history below and decide next steps.)")
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

        // Suspended Members (capped at 5)
        appendLine("### Suspended Members")
        val suspendedEntries = state.suspendedMembers.entries.toList().takeLast(5)
        if (suspendedEntries.isNotEmpty()) {
            for ((memberId, info) in suspendedEntries) {
                val consultPending = state.pendingConsultations.containsKey(memberId)
                val statusLabel = if (consultPending) "WAITING_USER_ANSWER" else "WAITING_ASSIST"
                appendLine("- **$memberId** [$statusLabel] (round ${info.suspendedAtRound}): ${info.blockReason.take(200)}")
            }
        } else {
            appendLine("(No suspended members)")
        }
        appendLine()

        appendLine("### Your Decision")
        appendLine("Review the trigger events and current state above. Handle any escalations,")
        appendLine("reassign work if needed, and decide whether the team has completed all objectives.")
        appendLine("You may choose to wait for running members or proceed with new assignments.")
        appendLine("If a member is blocked, you can:")
        appendLine("- Use \"suspendAndAssist\" to suspend it and assign a helper to resolve the issue")
        appendLine("- Use \"suspendAndConsultUser\" to suspend it and ask the user a question")
        appendLine("- Use \"reassignments\" to terminate and reassign its task to another member")
        appendLine("IMPORTANT: Do NOT issue suspendAndAssist or suspendAndConsultUser for members already listed under \"Suspended Members\" above — they are already being handled.")
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
        appendLine("  \"suspendAndAssist\": [")
        appendLine("    { \"blockedMemberId\": \"blocked-member\", \"helperMemberId\": \"helper\", \"assistTask\": \"What helper should do\" }")
        appendLine("  ],")
        appendLine("  \"suspendAndConsultUser\": [")
        appendLine("    { \"blockedMemberId\": \"blocked-member\", \"question\": \"Question for the user\", \"options\": [\"Option A\", \"Option B\"] }")
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

    /**
     * Suspend a blocked member: cancel its running job, record suspension state, and emit event.
     * Returns the member's original assignment.
     */
    private suspend fun suspendBlockedMember(
        state: TeamExecutionState,
        memberId: String,
        sessionId: String,
        blockReason: String,
        iteration: Int,
        run: SwarmRun,
        task: SwarmTask,
    ): String {
        val blockedJob = state.runningJobs[memberId]
        if (blockedJob != null && blockedJob.isActive) {
            blockedJob.cancelAndJoin()
        }
        state.runningJobs.remove(memberId)
        state.runningMemberIds.remove(memberId)
        val originalAssignment = state.memberAssignments[memberId] ?: ""
        state.suspendedMembers[memberId] = SuspendedMemberInfo(
            memberId = memberId,
            sessionId = sessionId,
            originalAssignment = originalAssignment,
            blockReason = blockReason,
            suspendedAtRound = iteration,
        )
        eventBridge.onTeamMemberSuspended(run, task, memberId, blockReason)
        return originalAssignment
    }

    /**
     * Defensive channel send: silently discards the result if the channel is already closed.
     * Prevents uncaught ClosedSendChannelException in fire-and-forget coroutines (watcher/resume).
     */
    private suspend fun trySendToChannel(channel: Channel<MemberExecutionResult>, result: MemberExecutionResult) {
        try {
            channel.send(result)
        } catch (_: kotlinx.coroutines.channels.ClosedSendChannelException) {
            logger.warn("Channel closed, discarding result for member '{}'", result.memberId)
        }
    }

    /**
     * Persist team history (member executions, round records, escalation history) safely.
     * Ignores exceptions to avoid disrupting the execution loop.
     * Called incrementally after each round and in the finally block for crash resilience.
     */
    private suspend fun persistTeamHistorySafely(run: SwarmRun, task: SwarmTask, state: TeamExecutionState) {
        if (store == null) return
        try {
            // Sync state to task fields for persistence
            task.memberExecutions = state.memberExecutions.toList()
            task.roundRecords = state.roundRecords.toList()
            task.escalationHistory = state.escalationHistory.toList()
            store.saveTeamHistory(run.id, task.id, task.memberExecutions, task.roundRecords)
            if (state.escalationHistory.isNotEmpty()) {
                store.saveEscalationHistory(run.id, task.id, task.escalationHistory)
            }
        } catch (e: Exception) {
            logger.warn("Failed to persist team history for task '{}': {}", task.id, e.message)
        }
    }

    /**
     * Rebuild [TeamExecutionState] from persisted task history for resume support.
     *
     * Restores: iterations, memberExecutions, roundRecords, escalationHistory,
     * leaderAnalyses, leaderPrompts, delegationHistory, memberAssignments, token counters.
     *
     * Not restorable (transient/coroutine-bound): runningJobs, runningMemberIds,
     * suspendedMembers, pendingConsultations, memberSessionIds.
     */
    private fun rebuildStateFromHistory(state: TeamExecutionState, task: SwarmTask) {
        // Restore round records and derive iteration count
        state.roundRecords.addAll(task.roundRecords)
        state.iterations = task.roundRecords.maxOfOrNull { it.round } ?: 0

        // Restore member executions and token counters
        state.memberExecutions.addAll(task.memberExecutions)
        for (exec in task.memberExecutions) {
            state.total.input += exec.inputTokens
            state.total.output += exec.outputTokens
            // Restore last known assignment per member
            state.memberAssignments[exec.memberId] = exec.assignment
        }

        // Restore escalation history
        state.escalationHistory.addAll(task.escalationHistory)

        // Restore leader analyses and prompts from round records
        for (record in task.roundRecords) {
            state.leaderAnalyses.add("Round ${record.round}: ${record.leaderAnalysis.take(500)}")
            // Use placeholder for null prompts to maintain index alignment with round numbers
            state.leaderPrompts.add(record.leaderPrompt ?: "(prompt not available)")
            // Rebuild delegation history from round records
            for (memberId in record.delegatedMembers) {
                state.delegationHistory.add(DelegationRecord(record.round, memberId, "(from history)"))
            }
        }
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
    val runningJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    val runningMemberIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** Members suspended awaiting assist result or user consultation. Key = memberId. */
    val suspendedMembers = java.util.concurrent.ConcurrentHashMap<String, SuspendedMemberInfo>()
    /** Pending user consultation deferreds. Key = "memberId". */
    val pendingConsultations = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<String>>()
    /** Last known sessionId for each member (from their most recent execution). Key = memberId. */
    val memberSessionIds = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Last known assignment for each member. Key = memberId. */
    val memberAssignments = java.util.concurrent.ConcurrentHashMap<String, String>()
}

/**
 * Tracks a suspended team member awaiting resolution (assist result or user answer).
 */
internal data class SuspendedMemberInfo(
    val memberId: String,
    val sessionId: String,
    val originalAssignment: String,
    val blockReason: String,
    val suspendedAtRound: Int,
)

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
    /** If this member was a helper for a suspended member, the blocked member's ID. */
    val assistForMemberId: String? = null,
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
