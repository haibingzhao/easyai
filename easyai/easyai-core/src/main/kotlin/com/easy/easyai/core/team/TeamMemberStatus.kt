package com.easy.easyai.core.team

/**
 * Unified member execution status shared by Swarm TEAM tasks and Team Agents.
 *
 * Swarm original values: RUNNING, COMPLETED, ESCALATED, SUSPENDED, REASSIGNED.
 * Team Agent additions: ERROR (split from ESCALATED), RESUMED.
 *
 * Compatibility note: Swarm's former ESCALATED covered both "member reported a block"
 * and "member execution failed". After the split, execution failures map to [ERROR]
 * while explicit escalation reports map to [ESCALATED]. DTO status fields remain
 * String-typed, so frontends are unaffected.
 */
enum class TeamMemberStatus {
    /** Member is currently executing its assignment. */
    RUNNING,

    /** Member finished its assignment successfully. */
    COMPLETED,

    /** Member explicitly reported being blocked (Swarm: escalate tool, Team Agent: ask_leader tool). */
    ESCALATED,

    /** Member execution failed with an error. */
    ERROR,

    /** Member was suspended by the Leader awaiting resolution (Swarm-specific). */
    SUSPENDED,

    /** Member was resumed after a block; a subsequent RUNNING record follows. */
    RESUMED,

    /** Member's task was reassigned to another member. */
    REASSIGNED;

    companion object {
        /**
         * Parse a status string, defaulting to [RUNNING] for unknown values.
         * Accepts case-insensitive input.
         */
        @JvmStatic
        fun fromString(value: String?): TeamMemberStatus =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: RUNNING
    }
}
