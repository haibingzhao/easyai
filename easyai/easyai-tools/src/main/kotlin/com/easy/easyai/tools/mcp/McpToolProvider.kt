package com.easy.easyai.tools.mcp

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentToolConfig
import com.easy.easyai.core.tool.ToolDefinition
import org.slf4j.LoggerFactory

/**
 * Provides MCP tools for injection into sessions.
 * Queries McpClientManager for connected servers and converts their tools
 * into McpToolDefinition instances with correct ownerUserId for callTool routing.
 *
 * Bean is registered via R2dbcRepositoryAutoConfiguration (not @Service)
 * so it only exists when MCP dependencies are fully available.
 */
class McpToolProvider(
    private val manager: McpClientManager
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private val objectMapper = SharedObjectMapper.instance
    }

    /**
     * Returns all available MCP tools across all connected servers visible to the user.
     * Includes both user-owned and system-level servers (UserScope semantics).
     * Lazily connects user-specific MCP servers on first access.
     * Called from SessionToolResolver.createSessionTools().
     */
    suspend fun getTools(agentContext: AgentContext): List<ToolDefinition> {
        val userId = agentContext.userId ?: McpClientManager.SYSTEM_USER_ID
        manager.ensureUserConnected(userId)
        return manager.getConnectedServers(userId).flatMap { server ->
            server.tools.map { tool ->
                McpToolDefinition(server.serverName, tool, manager, server.userId)
            }
        }
    }

    /**
     * Returns MCP tools filtered by agent's MCP configs.
     * Lazily connects user-specific MCP servers on first access.
     * - Empty configs = no MCP tools available (whitelist mode)
     * - Non-empty configs = only tools from bound servers, further filtered by metadata tool whitelist
     */
    suspend fun getTools(agentContext: AgentContext, mcpConfigs: List<AgentToolConfig>): List<ToolDefinition> {
        if (mcpConfigs.isEmpty()) {
            return emptyList()
        }

        val userId = agentContext.userId ?: McpClientManager.SYSTEM_USER_ID
        manager.ensureUserConnected(userId)
        val configByServer = mcpConfigs.associateBy { it.targetName }
        return manager.getConnectedServers(userId).flatMap { server ->
            val config = configByServer[server.serverName] ?: return@flatMap emptyList()
            val allowedTools = parseToolNames(config.metadata)
            server.tools
                .filter { tool -> allowedTools.isEmpty() || tool.name() in allowedTools }
                .map { tool -> McpToolDefinition(server.serverName, tool, manager, server.userId) }
        }
    }

    /**
     * Parse tool whitelist from AgentToolConfig metadata.
     * Supports both legacy array format `["tool1","tool2"]` and new object format `{"toolNames":[...],...}`.
     * Returns empty set on null/blank/parse-error (= allow all tools).
     */
    private fun parseToolNames(metadata: String?): Set<String> {
        if (metadata.isNullOrBlank()) return emptySet()
        return try {
            val node = objectMapper.readTree(metadata)
            if (node.isArray) {
                val result = mutableSetOf<String>()
                for (element in node) { result.add(element.asString()) }
                result
            } else {
                val toolNamesNode = node.get("toolNames")
                if (toolNamesNode != null && toolNamesNode.isArray) {
                    val result = mutableSetOf<String>()
                    for (element in toolNamesNode) { result.add(element.asString()) }
                    result
                } else {
                    emptySet()
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse MCP tool whitelist metadata, allowing all tools: {}", metadata, e)
            emptySet()
        }
    }
}
