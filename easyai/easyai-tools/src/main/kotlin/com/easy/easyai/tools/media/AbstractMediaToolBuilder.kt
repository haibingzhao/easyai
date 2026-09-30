package com.easy.easyai.tools.media

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.media.MediaProviderResolver
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.storage.ObjectStorage
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolDefinition
import kotlinx.coroutines.runBlocking

/**
 * Base builder for the media-generation tools (image / speech / video / music / asr).
 *
 * A tool appears only when both halves of its pipeline are configured for the calling user:
 * at least one enabled generation-model entry for its [serviceKind] (rows in `model_provider_config`
 * partitioned by `model_type`) *and* a usable [ObjectStorage] to put the produced bytes in.
 * Either missing means `build()` returns null and the tool hides itself — the same opt-out shape
 * [com.easy.easyai.tools.web.WebSearchToolBuilder] uses for absent API keys.
 *
 * The tool itself receives only the [MediaProviderResolver], not a single settings row: each call
 * re-resolves the concrete entry via its optional `model` argument, so adding/removing generation
 * models needs no agent re-assembly beyond the presence gate done here.
 *
 * The two resolvers are reached through [AgentService] (not constructor injection) to mirror
 * [com.easy.easyai.tools.memory.AbstractMemoryToolBuilder]; bridging their `suspend` resolve with a
 * one-shot [runBlocking] is acceptable here because [build] runs once per agent assembly, not per call.
 */
abstract class AbstractMediaToolBuilder(
    protected val serviceKind: String
) : ToolBuilder {

    /**
     * Generation is auto-approved: the tools only exist once a user has configured a paid generation
     * model, so that configuration is already the consent. Users can still add an ASK/DENY rule.
     */
    override val defaultPermissionRules = listOf(
        PermissionRule("tool.execute.media", "*", PermissionAction.ALLOW)
    )

    override fun build(context: AgentContext, agentService: AgentService): ToolDefinition? {
        val resolver = agentService.mediaProviderResolver ?: return null
        val storageResolver = agentService.objectStorageResolver ?: return null
        val userId = context.userId ?: SYSTEM_USER_ID
        val storage = runBlocking { storageResolver.resolve(userId) } ?: return null
        val entries = runBlocking { resolver.resolveEntries(userId, serviceKind) }
        if (entries.isEmpty()) return null
        return createTool(resolver, storage, userId)
    }

    protected abstract fun createTool(
        resolver: MediaProviderResolver,
        storage: ObjectStorage,
        userId: String
    ): ToolDefinition

    companion object {
        /** Fallback owner when a context carries no user, matching the DB column default. */
        const val SYSTEM_USER_ID = "system"
    }
}
