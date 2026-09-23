package com.easy.easyai.skills

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class SkillToolBuilder(
    private val registry: SkillRegistry?,
    private val catalogProvider: ObjectProvider<AsyncSkillCatalogStore>,
    private val skillConfigProvider: ObjectProvider<SkillConfig>,
    @param:Value("\${easyai.skills.rag.enabled:false}") private val ragEnabled: Boolean,
) : ToolBuilder {
    private val baseDescription =
        "Load an authorized skill by name to get detailed instructions and context for the current project."

    override val metadata = ToolMetadata(
        name = "load_skill",
        description = baseDescription,
        permissionCategory = "skill",
        isDefaultTool = false
    )

    override val defaultPermissionRules = listOf(
        PermissionRule("tool.execute.skill", "*", PermissionAction.ALLOW)
    )

    override fun build(context: AgentContext, agentService: AgentService): ToolDefinition? {
        val reg = registry ?: return null
        val effectiveMetadata = if (ragEnabled && context.allowedSkillNames.isNotEmpty()) {
            metadata.copy(description = "$baseDescription Discover suitable skills with `skill_search` first.")
        } else metadata
        // Catalog access control is independent of whether semantic indexing is enabled.
        return SkillTool(
            effectiveMetadata, reg, context.allowedSkillNames, catalogProvider.getIfAvailable(),
            skillConfigProvider.getIfAvailable() ?: SkillConfig()
        )
    }
}
