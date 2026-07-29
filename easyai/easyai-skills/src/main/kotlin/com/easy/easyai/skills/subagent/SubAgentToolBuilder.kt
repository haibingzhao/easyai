package com.easy.easyai.skills.subagent

import com.easy.easyai.core.agent.*
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component

/**
 * Builder for [SubAgentTool].
 * 
 * Returns null (tool not registered) when:
 * - The current agent is itself a sub-agent (parentAgentId != null) — prevents recursion
 * - No AsyncAgentStore is available — SubAgentTool requires it
 *
 * Note: [subAgentContextResolver] uses `@Lazy` on the constructor parameter to break the
 * circular dependency chain: ToolFactory → SubAgentToolBuilder → SubAgentContextResolver → SessionToolResolver → ToolFactory.
 * Spring injects a lazy proxy; the real bean is resolved only when first accessed at runtime.
 */
@Component
class SubAgentToolBuilder(
    private val agentStore: AsyncAgentStore?,
    @param:Lazy private val subAgentContextResolver: SubAgentContextResolver? = null,
    private val subAgentMessageListenerFactory: SubAgentMessageListenerFactory? = null
) : ToolBuilder {
    override val metadata = ToolMetadata(
        name = "task",
        description = "Launch a sub-agent for independent work. " +
            "Call this tool with name 'task' to delegate focused work to a specialized agent. " +
            "Available sub-agent types are listed in the system prompt. " +
            "The sub-agent runs independently and returns its result.",
        permissionCategory = "subagent"
    )
    override val defaultPermissionRules = listOf(
        PermissionRule("tool.execute.subagent", "*", PermissionAction.ALLOW)
    )
    override fun build(context: AgentContext, agentService: AgentService): ToolDefinition? {
        // If this agent is itself a sub-agent, don't register the tool — prevents recursion
        if (context.parentAgentId != null) {
            return null
        }

        // If no sub-agents are configured for this agent, don't register the tool
        if (context.subAgents.isEmpty()) {
            return null
        }

        // If no agentStore is available, don't register the tool
        val store = agentStore ?: return null

        val sessionId = context.sessionId
        val listenerFactory = if (sessionId != null && subAgentMessageListenerFactory != null) {
            { sid: String, ctx: AgentContext, parentMsgId: String, parentToolCallId: String ->
                subAgentMessageListenerFactory.create(sid, ctx, parentMsgId, parentToolCallId)
            }
        } else {
            null
        }

        return SubAgentTool(
            metadata = metadata,
            agentStore = store,
            agentService = agentService,
            contextResolver = subAgentContextResolver,
            subAgentMessageListenerFactory = listenerFactory
        )
    }
}
