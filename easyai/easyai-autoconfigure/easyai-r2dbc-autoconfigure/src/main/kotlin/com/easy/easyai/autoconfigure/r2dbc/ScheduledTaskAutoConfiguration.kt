package com.easy.easyai.autoconfigure.r2dbc

import com.easy.easyai.core.task.AsyncTaskRunner
import com.easy.easyai.core.task.AsyncTaskStore
import com.easy.easyai.core.task.ScheduledTaskPlanner
import com.easy.easyai.core.task.ScheduledTaskSettingStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.ZoneId

/**
 * Wires [ScheduledTaskRunner] with every registered [ScheduledTaskPlanner].
 *
 * Persistent schedules are preloaded every 30 minutes and triggered by in-memory timers; watches
 * have their own per-row timers. `easyai.tasks.scheduled.scan-cron` only controls planners that
 * poll their own due rows.
 * `easyai.tasks.scheduled.enabled=false` disables both paths without stopping existing tasks.
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
        planners: ObjectProvider<ScheduledTaskPlanner>,
        @Value("\${easyai.tasks.scheduled.zone:}") scheduleZone: String,
    ): ScheduledTaskRunner = ScheduledTaskRunner(
        settingStore = settingStore.ifAvailable,
        taskStore = taskStore.ifAvailable,
        taskRunner = taskRunner.ifAvailable,
        planners = planners.orderedStream().toList(),
        scheduleZone = scheduleZone.takeIf { it.isNotBlank() }?.let(ZoneId::of) ?: ZoneId.systemDefault(),
    )
}
