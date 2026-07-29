package com.easy.easyai.core.agent

import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.core.tool.ToolDefinition

/**
 * Factory interface for building Agent instances from database definitions.
 *
 * Agent now receives only two parameters: AgentContext and AgentService.
 * All system-level dependencies are provided via AgentService during construction.
 */
interface AgentBuilder {
    /**
     * Build an Agent from a database definition.
     * @param definition the agent definition from database
     * @param services the agent service providing all infrastructure dependencies
     * @param availableTools all available tools filtered by definition.toolNames
     * @return configured Agent instance
     */
    fun build(
        definition: AgentDefinition,
        services: AgentService,
        availableTools: List<ToolDefinition>
    ): Agent

    /**
     * Build an Agent with configuration.
     * @param agentContext the pre-built AgentContext with identity fields (agentId, mode, projectPath, etc.)
     * @param sessionId the session ID (used as fallback if agentContext.sessionId is null)
     * @param config the model provider config
     * @param services the agent service providing infrastructure dependencies
     * @param tools the available tools
     * @return configured Agent instance
     */
    fun buildWithConfig(
        agentContext: AgentContext,
        sessionId: String,
        config: ModelProviderConfig,
        services: AgentService,
        tools: List<ToolDefinition>
    ): Agent

    /**
     * Default implementation that creates an Agent from AgentDefinition.
     */
    companion object Default : AgentBuilder {
        override fun build(
            definition: AgentDefinition,
            services: AgentService,
            availableTools: List<ToolDefinition>
        ): Agent {
            val filteredTools = if (definition.toolNames.isEmpty()) {
                emptyList()
            } else {
                availableTools.filter { it.name in definition.toolNames }
            }
            return Agent(
                context = AgentContext(
                    agentId = definition.id,
                    promptTemplate = definition.promptTemplate,
                    customInstructions = definition.customInstructions,
                    tools = filteredTools,
                    maxIterations = 50
                ),
                services = services
            )
        }

        override fun buildWithConfig(
            agentContext: AgentContext,
            sessionId: String,
            config: ModelProviderConfig,
            services: AgentService,
            tools: List<ToolDefinition>
        ): Agent {
            val resolvedSessionId = agentContext.sessionId ?: sessionId
            val finalContext = agentContext.copy(
                modelConfig = config,
                tools = tools,
                sessionId = resolvedSessionId,
                maxIterations = 50
            )
            return Agent(context = finalContext, services = services)
        }
    }
}
