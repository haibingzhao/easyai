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

/** An optional index improves ranking; the scoped registry/catalog view is always the authority. */
@Component
internal class SkillSearchToolBuilder(
    private val skillStoreProvider: ObjectProvider<SkillStore>,
    private val catalogProvider: ObjectProvider<AsyncSkillCatalogStore>,
    @param:Value("\${easyai.skills.rag.search-top-k:5}") private val searchTopK: Int = SkillSearchTool.DEFAULT_SEARCH_TOP_K,
    private val registry: SkillRegistry?,
    private val skillConfigProvider: ObjectProvider<SkillConfig>,
    @param:Value("\${easyai.skills.rag.enabled:false}") private val ragEnabled: Boolean = false
) : ToolBuilder {
    override val metadata = ToolMetadata(
        name = "skill_search",
        description = "Discover authorized skills for this task in the current project and GLOBAL scope. " +
            "Uses semantic search when indexed, otherwise bounded name/description matching. " +
            "Load the selected skill with `load_skill` by name.",
        permissionCategory = "skill",
        isDefaultTool = false,
        alwaysInclude = true
    )

    override val defaultPermissionRules = listOf(
        PermissionRule("tool.execute.skill", "*", PermissionAction.ALLOW)
    )

    override fun build(context: AgentContext, agentService: AgentService): ToolDefinition? {
        val reg = registry ?: return null
        if (!ragEnabled || context.allowedSkillNames.isEmpty()) return null
        return SkillSearchTool(
            metadata, skillStoreProvider.getIfAvailable(), catalogProvider.getIfAvailable(), searchTopK,
            reg, skillConfigProvider.getIfAvailable() ?: SkillConfig(), context.allowedSkillNames
        )
    }
}
