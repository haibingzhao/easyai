package com.easy.easyai.repository.session

import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.core.agent.*
import com.easy.easyai.core.event.MessageListener
import com.easy.easyai.core.tool.ToolDefinition

/**
 * Factory for creating and rebuilding Agent instances for sessions.
 * Handles agent creation with proper context enrichment, todo manager creation, and message listener setup.
 *
 * @property messageTimestampsProvider Optional provider for message timestamps (messageId -> createdAt).
 *   Used by context compaction to determine correct ordering of the summary message.
 */
class SessionAgentFactory(
    private val agentBuilder: AgentBuilder,
    private val agentService: AgentService,
    private val defaultSystemPrompt: String,
    private val messageListenerFactory: ((String, AgentContext) -> MessageListener?)? = null,
    private val messageTimestampsProvider: (suspend (String) -> Map<String, Long>)? = null
) {
    /**
     * Create a default agent with basic configuration.
     */
    fun createDefaultAgent(): Agent {
        return agentBuilder.build(
            definition = AgentDefinition.create(
                id = DEFAULT_AGENT_ID,
                name = DEFAULT_AGENT_ID,
                customInstructions = defaultSystemPrompt,
                toolNames = emptyList()
            ),
            services = agentService,
            availableTools = emptyList()
        )
    }

    /**
     * Create an agent with ModelProviderConfig.
     */
    suspend fun createAgentWithConfig(
        sessionId: String,
        config: ModelProviderConfig,
        tools: List<ToolDefinition>,
        agentContext: AgentContext
    ): Agent {
        return buildAgent(sessionId, config, tools, agentContext)
    }

    /**
     * Create an agent with AgentDefinition.
     * Reads promptTemplate and customInstructions from agentDef and sets them on agentContext.
     */
    suspend fun createAgentWithAgentDef(
        agentDef: AgentDefinition,
        sessionId: String,
        config: ModelProviderConfig,
        tools: List<ToolDefinition>,
        agentContext: AgentContext
    ): Agent {
        // Enrich agentContext with prompt data and behavior config from agent definition
        val enrichedContext = agentContext.copy(
            promptTemplate = agentDef.promptTemplate,
            customInstructions = agentDef.customInstructions,
            maxIterations = agentDef.maxIterations,
            inputSchema = agentDef.inputSchema,
            outputSchema = agentDef.outputSchema,
            outputSchemaMultiTurn = agentDef.outputSchemaMultiTurn
        )
        return buildAgent(sessionId, config, tools, enrichedContext)
    }

    /**
     * Core agent building logic shared across all creation methods.
     */
    private fun buildAgent(
        sessionId: String,
        config: ModelProviderConfig,
        tools: List<ToolDefinition>,
        agentContext: AgentContext
    ): Agent {
        val timestampsProvider = messageTimestampsProvider
        val sessionService = if (messageListenerFactory != null || timestampsProvider != null) {
            val listener = messageListenerFactory?.invoke(sessionId, agentContext)
            SessionAgentService(
                delegate = agentService,
                messageListener = listener,
                timestampsProvider = timestampsProvider?.let { provider -> { provider(sessionId) } }
            )
        } else {
            agentService
        }

        return agentBuilder.buildWithConfig(
            agentContext = agentContext,
            sessionId = sessionId,
            config = config,
            services = sessionService,
            tools = tools
        )
    }

}

/**
 * AgentService wrapper that adds a session-specific message listener
 * and optional message timestamps provider for context compaction.
 */
private class SessionAgentService(
    private val delegate: AgentService,
    override val messageListener: MessageListener?,
    private val timestampsProvider: (suspend () -> Map<String, Long>)? = null
) : AgentService by delegate {

    override suspend fun getMessageTimestamps(): Map<String, Long> {
        return timestampsProvider?.invoke() ?: delegate.getMessageTimestamps()
    }
}

private const val DEFAULT_AGENT_ID = "default-agent"
