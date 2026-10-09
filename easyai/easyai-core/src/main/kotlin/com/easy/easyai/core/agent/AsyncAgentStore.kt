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

    // ─── Group-aware reads ───────────────────────────────────────────────────────
    // An agent's PK is (id, userId), so the same id can exist in a caller's own bucket, their group
    // bucket, and the system layer. Callers pass `SecurityUtils.currentOwners()` (ordered self → group
    // → system); the fan-out below visits owners in that order and `distinctBy { id }` keeps the first,
    // which yields self-over-group-over-system shadowing. Id collisions across buckets are possible
    // (the create path only rejects ids already used by a built-in system agent), so this ordering —
    // not id uniqueness — is what makes the result deterministic. Per-agent whitelists are scoped by
    // owner too (see below), so two agents sharing an id no longer share one whitelist.

    /** The agent [id] visible to any of [owners]; the earliest owner in the list wins. */
    suspend fun findById(id: String, owners: Collection<String>): AgentDefinition? =
        owners.distinct().mapNotNull { findById(id, it) }.firstOrNull()

    /** Every agent visible to any of [owners], de-duplicated by id (earliest owner wins). */
    suspend fun findAll(owners: Collection<String>): List<AgentDefinition> =
        owners.distinct().flatMap { findAll(it) }.distinctBy { it.id }

    /** Agents of [agentType] visible to any of [owners], de-duplicated by id. */
    suspend fun findByType(agentType: AgentType, owners: Collection<String>): List<AgentDefinition> =
        owners.distinct().flatMap { findByType(agentType, it) }.distinctBy { it.id }

    /** Sub-agents visible to any of [owners], de-duplicated by id. */
    suspend fun findSubAgents(owners: Collection<String>): List<AgentDefinition> =
        owners.distinct().flatMap { findSubAgents(it) }.distinctBy { it.id }

    /** Chat-context agents visible to any of [owners], de-duplicated by id. */
    suspend fun findChatAgents(owners: Collection<String>): List<AgentDefinition> =
        owners.distinct().flatMap { findChatAgents(it) }.distinctBy { it.id }

    /** Batch-load agents by id visible to any of [owners]. */
    suspend fun findByIds(ids: Collection<String>, owners: Collection<String>): Map<String, AgentDefinition> {
        val result = mutableMapOf<String, AgentDefinition>()
        for (id in ids) findById(id, owners)?.let { result[id] = it }
        return result
    }

    // ─── Per-agent whitelists ────────────────────────────────────────────────────
    // Every method below is scoped by [userId]: the owner of the *agent row* the whitelist belongs
    // to, i.e. `AgentDefinition.userId` — never the requesting caller's id. A member running their
    // group's agent must read the group bucket's whitelist, and the default agent's seeded whitelist
    // lives under `system`, so passing the caller would silently return empty and degrade every
    // agent to "inherit all". Resolve the agent first (`findById`) and pass its [AgentDefinition.userId].
    //
    // The parameter is required on purpose: an omitted owner is exactly the bug this scoping fixes,
    // so every call site has to state whose whitelist it means.

    /** Save tool whitelist for an agent (replaces existing TOOL entries). */
    suspend fun saveAgentTools(agentId: String, toolNames: List<String>, userId: String)
    /** Get tool names from the whitelist (targetType=TOOL). */
    suspend fun getAgentToolNames(agentId: String, userId: String): List<String>

    /** Save configs for a specific target type (replaces existing entries of that type). */
    suspend fun saveAgentToolConfigs(agentId: String, targetType: TargetType, targetNames: List<String>, userId: String)
    /** Get all configs for a given agent and target type. */
    suspend fun getAgentToolConfigs(agentId: String, targetType: TargetType, userId: String): List<AgentToolConfig>
    /** Get sub-agent names for a primary agent (targetType=SUBAGENT). */
    suspend fun getAgentSubAgentNames(agentId: String, userId: String): List<String>

    /** Save skill whitelist for an agent (replaces existing SKILL entries). */
    suspend fun saveAgentSkills(agentId: String, skillNames: List<String>, userId: String) {
        saveAgentToolConfigs(agentId, TargetType.SKILL, skillNames, userId)
    }

    /** Get skill names from the whitelist (targetType=SKILL). */
    suspend fun getAgentSkillNames(agentId: String, userId: String): List<String> {
        return getAgentToolConfigs(agentId, TargetType.SKILL, userId).map { it.targetName }
    }

    /**
     * Save MCP configs for an agent (replaces existing MCP entries).
     * Each config may include a metadata JSON string listing allowed tool names.
     */
    suspend fun saveAgentMcpConfigs(agentId: String, configs: List<AgentToolConfig>, userId: String)

    /** Get MCP configs for an agent (targetType=MCP). */
    suspend fun getAgentMcpConfigs(agentId: String, userId: String): List<AgentToolConfig> {
        return getAgentToolConfigs(agentId, TargetType.MCP, userId)
    }

    /** Save command whitelist for an agent (replaces existing COMMAND entries). */
    suspend fun saveAgentCommands(agentId: String, commandNames: List<String>, userId: String) {
        saveAgentToolConfigs(agentId, TargetType.COMMAND, commandNames, userId)
    }

    /** Get command names from the whitelist (targetType=COMMAND). */
    suspend fun getAgentCommandNames(agentId: String, userId: String): List<String> {
        return getAgentToolConfigs(agentId, TargetType.COMMAND, userId).map { it.targetName }
    }

    /** Save member list for a team agent (replaces existing MEMBER entries). */
    suspend fun saveAgentMembers(agentId: String, memberIds: List<String>, userId: String) {
        saveAgentToolConfigs(agentId, TargetType.MEMBER, memberIds, userId)
    }

    /** Get member agent IDs for a team agent (targetType=MEMBER, excludes inline entries). */
    suspend fun getAgentMemberIds(agentId: String, userId: String): List<String> {
        return getAgentToolConfigs(agentId, TargetType.MEMBER, userId)
            .filter { !it.targetName.startsWith("inline:") }
            .map { it.targetName }
    }

    /**
     * Save inline custom agent specs for an agent (replaces existing inline entries of the given target type).
     * Inline entries are distinguished by targetName prefix "inline:" and store spec JSON in metadata.
     * Global ID references (without "inline:" prefix) are preserved separately via [saveAgentToolConfigs].
     */
    suspend fun saveAgentInlineSpecs(agentId: String, targetType: TargetType, specs: List<AgentToolConfig>, userId: String)

    suspend fun count(): Long
}
