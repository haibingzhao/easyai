package com.easy.easyai.tools.background

/**
 * Background task execution status.
 */
enum class BackgroundTaskStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Represents a background task launched via run_background tool.
 * Pure in-memory, not persisted. Lost on server restart (acceptable for v1).
 *
 * @property taskId Unique identifier (UUID)
 * @property sessionId Session that owns this task
 * @property userId User who initiated the task
 * @property toolName Target tool to execute
 * @property toolArgs Arguments for the target tool
 * @property description Optional human-readable description
 * @property status Current execution status
 * @property result Result text on success
 * @property error Error message on failure
 * @property startedAt Timestamp when task started
 * @property completedAt Timestamp when task completed/failed/cancelled
 * @property parentToolCallId Tool call ID of the run_background invocation
 */
data class BackgroundTask(
    val taskId: String,
    val sessionId: String,
    val userId: String?,
    val toolName: String,
    val toolArgs: Map<String, Any?>,
    val description: String?,
    val status: BackgroundTaskStatus = BackgroundTaskStatus.RUNNING,
    val result: String? = null,
    val error: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val parentToolCallId: String? = null
)
