package com.easy.easyai.core.task

import java.time.Instant

/**
 * Expands one user's [ScheduledTaskSetting] into the tasks a scan tick should create for [type].
 * Pairs with [TaskHandler] by division: a planner decides *whether and what* to run, a handler runs it.
 *
 * The driver calls this at a due firing, including while an earlier batch for the same user is still
 * queued. [ScheduledTaskPlanContext.pendingTasks] supports duplicate suppression; an empty list
 * consumes the firing, so unavailable data must throw rather than masquerade as no work.
 */
interface ScheduledTaskPlanner {

    /** The task type whose settings this planner serves. */
    val type: AsyncTaskType

    /** Scan cron used when the user's row sets none; null means a row without a cron is never due. */
    val defaultCron: String?

    /** False for planners that claim their own due rows, such as host-owned watches. */
    val usesPersistentSchedule: Boolean get() = true

    /** Hard cap on drafts accepted per user per tick, so one scan cannot enqueue unbounded LLM runs. */
    val perUserPerTickLimit: Int

    /** The tasks to create for this `(user, type)` row, or an empty list. */
    suspend fun plan(context: ScheduledTaskPlanContext): List<TaskDraft>
}

/**
 * Everything a [ScheduledTaskPlanner] needs for one user, pre-resolved by the driver so planners stay
 * free of scheduling mechanics (cron parsing, tick timing, submission).
 *
 * @property now the tick instant, injected so a planner's due-ness logic stays testable
 * @property pendingTasks this user's non-terminal tasks of [ScheduledTaskPlanner.type]; the planner
 *   decides what makes a draft a duplicate of one of these
 */
data class ScheduledTaskPlanContext(
    val userId: String,
    val setting: ScheduledTaskSetting,
    val now: Instant,
    val pendingTasks: List<AsyncTask>
)

/**
 * A task not yet persisted. The driver mints the id, stamps `trigger = SCHEDULE` and submits it.
 *
 * [payload] must be self-sufficient: a scheduled run can be retried long after the settings row that
 * produced it changed or was deleted, so its handler must never need to read settings back.
 */
data class TaskDraft(
    val userId: String,
    val type: AsyncTaskType,
    val title: String,
    val payload: Map<String, Any?> = emptyMap(),
    /** Wall-clock bound for the run; null leaves the type's handler unbounded. */
    val timeoutMs: Long? = null,
    val sessionId: String? = null
)
