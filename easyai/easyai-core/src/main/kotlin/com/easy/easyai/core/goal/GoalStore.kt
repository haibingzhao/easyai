package com.easy.easyai.core.goal

/**
 * Async goal storage interface.
 * All operations are suspend functions for non-blocking access.
 *
 * All mutating methods require [userId] for ownership verification,
 * consistent with other stores in the project (SessionStore, UserCommandStore, etc.).
 */
interface GoalStore {

    /**
     * Get the active goal for a session, or null if none is set.
     * @param sessionId Session ID
     * @param userId User ID for ownership verification
     */
    suspend fun getGoal(sessionId: String, userId: String): GoalState?

    /**
     * Save or update the goal state for a session.
     * @param goal The goal state to persist
     * @param userId User ID for ownership verification
     */
    suspend fun saveGoal(goal: GoalState, userId: String)

    /**
     * Delete the goal for a session.
     * @param sessionId Session ID
     * @param userId User ID for ownership verification
     */
    suspend fun deleteGoal(sessionId: String, userId: String)
}
