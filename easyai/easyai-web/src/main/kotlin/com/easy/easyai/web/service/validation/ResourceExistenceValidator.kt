package com.easy.easyai.web.service.validation

import com.easy.easyai.agent.api.model.AgentCreateRequest
import com.easy.easyai.agent.registry.ToolRegistry
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
}
