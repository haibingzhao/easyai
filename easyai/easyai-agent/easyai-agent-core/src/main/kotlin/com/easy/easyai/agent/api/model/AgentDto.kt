package com.easy.easyai.agent.api.model

import com.easy.easyai.core.agent.AgentEnv
import com.easy.easyai.core.agent.AgentType
import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Inline custom agent specification.
 * Used for defining sub-agents or team members directly within a parent agent
 * without creating separate AgentDefinition records.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class InlineAgentSpec(
    val name: String,
    val description: String = "",
    val systemPrompt: String = "",
    val toolNames: List<String> = emptyList(),
    val skillNames: List<String> = emptyList(),
    val mcpConfigs: List<McpBindingDto> = emptyList()
)

/**
 * Agent DTO for API requests/responses.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AgentDto(
    val id: String,
    val name: String,
    val agentType: AgentType = AgentType.PRIMARY,
    val agentContext: AgentEnv = AgentEnv.CHAT,
    val description: String? = null,
    val customInstructions: String? = null,
    val promptTemplate: String? = null,
    val toolNames: List<String> = emptyList(),
    val subAgentIds: List<String> = emptyList(),
    val skillNames: List<String> = emptyList(),
    val mcpConfigs: List<McpBindingDto> = emptyList(),
    val commandNames: List<String> = emptyList(),
    /** Team member agent IDs. Only meaningful when agentType = TEAM. */
    val memberIds: List<String> = emptyList(),
    /** Inline custom sub-agents defined directly within this agent. */
    val customSubAgents: List<InlineAgentSpec> = emptyList(),
    /** Inline custom team members defined directly within this agent. */
    val customMembers: List<InlineAgentSpec> = emptyList(),
    val maxIterations: Int = 50,
    val maxSubAgentDepth: Int = 1,
    val color: String? = null,
    val enabled: Boolean = true,
    val instructionsEnabled: Boolean = true,
    val inputSchema: String? = null,
    val outputSchema: String? = null,
    val builtin: Boolean = false,
    val createdAt: Long? = null,
    val updatedAt: Long? = null
)

/**
 * Request body for creating an agent.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AgentCreateRequest(
    val id: String,
    val name: String,
    val agentType: AgentType = AgentType.PRIMARY,
    val agentContext: AgentEnv = AgentEnv.CHAT,
    val description: String? = null,
    val customInstructions: String? = null,
    val promptTemplate: String? = null,
    val toolNames: List<String> = emptyList(),
    val subAgentIds: List<String> = emptyList(),
    val skillNames: List<String> = emptyList(),
    val mcpConfigs: List<McpBindingDto> = emptyList(),
    val commandNames: List<String> = emptyList(),
    /** Team member agent IDs. Required when agentType = TEAM; members must be existing ALL or SUBAGENT agents. */
    val memberIds: List<String> = emptyList(),
    /** Inline custom sub-agents defined directly within this agent. */
    val customSubAgents: List<InlineAgentSpec> = emptyList(),
    /** Inline custom team members defined directly within this agent. */
    val customMembers: List<InlineAgentSpec> = emptyList(),
    val maxIterations: Int = 50,
    val maxSubAgentDepth: Int = 1,
    val color: String? = null,
    val enabled: Boolean = true,
    val instructionsEnabled: Boolean? = null,
    val inputSchema: String? = null,
    val outputSchema: String? = null
)

/**
 * Request body for updating agent tools.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AgentToolsRequest(
    val toolNames: List<String>
)

/**
 * DTO for agent tool/subagent configuration.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AgentToolConfigDto(
    val agentId: String,
    val targetType: String,
    val targetName: String,
    val metadata: String? = null
)

/**
 * MCP server binding DTO: a server name + optional tool/prompt whitelists.
 * Empty toolNames/promptNames = all tools/prompts from this server are allowed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class McpBindingDto(
    val serverName: String,
    val toolNames: List<String> = emptyList(),
    val promptNames: List<String> = emptyList()
)

/**
 * Request body for saving agent tool/subagent configs.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AgentConfigsRequest(
    val targetType: String,
    val targetNames: List<String>
)

/**
 * Request body for updating team agent members.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AgentMembersRequest(
    val memberIds: List<String>
)