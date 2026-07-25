package com.easy.easyai.core.team

/**
 * State of a blocked/suspended member awaiting Leader resolution.
 *
 * Unifies Swarm's SuspendedMemberInfo and Team Agent's MemberBridge concept:
 * - Swarm: created by suspendBlockedMember() when a member escalates.
 * - Team Agent: created when ask_leader (MemberSignalTool) fires.
 *
 * The [sessionId] enables resumption: the coordinator loads the member's
 * conversation history from that session and appends a resolution message.
 */
data class BlockedMemberState(
    /** The member agent's identifier. */
    val memberId: String,
    /** The member's session ID — used to load history for resume. */
    val sessionId: String,
    /** The original task assignment given to the member. */
    val originalAssignment: String,
    /** Why the member is blocked (escalation reason or question). */
    val blockReason: String,
    /** The coordination round at which the member became blocked. */
    val blockedAtRound: Int,
    /** Optional progress description provided by the member at block time. */
    val progressAtBlock: String? = null,
    /** The execution record ID of the blocked run — used to mark it RESUMED on resume. */
    val executionId: String? = null,
)
