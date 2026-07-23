package com.easy.easyai.tools.mcp

/**
 * Async store interface for persisting MCP server configurations.
 * All methods use Kotlin coroutines (suspend functions).
 */
interface AsyncMcpServerStore {
    suspend fun findAll(userId: String = "system"): List<McpServerConfig>
    suspend fun findByName(name: String, userId: String = "system"): McpServerConfig?
    suspend fun save(config: McpServerConfig, userId: String = "system")
    suspend fun update(config: McpServerConfig, userId: String = "system")
    suspend fun delete(name: String, userId: String = "system")

    /** Find all enabled configs across all users (for startup initialization). */
    suspend fun findAllEnabled(): List<McpServerConfig>

    /** Find all enabled configs for a specific user (for lazy per-user initialization). */
    suspend fun findAllEnabled(userId: String): List<McpServerConfig>
}
