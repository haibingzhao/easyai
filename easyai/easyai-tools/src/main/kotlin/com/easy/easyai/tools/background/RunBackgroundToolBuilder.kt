package com.easy.easyai.tools.background

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.permission.PermissionService
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

private const val RUN_BACKGROUND_TOOL_DESCRIPTION = """Launch any tool in the background without waiting for its result.

Use this tool for long-running operations (video generation, TTS, large builds, etc.) where you want to continue reasoning while the task executes.

The tool returns immediately with a task ID. Use task_status(task_id) to check progress, or task_list() to see all tasks.

When the background task completes, you will be automatically notified with the result.

Parameters:
- tool_name: Name of the tool to run (any available tool including MCP tools)
- arguments: Arguments to pass to the target tool as a JSON object
- description: Optional short description of what this background task does

Note: Tools that require user permission (ASK) cannot be run in background. Call them directly first to obtain permission."""

/**
 * Builder for [RunBackgroundTool].
 *
 * [PermissionService] is only provided by the persistence stack, so a deployment without it cannot
 * pre-check the target tool — the tool hides itself rather than letting the container fail to boot.
 */
@Component
class RunBackgroundToolBuilder(
    private val taskManagerRegistry: BackgroundTaskManagerRegistry,
    @param:Autowired(required = false)
    private val permissionService: PermissionService? = null
) : ToolBuilder {
    override val metadata = ToolMetadata(
        name = "run_background",
        description = RUN_BACKGROUND_TOOL_DESCRIPTION,
        permissionCategory = "background_task",
        uiRenderer = "background_task",
        isDefaultTool = true,
        skipOnResume = false
    )

    override val defaultPermissionRules = listOf(
        PermissionRule("tool.execute.background_task", "*", PermissionAction.ALLOW)
    )

    override fun build(context: AgentContext, agentService: AgentService): ToolDefinition? {
        val permissions = permissionService ?: return null
        return RunBackgroundTool(
            metadata = metadata,
            permissionService = permissions,
            taskManagerRegistry = taskManagerRegistry
        )
    }
}
