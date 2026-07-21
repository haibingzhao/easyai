package com.easy.easyai.core.tool

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService

/**
 * Factory for creating tool instances. Centralizes tool creation and metadata
 * so that callers do not need to know concrete tool classes.
 */
interface ToolFactory {
    /**
     * Get all registered [ToolBuilder] beans for metadata queries.
     * Does not create Tool instances - use for seed initialization,
     * tool registry, permission service, etc.
     */
    fun getBuilders(): List<ToolBuilder>

    /**
     * Create tools using AgentContext and AgentService.
     * Filters builders by [allowedToolNames] before creating instances.
     * 
     * @param context Agent context providing identity, project path, session info.
     * @param agentService Agent service providing infrastructure dependencies.
     * @param allowedToolNames Tool names to include. Empty = all tools.
     * @return List of created ToolDefinition instances.
     */
    fun createTools(
        context: AgentContext,
        agentService: AgentService,
        allowedToolNames: List<String> = emptyList()
    ): List<ToolDefinition>
}
