package com.easy.easyai.tools.background

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import org.springframework.stereotype.Component

private const val TASK_LIST_TOOL_DESCRIPTION = """List all background tasks for the current session.

Shows a summary of all background tasks including:
- Task ID and status (RUNNING, COMPLETED, FAILED, CANCELLED)
- Tool name and description
- Duration for completed tasks

Optionally filter by status to see only running, completed, failed, or cancelled tasks.

Use this to get an overview of all background work, or to find task IDs for task_status queries."""

/**
 * Builder for [TaskListTool].
 */
@Component
class TaskListToolBuilder(
    private val taskManagerRegistry: BackgroundTaskManagerRegistry
) : ToolBuilder {
    override val metadata = ToolMetadata(
        name = "task_list",
        description = TASK_LIST_TOOL_DESCRIPTION,
        permissionCategory = "background_task",
        uiRenderer = "background_task",
        isDefaultTool = true,
        skipOnResume = false
    )

    override val defaultPermissionRules = listOf(
        PermissionRule("tool.execute.background_task", "*", PermissionAction.ALLOW)
    )

    override fun build(context: AgentContext, agentService: AgentService): ToolDefinition {
        return TaskListTool(
            metadata = metadata,
            taskManagerRegistry = taskManagerRegistry
        )
    }
}
