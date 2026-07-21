package com.easy.easyai.swarm.event

import com.easy.easyai.swarm.model.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge
import org.slf4j.LoggerFactory

/**
 * Event bridge for swarm execution events using Kotlin SharedFlow.
 *
 * Emits structured events that can be consumed by:
 * - SSE stream (frontend real-time updates)
 * - Logging/observability
 * - Persistence (swarm_runs event log)
 *
 * Events are emitted as [SwarmEvent] objects with a type, timestamp, and metadata.
 * Consumers collect from [events] or [terminalEvents] flows.
 */
class SwarmEventBridge {
    private val logger = LoggerFactory.getLogger(javaClass)

    // Main event flow: progress events (replay=0, buffer=256, drop oldest on overflow)
    private val _events = MutableSharedFlow<SwarmEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Terminal event flow: final state events (replay=1, buffer=1 for non-blocking emit)
    private val _terminalEvents = MutableSharedFlow<SwarmEvent>(
        replay = 1,
        extraBufferCapacity = 1
    )

    /**
     * Main event flow for progress events (task_started, task_completed, layer_started, etc.)
     */
    fun events(): SharedFlow<SwarmEvent> = _events

    /**
     * Terminal event flow for final state events (run_completed, run_failed, run_paused, etc.)
     * Late subscribers receive the last terminal event due to replay=1.
     */
    fun terminalEvents(): SharedFlow<SwarmEvent> = _terminalEvents

    /**
     * Combined event flow filtered by runId.
     * Merges both main and terminal events for a specific run.
     */
    fun eventsForRun(runId: String): Flow<SwarmEvent> {
        return merge(_events, _terminalEvents).filter { it.runId == runId }
    }

    /**
     * Terminal event flow filtered by runId.
     */
    fun terminalEventsForRun(runId: String): Flow<SwarmEvent> {
        return _terminalEvents.filter { it.runId == runId }
    }

    // --- DAG-level events ---

    suspend fun onRunStarted(run: SwarmRun) {
        emit(SwarmEvent(
            type = "swarm_run_started",
            runId = run.id,
            data = mapOf(
                "presetName" to run.presetName,
                "title" to run.title,
                "taskCount" to run.tasks.size,
                "agentCount" to run.agents.size
            )
        ))
    }

    suspend fun onLayerStarted(run: SwarmRun, layerIndex: Int, taskIds: List<String>) {
        emit(SwarmEvent(
            type = "swarm_layer_started",
            runId = run.id,
            data = mapOf(
                "layerIndex" to layerIndex,
                "taskIds" to taskIds
            )
        ))
    }

    suspend fun onTaskStarted(run: SwarmRun, task: SwarmTask) {
        emit(SwarmEvent(
            type = "swarm_task_started",
            runId = run.id,
            taskId = task.id,
            data = mapOf(
                "taskType" to task.type.name,
                "startedAt" to (task.startedAt?.toEpochMilli() ?: System.currentTimeMillis())
            )
        ))
    }

    suspend fun onTaskProgress(
        run: SwarmRun,
        task: SwarmTask,
        iteration: Int,
        inputTokens: Long,
        outputTokens: Long
    ) {
        emit(SwarmEvent(
            type = "swarm_task_progress",
            runId = run.id,
            taskId = task.id,
            data = mapOf(
                "iteration" to iteration,
                "inputTokens" to inputTokens,
                "outputTokens" to outputTokens
            )
        ))
    }

    suspend fun onTaskCompleted(run: SwarmRun, task: SwarmTask) {
        emit(SwarmEvent(
            type = "swarm_task_completed",
            runId = run.id,
            taskId = task.id,
            data = mapOf(
                "summary" to (task.summary ?: ""),
                "iterations" to task.workerIterations,
                "inputTokens" to task.inputTokens,
                "outputTokens" to task.outputTokens,
                "cacheReadTokens" to task.cacheReadTokens,
                "cacheWriteTokens" to task.cacheWriteTokens,
                "durationMs" to computeDuration(task),
                "workerDurationMs" to task.durationMs
            )
        ))
    }

