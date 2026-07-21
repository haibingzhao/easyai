package com.easy.easyai.repository.todo

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.tools.todo.TodoManager
import com.easy.easyai.tools.todo.TodoWriteTool
import org.springframework.stereotype.Component

/**
 * Builder for [TodoWriteTool].
 * 
 * Session-scoped: creates a TodoManager bound to the current session ID
 * from [AgentContext.sessionId]. Requires [AsyncTodoStore] to be available.
 * 
 * Returns null (tool not registered) when:
 * - No sessionId is present in context (e.g., one-shot shell `prompt` command)
 * - No AsyncTodoStore is configured
 */
@Component
class TodoWriteToolBuilder(
    private val todoStore: AsyncTodoStore? = null
) : ToolBuilder {
    override val metadata = ToolMetadata(
        name = "todo_write",
        description = DESCRIPTION,
        permissionCategory = "todo",
        uiRenderer = "todo_write"
    )
    override val defaultPermissionRules = listOf(
        PermissionRule("tool.execute.todo", "*", PermissionAction.ALLOW)
    )

    override fun build(context: AgentContext, agentService: AgentService): ToolDefinition? {
        // No session means this is a one-shot prompt — todo tracking not applicable
        val sessionId = context.sessionId ?: return null

        // No store configured — cannot persist todos
        val store = todoStore ?: return null

        // agentRunId scopes todos per sub-agent invocation; null means main agent session scope.
        // The agentRunId is resolved at execution time from AgentContext, not at build time,
        // because it may not be available when this tool is built.
        val todoManager = TodoManager(
            persistTodos = { agentRunId, todos -> store.saveTodos(sessionId, agentRunId, todos) }
        )
        return TodoWriteTool(metadata, todoManager)
    }

    companion object {
        private val DESCRIPTION = """
Create or update the task todo list to track your progress on multi-step tasks.

## When to Use
1. Complex multistep tasks (3+ distinct steps)
2. Non-trivial tasks requiring careful planning
3. User explicitly requests todo list
4. User provides multiple tasks
5. After completing a task — mark it complete
6. When starting a new task — mark as in_progress (only ONE at a time)

## CRITICAL: Before Final Response
7. BEFORE giving your final answer or concluding the task, you MUST call this tool to update the todo list
8. Mark ALL completed tasks as "completed" before responding with your final result
9. If there is an in_progress task that you just finished, mark it as "completed" BEFORE saying you're done

## When NOT to Use
- Single straightforward task
- Trivial task (< 3 steps)
- Purely conversational or informational

## Task States
- pending: not started
- in_progress: currently working (limit ONE at a time)
- completed: finished
- cancelled: no longer needed
        """.trimIndent()
    }
}
