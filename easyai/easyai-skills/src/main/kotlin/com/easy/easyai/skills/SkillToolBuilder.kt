package com.easy.easyai.skills

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillStore
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Builder for [SkillTool].
 *
 * Returns null (tool not registered) when:
 * - No SkillRegistry is available (skills disabled)
 *
 * Two collaborators are wired through [ObjectProvider] because both are optional:
 * - [AsyncSkillCatalogStore] exists only when the R2DBC repositories are enabled;
 *   we hand it to [SkillTool] **only when** `easyai.skills.rag.enabled=true`, so a default
 *   deployment (r2dbc on, rag off) keeps its historical whitelist-only behaviour instead of
 *   rejecting every load against an empty catalog table.
 * - [SkillStore] is the read side of the RAG skill index. Only when it is present does the
 *   `skill_search` tool get registered, and only then does the `load_skill` description earn its
 *   "discover one with `skill_search` first" hint — recommending a tool that never registered
 *   would push the model into tool-call hallucinations.
 * - [SkillConfig] comes from the autoconfiguration bean and drives the granularity resolution of
 *   the catalog gate; without it the gate could not walk the same candidate roots the registry did.
 */
@Component
class SkillToolBuilder(
    private val registry: SkillRegistry?,
    private val catalogProvider: ObjectProvider<AsyncSkillCatalogStore>,
    private val skillStoreProvider: ObjectProvider<SkillStore>,
    private val skillConfigProvider: ObjectProvider<SkillConfig>,
    @param:Value($$"${easyai.skills.rag.enabled:false}") private val ragEnabled: Boolean,
) : ToolBuilder {

    private val baseDescription =
        "Load a skill by name to get detailed instructions and context. " +
            "Use this when you need specialized knowledge for a task. " +
            "Call with the skill name to retrieve its full content."

    override val metadata = ToolMetadata(
        name = "load_skill",
        description = baseDescription,
        permissionCategory = "skill",
        isDefaultTool = false
    )

    // Loading skills is read-only (reads SKILL.md content), auto-approve by default
    override val defaultPermissionRules = listOf(
        PermissionRule("tool.execute.skill", "*", PermissionAction.ALLOW)
    )

    override fun build(context: AgentContext, agentService: AgentService): ToolDefinition? {
        // No registry means skills are disabled
        val reg = registry ?: return null

        // Gate the catalog to the same switch that populates it. SkillIndexStartupRunner (and thus
        // SkillCatalogSyncService.backfillAll) only runs when skill RAG is enabled, so feeding the
        // catalog to SkillTool with the switch off would compare against a permanently empty table
        // and reject every legitimate load_skill call.
        val catalog = if (ragEnabled) catalogProvider.getIfAvailable() else null

        // Only advertise skill_search when it will actually be registered (SkillSearchToolBuilder
        // returns null when no SkillStore is available, i.e. rag disabled).
        val discoveryHintAvailable = ragEnabled && skillStoreProvider.getIfAvailable() != null
        val effectiveMetadata = if (discoveryHintAvailable) {
            metadata.copy(description = "$baseDescription If you do not know which skill fits, discover one with `skill_search` first.")
        } else {
            metadata
        }

        return SkillTool(effectiveMetadata, reg, context.allowedSkillNames, catalog, skillConfigProvider.getIfAvailable() ?: SkillConfig())
    }
}
