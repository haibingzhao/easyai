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

    // ─── Group-aware reads ───────────────────────────────────────────────────────
    // MCP configs never folded in the `system` layer (the single-id form is a strict owner match), so
    // callers pass a group-owners set (`SecurityUtils.currentGroupOwners()`: self + group bucket, no
    // system). Defaults fan out over the single-id form; the R2dbc store overrides with one IN query.

    /** Every config owned by any of [owners]. */
    suspend fun findAll(owners: Collection<String>): List<McpServerConfig> =
        owners.distinct().flatMap { findAll(it) }.distinctBy { it.id }

    /** The config named [name] owned by any of [owners]; the first owner with it wins. */
    suspend fun findByName(name: String, owners: Collection<String>): McpServerConfig? =
        owners.distinct().mapNotNull { findByName(name, it) }.firstOrNull()
}
