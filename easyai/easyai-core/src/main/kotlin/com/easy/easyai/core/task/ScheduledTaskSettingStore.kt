package com.easy.easyai.core.task

interface ScheduledTaskSettingStore {

    /** The row with the given schedule key; the default empty key is the type's single-row schedule. */
    suspend fun get(
        userId: String,
        taskType: AsyncTaskType,
        scheduleKey: String = ""
    ): ScheduledTaskSetting?

    /** Every row the user has for this type, ordered by schedule key. */
    suspend fun list(userId: String, taskType: AsyncTaskType): List<ScheduledTaskSetting>

    suspend fun save(setting: ScheduledTaskSetting)

    /**
     * Remove one row. The default empty key only addresses the legacy single-row schedule; a host
     * that writes several rows of one type must pass its own key.
     */
    suspend fun delete(
        userId: String,
        taskType: AsyncTaskType,
        scheduleKey: String = ""
    ): Boolean

    suspend fun listEnabled(taskType: AsyncTaskType? = null): List<ScheduledTaskSetting>

    /**
     * Includes uninitialized schedules and every overdue row; paginated by `(user, schedule key)`.
     * Pass [afterUserId] and [afterScheduleKey] together (the last row's pair), or neither: a user
     * cursor without its key cannot address the next row inside that user.
     */
    suspend fun listForScheduling(
        taskType: AsyncTaskType,
        horizon: Long,
        afterUserId: String? = null,
        limit: Int = 200,
        afterScheduleKey: String? = null
    ): List<ScheduledTaskSetting>

    suspend fun initializeSchedule(setting: ScheduledTaskSetting, nextFireAt: Long?, zone: String): Boolean

    /** Insert the whole batch and advance the expected firing atomically; null means the claim was lost. */
    suspend fun submitScheduled(
        setting: ScheduledTaskSetting,
        nextFireAt: Long?,
        tasks: List<AsyncTask>
    ): List<AsyncTask>?
}
