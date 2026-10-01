package com.easy.easyai.tools.background

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionService
import com.easy.easyai.core.tool.*
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import kotlinx.coroutines.CoroutineScope

/**
 * Parameters for run_background tool.
 */
data class RunBackgroundParameters(
    @param:JsonPropertyDescription("Name of the tool to run in background (any available tool including MCP tools)")
    val toolName: String,
    @param:JsonPropertyDescription("Arguments to pass to the target tool as a JSON object")
    val arguments: Map<String, Any?>,
    @param:JsonPropertyDescription("Short description of what this background task does")
    val description: String? = null
)

/**
 * Tool for launching other tools in the background without waiting for their result.
 * Returns immediately with a task ID that can be used to query status via task_status/task_list.
 * When the background task completes, the result is injected into the agent's steering queue.
 */
class RunBackgroundTool(
    metadata: ToolMetadata,
    private val permissionService: PermissionService,
    private val taskManagerRegistry: BackgroundTaskManagerRegistry
) : BaseToolDefinition(metadata) {

    override val executionMode: ToolExecutionMode = ToolExecutionMode.SEQUENTIAL

    override fun parameterType(): Class<*> = RunBackgroundParameters::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val toolName = args["toolName"] as? String
            ?: return errorResult("Missing required parameter: toolName")
        @Suppress("UNCHECKED_CAST")
        val toolArgs = (args["arguments"] as? Map<String, Any?>) ?: emptyMap()
        val description = args["description"] as? String

        // 1. Find the target tool
        val targetTool = agentContext.tools.find { it.name == toolName }
            ?: return errorResult("Unknown tool: $toolName. Use task_list to see available tools.")

        // 2. Permission pre-check for the target tool
        val projectId = agentContext.projectId ?: ""
        val permissionResult = permissionService.evaluateWithContext(
            projectId = projectId,
            projectPath = agentContext.projectPath,
            toolName = toolName,
            arguments = toolArgs,
            userId = agentContext.userId
        )

        when (permissionResult.action) {
            PermissionAction.ASK -> {
                return errorResult(
                    "Tool '$toolName' requires user approval and cannot be run in background. " +
                    "Please call it directly first to obtain permission, then retry run_background."
                )
            }
            PermissionAction.DENY -> {
                return errorResult("Permission denied for tool '$toolName'")
            }
            PermissionAction.ALLOW -> {
                // Continue with launch
            }
        }

        // 3. Generate task ID
        val taskId = java.util.UUID.randomUUID().toString()

        // 4. Get manager for this session (should be created by ChatStreamService)
        val sessionId = agentContext.sessionId
            ?: return errorResult("Background tasks require an active session")

        val manager = taskManagerRegistry.get(sessionId)
            ?: return errorResult("Background task manager not initialized for this session. Background tasks are only available during active chat sessions.")

        // 5. Launch the background task
        manager.launch(
            taskId = taskId,
            toolName = toolName,
            toolArgs = toolArgs,
            description = description,
            parentToolCallId = toolCallId,
            agentContext = agentContext,
            targetTool = targetTool
        )

        // 6. Return immediately with task ID
        return ToolResult(
            content = listOf(TextContent(
                "Background task launched.\n" +
                "Task ID: $taskId\n" +
                "Tool: $toolName\n" +
                "Use task_status(task_id=\"$taskId\") to check progress."
            ))
        )
    }
}
