package com.easy.easyai.web.model

import com.easy.easyai.core.model.TodoInfo

/**
 * REST API response DTO for todo items.
 * Used by GET /{sessionId}/todos endpoint.
 */
data class TodoResponse(
    val id: String,
    val content: String,
    val status: String,
    val priority: String,
    val position: Int,
    val createdAt: Long
)

/**
 * Convert a [TodoInfo] domain model to a [TodoResponse] API DTO.
 * Enum fields are serialized as lowercase strings for frontend compatibility.
 */
fun TodoInfo.toResponse(): TodoResponse = TodoResponse(
    id = id,
    content = content,
    status = status.name.lowercase(),
    priority = priority.name.lowercase(),
    position = position,
    createdAt = createdAt
)

/**
 * Grouped todo response: main agent todos + sub-agent todo groups.
 * Used by GET /{sessionId}/todos/grouped endpoint.
 */
data class TodoGroupResponse(
    val main: List<TodoResponse>,
    val subAgents: List<SubAgentTodoGroup>
)

/**
 * A sub-agent's todo group, identified by agentName (agentRunId).
 */
data class SubAgentTodoGroup(
    val agentName: String,
    val todos: List<TodoResponse>
)
