package com.easy.easyai.swarm.runtime

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.event.MessageListener
import com.easy.easyai.core.model.EasyAiMessage
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [SwarmSessionManager] for dry-run mode.
 *
 * Generates ephemeral UUID session IDs and stores messages in memory,
 * enabling member suspend/resume logic to work without any DB writes.
 * Messages are lost when the instance is garbage collected (acceptable
 * for short-lived dry-run executions).
 */
internal class InMemorySwarmSessionManager : SwarmSessionManager {

    private val sessions = ConcurrentHashMap<String, MutableList<EasyAiMessage>>()

    override suspend fun createSession(
        agentId: String,
        swarmRunId: String,
        swarmTaskId: String,
        userId: String
    ): String {
        val sessionId = UUID.randomUUID().toString()
        sessions[sessionId] = mutableListOf()
        return sessionId
    }

    override fun createMessageListener(sessionId: String, context: AgentContext): MessageListener {
        return object : MessageListener {
            override suspend fun onMessageAdded(messages: List<EasyAiMessage>) {
                sessions[sessionId]?.let { list ->
                    synchronized(list) { list.addAll(messages) }
                }
            }
        }
    }

    override suspend fun loadMessages(sessionId: String): List<EasyAiMessage> {
        val list = sessions[sessionId] ?: return emptyList()
        return synchronized(list) { list.toList() }
    }
}
