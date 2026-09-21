package com.easy.easyai.skills

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/**
 * Builder for [RefreshSkillsTool].
 *
 * The tool needs a [SkillRegistry] to re-read at all, so with the skill system switched off it is not
 * offered — the same guard `load_skill` uses. The [SkillRefreshService] behind the catalog and index
 * bookkeeping only exists once skill RAG is wired up; without it the tool still refreshes what the
 * process knows and says so, rather than disappearing and leaving the agent with no way to pick up a
 * file it just wrote.
 */
@Component
class RefreshSkillsToolBuilder(
    private val registryProvider: ObjectProvider<SkillRegistry>,
    private val refresherProvider: ObjectProvider<SkillRefreshService>
) : ToolBuilder {

    override val metadata = ToolMetadata(
        name = "refresh_skills",
        description = "Make skills you just wrote with `write` usable. Re-reads the skill directories, " +
            "claims a catalog row for anything new, and updates the `skill_search` index: a SKILL.md is " +
            "only a file until this runs. Call it once after creating or editing a skill, after every " +
            "attachment has been written. Its only argument is an optional `note` for the log; the answer " +
            "lists what was added, which file failed to parse, and which skills still need enabling for " +
            "this agent.",
        permissionCategory = "skill",
        isDefaultTool = false,
        // Writing a skill is a `write` away, so being able to publish it must not depend on the agent's
        // toolNames whitelist — existing rows would keep hiding this tool forever, which is exactly the
        // gap it exists to close. The builder's own registry guard is the single switch.
        alwaysInclude = true
    )

    /** Reading the skill directories is no more dangerous than `load_skill`, which is auto-approved. */
    override val defaultPermissionRules = listOf(
        PermissionRule("tool.execute.skill", "*", PermissionAction.ALLOW)
    )

    override fun build(context: AgentContext, agentService: AgentService): ToolDefinition? {
        val registry = registryProvider.getIfAvailable() ?: return null
        return RefreshSkillsTool(metadata, registry, refresherProvider.getIfAvailable(), context.allowedSkillNames)
    }
}
