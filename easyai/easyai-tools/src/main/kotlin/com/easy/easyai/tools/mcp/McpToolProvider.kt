package com.easy.easyai.tools.mcp

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentToolConfig
import com.easy.easyai.core.agent.McpBindingSpec
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

    /**
     * Returns all available MCP tools across all connected servers visible to the user.
     * Includes both user-owned and system-level servers (UserScope semantics).
     * Lazily connects user-specific MCP servers on first access.
     * Called from SessionToolResolver.createSessionTools().
     *
     * Servers are shadowed by name across the owner set (self → group → system): a tool is named
     * `{server}__{tool}` with no owner component, so two buckets holding a same-named server would
     * otherwise produce two identical tool names and route a call to an arbitrary one.
     */
    suspend fun getTools(agentContext: AgentContext): List<ToolDefinition> {
        val owners = agentContext.effectiveOwners
        manager.ensureOwnersConnected(owners)
        return manager.getVisibleServers(owners).flatMap { server ->
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

        val owners = agentContext.effectiveOwners
        manager.ensureOwnersConnected(owners)
        val configByServer = mcpConfigs.associateBy { it.targetName }
        return manager.getVisibleServers(owners).flatMap { server ->
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
        val spec = McpBindingSpec.parseOrNull(metadata)
        if (spec == null) {
            if (!metadata.isNullOrBlank()) {
                logger.warn("Failed to parse MCP tool whitelist metadata, allowing all tools: {}", metadata)
            }
            return emptySet()
        }
        return spec.toolNames.toSet()
    }
}
