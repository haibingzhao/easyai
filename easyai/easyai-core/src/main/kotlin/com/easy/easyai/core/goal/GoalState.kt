package com.easy.easyai.core.goal

import com.easy.easyai.core.goal.GoalState.Companion.MAX_HISTORY_ENTRIES


/**
 * Goal lifecycle status.
 */
enum class GoalStatus {
    ACTIVE,
    COMPLETED,
    BLOCKED,
    PAUSED,
    LIMIT_REACHED
}

/**
 * Session-scoped goal state.
 * Tracks the objective, budget usage, and lifecycle history for an active goal.
 *
 * @param sessionId The session this goal belongs to.
 * @param objective The goal text the agent should work toward.
 * @param successCriteria Optional criteria that define when the goal is satisfied.
 * @param constraints Optional constraints / non-goals to respect.
 * @param turnCount Number of auto-continue turns consumed so far.
 * @param maxTurns Maximum auto-continue turns before stopping.
 * @param startedAt Epoch millis when the goal was set.
 * @param maxDurationMs Maximum active (non-paused) duration before stopping.
 * @param totalPausedMs Cumulative wall-clock millis spent waiting for user input (permission / ask_question).
 * @param pausedAt Epoch millis when the current pause started, or null if not paused.
 * @param status Current lifecycle status.
 * @param stopReason Human-readable reason when stopped.
 * @param blockedReason Concrete blocker text when status is BLOCKED.
 * @param completionEvidence Evidence text provided with [goal:complete].
 * @param lastAssistantText Last seen assistant output (for change detection).
 * @param history Append-only lifecycle event log (capped).
 */
data class GoalState(
    val sessionId: String,
    val objective: String,
    val successCriteria: String? = null,
    val constraints: String? = null,
    val turnCount: Int = 0,
    val maxTurns: Int = DEFAULT_MAX_TURNS,
    val startedAt: Long = System.currentTimeMillis(),
    val maxDurationMs: Long = DEFAULT_MAX_DURATION_MS,
    val totalPausedMs: Long = 0,
    val pausedAt: Long? = null,
    val status: GoalStatus = GoalStatus.ACTIVE,
    val stopReason: String? = null,
    val blockedReason: String? = null,
    val completionEvidence: String? = null,
    val lastAssistantText: String? = null,
    val wrapupSent: Boolean = false,
    val history: List<GoalHistoryEntry> = emptyList()
) {
    /** Append a history entry, keeping the list capped at [MAX_HISTORY_ENTRIES]. */
    fun withHistory(type: String, detail: String): GoalState {
        val entry = GoalHistoryEntry(type = type, detail = detail)
        val newHistory = history + entry
        return copy(history = if (newHistory.size > MAX_HISTORY_ENTRIES) newHistory.takeLast(MAX_HISTORY_ENTRIES) else newHistory)
    }

    companion object {
        const val DEFAULT_MAX_TURNS = 10
        const val DEFAULT_MAX_DURATION_MS = 150 * 60 * 1000L
        private const val MAX_HISTORY_ENTRIES = 20
    }

    /** Whether this goal is still active (not stopped for any reason). */
    val isActive: Boolean get() = status == GoalStatus.ACTIVE

    /** Elapsed active (non-paused) time in millis since the goal was set. */
    val elapsedMs: Long
        get() {
            val now = pausedAt ?: System.currentTimeMillis()
            return (now - startedAt) - totalPausedMs
        }

    /** Mark the goal timer as paused (agent waiting for user input). Idempotent. */
    fun pauseTimer(): GoalState {
        if (pausedAt != null) return this
        return copy(pausedAt = System.currentTimeMillis())
    }

    /** Resume the goal timer after a pause. Accumulates paused duration. Idempotent. */
    fun resumeTimer(): GoalState {
        val pa = pausedAt ?: return this
        val pausedDuration = System.currentTimeMillis() - pa
        return copy(totalPausedMs = totalPausedMs + pausedDuration, pausedAt = null)
    }
}

/**
 * Append-only lifecycle event for goal tracking.
 */
data class GoalHistoryEntry(
    val type: String,
    val detail: String,
    val timestamp: Long = System.currentTimeMillis()
)
