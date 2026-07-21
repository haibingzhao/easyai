package com.easy.easyai.core.agent

/**
 * Factory interface for creating Agent instances.
 * This allows different integration layers (Socket, Shell, etc.) to be decoupled from specific Agent configurations.
 */
fun interface AgentFactory {
    /**
     * Create a new Agent for the given session ID.
     */
    fun createAgent(sessionId: String): Agent
}