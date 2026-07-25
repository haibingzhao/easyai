package com.easy.easyai.core.team

/**
 * Event emitted when a member's execution state changes.
 *
 * Both Swarm TEAM (TeamTaskExecutor's resultChannel) and Team Agents
 * (TeamCoordinationState's eventChannel) communicate member state transitions
 * through this unified event model.
 *
 * Swarm converts its internal MemberExecutionResult into these events;
 * Team Agent coroutines construct them directly upon completion.
 */
sealed class TeamMemberEvent {
    /** The member agent's identifier. */
    abstract val memberId: String

    /** The full execution record snapshot at the time of the event. */
    abstract val execution: TeamMemberExecution

    /** Member finished its assignment successfully. */
    data class Completed(
        override val memberId: String,
        override val execution: TeamMemberExecution,
    ) : TeamMemberEvent()

    /** Member reported being blocked and awaiting Leader resolution. */
    data class Blocked(
        override val memberId: String,
        override val execution: TeamMemberExecution,
    ) : TeamMemberEvent()

    /** Member execution failed with an error. */
    data class Failed(
        override val memberId: String,
        override val execution: TeamMemberExecution,
    ) : TeamMemberEvent()
}
