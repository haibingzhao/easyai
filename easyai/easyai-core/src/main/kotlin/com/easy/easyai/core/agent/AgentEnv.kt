package com.easy.easyai.core.agent

import org.slf4j.LoggerFactory

/**
 * Agent execution environment compatibility — controls where the agent's prompt can be correctly rendered.
 *
 * Orthogonal to [AgentType] (which controls invocation visibility).
 *
 * - [CHAT]: Prompt uses only PromptContext variables. Usable in Chat sessions.
 * - [SWARM]: Prompt may use Swarm-specific variables (user_input, deliberation_history, etc.).
 * - [BOTH]: Compatible with both Chat and Swarm environments.
 */
enum class AgentEnv {
    CHAT,
    SWARM,
    BOTH;

    companion object {
        private val logger = LoggerFactory.getLogger(AgentEnv::class.java)

        fun fromString(value: String?): AgentEnv =
            when (value?.uppercase()) {
                "CHAT", null -> CHAT
                "SWARM" -> SWARM
                "BOTH" -> BOTH
                else -> {
                    logger.warn("Unknown AgentEnv '{}', defaulting to CHAT", value)
                    CHAT
                }
            }
    }

    fun supportsChat(): Boolean = this == CHAT || this == BOTH
    fun supportsSwarm(): Boolean = this == SWARM || this == BOTH
}
