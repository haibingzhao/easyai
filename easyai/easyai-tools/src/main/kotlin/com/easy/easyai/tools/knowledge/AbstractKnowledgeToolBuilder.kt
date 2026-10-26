package com.easy.easyai.tools.knowledge

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.knowledge.KnowledgeAccessContext
import com.easy.easyai.core.knowledge.KnowledgeAccessGuard
import com.easy.easyai.core.knowledge.KnowledgeOwnership
import com.easy.easyai.core.knowledge.KnowledgeStore
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolDefinition
import org.springframework.beans.factory.ObjectProvider

/**
 * Base builder for knowledge tools (search, read).
 * Provides shared permission rules and build logic that depends on [KnowledgeStore].
 *
 * The knowledge slice owner is resolved once at build time from the agent context: the group bucket
 * when `easyai.knowledge.shared-within-group` is on and the context carries one, otherwise the user.
 * Access is enforced by the host [KnowledgeAccessGuard]; without a policy/guard or a login user the
 * tools stay unavailable instead of running unguarded.
 */
abstract class AbstractKnowledgeToolBuilder(
    protected val sharedWithinGroup: Boolean,
    private val accessGuardProvider: ObjectProvider<KnowledgeAccessGuard>,
) : ToolBuilder {
    override val defaultPermissionRules = listOf(
        PermissionRule("tool.execute.knowledge", "*", PermissionAction.ALLOW)
    )

    override suspend fun build(context: AgentContext, agentService: AgentService): ToolDefinition? {
        val store = agentService.knowledgeStore ?: return null
        val guard = accessGuardProvider.getIfAvailable() ?: return null
        val userId = context.userId?.takeIf { it.isNotBlank() } ?: return null
        val groupUserId = KnowledgeOwnership.groupBucket(userId, context.owners)
        val knowledgeOwnerId = KnowledgeOwnership.ownerId(userId, groupUserId, sharedWithinGroup)
        val accessContext = KnowledgeAccessContext(
            userId = userId,
            groupUserId = groupUserId,
            owners = context.owners,
        )
        return createTool(store, knowledgeOwnerId, guard, accessContext)
    }

    protected abstract fun createTool(
        store: KnowledgeStore,
        knowledgeOwnerId: String,
        accessGuard: KnowledgeAccessGuard,
        accessContext: KnowledgeAccessContext,
    ): ToolDefinition
}
