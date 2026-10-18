package com.easy.easyai.core.task

/**
 * Kinds of app-scoped asynchronous work tracked in the `async_task` table. Adding a type is one
 * enum value plus a [TaskHandler] bean (backend) and a `TASK_TYPE_REGISTRY` entry (frontend); the
 * table, runner, controller and page need no change. To make the type schedulable as well, add a
 * [ScheduledTaskPlanner] bean — the shared `scheduled_task_setting` table and its scan already cover
 * it, so no table, scheduler or runner change is involved.
 */
enum class AsyncTaskType(val key: String) {
    /** Near-duplicate merge + stale decay over a memory slice (see MemoryConsolidationAgent). */
    MEMORY_CONSOLIDATION("memory_consolidation");

    companion object {
        fun fromKey(key: String): AsyncTaskType? = entries.firstOrNull { it.key == key }
    }
}

/** Lifecycle state of an [AsyncTask]. Persisted, so it survives session close and server restart. */
enum class AsyncTaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    /** A task still marked RUNNING when the process restarted — its worker is gone, so it is parked here for manual retry. */
    INTERRUPTED;

    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED || this == INTERRUPTED
}

/** What caused a task to be created; lets the UI distinguish self-fired work from user/scheduler-initiated. */
enum class TaskTrigger {
    /** Fired by the backend itself, without a user action or a scheduler tick. */
    AUTO,
    /** Fired from the frontend (Tasks page / Memories page / an endpoint). */
    FRONTEND,
    /** Fired by the scheduled scan, on the user's `scheduled_task_setting` row. */
    SCHEDULE
}

/**
 * A persisted unit of app-scoped async work. Execution is decoupled from any chat session: the
 * session is only a reference ([sessionId]) and a realtime-push target, so closing/aborting a
 * session or restarting the backend never cancels the work — only [AsyncTaskStatus] reflects it.
 *
 * @property payload type-specific inputs, the minimal set needed to re-run after a restart
 * @property result type-specific outputs (the "enrichment" landing spot), JSON-serialized
 * @property retryCount how many times [AsyncTaskStore.retry] reset this task for another run
 */
data class AsyncTask(
    val id: String,
    val userId: String,
    val type: AsyncTaskType,
    val trigger: TaskTrigger,
    val title: String,
    val status: AsyncTaskStatus = AsyncTaskStatus.PENDING,
    val sessionId: String? = null,
    val payload: Map<String, Any?> = emptyMap(),
    val result: Map<String, Any?>? = null,
    val error: String? = null,
    val retryCount: Int = 0,
    val retriedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val durationMs: Long? get() = if (startedAt != null && completedAt != null) completedAt - startedAt else null
}

/** Result of a [TaskHandler.execute]: either a success payload or a failure reason. */
sealed interface TaskOutcome {
    data class Success(val result: Map<String, Any?> = emptyMap()) : TaskOutcome
    data class Failure(val error: String) : TaskOutcome
}