    suspend fun onTaskFailed(run: SwarmRun, task: SwarmTask) {
        emit(SwarmEvent(
            type = "swarm_task_failed",
            runId = run.id,
            taskId = task.id,
            data = mapOf(
                "status" to task.status.name,
                "error" to (task.error ?: "unknown"),
                "inputTokens" to task.inputTokens,
                "outputTokens" to task.outputTokens,
                "cacheReadTokens" to task.cacheReadTokens,
                "cacheWriteTokens" to task.cacheWriteTokens,
                "workerDurationMs" to task.durationMs
            )
        ))
    }

    suspend fun onTaskBlocked(run: SwarmRun, task: SwarmTask) {
        emit(SwarmEvent(
            type = "swarm_task_blocked",
            runId = run.id,
            taskId = task.id,
            data = mapOf(
                "blockedBy" to task.blockedBy
            )
        ))
    }

    suspend fun onTaskCancelled(runId: String, task: SwarmTask) {
        emit(SwarmEvent(
            type = "swarm_task_cancelled",
            runId = runId,
            taskId = task.id,
            data = mapOf("taskId" to task.id)
        ))
    }

    suspend fun onRunCompleted(run: SwarmRun) {
        emit(SwarmEvent(
            type = "swarm_run_completed",
            runId = run.id,
            data = mapOf(
                "status" to run.status.name,
                "totalInputTokens" to run.totalInputTokens,
                "totalOutputTokens" to run.totalOutputTokens,
                "totalCacheReadTokens" to run.totalCacheReadTokens,
                "totalCacheWriteTokens" to run.totalCacheWriteTokens,
                "totalDurationMs" to run.totalDurationMs,
                "error" to (run.error ?: "")
            )
        ))
    }

    suspend fun onRunPaused(runId: String) {
        emit(SwarmEvent(
            type = "swarm_run_paused",
            runId = runId,
            data = emptyMap()
        ))
    }

    suspend fun onRunCancelled(runId: String) {
        emit(SwarmEvent(
            type = "swarm_run_cancelled",
            runId = runId,
            data = emptyMap()
        ))
    }

    suspend fun onRunFailed(runId: String, error: String) {
        emit(SwarmEvent(
            type = "swarm_run_failed",
            runId = runId,
            data = mapOf("error" to error)
        ))
    }

    // --- Deliberation-specific events ---

    suspend fun onDeliberationStarted(run: SwarmRun, task: SwarmTask, spec: DeliberationSpec) {
        emit(SwarmEvent(
            type = "swarm_deliberation_started",
            runId = run.id,
            taskId = task.id,
            data = mapOf(
                "participants" to spec.participants,
                "judge" to spec.judge,
                "maxRounds" to spec.maxRounds,
                "order" to spec.order.name
            )
        ))
    }

    suspend fun onDeliberationRoundStarted(run: SwarmRun, task: SwarmTask, round: Int) {
        emit(SwarmEvent(
            type = "swarm_deliberation_round_started",
            runId = run.id,
            taskId = task.id,
            data = mapOf("round" to round)
        ))
    }

    suspend fun onDeliberationSpeakerDone(
        run: SwarmRun,
        task: SwarmTask,
        speakerId: String,
        round: Int,
        responseSummary: String
    ) {
        emit(SwarmEvent(
            type = "swarm_deliberation_speaker_done",
            runId = run.id,
            taskId = task.id,
            data = mapOf(
                "speakerId" to speakerId,
                "round" to round,
                "summary" to responseSummary.take(500)
            )
        ))
    }

    suspend fun onDeliberationConverged(run: SwarmRun, task: SwarmTask, round: Int, speakerId: String) {
        emit(SwarmEvent(
            type = "swarm_deliberation_converged",
            runId = run.id,
            taskId = task.id,
            data = mapOf(
                "round" to round,
                "convergedBy" to speakerId
            )
        ))
    }

    suspend fun onDeliberationJudging(run: SwarmRun, task: SwarmTask) {
        emit(SwarmEvent(
            type = "swarm_deliberation_judging",
            runId = run.id,
            taskId = task.id,
            data = emptyMap()
        ))
    }

    suspend fun onDeliberationCompleted(run: SwarmRun, task: SwarmTask, verdict: String) {
        emit(SwarmEvent(
            type = "swarm_deliberation_completed",
            runId = run.id,
            taskId = task.id,
            data = mapOf(
                "verdict" to verdict.take(1000)
            )
        ))
    }

    // --- Team-specific events ---

