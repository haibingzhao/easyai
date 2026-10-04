package com.easy.easyai.web.service.validation

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolCapability
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolFactory
import com.easy.easyai.core.tool.ToolMetadata

/**
 * ToolFactory stub exposing a single `load_skill` builder with the SKILL_LOADING capability,
 * so validation paths that derive skill-loader names from metadata behave like production.
 */
internal object SkillLoaderToolFactory : ToolFactory {
    private val skillLoaderBuilder = object : ToolBuilder {
        override val metadata = ToolMetadata(
            name = "load_skill",
            description = "Load an authorized skill by name.",
            capabilities = setOf(ToolCapability.SKILL_LOADING)
        )

        override fun build(context: AgentContext, agentService: AgentService): ToolDefinition? = null
    }

    override fun getBuilders(): List<ToolBuilder> = listOf(skillLoaderBuilder)

    override fun createTools(
        context: AgentContext,
        agentService: AgentService,
        allowedToolNames: List<String>
    ): List<ToolDefinition> = emptyList()
}
