package com.easy.easyai.skills

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import org.springframework.stereotype.Component

/**
 * Builder for [SkillTool].
 * 
 * Returns null (tool not registered) when:
 * - No SkillRegistry is available (skills disabled)
 */
@Component
class SkillToolBuilder(
    private val registry: SkillRegistry?
) : ToolBuilder {
    override val metadata = ToolMetadata(
        name = "load_skill",
        description = "Load a skill by name to get detailed instructions and context. " +
            "Use this when you need specialized knowledge for a task. " +
            "Call with the skill name to retrieve its full content.",
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
        
        return SkillTool(metadata, reg, context.allowedSkillNames)
    }
}
