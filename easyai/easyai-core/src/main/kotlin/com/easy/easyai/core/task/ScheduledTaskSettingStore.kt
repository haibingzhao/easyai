package com.easy.easyai.core.task

/**
 * Persistence for [ScheduledTaskSetting] — the configuration side of the async-task subsystem,
 * complementing [AsyncTaskStore] (one row per run) with one row per `(user, type)` preference.
 *
 * Ownership is `user_id` strict, matching the task rows these settings schedule.
 */
interface ScheduledTaskSettingStore {

    /** The user's row for [taskType]; null when they never configured it (treated as off). */
    suspend fun get(userId: String, taskType: AsyncTaskType): ScheduledTaskSetting?

    /** Insert or replace the caller's `(user, type)` row. */
    suspend fun save(setting: ScheduledTaskSetting)

    /** Drop the `(user, type)` row, returning that type to off for that user; false when absent. */
    suspend fun delete(userId: String, taskType: AsyncTaskType): Boolean

    /**
     * Every enabled row for the scheduled scan. Pass [taskType] to restrict to one type.
     *
     * Rows for a type with no planner registered in this build are still returned; the scan skips
     * them itself, so a rolled-back host keeps the preference for a build that understands it.
     */
    suspend fun listEnabled(taskType: AsyncTaskType? = null): List<ScheduledTaskSetting>
}
