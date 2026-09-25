package com.easy.easyai.tools.media

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.media.MediaProviderSettings
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.storage.ObjectStorage
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolDefinition
import kotlinx.coroutines.runBlocking

/**
 * Base builder for the media-generation tools (image / speech / video).
 *
 * A tool appears only when both halves of its pipeline are configured for the calling user:
 * an enabled [MediaProviderSettings] row for its [serviceKind] *and* a usable [ObjectStorage] to put
 * the produced bytes in. Either missing means `build()` returns null and the tool hides itself — the
 * same opt-out shape [com.easy.easyai.tools.web.WebSearchToolBuilder] uses for absent API keys.
 *
 * The two resolvers are reached through [AgentService] (not constructor injection) to mirror
 * [com.easy.easyai.tools.memory.AbstractMemoryToolBuilder]; bridging their `suspend` resolve with a
 * one-shot [runBlocking] is acceptable here because [build] runs once per agent assembly, not per call.
 */
abstract class AbstractMediaToolBuilder(
    protected val serviceKind: String
) : ToolBuilder {

    /**
     * Generation is auto-approved: the tools only exist once a user has configured a paid provider for
     * [serviceKind], so that configuration is already the consent. Users can still add an ASK/DENY rule.
     */
    override val defaultPermissionRules = listOf(
        PermissionRule("tool.execute.media", "*", PermissionAction.ALLOW)
    )

    override fun build(context: AgentContext, agentService: AgentService): ToolDefinition? {
        val mediaResolver = agentService.mediaProviderResolver ?: return null
        val storageResolver = agentService.objectStorageResolver ?: return null
        val userId = context.userId ?: SYSTEM_USER_ID
        val settings = runBlocking { mediaResolver.resolve(userId, serviceKind) } ?: return null
        val storage = runBlocking { storageResolver.resolve(userId) } ?: return null
        return createTool(settings, storage, userId)
    }

    protected abstract fun createTool(
        settings: MediaProviderSettings,
        storage: ObjectStorage,
        userId: String
    ): ToolDefinition

    companion object {
        /** Fallback owner when a context carries no user, matching the DB column default. */
        const val SYSTEM_USER_ID = "system"
    }
}
