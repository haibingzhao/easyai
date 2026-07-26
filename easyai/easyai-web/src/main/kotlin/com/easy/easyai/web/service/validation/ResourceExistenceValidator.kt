package com.easy.easyai.web.service.validation

import com.easy.easyai.agent.api.model.AgentCreateRequest
import com.easy.easyai.agent.registry.ToolRegistry
import com.easy.easyai.core.agent.AgentEnv
import com.easy.easyai.core.agent.AgentType
import com.easy.easyai.core.agent.AsyncAgentStore
import com.easy.easyai.skills.SkillRegistry
import com.easy.easyai.tools.mcp.McpClientManager
import com.easy.easyai.web.model.ConfigValidationError

/**
 * Validates that referenced resources (tools, skills, sub-agents, MCP servers) exist.
 */
class ResourceExistenceValidator(
    private val toolRegistry: ToolRegistry,
    private val agentStore: AsyncAgentStore,
    private val skillRegistry: SkillRegistry? = null,
    private val mcpClientManager: McpClientManager? = null,
) : AgentConfigValidator {

    override suspend fun validate(request: AgentCreateRequest, userId: String): List<ConfigValidationError> {
        val errors = mutableListOf<ConfigValidationError>()

        // Validate tools
        if (request.toolNames.isNotEmpty()) {
            val availableNames = toolRegistry.getAllTools().map { it.name }.toSet()
            for (name in request.toolNames) {
                if (name !in availableNames) {
                    errors.add(ConfigValidationError("toolNames", "Tool '$name' does not exist"))
                }
            }
        }

        // Validate skills
        if (request.skillNames.isNotEmpty()) {
            val availableNames = skillRegistry?.all()?.map { it.name }?.toSet() ?: emptySet()
            for (name in request.skillNames) {
                if (name !in availableNames) {
                    if (skillRegistry != null) {
                        errors.add(ConfigValidationError("skillNames", "Skill '$name' does not exist"))
                    } else {
                        errors.add(ConfigValidationError("skillNames", "Skill '$name' cannot be verified (skill system unavailable)", "warning"))
                    }
                }
            }
        }

        // Validate sub-agents
        if (request.subAgentIds.isNotEmpty()) {
            for (id in request.subAgentIds) {
                val exists = agentStore.findById(id, userId) != null
                if (!exists) {
                    errors.add(ConfigValidationError("subAgentIds", "Sub-agent '$id' does not exist"))
                }
            }
        }

        // Validate team members (TEAM agents)
        if (request.agentType == AgentType.TEAM) {
            if (request.memberIds.isEmpty() && request.customMembers.isEmpty()) {
                errors.add(ConfigValidationError("memberIds", "TEAM agent requires at least one member (memberIds or customMembers)"))
            }
            for (id in request.memberIds) {
                val member = agentStore.findById(id, userId)
                if (member == null) {
                    errors.add(ConfigValidationError("memberIds", "Member agent '$id' does not exist"))
                } else if (member.agentType != AgentType.ALL && member.agentType != AgentType.SUBAGENT) {
                    errors.add(ConfigValidationError("memberIds", "Member '$id' is ${member.agentType} — only ALL or SUBAGENT agents can be team members"))
                }
            }
        }

        // Validate agent-type-specific tool applicability (warnings — the config is
        // technically savable, but the tools won't function for this agent type).
        if (request.toolNames.isNotEmpty()) {
            when (request.agentType) {
                AgentType.SUBAGENT -> {
                    // A SUBAGENT always runs with a parent: `task` is not built
                    // (recursion guard) and `run_swarm` is mainAgentOnly-blocked.
                    val blocked = request.toolNames.filter { it in SUBAGENT_BLOCKED_TOOLS }
                    if (blocked.isNotEmpty()) {
                        errors.add(ConfigValidationError(
                            "toolNames",
                            "SUBAGENT agents cannot use ${blocked.joinToString(", ")} at runtime (blocked for sub-agents); consider removing them",
                            "warning"
                        ))
                    }
                }
                AgentType.TEAM -> {
                    // `task` only launches agents in the subAgentIds whitelist, which
                    // is always empty for TEAM (no Sub-Agent config) — never usable.
                    val unusable = request.toolNames.filter { it in TEAM_UNUSABLE_TOOLS }
                    if (unusable.isNotEmpty()) {
                        errors.add(ConfigValidationError(
                            "toolNames",
                            "TEAM leaders coordinate members via delegate_to_member; ${unusable.joinToString(", ")} is not usable (no sub-agent whitelist). Consider removing it",
                            "warning"
                        ))
                    }
                }
                else -> {}
            }
        }

        // Validate skill/tool consistency: skills require the load_skill tool to be
        // accessible at runtime. In SWARM context load_skill is unavailable, so skip.
        if (request.skillNames.isNotEmpty() &&
            "load_skill" !in request.toolNames &&
            request.agentContext != AgentEnv.SWARM
        ) {
            errors.add(ConfigValidationError(
                "skillNames",
                "Skills are configured but 'load_skill' tool is missing from toolNames — the agent cannot load skill content at runtime",
                "warning"
            ))
        }

        // Validate command/tool consistency: the /goal command creates a goal that
        // requires the 'goal' tool for lifecycle management (complete/block/pause).
        // Without it, a created goal can never be resolved and the agent loop stalls.
        if ("goal" in request.commandNames && "goal" !in request.toolNames) {
            errors.add(ConfigValidationError(
                "commandNames",
                "The /goal command is configured but 'goal' tool is missing from toolNames — created goals cannot be completed or blocked",
                "warning"
            ))
        }

        // Validate MCP configs
        if (request.mcpConfigs.isNotEmpty()) {
            val connectedNames = mcpClientManager?.getConnectedServers(userId)?.map { it.serverName }?.toSet() ?: emptySet()
            for (config in request.mcpConfigs) {
                if (config.serverName !in connectedNames) {
                    if (mcpClientManager != null) {
                        errors.add(ConfigValidationError("mcpConfigs", "MCP server '${config.serverName}' is not connected", "warning"))
                    } else {
                        errors.add(ConfigValidationError("mcpConfigs", "MCP server '${config.serverName}' cannot be verified (MCP unavailable)", "warning"))
                    }
                }
            }
        }

        return errors
    }

    companion object {
        /** Tools blocked at runtime for SUBAGENT agents (parentAgentId guard / mainAgentOnly). */
        private val SUBAGENT_BLOCKED_TOOLS = setOf("task", "run_swarm")

        /** Tools that can never function for a TEAM leader (empty sub-agent whitelist). */
        private val TEAM_UNUSABLE_TOOLS = setOf("task")
    }
}
