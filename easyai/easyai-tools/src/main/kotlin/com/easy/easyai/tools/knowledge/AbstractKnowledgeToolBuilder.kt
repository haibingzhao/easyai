package com.easy.easyai.tools.knowledge

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.knowledge.KnowledgeOwnership
import com.easy.easyai.core.knowledge.KnowledgeStore
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolDefinition

/**
 * Base builder for knowledge tools (search, read).
 * Provides shared permission rules and build logic that depends on [KnowledgeStore].
 *
 * The knowledge slice owner is resolved once at build time from the agent context: the group bucket
 * when `easyai.knowledge.shared-within-group` is on and the context carries one, otherwise the user.
 */
abstract class AbstractKnowledgeToolBuilder(
    protected val sharedWithinGroup: Boolean
) : ToolBuilder {
    override val defaultPermissionRules = listOf(
        PermissionRule("tool.execute.knowledge", "*", PermissionAction.ALLOW)
    )

    override fun build(context: AgentContext, agentService: AgentService): ToolDefinition? {
        val store = agentService.knowledgeStore ?: return null
        val knowledgeOwnerId = KnowledgeOwnership.ownerId(
            context.userId,
            KnowledgeOwnership.groupBucket(context.userId, context.owners),
            sharedWithinGroup
        )
        return createTool(store, knowledgeOwnerId)
    }

    protected abstract fun createTool(store: KnowledgeStore, knowledgeOwnerId: String): ToolDefinition
}
