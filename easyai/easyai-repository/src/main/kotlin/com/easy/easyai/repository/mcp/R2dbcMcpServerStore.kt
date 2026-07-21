package com.easy.easyai.repository.mcp

import com.easy.easyai.tools.mcp.AsyncMcpServerStore
import com.easy.easyai.tools.mcp.McpServerConfig
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.*
import com.easy.easyai.repository.database.Tables
import com.easy.easyai.repository.database.UserScope
import org.slf4j.LoggerFactory
import com.easy.easyai.common.util.SharedObjectMapper
import tools.jackson.module.kotlin.readValue

/**
 * R2DBC-based implementation of AsyncMcpServerStore.
 * Uses Exposed R2DBC with suspendTransaction for all async operations.
 */
class R2dbcMcpServerStore(
    private val db: R2dbcDatabase
) : AsyncMcpServerStore {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper = SharedObjectMapper.instance

    override suspend fun findAll(userId: String): List<McpServerConfig> = suspendTransaction(db) {
        Tables.McpServerConfigTable.selectAll()
            .where(UserScope.filterStrict(Tables.McpServerConfigTable.userId, userId))
            .orderBy(Tables.McpServerConfigTable.createdAt to SortOrder.ASC)
            .toList()
            .map { it.toConfig() }
    }

    override suspend fun findByName(name: String, userId: String): McpServerConfig? = suspendTransaction(db) {
        Tables.McpServerConfigTable.selectAll()
            .where { (Tables.McpServerConfigTable.name eq name) and UserScope.filterStrict(Tables.McpServerConfigTable.userId, userId) }
            .limit(1)
            .firstOrNull()
            ?.toConfig()
    }

    override suspend fun save(config: McpServerConfig, userId: String): Unit = suspendTransaction(db) {
        Tables.McpServerConfigTable.insert {
            it[id] = config.id
            it[name] = config.name
            it[type] = config.type
            it[command] = config.command?.let { cmd -> objectMapper.writeValueAsString(cmd) }
            it[env] = if (config.env.isNotEmpty()) objectMapper.writeValueAsString(config.env) else null
            it[url] = config.url
            it[headers] = if (config.headers.isNotEmpty()) objectMapper.writeValueAsString(config.headers) else null
            it[cwd] = config.cwd
            it[timeoutSeconds] = config.timeoutSeconds
            it[enabled] = config.enabled
            it[Tables.McpServerConfigTable.userId] = userId
            it[createdAt] = config.createdAt
            it[updatedAt] = config.updatedAt
        }
        logger.info("Saved MCP server config: {}", config.name)
    }

    override suspend fun update(config: McpServerConfig, userId: String): Unit = suspendTransaction(db) {
        Tables.McpServerConfigTable.update(
            where = { (Tables.McpServerConfigTable.name eq config.name) and UserScope.filterStrict(Tables.McpServerConfigTable.userId, userId) }
        ) {
            it[type] = config.type
            it[command] = config.command?.let { cmd -> objectMapper.writeValueAsString(cmd) }
            it[env] = if (config.env.isNotEmpty()) objectMapper.writeValueAsString(config.env) else null
            it[url] = config.url
            it[headers] = if (config.headers.isNotEmpty()) objectMapper.writeValueAsString(config.headers) else null
            it[cwd] = config.cwd
            it[timeoutSeconds] = config.timeoutSeconds
            it[enabled] = config.enabled
            it[updatedAt] = config.updatedAt
        }
        logger.info("Updated MCP server config: {}", config.name)
    }

    override suspend fun delete(name: String, userId: String): Unit = suspendTransaction(db) {
        Tables.McpServerConfigTable.deleteWhere {
            (Tables.McpServerConfigTable.name eq name) and UserScope.filterStrict(Tables.McpServerConfigTable.userId, userId)
        }
        logger.info("Deleted MCP server config: {}", name)
    }

    override suspend fun findAllEnabled(): List<McpServerConfig> = suspendTransaction(db) {
        Tables.McpServerConfigTable.selectAll()
            .where { Tables.McpServerConfigTable.enabled eq true }
            .orderBy(Tables.McpServerConfigTable.createdAt to SortOrder.ASC)
            .toList()
            .map { it.toConfig() }
    }

    private fun ResultRow.toConfig(): McpServerConfig {
        val t = Tables.McpServerConfigTable
        val commandJson = this[t.command]
        val envJson = this[t.env]
        val headersJson = this[t.headers]
        return McpServerConfig(
            id = this[t.id],
            name = this[t.name],
            type = this[t.type],
            command = commandJson?.let { objectMapper.readValue<List<String>>(it) },
            env = envJson?.let { objectMapper.readValue<Map<String, String>>(it) } ?: emptyMap(),
            url = this[t.url],
            headers = headersJson?.let { objectMapper.readValue<Map<String, String>>(it) } ?: emptyMap(),
            cwd = this[t.cwd],
            timeoutSeconds = this[t.timeoutSeconds],
            enabled = this[t.enabled],
            userId = this[t.userId],
            createdAt = this[t.createdAt],
            updatedAt = this[t.updatedAt]
        )
    }
}
