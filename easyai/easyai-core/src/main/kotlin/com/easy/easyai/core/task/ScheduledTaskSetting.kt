package com.easy.easyai.core.task

/**
 * One user's opt-in preferences for a schedulable [AsyncTaskType] — the row behind the
 * `scheduled_task_setting` table, keyed by `(user, type)`.
 *
 * Deliberately type-agnostic: everything specific to a task type lives in [params] and is decoded by
 * that type's [ScheduledTaskPlanner], so teaching the system a new schedulable type adds neither a
 * column nor a table.
 *
 * @property cron optional override of [ScheduledTaskPlanner.defaultCron]; null follows the default
 * @property params type-specific configuration, JSON-persisted and owned by the planner
 */
data class ScheduledTaskSetting(
    val userId: String,
    val taskType: AsyncTaskType,
    val enabled: Boolean = false,
    val cron: String? = null,
    val params: Map<String, Any?> = emptyMap(),
    val updatedAt: Long = System.currentTimeMillis()
)
