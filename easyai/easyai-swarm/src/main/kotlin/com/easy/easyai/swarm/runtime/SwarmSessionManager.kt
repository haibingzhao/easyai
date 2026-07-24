package com.easy.easyai.swarm.runtime

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.event.MessageListener
import com.easy.easyai.core.model.EasyAiMessage

/**
 * Abstraction for swarm worker session lifecycle management.
 *
 * Implementations handle creating persisted sessions and message listeners
 * for each swarm worker execution, enabling message persistence and
 * real-time streaming.
 *
 * For dry-run mode, use [InMemorySwarmSessionManager] which generates ephemeral
 * session IDs without DB writes, enabling member suspend/resume to function normally.
 * Pass `null` only when session management is entirely unnecessary.
 */
interface SwarmSessionManager {

    /**
     * Create a persisted session for a swarm worker.
     *
     * @param agentId The ID of the agent executing the task
     * @param swarmRunId The ID of the current swarm run
     * @param swarmTaskId The ID of the task being executed
     * @param userId The user who initiated the swarm run
     * @return The created session ID, or null if session creation failed
     */
    suspend fun createSession(
        agentId: String,
        swarmRunId: String,
        swarmTaskId: String,
        userId: String
    ): String?

    /**
     * Create a [MessageListener] for real-time message persistence on a session.
     *
     * @param sessionId The session to attach the listener to
     * @param context The agent context for the current worker execution
     * @return A MessageListener instance, or null if listener creation is not supported
     */
    fun createMessageListener(sessionId: String, context: AgentContext): MessageListener?

    /**
     * Load persisted messages for a session (excluding compacted messages).
     * Used by [SwarmWorkerExecutor.resumeWorker] to restore conversation history
     * before resuming a suspended member.
     *
     * @param sessionId The session to load messages from
     * @return List of messages ordered by creation time, or empty list if not supported
     */
    suspend fun loadMessages(sessionId: String): List<EasyAiMessage> = emptyList()

    /**
     * Find an existing session ID for a swarm task (by run + task ID).
     * Used by [SwarmRuntime.resume] to recover SINGLE task sessions.
     * Returns null if no session exists or lookup is not supported.
     *
     * @param swarmRunId The ID of the swarm run
     * @param swarmTaskId The ID of the task within the run
     * @return The session ID if found, null otherwise
     */
    suspend fun findSessionByTask(swarmRunId: String, swarmTaskId: String): String? = null
}
