package com.easy.easyai.core.task

interface ScheduledTaskSettingStore {

    suspend fun get(userId: String, taskType: AsyncTaskType): ScheduledTaskSetting?

    suspend fun save(setting: ScheduledTaskSetting)

    suspend fun delete(userId: String, taskType: AsyncTaskType): Boolean

    suspend fun listEnabled(taskType: AsyncTaskType? = null): List<ScheduledTaskSetting>

    /** Includes uninitialized schedules and every overdue row; paginated by user within one type. */
    suspend fun listForScheduling(
        taskType: AsyncTaskType,
        horizon: Long,
        afterUserId: String? = null,
        limit: Int = 200
    ): List<ScheduledTaskSetting>

    suspend fun initializeSchedule(setting: ScheduledTaskSetting, nextFireAt: Long?, zone: String): Boolean

    /** Insert the whole batch and advance the expected firing atomically; null means the claim was lost. */
    suspend fun submitScheduled(
        setting: ScheduledTaskSetting,
        nextFireAt: Long?,
        tasks: List<AsyncTask>
    ): List<AsyncTask>?
}
