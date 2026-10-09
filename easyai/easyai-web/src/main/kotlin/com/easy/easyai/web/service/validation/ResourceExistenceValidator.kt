package com.easy.easyai.web.service.validation

import com.easy.easyai.agent.api.model.AgentCreateRequest
import com.easy.easyai.agent.registry.ToolRegistry
import com.easy.easyai.core.agent.AgentType
import com.easy.easyai.core.agent.AsyncAgentStore
import com.easy.easyai.core.tool.ToolFactory
import com.easy.easyai.skills.SkillAccessResolver
import com.easy.easyai.tools.mcp.McpClientManager
import com.easy.easyai.web.model.ConfigValidationError

/**
 * Validates that referenced resources (tools, skills, sub-agents, MCP servers) exist.
 */
class ResourceExistenceValidator(
    private val toolRegistry: ToolRegistry,
    private val agentStore: AsyncAgentStore,
    private val toolFactory: ToolFactory,
    private val skillAccessResolver: SkillAccessResolver? = null,
    private val mcpClientManager: McpClientManager? = null,
) : AgentConfigValidator {

    override suspend fun validate(
        request: AgentCreateRequest,
        userId: String,
        owners: Collection<String>
    ): List<ConfigValidationError> {
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

        // Validate skills against the caller's visible set (own rows, the group bucket, and the shared
        // layer), never the global registry: another owner's skill name must not validate this request.
        if (request.skillNames.isNotEmpty()) {
            val visible = skillAccessResolver?.listScopedSkillsForOwners(owners)?.associateBy { it.skill.name }
            for (name in request.skillNames) {
                val candidate = visible?.get(name)
                if (candidate == null) {
                    if (visible != null) {
                        errors.add(ConfigValidationError("skillNames", "Skill '$name' does not exist"))
                    } else {
                        errors.add(ConfigValidationError("skillNames", "Skill '$name' cannot be verified (skill system unavailable)", "warning"))
                    }
                } else if (candidate.catalogEntry?.enabled == false) {
                    errors.add(ConfigValidationError(
                        "skillNames",
                        "Skill '$name' is disabled; load_skill will refuse it until it is enabled",
                        "warning"
                    ))
                }
            }
        }

        // Sub-agents and members resolve against the caller's whole visibility set, same as the
        // runtime lookup does — otherwise a shared agent could not reference its own bucket's members.
        if (request.subAgentIds.isNotEmpty()) {
            for (id in request.subAgentIds) {
                val exists = agentStore.findById(id, owners) != null
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
                val member = agentStore.findById(id, owners)
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
                    // A SUBAGENT always runs with a parent: sub-agent-spawning tools are not
                    // built (recursion guard) and mainAgentOnly tools are blocked.
                    val blockedSet = ToolAvailability.subAgentBlocked(toolFactory)
                    val blocked = request.toolNames.filter { it in blockedSet }
                    if (blocked.isNotEmpty()) {
                        errors.add(ConfigValidationError(
                            "toolNames",
                            "SUBAGENT agents cannot use ${blocked.joinToString(", ")} at runtime (blocked for sub-agents); consider removing them",
                            "warning"
                        ))
                    }
                }
                AgentType.TEAM -> {
                    // Sub-agent-spawning tools only launch agents in the subAgentIds whitelist,
                    // which is always empty for TEAM (no Sub-Agent config) — never usable.
                    val unusableSet = ToolAvailability.teamUnusable(toolFactory)
                    val unusable = request.toolNames.filter { it in unusableSet }
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

        errors.addAll(validateSkillTools(request, ToolAvailability.skillLoaders(toolFactory)))

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
            val connectedNames = mcpClientManager?.getConnectedServers(owners)?.map { it.serverName }?.toSet() ?: emptySet()
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
        /** Shared by config validation and every Agent save endpoint, including inline agents. */
        @JvmStatic
        fun validateSkillTools(request: AgentCreateRequest, skillLoaderNames: Set<String>): List<ConfigValidationError> = buildList {
            addAll(validateSkillTools(request.toolNames, request.skillNames, skillLoaderNames))
            request.customSubAgents.forEachIndexed { index, spec ->
                addAll(validateSkillTools(spec.toolNames, spec.skillNames, skillLoaderNames, "customSubAgents[$index].skillNames"))
            }
            request.customMembers.forEachIndexed { index, spec ->
                addAll(validateSkillTools(spec.toolNames, spec.skillNames, skillLoaderNames, "customMembers[$index].skillNames"))
            }
        }

        @JvmStatic
        fun validateSkillTools(
            toolNames: List<String>,
            skillNames: List<String>,
            skillLoaderNames: Set<String>,
            field: String = "skillNames"
        ): List<ConfigValidationError> =
            if (skillNames.isNotEmpty() && toolNames.none { it in skillLoaderNames }) {
                listOf(ConfigValidationError(
                    field,
                    "Skills are configured but no skill-loading tool (${skillLoaderNames.joinToString(", ")}) is present in toolNames — the agent cannot load skill content at runtime"
                ))
            } else emptyList()
    }
}
