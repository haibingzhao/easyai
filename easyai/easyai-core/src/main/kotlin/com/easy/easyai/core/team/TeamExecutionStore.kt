package com.easy.easyai.core.team

/**
 * Persistence entity for a team member execution record (DB row model).
 *
 * Extends the shared [TeamMemberExecution] domain model with persistence fields:
 * primary key, team session association, and timestamps.
 */
data class TeamMemberExecutionEntity(
    val id: String,
    val teamSessionId: String,
    val memberId: String,
    val round: Int,
    val assignment: String,
    val status: TeamMemberStatus,
    val summary: String? = null,
    val escalationReason: String? = null,
    val memberSessionId: String? = null,
    val toolCallId: String? = null,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
) {
    /** Convert to the shared domain model (for event construction). */
    fun toDomain(): TeamMemberExecution = TeamMemberExecution(
        memberId = memberId,
        round = round,
        assignment = assignment,
        status = status,
        summary = summary,
        escalationReason = escalationReason,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        memberSessionId = memberSessionId,
        toolCallId = toolCallId,
    )
}

/**
 * Persistence entity for a team coordination round record.
 *
 * Records which members changed state during each wait_for_member_events cycle.
 * Member lists are stored as JSON-serialized List<String> in the DB.
 */
data class TeamRoundRecord(
    val id: String,
    val teamSessionId: String,
    val round: Int,
    val delegatedMembers: List<String> = emptyList(),
    val completedMembers: List<String> = emptyList(),
    val blockedMembers: List<String> = emptyList(),
    val resumedMembers: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Persistence store for Team Agent execution records.
 *
 * Independent from SwarmRunStore: Team Agent records are keyed by
 * team_session_id (chat session context), while Swarm records are keyed
 * by run_id + task_id (DAG workflow context).
 *
 * Implementation: easyai-repository (Exposed R2DBC + asyncTransaction).
 */
interface TeamExecutionStore {
    /** Insert a new member execution record (status=RUNNING at delegation time). */
    suspend fun saveExecution(entity: TeamMemberExecutionEntity)

    /** Update an execution record when the member finishes (COMPLETED/ESCALATED/ERROR/RESUMED). */
    suspend fun updateExecution(
        id: String,
        status: TeamMemberStatus,
        summary: String? = null,
        escalationReason: String? = null,
        inputTokens: Long = 0,
        outputTokens: Long = 0,
    )

    /**
     * Update ONLY the status (and completedAt) of an execution record,
     * preserving summary / escalationReason / token counts.
     *
     * Used for the RESUMED transition, where the original record's block reason
     * and accumulated tokens must NOT be overwritten by default values.
     */
    suspend fun updateStatus(id: String, status: TeamMemberStatus)

    /** Get all execution records for a team session, ordered by startedAt. */
    suspend fun getExecutions(teamSessionId: String): List<TeamMemberExecutionEntity>

    /** Get executions that were interrupted (RUNNING status at shutdown). */
    suspend fun getIncompleteExecutions(teamSessionId: String): List<TeamMemberExecutionEntity>

    /** Save a coordination round record. */
    suspend fun saveRound(record: TeamRoundRecord)

    /** Get all round records for a team session, ordered by round. */
    suspend fun getRounds(teamSessionId: String): List<TeamRoundRecord>

    /**
     * Delete all execution and round records for a team session.
     * Called when the owning chat session is deleted, to avoid orphaned rows.
     */
    suspend fun deleteByTeamSession(teamSessionId: String)

    /**
     * Delete execution and round records for a team session created at or after [fromTimestamp]
     * (executions by startedAt, rounds by createdAt).
     *
     * Called when editing a historical message truncates the conversation: team activity from the
     * removed portion (delegations/rounds at or after the edited message's timestamp) is cleaned up
     * so stale records don't linger in the Team panel.
     */
    suspend fun deleteByTeamSessionFrom(teamSessionId: String, fromTimestamp: Long)
}
