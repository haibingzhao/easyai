package com.easy.easyai.tools.memory

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolDefinition

/**
 * Base builder for memory tools (search, read, write, list).
 * Provides shared permission rules and build logic that depends on [MemoryStore].
 */
abstract class AbstractMemoryToolBuilder : ToolBuilder {
    override val defaultPermissionRules = listOf(
        PermissionRule("tool.execute.memory", "*", PermissionAction.ALLOW)
    )

    override fun build(context: AgentContext, agentService: AgentService): ToolDefinition? {
        val store = agentService.memoryStore ?: return null
        return createTool(store)
    }

    protected abstract fun createTool(store: MemoryStore): ToolDefinition
}
