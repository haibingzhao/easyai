package com.easy.easyai.web.model

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MemoryEntryDto(
    val name: String,
    val description: String,
    val type: String,
    val scope: String,
    val content: String,
    val keywords: List<String> = emptyList(),
    val maturity: String? = null,
    val scenarios: List<String> = emptyList(),
    val created: String? = null,
    val updated: String? = null,
    /** Stable EasyRAG docId; the key used by consolidation refs and archive drill-down. */
    val id: String? = null,
    /** Consolidation depth: 0 for an original entry, N for a merged entry absorbing level N-1. */
    val level: Int = 0,
    /** Source docIds absorbed into this merged entry. */
    val refs: List<String> = emptyList(),
    /** Set on an archived entry: the merged docId that absorbed it. */
    val mergedInto: String? = null,
    /** Archived entries are only returned by the archive drill-down endpoint. */
    val archived: Boolean = false
)

data class CreateMemoryRequest(
    val name: String,
    val description: String,
    val type: String,
    val scope: String,
    val content: String,
    val keywords: List<String> = emptyList(),
    /** Maturity level (low/medium/high); invalid values are ignored. */
    val maturity: String? = null,
    /** Usage scenarios; blank entries are dropped. */
    val scenarios: List<String> = emptyList(),
    /** Runtime project path; required when scope is "project". */
    val projectPath: String? = null
)

/** Partial update of a memory entry's editable fields. The type/name are immutable. */
data class UpdateMemoryRequest(
    val description: String? = null,
    val content: String? = null,
    val keywords: List<String>? = null,
    val maturity: String? = null,
    val scenarios: List<String>? = null
)

data class MemoryConfigDto(
    val enabled: Boolean
)

data class UpdateMemoryConfigRequest(
    val enabled: Boolean? = null
)

/**
 * Runtime consolidation preferences for the current user.
 *
 * @property enabled per-user master switch (off until the user turns it on)
 * @property typeThresholds per-type "consolidate when active count reaches N", keyed by type dirName
 * @property scanCron per-user scan override; null follows [defaultScanCron]
 * @property defaultThreshold deployment default used for types absent from [typeThresholds]
 * @property defaultScanCron deployment default scan cron
 * @property curationModelConfigured whether a MEMORY_CURATION model is resolvable; enabling requires it
 * @property available deployment kill switch (`easyai.memory.consolidation.available`)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MemoryConsolidationSettingsDto(
    val enabled: Boolean,
    val typeThresholds: Map<String, Int> = emptyMap(),
    val scanCron: String? = null,
    val defaultThreshold: Int,
    val defaultScanCron: String,
    val curationModelConfigured: Boolean,
    val available: Boolean
)

/** Partial update; blank [scanCron] clears the override, null leaves it unchanged. */
data class UpdateMemoryConsolidationSettingsRequest(
    val enabled: Boolean? = null,
    val typeThresholds: Map<String, Int>? = null,
    val scanCron: String? = null
)

/** Request to run a memory-consolidation pass over one type in one slice. */
data class ConsolidationRequest(
    val scope: String = "global",
    /** Runtime project path; required when scope is "project". */
    val projectPath: String? = null,
    /** [com.easy.easyai.core.memory.MemoryType.dirName] to consolidate. */
    val type: String,
    /** When true, compute the cluster plan without writing or archiving. */
    val dryRun: Boolean = false
)

/** Outcome of a consolidation pass. The plan is present for dry runs and as an audit trail. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ConsolidationReportDto(
    val type: String,
    val scope: String,
    val activeBefore: Int,
    /** Clusters the grouping pass proposed and its validator accepted, whether or not a body was written. */
    val clustersProposed: Int = 0,
    val mergedWritten: Int = 0,
    val archived: Int = 0,
    /** Sources a written cluster failed to archive, so they are still live duplicates of it. */
    val archiveShortfall: Int = 0,
    val skippedSingletons: Int = 0,
    val skippedOverCap: Int = 0,
    /** Groups refused for being too large to distill into one merged entry even after trimming. */
    val droppedGroups: Int = 0,
    /** Written bodies past the key-points target but inside the hard cap: stored, and worth reading. */
    val overTarget: Int = 0,
    /** Prompts whose answer could not be applied: an unusable first answer, or an unusable re-ask. */
    val rejectedBatches: Int = 0,
    /** Prompts whose model call itself failed, so the model never answered at all. */
    val llmFailures: Int = 0,
    /** Why the first failed call failed, as the model client reported it. */
    val llmFailureReason: String? = null,
    /** Sources the model neither clustered nor declared as leftovers. */
    val uncoveredDocIds: Int = 0,
    val dryRun: Boolean = false,
    val sessionId: String? = null,
    val plan: List<Map<String, Any?>> = emptyList()
)

/**
 * Response of `POST /api/memories/consolidation`. A dry run computes the plan synchronously and
 * returns [report]; a real run creates a durable async task and returns [taskId] (track it via
 * `/api/tasks`; the hidden session id is on the task row).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ConsolidationResponse(
    val taskId: String? = null,
    val report: ConsolidationReportDto? = null
)
