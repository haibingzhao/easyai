package com.easy.easyai.core.team

/**
 * Unified member execution record shared by Swarm TEAM tasks and Team Agents.
 *
 * Merges the former Swarm-only [TeamMemberExecution] model with Team Agent requirements.
 * Swarm persists this via SwarmRunStore (keyed by runId + taskId); Team Agents persist
 * via TeamExecutionStore (keyed by teamSessionId).
 *
 * @property memberId The member agent's identifier.
 * @property round Coordination round in which this execution was delegated (1-based).
 * @property assignment The task prompt delegated to the member.
 * @property status Current execution status.
 * @property summary Execution result summary (populated on COMPLETED).
 * @property escalationReason Block reason (ESCALATED) or error message (ERROR).
 * @property inputTokens Input tokens consumed by this execution.
 * @property outputTokens Output tokens produced by this execution.
 * @property memberSessionId The member's session ID — used by both Swarm resumeWorker
 *   and Team Agent resume_member to load conversation history and continue execution.
 * @property toolCallId The tool call ID that triggered this delegation — used by
 *   Team Agent frontend to correlate execution cards with Leader tool messages.
 */
data class TeamMemberExecution(
    val memberId: String,
    val round: Int,
    val assignment: String,
    val status: TeamMemberStatus,
    val summary: String? = null,
    val escalationReason: String? = null,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val memberSessionId: String? = null,
    val toolCallId: String? = null,
)
