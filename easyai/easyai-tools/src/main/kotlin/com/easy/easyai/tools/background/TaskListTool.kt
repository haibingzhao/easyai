package com.easy.easyai.tools.background

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.*
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import kotlinx.coroutines.CoroutineScope

/**
 * Parameters for task_list tool.
 */
data class TaskListParameters(
    @param:JsonPropertyDescription("Filter by status: 'running', 'completed', 'failed', 'cancelled'. Omit for all.")
    val status: String? = null
)

/**
 * Tool for listing all background tasks, optionally filtered by status.
 */
class TaskListTool(
    metadata: ToolMetadata,
    private val taskManagerRegistry: BackgroundTaskManagerRegistry
) : BaseToolDefinition(metadata) {

    override fun parameterType(): Class<*> = TaskListParameters::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val statusFilter = (args["status"] as? String)?.let {
            try {
                BackgroundTaskStatus.valueOf(it.uppercase())
            } catch (e: IllegalArgumentException) {
                return errorResult("Invalid status filter: $it. Valid values: running, completed, failed, cancelled")
            }
        }

        val sessionId = agentContext.sessionId
            ?: return errorResult("Session context required")

        val manager = taskManagerRegistry.get(sessionId)
            ?: return ToolResult(content = listOf(TextContent("No background tasks found for this session.")))

        val tasks = manager.listTasks(statusFilter)

        if (tasks.isEmpty()) {
            val filterText = statusFilter?.let { " with status $it" } ?: ""
            return ToolResult(content = listOf(TextContent("No background tasks found$filterText.")))
        }

        val result = buildString {
            appendLine("Background Tasks (${tasks.size}):")
            appendLine()

            tasks.forEach { task ->
                val statusIcon = when (task.status) {
                    BackgroundTaskStatus.RUNNING -> "🔄"
                    BackgroundTaskStatus.COMPLETED -> "✅"
                    BackgroundTaskStatus.FAILED -> "❌"
                    BackgroundTaskStatus.CANCELLED -> "⚠️"
                }

                val durationMs = task.completedAt?.let { it - task.startedAt }
                val durationText = durationMs?.let { " (${it}ms)" } ?: ""

                appendLine("$statusIcon ${task.taskId}")
                appendLine("   Tool: ${task.toolName}")
                appendLine("   Status: ${task.status}$durationText")
                task.description?.let { appendLine("   Description: $it") }
                appendLine()
            }
        }

        return ToolResult(content = listOf(TextContent(result.trim())))
    }
}
