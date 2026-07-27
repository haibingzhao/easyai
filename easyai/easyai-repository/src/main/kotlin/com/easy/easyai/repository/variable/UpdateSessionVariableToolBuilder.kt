package com.easy.easyai.repository.variable

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.repository.session.AsyncSessionStore
import com.easy.easyai.tools.variable.UpdateSessionVariableTool
import org.springframework.stereotype.Component

private const val DESCRIPTION = """Store or update session variables that persist across context compaction and session resume.

## When to Use
- Record key data points (financial figures, analysis results) that must survive context window management
- Store intermediate computation results for later reference
- Save large content (reports, tables) to files for on-demand loading via read tool

## Behavior
- Small values (≤500 chars): stored inline, visible in system prompt every turn
- Large values (>500 chars): saved to file, variable stores file path — use `read` tool to load
- Variables persist to database and survive session resume
- Maximum 50 variables per session"""

/**
 * Builder for [UpdateSessionVariableTool].
 *
 * This tool is NOT included by default in agent prompts (alwaysInclude = false).
 * It is only used internally by the compaction agent for variable extraction during
 * context compaction. The compaction agent creates its own lightweight instance.
 */
@Component
class UpdateSessionVariableToolBuilder(
    private val sessionStore: AsyncSessionStore? = null
) : ToolBuilder {
    override val metadata = ToolMetadata(
        name = "update_variable",
        description = DESCRIPTION,
        permissionCategory = "variable",
        isDefaultTool = false,
        alwaysInclude = false
    )

    override val defaultPermissionRules = listOf(
        PermissionRule("tool.execute.variable", "*", PermissionAction.ALLOW)
    )

    override fun build(context: AgentContext, agentService: AgentService): ToolDefinition? {
        // No session means this is a one-shot prompt — variable persistence not applicable
        val sessionId = context.sessionId ?: return null

        val persistCallback: (suspend (String, String?) -> Unit)? = sessionStore?.let { store ->
            { sid, json -> store.saveSessionVariables(sid, json, context.userId ?: "system") }
        }

        return UpdateSessionVariableTool(
            metadata = metadata,
            sessionVariables = context.sessionVariables,
            projectPath = context.projectPath,
            sessionId = sessionId,
            persistCallback = persistCallback
        )
    }
}
