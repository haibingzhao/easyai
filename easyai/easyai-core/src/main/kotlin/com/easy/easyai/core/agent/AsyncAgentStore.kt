package com.easy.easyai.core.agent

/**
 * Async agent store interface.
 * All operations are suspend functions.
 *
 * Implemented by R2dbcAgentStore in the repository module.
 */
interface AsyncAgentStore {
    suspend fun save(agent: AgentDefinition, userId: String = "system")
    suspend fun findById(id: String, userId: String = "system"): AgentDefinition?
    /**
     * Batch-load agents by IDs. Default implementation falls back to individual findById calls.
     */
    suspend fun findByIds(ids: Collection<String>, userId: String = "system"): Map<String, AgentDefinition> {
        val result = mutableMapOf<String, AgentDefinition>()
        for (id in ids) {
            findById(id, userId)?.let { result[id] = it }
        }
        return result
    }
    suspend fun findAll(userId: String = "system"): List<AgentDefinition>
    suspend fun findByType(agentType: AgentType, userId: String = "system"): List<AgentDefinition>
    /** Returns agents with agentType = SUBAGENT or ALL. */
    suspend fun findSubAgents(userId: String = "system"): List<AgentDefinition>
    /** Returns agents usable in Chat context (agentContext = CHAT or BOTH). */
    suspend fun findChatAgents(userId: String = "system"): List<AgentDefinition>
    suspend fun update(agent: AgentDefinition, userId: String = "system")
    suspend fun delete(id: String, userId: String = "system")

    /** Save tool whitelist for an agent (replaces existing TOOL entries). */
    suspend fun saveAgentTools(agentId: String, toolNames: List<String>)
    /** Get tool names from the whitelist (targetType=TOOL). */
    suspend fun getAgentToolNames(agentId: String): List<String>

    /** Save configs for a specific target type (replaces existing entries of that type). */
    suspend fun saveAgentToolConfigs(agentId: String, targetType: TargetType, targetNames: List<String>)
    /** Get all configs for a given agent and target type. */
    suspend fun getAgentToolConfigs(agentId: String, targetType: TargetType): List<AgentToolConfig>
    /** Get sub-agent names for a primary agent (targetType=SUBAGENT). */
    suspend fun getAgentSubAgentNames(agentId: String): List<String>

    /** Save skill whitelist for an agent (replaces existing SKILL entries). */
    suspend fun saveAgentSkills(agentId: String, skillNames: List<String>) {
        saveAgentToolConfigs(agentId, TargetType.SKILL, skillNames)
    }

    /** Get skill names from the whitelist (targetType=SKILL). */
    suspend fun getAgentSkillNames(agentId: String): List<String> {
        return getAgentToolConfigs(agentId, TargetType.SKILL).map { it.targetName }
    }

    /**
     * Save MCP configs for an agent (replaces existing MCP entries).
     * Each config may include a metadata JSON string listing allowed tool names.
     */
    suspend fun saveAgentMcpConfigs(agentId: String, configs: List<AgentToolConfig>)

    /** Get MCP configs for an agent (targetType=MCP). */
    suspend fun getAgentMcpConfigs(agentId: String): List<AgentToolConfig> {
        return getAgentToolConfigs(agentId, TargetType.MCP)
    }

    /** Save command whitelist for an agent (replaces existing COMMAND entries). */
    suspend fun saveAgentCommands(agentId: String, commandNames: List<String>) {
        saveAgentToolConfigs(agentId, TargetType.COMMAND, commandNames)
    }

    /** Get command names from the whitelist (targetType=COMMAND). */
    suspend fun getAgentCommandNames(agentId: String): List<String> {
        return getAgentToolConfigs(agentId, TargetType.COMMAND).map { it.targetName }
    }

    suspend fun count(): Long
}
