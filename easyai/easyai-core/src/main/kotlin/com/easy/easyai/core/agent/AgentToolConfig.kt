package com.easy.easyai.core.agent

/**
 * Target type for agent_tool configuration rows.
 */
enum class TargetType {
    TOOL,
    SUBAGENT,
    SKILL,
    MCP,
    COMMAND
}

/**
 * A single row in the agent_tool whitelist table.
 *
 * Pure whitelist model: a row means "allowed", absence means "not allowed".
 *
 * @param id Unique row identifier.
 * @param agentId The agent this config belongs to.
 * @param targetType Whether [targetName] refers to a tool or a sub-agent.
 * @param targetName The name of the tool or sub-agent.
 */
data class AgentToolConfig(
    val id: String,
    val agentId: String,
    val targetType: TargetType,
    val targetName: String,
    val metadata: String? = null
)
