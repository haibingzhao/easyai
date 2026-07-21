package com.easy.easyai.core.agent

import org.springframework.ai.chat.model.ChatModel

/**
 * Agent - configured, executable agent instance.
 *
 * Binds together:
 * - [context]: identity, behavior config, tools
 * - [services]: infrastructure dependencies
 * - [chatModel]: resolved ChatModel (built once at construction)
 *
 * Execution is handled by [AgentRunner] (created per prompt).
 * Session state (messages, listeners, abort) is managed by [ChatSession].
 */
data class Agent(
    val context: AgentContext,
    val services: AgentService
) {
    val chatModel: ChatModel = context.modelConfig
        ?.takeIf { services.supportsProtocol(it.protocol) }
        ?.let { services.createChatModel(it) }
        ?: services.defaultChatModel
}