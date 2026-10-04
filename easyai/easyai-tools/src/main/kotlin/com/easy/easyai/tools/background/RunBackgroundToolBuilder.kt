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

The tool returns immediately with a task ID. You will be automatically notified with the result when the task finishes, and the conversation resumes on its own even if this turn has already ended. So do not poll in order to wait: keep working on something else, or finish the turn and tell the user the task is running.

Call task_status(taskId=...) only when you need intermediate status before that notification arrives, or task_list() to see all tasks.

Parameters:
- toolName: Name of the tool to run (any available tool including MCP tools)
- arguments: Arguments to pass to the target tool as a JSON object
- description: Optional short description of what this background task does

Note: Tools that require user permission (ASK) cannot be run in background. Call them directly first to obtain permission."""

/** Static guidance for asynchronous execution via run_background / task_* tools (cache-stable). */
private const val BACKGROUND_TASK_SEGMENT = """
## Background (Async) Tasks

You can run long operations ASYNCHRONOUSLY with the `run_background` tool. Instead of blocking
on a slow tool (video generation, TTS, large builds, long shell commands, etc.), launch it in the
background and keep reasoning or doing other work while it runs.

- `run_background(tool_name, arguments, description?)` returns IMMEDIATELY with a `task_id`.
- Check on it later with `task_status(task_id)` — pass its `sleep` parameter (seconds) to wait a
  bit before reading the status instead of polling in a tight loop. Use `task_list()` for an
  overview of all tasks in this session.
- When a background task finishes, its result is injected into the conversation automatically,
  so you do not need to keep polling — but you may check status at any time.

Prefer `run_background` whenever a step is slow and you have other useful work to do meanwhile.
Tools that require user approval (ASK) cannot be backgrounded — call those directly first.
"""

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
        skipOnResume = false,
        systemPromptSegment = BACKGROUND_TASK_SEGMENT.trimIndent(),
        promptSegmentOrder = 40
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
