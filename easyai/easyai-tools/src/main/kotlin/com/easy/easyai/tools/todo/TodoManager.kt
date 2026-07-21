package com.easy.easyai.tools.todo

import com.easy.easyai.core.model.TodoInfo
import com.easy.easyai.core.model.TodoStatus

/**
 * Lightweight todo state manager.
 * - Normalize (single in_progress, auto position)
 * - Persist synchronously via pluggable suspend callback
 */
class TodoManager(
    private val persistTodos: suspend (agentRunId: String?, todos: List<TodoInfo>) -> Unit
) {
    /**
     * Replace the current todo list with [todos].
     * Normalizes (single in_progress, position = index), then persists.
     * [agentRunId] scopes todos per sub-agent invocation; null means main agent session scope.
     * Returns the normalized list.
     */
    suspend fun updateTodos(agentRunId: String?, todos: List<TodoInfo>): List<TodoInfo> {
        val normalized = normalizeTodos(todos)
        persistTodos(agentRunId, normalized)
        return normalized
    }

    private fun normalizeTodos(todos: List<TodoInfo>): List<TodoInfo> {
        // Ensure at most one in_progress
        var foundInProgress = false
        val singleInProgress = todos.map { todo ->
            if (todo.status == TodoStatus.IN_PROGRESS) {
                if (foundInProgress) todo.copy(status = TodoStatus.PENDING)
                else { foundInProgress = true; todo }
            } else todo
        }
        // position = list index; createdAt preserved from original
        return singleInProgress.mapIndexed { index, todo -> todo.copy(position = index) }
    }
}