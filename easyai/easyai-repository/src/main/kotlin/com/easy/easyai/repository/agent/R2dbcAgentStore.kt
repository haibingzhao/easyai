package com.easy.easyai.repository.agent

import com.easy.easyai.core.agent.*
import com.easy.easyai.repository.database.Tables
import com.easy.easyai.repository.database.UserScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
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
                    it[Tables.AgentTable.inputSchema] = agent.inputSchema
                    it[Tables.AgentTable.outputSchema] = agent.outputSchema
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
                    it[Tables.AgentTable.inputSchema] = agent.inputSchema
                    it[Tables.AgentTable.outputSchema] = agent.outputSchema
                    it[Tables.AgentTable.userId] = userId
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                logger.info("Inserted agent: {}", agent.id)
            }
        }
    }

    override suspend fun findById(id: String, userId: String): AgentDefinition? {
        val agent = suspendTransaction(db) {
            val row = Tables.AgentTable
                .selectAll()
                .where { (Tables.AgentTable.id eq id) and UserScope.filter(Tables.AgentTable.userId, userId) }
                .limit(1)
                .firstOrNull() ?: return@suspendTransaction null
            toAgentWithoutTools(row)
        }
        return agent?.copy(toolNames = getAgentToolNames(agent.id))
    }

    override suspend fun findByIds(ids: Collection<String>, userId: String): Map<String, AgentDefinition> {
        if (ids.isEmpty()) return emptyMap()
        val idList = ids.toList()

        // Single query: load all agent rows matching the given IDs
        val agents = suspendTransaction(db) {
            Tables.AgentTable
                .selectAll()
                .where {
                    (Tables.AgentTable.id inList idList) and UserScope.filter(Tables.AgentTable.userId, userId)
                }
                .map { row -> toAgentWithoutTools(row) }
                .toList()
        }

        // Batch-load tool names for all agents in one query
        val toolNamesByAgentId = batchLoadToolNames(agents.map { it.id })

        return agents.associateBy(
            keySelector = { it.id },
            valueTransform = { it.copy(toolNames = toolNamesByAgentId[it.id].orEmpty()) }
        )
    }

    /**
     * Batch-load TOOL target names for multiple agents in a single query.
     * Returns a map of agentId → list of tool names.
     */
    private suspend fun batchLoadToolNames(agentIds: List<String>): Map<String, List<String>> {
        if (agentIds.isEmpty()) return emptyMap()
        val toolTable = Tables.AgentToolTable
        return suspendTransaction(db) {
            toolTable
                .selectAll()
                .where {
                    (toolTable.agentId inList agentIds) and (toolTable.targetType eq TargetType.TOOL.name)
                }
                .toList()
                .groupBy({ it[toolTable.agentId] }, { it[toolTable.targetName] })
        }
    }

    override suspend fun findAll(userId: String): List<AgentDefinition> = queryAgentsWithTools(userId = userId)

    override suspend fun findByType(agentType: AgentType, userId: String): List<AgentDefinition> = queryAgentsWithTools(
        userId = userId
    ) {
        Tables.AgentTable.agentType eq agentType.name
    }

    override suspend fun findSubAgents(userId: String): List<AgentDefinition> = queryAgentsWithTools(
        userId = userId
    ) {
        ((Tables.AgentTable.agentType eq AgentType.SUBAGENT.name) or
            (Tables.AgentTable.agentType eq AgentType.ALL.name)) and
            ((Tables.AgentTable.agentContext eq AgentEnv.CHAT.name) or
                (Tables.AgentTable.agentContext eq AgentEnv.BOTH.name))
    }

    override suspend fun findChatAgents(userId: String): List<AgentDefinition> = queryAgentsWithTools(
        userId = userId
    ) {
        (Tables.AgentTable.agentContext eq AgentEnv.CHAT.name) or
        (Tables.AgentTable.agentContext eq AgentEnv.BOTH.name)
    }

    private suspend fun queryAgentsWithTools(
        userId: String = UserScope.SYSTEM_USER_ID,
        condition: () -> org.jetbrains.exposed.v1.core.Op<Boolean>? = { null }
    ): List<AgentDefinition> {
        val agents = suspendTransaction(db) {
            val query = Tables.AgentTable.selectAll()
            val cond = condition()
            val userFilter = UserScope.filter(Tables.AgentTable.userId, userId)
            if (cond != null) {
                query.where(cond and userFilter)
            } else {
                query.where(userFilter)
            }
            query.map { row -> toAgentWithoutTools(row) }.toList()
        }
        return agents.map { agent -> agent.copy(toolNames = getAgentToolNames(agent.id)) }
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
                Tables.AgentToolTable.deleteWhere { Tables.AgentToolTable.agentId eq id }
                Tables.AgentTable.deleteWhere {
                    (Tables.AgentTable.id eq id) and UserScope.filterStrict(Tables.AgentTable.userId, userId)
                }
                logger.info("Deleted agent: {}", id)
            } else {
                logger.debug("Agent {} not owned by user {}, skipping delete", id, userId)
            }
        }
    }

    override suspend fun saveAgentTools(agentId: String, toolNames: List<String>) {
        saveAgentToolConfigs(agentId, TargetType.TOOL, toolNames)
    }

    override suspend fun getAgentToolNames(agentId: String): List<String> {
        return getAgentToolConfigs(agentId, TargetType.TOOL).map { it.targetName }
    }

    override suspend fun saveAgentToolConfigs(agentId: String, targetType: TargetType, targetNames: List<String>) {
        val toolTable = Tables.AgentToolTable
        suspendTransaction(db) {
            toolTable.deleteWhere {
                (toolTable.agentId eq agentId) and
                (toolTable.targetType eq targetType.name)
            }
            targetNames.forEach { name ->
                toolTable.insert {
                    it[toolTable.id] = UUID.randomUUID().toString()
                    it[toolTable.agentId] = agentId
                    it[toolTable.targetType] = targetType.name
                    it[toolTable.targetName] = name
                }
            }
            logger.info("Saved {} {} configs for agent {}", targetNames.size, targetType, agentId)
        }
    }

    override suspend fun getAgentToolConfigs(agentId: String, targetType: TargetType): List<AgentToolConfig> {
        return suspendTransaction(db) {
            Tables.AgentToolTable
                .selectAll()
                .where {
                    (Tables.AgentToolTable.agentId eq agentId) and
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

    override suspend fun getAgentSubAgentNames(agentId: String): List<String> {
        return getAgentToolConfigs(agentId, TargetType.SUBAGENT).map { it.targetName }
    }

    override suspend fun saveAgentMcpConfigs(agentId: String, configs: List<AgentToolConfig>) {
        val toolTable = Tables.AgentToolTable
        suspendTransaction(db) {
            // Delete existing MCP configs for this agent
            toolTable.deleteWhere {
                (toolTable.agentId eq agentId) and
                (toolTable.targetType eq TargetType.MCP.name)
            }
            // Insert new MCP configs with metadata
            configs.forEach { config ->
                toolTable.insert {
                    it[toolTable.id] = UUID.randomUUID().toString()
                    it[toolTable.agentId] = agentId
                    it[toolTable.targetType] = TargetType.MCP.name
                    it[toolTable.targetName] = config.targetName
                    it[toolTable.metadata] = config.metadata
                }
            }
            logger.info("Saved {} MCP configs for agent {}", configs.size, agentId)
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
        inputSchema = row[Tables.AgentTable.inputSchema],
        outputSchema = row[Tables.AgentTable.outputSchema],
        userId = row[Tables.AgentTable.userId],
        createdAt = row[Tables.AgentTable.createdAt],
        updatedAt = row[Tables.AgentTable.updatedAt]
    )
}