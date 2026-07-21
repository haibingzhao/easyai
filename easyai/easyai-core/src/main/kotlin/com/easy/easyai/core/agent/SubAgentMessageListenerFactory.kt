package com.easy.easyai.core.agent

import com.easy.easyai.core.event.MessageListener

fun interface SubAgentMessageListenerFactory {
    /**
     * Factory for creating session-scoped [MessageListener] instances.
     * Used by tool builders (e.g., SubAgentToolBuilder) to create listeners
     * for persisting messages with parent-child relationships.
     *
     * @param sessionId The current session ID.
     * @param context The agent context for the sub-agent.
     * @param parentMessageId The parent message ID (the assistant message that triggered the tool call).
     * @param parentToolCallId The parent tool call ID (the specific toolCall that triggered the sub-agent).
     * @return A MessageListener instance, or null if persistence is not available.
     */
    fun create(sessionId: String, context: AgentContext, parentMessageId: String, parentToolCallId: String): MessageListener?
}
