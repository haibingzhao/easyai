package com.easy.easyai.repository.todo

import com.easy.easyai.core.model.TodoInfo

/**
 * Async todo storage interface.
 * All operations are suspend functions for non-blocking R2DBC access.
 */
interface AsyncTodoStore {

    /**
     * Save or replace the todo list for a session scope (full replacement).
     * @param agentRunId null for the main agent's session-level todos;
     *                   non-null for a specific sub-agent invocation scope.
     */
    suspend fun saveTodos(sessionId: String, agentRunId: String?, todos: List<TodoInfo>)

    /**
     * Get the todo list for a session scope, ordered by position.
     * @param agentRunId null for the main agent's session-level todos.
     */
    suspend fun getTodos(sessionId: String, agentRunId: String?): List<TodoInfo>

    /**
     * Delete all todos for a session scope.
     * @param agentRunId null for the main agent's session-level todos.
     */
    suspend fun deleteTodos(sessionId: String, agentRunId: String?)

    /**
     * Delete all todos for a session across all scopes (main agent + all sub-agents).
     */
    suspend fun deleteAllTodos(sessionId: String)

    /**
     * Get all todos for a session across all scopes (main + sub-agents).
     * Returns a map: null key = main agent, non-null key = agentRunId.
     */
    suspend fun getAllTodos(sessionId: String): Map<String?, List<TodoInfo>>
}
