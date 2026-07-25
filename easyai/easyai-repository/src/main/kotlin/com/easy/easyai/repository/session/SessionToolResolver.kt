package com.easy.easyai.repository.session

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentDefinition
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.agent.AsyncAgentStore
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolFactory
import com.easy.easyai.repository.project.AsyncProjectStore
import com.easy.easyai.tools.mcp.McpToolProvider
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves project paths and tools for sessions.
 * Handles project lookup, tool creation with project scoping, and agent-specific tool filtering.
 *
 * Uses [ToolFactory] and [AgentService] to create tools via ToolBuilder pattern.
 * All tools (including TodoWriteTool and SubAgentTool) are created uniformly by their builders.
 */
class SessionToolResolver(
    private val projectStore: AsyncProjectStore?,
    private val toolFactory: ToolFactory,
    private val agentService: AgentService?,
    private val mcpToolProvider: McpToolProvider? = null,
    private val agentStore: AsyncAgentStore? = null
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Resolve project path from projectId using the projectStore.
     * Returns null if projectId is null, project not found, or path is invalid.
     */
    suspend fun resolveProjectPath(projectId: String?, userId: String = "system"): Path? {
        if (projectId == null || projectStore == null) return null
        return try {
            val project = projectStore.findById(projectId, userId)
            project?.path?.let {
                val path = Path.of(it).toAbsolutePath().normalize()
                if (!Files.exists(path)) {
                    logger.warn("Project path does not exist for projectId {}: {}", projectId, path)
                    null
                } else {
                    path
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to resolve project path for projectId {}: {}", projectId, e.message)
            null
        }
    }

    /**
     * Create all tools for a session using ToolBuilder pattern.
     * Uses the upstream [agentContext] directly so session-scoped tools
     * (TodoWriteTool, SubAgentTool) are correctly bound with the real agentId.
     *
     * Callers that need to override specific fields should use `agentContext.copy(...)`.
     */
    suspend fun createSessionTools(agentContext: AgentContext): List<ToolDefinition> {
        val service = agentService ?: return emptyList()
        val staticTools = toolFactory.createTools(agentContext, service)
        val mcpTools = resolveMcpTools(agentContext)
        return staticTools + mcpTools
    }

    /**
     * Resolve tools for an agent, respecting agentDef.toolNames.
     * Static tools are filtered by agentDef.toolNames; MCP tools have their own
     * filtering via getAgentMcpConfigs and are not subject to toolNames.
     * Tools with [ToolDefinition.alwaysInclude] (e.g., team coordination tools)
     * bypass toolNames filtering — their builders already guard applicability.
     */
    suspend fun resolveToolsForAgent(agentDef: AgentDefinition, agentContext: AgentContext): List<ToolDefinition> {
        val service = agentService ?: return emptyList()
        val staticTools = toolFactory.createTools(agentContext, service)
        val filteredStatic = if (agentDef.toolNames.isEmpty())
            staticTools.filter { it.alwaysInclude }
        else
            staticTools.filter { it.name in agentDef.toolNames || it.alwaysInclude }
        // MCP tools have their own filtering via getAgentMcpConfigs, not subject to toolNames
        val mcpTools = resolveMcpTools(agentContext)
        return filteredStatic + mcpTools
    }

    /**
     * Resolve MCP tools with agent-level filtering.
     * Prefers explicit [AgentContext.mcpConfigs] when present (inline agents without a DB row);
     * otherwise, if agentStore is available, queries MCP configs for per-agent filtering.
     * Falls back to all MCP tools when no store is available.
     */
    private suspend fun resolveMcpTools(agentContext: AgentContext): List<ToolDefinition> {
        val provider = mcpToolProvider ?: return emptyList()
        if (agentContext.mcpConfigs.isNotEmpty()) {
            return provider.getTools(agentContext, agentContext.mcpConfigs)
        }
        val store = agentStore ?: return provider.getTools(agentContext)
        val mcpConfigs = store.getAgentMcpConfigs(agentContext.agentId)
        return provider.getTools(agentContext, mcpConfigs)
    }
}
