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
 * Builder for [SkillSearchTool].
 *
 * Both dependencies are optional by nature — `skillStore` only exists when
 * `easyai.skills.rag.enabled=true`, the catalog only when the R2DBC repositories are on — so they
 * arrive through [ObjectProvider] instead of forcing a bean that may legitimately be absent. When
 * the retrieval store is missing the tool is simply not offered, which is the pre-RAG behaviour.
 */
@Component
internal class SkillSearchToolBuilder(
    private val skillStoreProvider: ObjectProvider<SkillStore>,
    private val catalogProvider: ObjectProvider<AsyncSkillCatalogStore>,
    @param:Value("\${easyai.skills.rag.search-top-k:5}") private val searchTopK: Int = SkillSearchTool.DEFAULT_SEARCH_TOP_K
) : ToolBuilder {

    override val metadata = ToolMetadata(
        name = "skill_search",
        description = "Discover skills for the current task by semantic search. Call this first with keywords " +
            "from the user's request: it searches your global skills and the skills of the current project in " +
            "one shot, and reports which granularity each hit came from. Then load the winner with `load_skill`.",
        permissionCategory = "skill",
        isDefaultTool = false,
        // The whole discovery chain is opt-in via `easyai.skills.rag.enabled`. When the flag is off,
        // [skillStoreProvider] resolves to null and [build] returns null (tool absent). When it is on,
        // existing agent rows in the DB still carry the pre-RAG toolNames whitelist, which would hide
        // this tool forever. Marking it alwaysInclude lets the builder's own guard be the single
        // switch, without a V4 migration touching every agent's tool configuration.
        alwaysInclude = true
    )

    // Discovery is read-only, same as `load_skill`; writing skills is a separate, confirmed action.
    override val defaultPermissionRules = listOf(
        PermissionRule("tool.execute.skill", "*", PermissionAction.ALLOW)
    )

    override fun build(context: AgentContext, agentService: AgentService): ToolDefinition? {
        val store = skillStoreProvider.getIfAvailable() ?: return null
        return SkillSearchTool(metadata, store, catalogProvider.getIfAvailable(), searchTopK)
    }
}
