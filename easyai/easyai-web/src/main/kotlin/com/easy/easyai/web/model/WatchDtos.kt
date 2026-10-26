package com.easy.easyai.web.model

import com.easy.easyai.core.watch.Watch
import com.easy.easyai.core.watch.WatchFinding
import com.fasterxml.jackson.annotation.JsonInclude

/** One tag of a watch's universe, as returned by the REST surface. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class WatchTagDto(
    val key: String,
    val label: String,
    val description: String? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class WatchSourceDto(
    val label: String,
    val url: String
)

/** A stored conclusion. `verdict` stays machine-readable; `tags` carry the shown labels. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class WatchFindingDto(
    val conclusion: String,
    val verdict: String,
    val tags: List<WatchTagDto>,
    val sources: List<WatchSourceDto>,
    val definitionVersion: Int,
    val changed: Boolean,
    val notified: Boolean,
    val applied: Boolean,
    val stale: Boolean,
    val scheduledFor: Long?,
    val lateByMs: Long
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class WatchScheduleDto(
    val kind: String,
    val timezone: String,
    /** `HH:mm` for the recurring kinds; null for a one-shot. */
    val localTime: String? = null,
    val weekday: String? = null,
    val dayOfMonth: Int? = null,
    /** Epoch millis of the one-shot instant; null for the recurring kinds. */
    val anchorAt: Long? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class WatchDto(
    val id: String,
    val title: String,
    val condition: String,
    val mode: String,
    val reminder: String?,
    val schedule: WatchScheduleDto,
    val untilAt: Long?,
    val nextDueAt: Long,
    val status: String,
    val pendingAction: String,
    val version: Int,
    val extensionCount: Int,
    val tags: List<WatchTagDto>,
    val completeOnMet: Boolean,
    val notifyPolicy: String,
    val notifyTags: List<String>,
    val sourceAgentId: String,
    val originSessionId: String?,
    val runSessionId: String,
    val allowedTools: List<String>,
    val sourceHints: String?,
    val lastTaskId: String?,
    val lastRunAt: Long?,
    val lastFinding: WatchFindingDto?,
    val consecutiveFailures: Int,
    val lastError: String?,
    val statusReason: String?,
    val createdAt: Long,
    val updatedAt: Long
)

/** One firing as stored in `async_task`: the UI decides by `result.kind` how to render it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class WatchFindingTaskDto(
    val taskId: String,
    val status: String,
    val createdAt: Long,
    val completedAt: Long?,
    val error: String?,
    val result: Map<String, Any?>?
)

/**
 * PATCH body. Every field is optional: null means "leave it alone". A terminal watch
 * (COMPLETED/EXPIRED) needs a new [schedule] to be renewed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class WatchUpdateRequest(
    val title: String? = null,
    val condition: String? = null,
    val mode: String? = null,
    val reminder: String? = null,
    val timezone: String? = null,
    val until: String? = null,
    val clearUntil: Boolean? = null,
    val tags: List<WatchTagDto>? = null,
    val completeOnMet: Boolean? = null,
    val notifyPolicy: String? = null,
    val notifyTags: List<String>? = null,
    val sourceHints: String? = null,
    val schedule: WatchScheduleRequest? = null
)

/** Structured trigger fields; the same forms the `watch` tool accepts. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class WatchScheduleRequest(
    val at: String? = null,
    val within: String? = null,
    val dayOffset: Int? = null,
    val every: String? = null,
    val time: String? = null,
    val weekday: String? = null,
    val dayOfMonth: Int? = null
)

object WatchDtos {

    @JvmStatic
    fun toDto(watch: Watch): WatchDto = WatchDto(
        id = watch.id,
        title = watch.title,
        condition = watch.condition,
        mode = watch.mode.name.lowercase(),
        reminder = watch.reminderText,
        schedule = WatchScheduleDto(
            kind = watch.schedule.kind.name.lowercase(),
            timezone = watch.schedule.zone.id,
            localTime = watch.schedule.localTime?.toString(),
            weekday = watch.schedule.weekday?.name,
            dayOfMonth = watch.schedule.dayOfMonth,
            anchorAt = watch.schedule.anchorAt?.toEpochMilli()
        ),
        untilAt = watch.untilAt?.toEpochMilli(),
        nextDueAt = watch.nextDueAt.toEpochMilli(),
        status = watch.status.name.lowercase(),
        pendingAction = watch.pendingAction.name.lowercase(),
        version = watch.version,
        extensionCount = watch.extensionCount,
        tags = watch.tags.map { WatchTagDto(it.key, it.label, it.description) },
        completeOnMet = watch.completeOnMet,
        notifyPolicy = watch.notifyPolicy.name.lowercase(),
        notifyTags = watch.notifyTags.toList(),
        sourceAgentId = watch.sourceAgentId,
        originSessionId = watch.originSessionId,
        runSessionId = watch.runSessionId,
        allowedTools = watch.allowedTools,
        sourceHints = watch.sourceHints,
        lastTaskId = watch.lastTaskId,
        lastRunAt = watch.lastRunAt,
        lastFinding = watch.lastFinding?.let { finding -> toDto(finding) },
        consecutiveFailures = watch.consecutiveFailures,
        lastError = watch.lastError,
        statusReason = watch.statusReason,
        createdAt = watch.createdAt,
        updatedAt = watch.updatedAt
    )

    @JvmStatic
    fun toDto(finding: WatchFinding): WatchFindingDto = WatchFindingDto(
        conclusion = finding.conclusion,
        verdict = finding.verdict.name.lowercase(),
        tags = finding.tags.map { WatchTagDto(it.key, it.label) },
        sources = finding.sources.map { WatchSourceDto(it.label, it.url) },
        definitionVersion = finding.definitionVersion,
        changed = finding.changed,
        notified = finding.notified,
        applied = finding.applied,
        stale = finding.stale,
        scheduledFor = finding.scheduledFor,
        lateByMs = finding.lateByMs
    )
}