    suspend fun onTeamStarted(run: SwarmRun, task: SwarmTask, teamSpec: TeamSpec) {
        emit(SwarmEvent(
            type = "swarm_team_started",
            runId = run.id,
            taskId = task.id,
            data = mapOf(
                "leader" to teamSpec.leader,
                "members" to teamSpec.members,
                "maxIterations" to teamSpec.maxIterations,
                "maxDynamicTasks" to teamSpec.maxDynamicTasks
            )
        ))
    }

    suspend fun onTeamRoundStarted(run: SwarmRun, task: SwarmTask, round: Int) {
        emit(SwarmEvent(
            type = "swarm_team_round_started",
            runId = run.id,
            taskId = task.id,
            data = mapOf("round" to round)
        ))
    }

    suspend fun onTeamDelegated(run: SwarmRun, task: SwarmTask, memberId: String, assignment: String) {
        emit(SwarmEvent(
            type = "swarm_team_delegated",
            runId = run.id,
            taskId = task.id,
            data = mapOf(
                "memberId" to memberId,
                "assignment" to assignment.take(500)
            )
        ))
    }

    suspend fun onTeamMemberCompleted(run: SwarmRun, task: SwarmTask, memberId: String, summary: String) {
        emit(SwarmEvent(
            type = "swarm_team_member_completed",
            runId = run.id,
            taskId = task.id,
            data = mapOf(
                "memberId" to memberId,
                "summary" to summary.take(500)
            )
        ))
    }

    suspend fun onTeamEscalated(run: SwarmRun, task: SwarmTask, memberId: String, issue: String) {
        emit(SwarmEvent(
            type = "swarm_team_escalated",
            runId = run.id,
            taskId = task.id,
            data = mapOf(
                "memberId" to memberId,
                "issue" to issue.take(500)
            )
        ))
    }

    suspend fun onTeamResolved(run: SwarmRun, task: SwarmTask, memberId: String, resolution: String) {
        emit(SwarmEvent(
            type = "swarm_team_resolved",
            runId = run.id,
            taskId = task.id,
            data = mapOf(
                "memberId" to memberId,
                "resolution" to resolution.take(500)
            )
        ))
    }

    suspend fun onTeamRoundCompleted(run: SwarmRun, task: SwarmTask, round: Int, roundRecord: TeamRoundRecord) {
        emit(SwarmEvent(
            type = "swarm_team_round_completed",
            runId = run.id,
            taskId = task.id,
            data = mapOf(
                "round" to round,
                "leaderAnalysis" to roundRecord.leaderAnalysis.take(500),
                "delegatedMembers" to roundRecord.delegatedMembers,
                "completedMembers" to roundRecord.completedMembers,
                "escalations" to roundRecord.escalations
            )
        ))
    }

    suspend fun onTeamCompleted(run: SwarmRun, task: SwarmTask, summary: String) {
        emit(SwarmEvent(
            type = "swarm_team_completed",
            runId = run.id,
            taskId = task.id,
            data = mapOf(
                "summary" to summary.take(1000)
            )
        ))
    }

    // --- Worker events ---

    /**
     * Forward worker internal events (ToolCallEvent, ToolResultEvent) to the main event flow.
     */
    suspend fun onWorkerEvent(event: SwarmEvent) {
        emit(event)
    }

    // --- Internal ---

    private suspend fun emit(event: SwarmEvent) {
        logger.debug("Swarm event: {} (run={}, task={})", event.type, event.runId, event.taskId)
        if (isTerminalEvent(event)) {
            _terminalEvents.emit(event)
        } else {
            _events.emit(event)
        }
    }

    private fun isTerminalEvent(event: SwarmEvent): Boolean {
        return event.type == "swarm_run_completed" ||
               event.type == "swarm_run_failed" ||
               event.type == "swarm_run_paused" ||
               event.type == "swarm_run_cancelled"
    }

    private fun computeDuration(task: SwarmTask): Long {
        val start = task.startedAt ?: return 0
        val end = task.completedAt ?: return 0
        return java.time.Duration.between(start, end).toMillis()
    }
}

/**
 * Structured swarm event emitted by [SwarmEventBridge].
 */
data class SwarmEvent(
    val type: String,
    val runId: String,
    val taskId: String? = null,
    val data: Map<String, Any?> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)
