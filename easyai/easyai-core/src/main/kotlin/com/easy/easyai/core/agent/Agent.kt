package com.easy.easyai.core.agent

import com.easy.easyai.api.llm.ChatModel

/**
 * Agent - configured, executable agent instance.
 *
 * Binds together:
 * - [context]: identity, behavior config, tools
 * - [services]: infrastructure dependencies
 * - [chatModel]: resolved ChatModel (built on first use)
 *
 * Execution is handled by [AgentRunner] (created per prompt).
 * Session state (messages, listeners, abort) is managed by [ChatSession].
 */
data class Agent(
    val context: AgentContext,
    val services: AgentService
) {
    // Deferred on purpose: a session minted before any model is chosen carries no config, and
    // building its placeholder agent must not require one. The failure lands on first use instead.
    val chatModel: ChatModel by lazy {
        context.modelConfig
            ?.takeIf { services.supportsProtocol(it.protocol) }
            ?.let { services.createChatModel(it) }
            ?: services.defaultChatModel
            ?: error(
                "No ChatModel for agent '${context.agentId}'" +
                    (context.modelConfig?.let { ": protocol '${it.protocol}' of config '${it.id}' is unsupported" }
                        ?: ": no model config was resolved") +
                    " and the host registers no default ChatModel bean"
            )
    }
}