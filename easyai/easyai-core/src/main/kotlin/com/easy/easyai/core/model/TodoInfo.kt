package com.easy.easyai.core.model

import java.util.UUID

/**
 * Todo priority levels.
 */
enum class TodoPriority {
    HIGH,
    MEDIUM,
    LOW
}

/**
 * Todo status lifecycle.
 * - PENDING: not started
 * - IN_PROGRESS: currently working (at most one at a time)
 * - COMPLETED: done
 * - CANCELLED: abandoned
 */
enum class TodoStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED
}

/**
 * Todo item for tracking task progress.
 * position is maintained automatically by the system (equals list index).
 * createdAt is preserved across full-replacement updates.
 */
data class TodoInfo(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val status: TodoStatus = TodoStatus.PENDING,
    val priority: TodoPriority = TodoPriority.MEDIUM,
    val position: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
