package com.easy.easyai.tools.todo

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.*
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import kotlinx.coroutines.CoroutineScope
import tools.jackson.module.kotlin.readValue

data class TodoWriteParams(
    val todos: List<TodoItemParam>
)

data class TodoItemParam(
    val content: String,
    val status: String = "pending",
    val priority: String = "medium"
)

class TodoWriteTool(
    metadata: ToolMetadata,
    private val todoManager: TodoManager
) : BaseToolDefinition(metadata) {

    override fun parameterType(): Class<*> = TodoWriteParams::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val todos = parseTodos(args)
            ?: return ToolResult(
                content = listOf(ToolResultContent(
                    toolCallId = toolCallId, toolName = name,
                    output = "Error: invalid 'todos' parameter", isError = true
                )),
                isError = true
            )

        val updated = todoManager.updateTodos(agentContext.agentRunId, todos)

        val summary = buildSummary(updated)
        val json = objectMapper.writerWithDefaultPrettyPrinter()
            .writeValueAsString(updated)

        return ToolResult(
            content = listOf(TextContent("$summary\n\n```json\n$json\n```"))
        )
    }

    private fun parseTodos(args: Map<String, Any?>): List<TodoInfo>? {
        val todosRaw = args["todos"] ?: return null
        return try {
            val items: List<TodoItemParam> = when (todosRaw) {
                is List<*> -> objectMapper.convertValue(todosRaw,
                    objectMapper.typeFactory.constructCollectionType(List::class.java, TodoItemParam::class.java))
                is String -> objectMapper.readValue(todosRaw)
                else -> return null
            }
            items.mapIndexed { index, item ->
                TodoInfo(
                    content = item.content,
                    status = parseStatus(item.status),
                    priority = parsePriority(item.priority),
                    position = index,
                    createdAt = System.currentTimeMillis()
                )
            }
        } catch (_: Exception) { null }
    }

    private fun parseStatus(s: String): TodoStatus = when (s.lowercase()) {
        "pending" -> TodoStatus.PENDING
        "in_progress" -> TodoStatus.IN_PROGRESS
        "completed" -> TodoStatus.COMPLETED
        "cancelled" -> TodoStatus.CANCELLED
        else -> TodoStatus.PENDING
    }

    private fun parsePriority(s: String): TodoPriority = when (s.lowercase()) {
        "high" -> TodoPriority.HIGH
        "medium" -> TodoPriority.MEDIUM
        "low" -> TodoPriority.LOW
        else -> TodoPriority.MEDIUM
    }

    private fun buildSummary(todos: List<TodoInfo>): String {
        val pending = todos.count { it.status == TodoStatus.PENDING }
        val inProgress = todos.count { it.status == TodoStatus.IN_PROGRESS }
        val completed = todos.count { it.status == TodoStatus.COMPLETED }
        val cancelled = todos.count { it.status == TodoStatus.CANCELLED }
        return buildString {
            append("Todo list updated: ${todos.size} items")
            append(" ($pending pending, $inProgress in_progress, $completed completed")
            if (cancelled > 0) append(", $cancelled cancelled")
            append(")")
        }
    }

    companion object {
        private val objectMapper = SharedObjectMapper.instance
    }
}
