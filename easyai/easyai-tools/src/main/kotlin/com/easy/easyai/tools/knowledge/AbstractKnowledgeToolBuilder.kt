package com.easy.easyai.tools.knowledge

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.knowledge.KnowledgeAccessContext
import com.easy.easyai.core.knowledge.KnowledgeAccessGuard
import com.easy.easyai.core.knowledge.KnowledgeArticleStore
import com.easy.easyai.core.knowledge.KnowledgeFolderStore
import com.easy.easyai.core.knowledge.KnowledgeOwnership
import com.easy.easyai.core.knowledge.KnowledgeStore
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolDefinition
import org.springframework.beans.factory.ObjectProvider

/**
 * Base builder for knowledge tools (search, read).
 *
 * The knowledge slice owner is resolved once at build time from the agent context: the group bucket
 * when `easyai.knowledge.shared-within-group` is on and the context carries one, otherwise the user.
 * Access is enforced by the host [KnowledgeAccessGuard]; without a policy/guard or a login user the
 * tools stay unavailable instead of running unguarded.
 *
 * Article/folder stores are optional enrichment: when present, search results carry titles, folder
 * paths and category filters; when absent, retrieval still works with the index metadata alone.
 */
abstract class AbstractKnowledgeToolBuilder(
    protected val sharedWithinGroup: Boolean,
    private val accessGuardProvider: ObjectProvider<KnowledgeAccessGuard>,
    private val articleStoreProvider: ObjectProvider<KnowledgeArticleStore>,
    private val folderStoreProvider: ObjectProvider<KnowledgeFolderStore>,
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
        return createTool(
            store = store,
            knowledgeOwnerId = knowledgeOwnerId,
            accessGuard = guard,
            accessContext = accessContext,
            articleStore = articleStoreProvider.getIfAvailable(),
            folderStore = folderStoreProvider.getIfAvailable(),
        )
    }

    protected abstract fun createTool(
        store: KnowledgeStore,
        knowledgeOwnerId: String,
        accessGuard: KnowledgeAccessGuard,
        accessContext: KnowledgeAccessContext,
        articleStore: KnowledgeArticleStore?,
        folderStore: KnowledgeFolderStore?,
    ): ToolDefinition
}
