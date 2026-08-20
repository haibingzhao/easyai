package com.easy.easyai.tools.knowledge

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.knowledge.KnowledgeStore
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolDefinition

/**
 * Base builder for knowledge tools (search, read).
 * Provides shared permission rules and build logic that depends on [KnowledgeStore].
 */
abstract class AbstractKnowledgeToolBuilder : ToolBuilder {
    override val defaultPermissionRules = listOf(
        PermissionRule("tool.execute.knowledge", "*", PermissionAction.ALLOW)
    )

    override fun build(context: AgentContext, agentService: AgentService): ToolDefinition? {
        val store = agentService.knowledgeStore ?: return null
        return createTool(store)
    }

    protected abstract fun createTool(store: KnowledgeStore): ToolDefinition
}
