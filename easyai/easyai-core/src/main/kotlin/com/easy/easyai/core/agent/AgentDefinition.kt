package com.easy.easyai.core.agent

import java.time.Instant

/**
 * Persistent agent definition stored in the database.
 * Defines behavior: name, custom instructions, and enabled tools.
 * Model configuration comes from Session's reference to model_provider_config.
 *
 * @param agentType Role type (PRIMARY/SUBAGENT/ALL).
 * @param agentContext Execution environment compatibility (CHAT/SWARM/BOTH).
 * @param promptTemplate Optional Jinja2 template for system prompt generation.
 *   When null, the default [com.easy.easyai.core.prompt.SystemPromptBuilder] logic is used.
 * @param maxIterations Maximum agent-loop iterations.
 * @param maxSubAgentDepth Maximum nesting depth for sub-agent invocation (1 = no nesting).
 * @param color Optional UI display color.
 * @param userId Owner of this agent. System-owned agents (userId = "system") are treated as built-in.
 */
data class AgentDefinition(
    val id: String,
    val name: String,
    val agentType: AgentType = AgentType.PRIMARY,
    val agentContext: AgentEnv = AgentEnv.CHAT,
    val description: String? = null,
    val promptTemplate: String? = null,
    val customInstructions: String? = null,
    val toolNames: List<String> = emptyList(),
    val maxIterations: Int = 50,
    val maxSubAgentDepth: Int = 1,
    val color: String? = null,
    val enabled: Boolean = true,
    val instructionsEnabled: Boolean = true,
    val inputSchema: String? = null,
    val outputSchema: String? = null,
    /** When true, defer structured output to a final enforced iteration after multi-turn tool calling. */
    val outputSchemaMultiTurn: Boolean = false,
    val userId: String = "system",
    val createdAt: Long,
    val updatedAt: Long
) {
    companion object {
        fun create(
            id: String,
            name: String,
            agentType: AgentType = AgentType.PRIMARY,
            agentContext: AgentEnv = AgentEnv.CHAT,
            description: String? = null,
            promptTemplate: String? = null,
            customInstructions: String? = null,
            toolNames: List<String> = emptyList(),
            maxIterations: Int = 50,
            maxSubAgentDepth: Int = 1,
            color: String? = null,
            enabled: Boolean = true,
            instructionsEnabled: Boolean = true,
            inputSchema: String? = null,
            outputSchema: String? = null,
            outputSchemaMultiTurn: Boolean = false
        ): AgentDefinition {
            val now = Instant.now().epochSecond
            return AgentDefinition(
                id, name, agentType, agentContext, description, promptTemplate,
                customInstructions, toolNames, maxIterations, maxSubAgentDepth,
                color, enabled, instructionsEnabled, inputSchema, outputSchema,
                outputSchemaMultiTurn, "system", now, now
            )
        }
    }
}
