package com.easy.easyai.core.agent

/**
 * Callback invoked by [com.easy.easyai.core.agent.AgentLoop] when the agent pauses waiting for user input
 * (permission request or ask_question).
 *
 * The web layer registers an implementation to update goal pause timing,
 * ensuring the goal timeout only counts active (non-waiting) duration.
 */
interface WaitForUserListener {
    /**
     * Called when the agent loop detects a needPause result and is about to end the SSE stream.
     *
     * @param sessionId The session ID of the paused agent.
     * @param userId The user ID that owns the session.
     * @param reason Human-readable reason: "permission_request" or "ask_question".
     */
    suspend fun onWaitForUser(sessionId: String, userId: String, reason: String)
}
