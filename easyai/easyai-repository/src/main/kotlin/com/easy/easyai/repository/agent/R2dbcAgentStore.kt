package com.easy.easyai.repository.agent

import com.easy.easyai.core.agent.*
import com.easy.easyai.repository.database.Tables
import com.easy.easyai.repository.database.UserScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.notLike
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.*

/**
 * R2DBC-based implementation of AsyncAgentStore.
 * Uses Exposed R2DBC for pure async database operations.
 */
class R2dbcAgentStore(private val db: R2dbcDatabase) : AsyncAgentStore {
    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun save(agent: AgentDefinition, userId: String) {
        suspendTransaction(db) {
            val existingCount = Tables.AgentTable
                .selectAll()
                .where { (Tables.AgentTable.id eq agent.id) and (Tables.AgentTable.userId eq userId) }
                .count()

            val now = Instant.now().epochSecond
            if (existingCount > 0) {
                Tables.AgentTable.update(
                    where = { (Tables.AgentTable.id eq agent.id) and (Tables.AgentTable.userId eq userId) }
                ) {
                    it[name] = agent.name
                    it[agentType] = agent.agentType.name
                    it[agentContext] = agent.agentContext.name
                    it[description] = agent.description
                    it[promptTemplate] = agent.promptTemplate
                    it[customInstructions] = agent.customInstructions
                    it[maxIterations] = agent.maxIterations
                    it[maxSubAgentDepth] = agent.maxSubAgentDepth
                    it[color] = agent.color
                    it[enabled] = agent.enabled
                    it[Tables.AgentTable.instructionsEnabled] = agent.instructionsEnabled
                    it[Tables.AgentTable.toolFoldEnabled] = agent.toolFoldEnabled
                    it[Tables.AgentTable.toolFoldKeepRecentRuns] = agent.toolFoldKeepRecentRuns
                    it[Tables.AgentTable.thinkingHistoryEnabled] = agent.thinkingHistoryEnabled
                    it[Tables.AgentTable.inputSchema] = agent.inputSchema
                    it[Tables.AgentTable.outputSchema] = agent.outputSchema
                    it[Tables.AgentTable.outputSchemaMultiTurn] = agent.outputSchemaMultiTurn
                    it[updatedAt] = now
                }
                logger.info("Updated agent: {}", agent.id)
            } else {
                Tables.AgentTable.insert {
                    it[id] = agent.id
                    it[name] = agent.name
                    it[agentType] = agent.agentType.name
                    it[agentContext] = agent.agentContext.name
                    it[description] = agent.description
                    it[promptTemplate] = agent.promptTemplate
                    it[customInstructions] = agent.customInstructions
                    it[maxIterations] = agent.maxIterations
                    it[maxSubAgentDepth] = agent.maxSubAgentDepth
                    it[color] = agent.color
                    it[enabled] = agent.enabled
                    it[Tables.AgentTable.instructionsEnabled] = agent.instructionsEnabled
                    it[Tables.AgentTable.toolFoldEnabled] = agent.toolFoldEnabled
                    it[Tables.AgentTable.toolFoldKeepRecentRuns] = agent.toolFoldKeepRecentRuns
                    it[Tables.AgentTable.thinkingHistoryEnabled] = agent.thinkingHistoryEnabled
                    it[Tables.AgentTable.inputSchema] = agent.inputSchema
                    it[Tables.AgentTable.outputSchema] = agent.outputSchema
                    it[Tables.AgentTable.outputSchemaMultiTurn] = agent.outputSchemaMultiTurn
                    it[Tables.AgentTable.userId] = userId
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                logger.info("Inserted agent: {}", agent.id)
            }
        }
    }

    override suspend fun findById(id: String, userId: String): AgentDefinition? =
        findById(id, listOf(userId))

    override suspend fun findById(id: String, owners: Collection<String>): AgentDefinition? {
        val ordered = normalizeOwners(owners)
        val agent = suspendTransaction(db) {
            val rows = Tables.AgentTable
                .selectAll()
                .where {
                    (Tables.AgentTable.id eq id) and UserScope.filterOwners(Tables.AgentTable.userId, ordered)
                }
                .map { row -> toAgentWithoutTools(row) }
                .toList()
            byOwnerPriority(rows, ordered).firstOrNull()
        } ?: return null
        return agent.copy(toolNames = getAgentToolNames(agent.id, agent.userId))
    }

    override suspend fun findByIds(ids: Collection<String>, userId: String): Map<String, AgentDefinition> =
        findByIds(ids, listOf(userId))

    override suspend fun findByIds(ids: Collection<String>, owners: Collection<String>): Map<String, AgentDefinition> {
        if (ids.isEmpty()) return emptyMap()
        val ordered = normalizeOwners(owners)
        val idList = ids.toList()

        // Single query: load all agent rows matching the given IDs across every visible bucket.
        val agents = suspendTransaction(db) {
            Tables.AgentTable
                .selectAll()
                .where {
                    (Tables.AgentTable.id inList idList) and
                        UserScope.filterOwners(Tables.AgentTable.userId, ordered)
                }
                .map { row -> toAgentWithoutTools(row) }
                .toList()
        }

        // Batch-load tool names for all agents in one query, keyed by (id, owner) because the same
        // id can appear in more than one bucket and each carries its own whitelist.
        val toolNamesByAgent = batchLoadToolNames(agents)

        return byOwnerPriority(
            agents.map { it.copy(toolNames = toolNamesByAgent[it.id to it.userId].orEmpty()) },
            ordered
        )
            .distinctBy { it.id }
            .associateBy { it.id }
    }

    /**
     * Order agents by the caller's owner priority — self, then group, then the shared layer — so a
     * later `distinctBy { id }` / `firstOrNull()` keeps the highest-priority bucket's row. The agent
     * PK is `(id, userId)`, so one id can legitimately exist in several visible buckets; without
     * this the winner would be whatever order the database happened to return. Stable within a
     * bucket, so a caller-supplied ordering is preserved.
     */
    private fun byOwnerPriority(agents: List<AgentDefinition>, ordered: List<String>): List<AgentDefinition> =
        agents.sortedBy { ordered.indexOf(it.userId) }

    /**
     * The visibility set to query: [owners] with blanks dropped, plus the shared `system` layer as the
     * final fallback — mirroring the single-owner [UserScope.filter], which folds `system` in
     * implicitly. Callers that already pass `SecurityUtils.currentOwners()` are unchanged.
     */
    private fun normalizeOwners(owners: Collection<String>): List<String> {
        val ordered = owners.filter { it.isNotBlank() }.distinct().toMutableList()
        if (UserScope.SYSTEM_USER_ID !in ordered) ordered.add(UserScope.SYSTEM_USER_ID)
        return ordered
    }

    /**
     * Batch-load TOOL target names for multiple agents in a single query.
     * Returns a map of (agentId, agent owner) → list of tool names.
     */
    private suspend fun batchLoadToolNames(agents: List<AgentDefinition>): Map<Pair<String, String>, List<String>> {
        if (agents.isEmpty()) return emptyMap()
        val toolTable = Tables.AgentToolTable
        val agentIds = agents.map { it.id }.distinct()
        val ownerIds = agents.map { it.userId }.distinct()
        return suspendTransaction(db) {
            toolTable
                .selectAll()
                .where {
                    (toolTable.agentId inList agentIds) and
                        (toolTable.userId inList ownerIds) and
                        (toolTable.targetType eq TargetType.TOOL.name)
                }
                .toList()
                .groupBy(
                    { it[toolTable.agentId] to it[toolTable.userId] },
                    { it[toolTable.targetName] }
                )
        }
    }

    override suspend fun findAll(userId: String): List<AgentDefinition> = findAll(listOf(userId))

    override suspend fun findAll(owners: Collection<String>): List<AgentDefinition> =
        queryAgentsWithTools(owners)

    override suspend fun findByType(agentType: AgentType, userId: String): List<AgentDefinition> =
        findByType(agentType, listOf(userId))

    override suspend fun findByType(agentType: AgentType, owners: Collection<String>): List<AgentDefinition> =
        queryAgentsWithTools(owners) {
            Tables.AgentTable.agentType eq agentType.name
        }

    override suspend fun findSubAgents(userId: String): List<AgentDefinition> = findSubAgents(listOf(userId))

    override suspend fun findSubAgents(owners: Collection<String>): List<AgentDefinition> =
        queryAgentsWithTools(owners) {
            ((Tables.AgentTable.agentType eq AgentType.SUBAGENT.name) or
                (Tables.AgentTable.agentType eq AgentType.ALL.name)) and
                ((Tables.AgentTable.agentContext eq AgentEnv.CHAT.name) or
                    (Tables.AgentTable.agentContext eq AgentEnv.BOTH.name))
        }

    override suspend fun findChatAgents(userId: String): List<AgentDefinition> = findChatAgents(listOf(userId))

    override suspend fun findChatAgents(owners: Collection<String>): List<AgentDefinition> =
        queryAgentsWithTools(owners) {
            (Tables.AgentTable.agentContext eq AgentEnv.CHAT.name) or
            (Tables.AgentTable.agentContext eq AgentEnv.BOTH.name)
        }

    /**
     * One query for the agents plus one batched query for their whitelists, across every visible
     * bucket. Results come back in owner-priority order (self, group, system) de-duplicated by id, so
     * an agent that exists in more than one bucket is reported once — from the bucket that shadows
     * the others, exactly as the fan-out defaults on [AsyncAgentStore] specify.
     */
    private suspend fun queryAgentsWithTools(
        owners: Collection<String>,
        condition: () -> Op<Boolean>? = { null }
    ): List<AgentDefinition> {
        val ordered = normalizeOwners(owners)
        val agents = suspendTransaction(db) {
            val query = Tables.AgentTable.selectAll()
            val cond = condition()
            val userFilter = UserScope.filterOwners(Tables.AgentTable.userId, ordered)
            if (cond != null) {
                query.where(cond and userFilter)
            } else {
                query.where(userFilter)
            }
            query.map { row -> toAgentWithoutTools(row) }.toList()
        }
        val toolNamesByAgent = batchLoadToolNames(agents)
        return byOwnerPriority(
            agents.map { it.copy(toolNames = toolNamesByAgent[it.id to it.userId].orEmpty()) },
            ordered
        ).distinctBy { it.id }
    }

    override suspend fun update(agent: AgentDefinition, userId: String) {
        save(agent, userId)
    }

    override suspend fun delete(id: String, userId: String) {
        suspendTransaction(db) {
            // Verify strict ownership first — do NOT delete AgentToolTable for system agents
            val ownedCount = Tables.AgentTable.selectAll()
                .where { (Tables.AgentTable.id eq id) and UserScope.filterStrict(Tables.AgentTable.userId, userId) }
                .count()
            if (ownedCount > 0) {
                // Scope the whitelist delete to the same bucket: an agent id can exist in another
                // owner's bucket too, and its rows must survive this delete.
                Tables.AgentToolTable.deleteWhere {
                    (Tables.AgentToolTable.agentId eq id) and (Tables.AgentToolTable.userId eq userId)
                }
                Tables.AgentTable.deleteWhere {
                    (Tables.AgentTable.id eq id) and UserScope.filterStrict(Tables.AgentTable.userId, userId)
                }
                logger.info("Deleted agent: {}", id)
            } else {
                logger.debug("Agent {} not owned by user {}, skipping delete", id, userId)
            }
        }
    }

    override suspend fun saveAgentTools(agentId: String, toolNames: List<String>, userId: String) {
        saveAgentToolConfigs(agentId, TargetType.TOOL, toolNames, userId)
    }

    override suspend fun getAgentToolNames(agentId: String, userId: String): List<String> {
        return getAgentToolConfigs(agentId, TargetType.TOOL, userId).map { it.targetName }
    }

    override suspend fun saveAgentToolConfigs(
        agentId: String,
        targetType: TargetType,
        targetNames: List<String>,
        userId: String
    ) {
        val toolTable = Tables.AgentToolTable
        suspendTransaction(db) {
            // For SUBAGENT/MEMBER types, preserve inline entries (targetName starts with "inline:")
            if (targetType == TargetType.SUBAGENT || targetType == TargetType.MEMBER) {
                toolTable.deleteWhere {
                    (toolTable.agentId eq agentId) and
                    (toolTable.userId eq userId) and
                    (toolTable.targetType eq targetType.name) and
                    (toolTable.targetName notLike "inline:%")
                }
            } else {
                toolTable.deleteWhere {
                    (toolTable.agentId eq agentId) and
                    (toolTable.userId eq userId) and
                    (toolTable.targetType eq targetType.name)
                }
            }
            targetNames.forEach { name ->
                toolTable.insert {
                    it[toolTable.id] = UUID.randomUUID().toString()
                    it[toolTable.agentId] = agentId
                    it[toolTable.userId] = userId
                    it[toolTable.targetType] = targetType.name
                    it[toolTable.targetName] = name
                }
            }
            logger.info("Saved {} {} configs for agent {} of owner {}", targetNames.size, targetType, agentId, userId)
        }
    }

    override suspend fun getAgentToolConfigs(
        agentId: String,
        targetType: TargetType,
        userId: String
    ): List<AgentToolConfig> {
        return suspendTransaction(db) {
            Tables.AgentToolTable
                .selectAll()
                .where {
                    (Tables.AgentToolTable.agentId eq agentId) and
                    (Tables.AgentToolTable.userId eq userId) and
                    (Tables.AgentToolTable.targetType eq targetType.name)
                }
                .map { row ->
                    AgentToolConfig(
                        id = row[Tables.AgentToolTable.id],
                        agentId = row[Tables.AgentToolTable.agentId],
                        targetType = TargetType.valueOf(row[Tables.AgentToolTable.targetType]),
                        targetName = row[Tables.AgentToolTable.targetName],
                        metadata = row[Tables.AgentToolTable.metadata]
                    )
                }
                .toList()
        }
    }

    override suspend fun getAgentSubAgentNames(agentId: String, userId: String): List<String> {
        return getAgentToolConfigs(agentId, TargetType.SUBAGENT, userId)
            .filter { !it.targetName.startsWith("inline:") }
            .map { it.targetName }
    }

    override suspend fun saveAgentMcpConfigs(agentId: String, configs: List<AgentToolConfig>, userId: String) {
        val toolTable = Tables.AgentToolTable
        suspendTransaction(db) {
            // Delete existing MCP configs for this agent
            toolTable.deleteWhere {
                (toolTable.agentId eq agentId) and
                (toolTable.userId eq userId) and
                (toolTable.targetType eq TargetType.MCP.name)
            }
            // Insert new MCP configs with metadata
            configs.forEach { config ->
                toolTable.insert {
                    it[toolTable.id] = UUID.randomUUID().toString()
                    it[toolTable.agentId] = agentId
                    it[toolTable.userId] = userId
                    it[toolTable.targetType] = TargetType.MCP.name
                    it[toolTable.targetName] = config.targetName
                    it[toolTable.metadata] = config.metadata
                }
            }
            logger.info("Saved {} MCP configs for agent {} of owner {}", configs.size, agentId, userId)
        }
    }

    override suspend fun saveAgentInlineSpecs(
        agentId: String,
        targetType: TargetType,
        specs: List<AgentToolConfig>,
        userId: String
    ) {
        val toolTable = Tables.AgentToolTable
        suspendTransaction(db) {
            // Delete existing inline entries (targetName starts with "inline:") for this agent + targetType
            toolTable.deleteWhere {
                (toolTable.agentId eq agentId) and
                (toolTable.userId eq userId) and
                (toolTable.targetType eq targetType.name) and
                (toolTable.targetName like "inline:%")
            }
            // Insert new inline specs with metadata JSON
            specs.forEach { config ->
                toolTable.insert {
                    it[toolTable.id] = UUID.randomUUID().toString()
                    it[toolTable.agentId] = agentId
                    it[toolTable.userId] = userId
                    it[toolTable.targetType] = targetType.name
                    it[toolTable.targetName] = config.targetName
                    it[toolTable.metadata] = config.metadata
                }
            }
            logger.info("Saved {} inline {} specs for agent {} of owner {}", specs.size, targetType.name, agentId, userId)
        }
    }

    override suspend fun count(): Long {
        return suspendTransaction(db) {
            Tables.AgentTable.selectAll().count()
        }
    }

    private fun toAgentWithoutTools(row: ResultRow): AgentDefinition = AgentDefinition(
        id = row[Tables.AgentTable.id],
        name = row[Tables.AgentTable.name],
        agentType = AgentType.fromString(row[Tables.AgentTable.agentType]),
        agentContext = AgentEnv.fromString(row[Tables.AgentTable.agentContext]),
        description = row[Tables.AgentTable.description],
        promptTemplate = row[Tables.AgentTable.promptTemplate],
        customInstructions = row[Tables.AgentTable.customInstructions],
        toolNames = emptyList(),
        maxIterations = row[Tables.AgentTable.maxIterations],
        maxSubAgentDepth = row[Tables.AgentTable.maxSubAgentDepth],
        color = row[Tables.AgentTable.color],
        enabled = row[Tables.AgentTable.enabled],
        instructionsEnabled = row[Tables.AgentTable.instructionsEnabled],
        toolFoldEnabled = row[Tables.AgentTable.toolFoldEnabled],
        toolFoldKeepRecentRuns = row[Tables.AgentTable.toolFoldKeepRecentRuns],
        thinkingHistoryEnabled = row[Tables.AgentTable.thinkingHistoryEnabled],
        inputSchema = row[Tables.AgentTable.inputSchema],
        outputSchema = row[Tables.AgentTable.outputSchema],
        outputSchemaMultiTurn = row[Tables.AgentTable.outputSchemaMultiTurn],
        userId = row[Tables.AgentTable.userId],
        createdAt = row[Tables.AgentTable.createdAt],
        updatedAt = row[Tables.AgentTable.updatedAt]
    )
}