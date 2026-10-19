package com.easy.easyai.autoconfigure.r2dbc

import com.easy.easyai.core.task.AsyncTaskRunner
import com.easy.easyai.core.task.AsyncTaskStore
import com.easy.easyai.core.task.ScheduledTaskPlanner
import com.easy.easyai.core.task.ScheduledTaskSettingStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Wires the one scheduler the whole application needs: [ScheduledTaskRunner] collects every
 * [ScheduledTaskPlanner] present and drives them from the shared `scheduled_task_setting` table.
 *
 * Making a task type schedulable is a planner bean plus its settings key — no new scheduler, no new
 * table, no change here. Turn `easyai.tasks.scheduled.enabled=false` to stop all scanning without
 * touching task execution; the tick interval is `easyai.tasks.scheduled.scan-cron` (kebab-case, because
 * a `@Scheduled` placeholder reads the literal property name and does not get relaxed binding).
 *
 * Sits in this module because the settings rows require the repository layer; without R2DBC there is
 * nothing durable to scan.
 */
@AutoConfiguration(after = [R2dbcRepositoryAutoConfiguration::class])
@ConditionalOnProperty(prefix = "easyai.r2dbc", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(
    prefix = "easyai.tasks.scheduled",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true
)
@EnableScheduling
class ScheduledTaskAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun scheduledTaskRunner(
        settingStore: ObjectProvider<ScheduledTaskSettingStore>,
        taskStore: ObjectProvider<AsyncTaskStore>,
        taskRunner: ObjectProvider<AsyncTaskRunner>,
        planners: ObjectProvider<ScheduledTaskPlanner>
    ): ScheduledTaskRunner = ScheduledTaskRunner(
        settingStore = settingStore.ifAvailable,
        taskStore = taskStore.ifAvailable,
        taskRunner = taskRunner.ifAvailable,
        planners = planners.orderedStream().toList()
    )
}
