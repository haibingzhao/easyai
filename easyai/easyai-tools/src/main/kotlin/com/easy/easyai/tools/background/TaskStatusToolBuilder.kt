package com.easy.easyai.tools.background

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import org.springframework.stereotype.Component

private const val TASK_STATUS_TOOL_DESCRIPTION = """Query the status of a specific background task.

Returns detailed information about a background task including:
- Task ID and tool name
- Current status (RUNNING, COMPLETED, FAILED, CANCELLED)
- Duration and timestamps
- Result or error message

Use this after launching a task with run_background to check its progress or get its result."""

/**
 * Builder for [TaskStatusTool].
 */
@Component
class TaskStatusToolBuilder(
    private val taskManagerRegistry: BackgroundTaskManagerRegistry
) : ToolBuilder {
    override val metadata = ToolMetadata(
        name = "task_status",
        description = TASK_STATUS_TOOL_DESCRIPTION,
        permissionCategory = "background_task",
        uiRenderer = "background_task",
        isDefaultTool = true,
        skipOnResume = false
    )

    override val defaultPermissionRules = listOf(
        PermissionRule("tool.execute.background_task", "*", PermissionAction.ALLOW)
    )

    override fun build(context: AgentContext, agentService: AgentService): ToolDefinition {
        return TaskStatusTool(
            metadata = metadata,
            taskManagerRegistry = taskManagerRegistry
        )
    }
}
