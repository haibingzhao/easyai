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
     * A row whose type this build does not know is skipped rather than returned: an unrecognised
     * preference must not poison the whole scan, and no planner can serve it anyway.
     */
    suspend fun listEnabled(taskType: AsyncTaskType? = null): List<ScheduledTaskSetting>
}
