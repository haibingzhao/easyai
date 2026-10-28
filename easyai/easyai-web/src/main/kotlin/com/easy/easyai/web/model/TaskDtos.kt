package com.easy.easyai.web.model

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Wire form of an `async_task` row for the global Tasks page and the Session Summary panel.
 * `payload`/`result` are passed through as opaque type-specific maps; the frontend's
 * `TASK_TYPE_REGISTRY` knows how to render each type.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AsyncTaskDto(
    val id: String,
    val type: String,
    val status: String,
    val trigger: String,
    val title: String,
    val sessionId: String? = null,
    /** The application that ran this task; null on rows written before ownership was recorded. */
    val ownerApp: String? = null,
    /**
     * False when another application wrote the row. The task is still listed (the queue is shared),
     * but retry/cancel/delete would act on a worker this process cannot reach.
     */
    val controllable: Boolean = true,
    val timeoutMs: Long? = null,
    val payload: Map<String, Any?>? = null,
    val result: Map<String, Any?>? = null,
    val error: String? = null,
    val retryCount: Int = 0,
    val retriedAt: Long? = null,
    val createdAt: Long,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val updatedAt: Long,
    val durationMs: Long? = null
)
