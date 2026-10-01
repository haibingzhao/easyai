package com.easy.easyai.tools.background

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.*
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import kotlinx.coroutines.CoroutineScope

/**
 * Parameters for task_status tool.
 */
data class TaskStatusParameters(
    @param:JsonPropertyDescription("The task ID to query")
    val taskId: String
)

/**
 * Tool for querying the status of a specific background task.
 */
class TaskStatusTool(
    metadata: ToolMetadata,
    private val taskManagerRegistry: BackgroundTaskManagerRegistry
) : BaseToolDefinition(metadata) {

    override fun parameterType(): Class<*> = TaskStatusParameters::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val taskId = args["taskId"] as? String
            ?: return errorResult("Missing required parameter: taskId")

        val sessionId = agentContext.sessionId
            ?: return errorResult("Session context required")

        val manager = taskManagerRegistry.get(sessionId)
            ?: return ToolResult(content = listOf(TextContent("No background tasks found for this session.")))

        val task = manager.getStatus(taskId)
            ?: return ToolResult(content = listOf(TextContent("Task not found: $taskId")))

        val durationMs = task.completedAt?.let { it - task.startedAt }
        val statusText = when (task.status) {
            BackgroundTaskStatus.RUNNING -> "RUNNING"
            BackgroundTaskStatus.COMPLETED -> "COMPLETED"
            BackgroundTaskStatus.FAILED -> "FAILED"
            BackgroundTaskStatus.CANCELLED -> "CANCELLED"
        }

        val result = buildString {
            appendLine("Task ID: ${task.taskId}")
            appendLine("Tool: ${task.toolName}")
            appendLine("Status: $statusText")
            task.description?.let { appendLine("Description: $it") }
            appendLine("Started: ${task.startedAt}")
            durationMs?.let { appendLine("Duration: ${it}ms") }

            if (task.result != null) {
                appendLine()
                appendLine("Result:")
                appendLine(task.result.take(500))
                if (task.result.length > 500) {
                    appendLine("... (truncated)")
                }
            }

            if (task.error != null) {
                appendLine()
                appendLine("Error:")
                appendLine(task.error.take(500))
            }
        }

        return ToolResult(content = listOf(TextContent(result.trim())))
    }
}
