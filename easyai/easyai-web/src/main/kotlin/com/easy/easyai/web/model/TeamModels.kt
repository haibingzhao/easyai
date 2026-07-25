package com.easy.easyai.web.model

/**
 * DTO for a team member execution record (Team Member Panel display).
 */
data class TeamMemberExecutionDto(
    val id: String,
    val memberId: String,
    /** Resolved display name of the member agent (falls back to memberId). */
    val memberName: String,
    val round: Int,
    val assignment: String,
    /** TeamMemberStatus name: RUNNING/COMPLETED/ESCALATED/ERROR/SUSPENDED/RESUMED/REASSIGNED. */
    val status: String,
    val summary: String? = null,
    /** Escalation/block reason (mapped from escalationReason for frontend clarity). */
    val blockedQuestion: String? = null,
    /** Member session ID — used by frontend to load the member's message history. */
    val memberSessionId: String? = null,
    /** The delegate_to_member tool call ID that triggered this execution. */
    val toolCallId: String? = null,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
)

/**
 * DTO for a team coordination round record.
 */
data class TeamRoundRecordDto(
    val id: String,
    val round: Int,
    val delegatedMembers: List<String> = emptyList(),
    val completedMembers: List<String> = emptyList(),
    val blockedMembers: List<String> = emptyList(),
    val resumedMembers: List<String> = emptyList(),
    val createdAt: Long = 0,
)
