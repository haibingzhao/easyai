package com.easy.easyai.autoconfigure.rag

import com.easy.easyai.core.memory.MemoryConsolidationSettings
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Bindings for `easyai.memory.consolidation.*`, mapped onto the core
 * [com.easy.easyai.core.memory.MemoryConsolidationSettings].
 *
 * These are deployment-level defaults and limits only. Whether consolidation runs for a user is a
 * runtime, per-user setting (the `memory_consolidation` row of `scheduled_task_setting`), validated at
 * the web boundary against a configured MEMORY_CURATION model.
 *
 * Every default below is read from a core [MemoryConsolidationSettings] rather than restated: a literal
 * here silently loses to the core default the moment one side is tuned, and the binding always wins
 * because auto-configuration builds the core type from these values. A core limit left out of this class
 * is not merely un-advertised — it becomes unconfigurable, because the bean is rebuilt from these
 * properties and the omitted field falls back to the core default no matter what the operator sets.
 */
@ConfigurationProperties(prefix = "easyai.memory.consolidation")
class MemoryConsolidationProperties {
    private val coreDefaults = MemoryConsolidationSettings()

    /** Deployment-wide kill switch. When false no agent/handler/planner is wired. */
    var available: Boolean = coreDefaults.available

    /** Character budget of one grouping-pass prompt (index rows only, never bodies). */
    var maxGroupingPromptChars: Int = coreDefaults.maxGroupingPromptChars

    /** Largest cluster one merged entry may absorb. */
    var maxSourcesPerGroup: Int = coreDefaults.maxSourcesPerGroup

    /** Summed stored size of one cluster's sources. */
    var maxGroupSourceChars: Int = coreDefaults.maxGroupSourceChars

    /** Clusters answered by one merge-pass prompt. */
    var clustersPerMergeCall: Int = coreDefaults.clustersPerMergeCall

    /** Character budget of one merge-pass prompt, bounded by what the model must emit. */
    var maxMergePromptCharsPerCall: Int = coreDefaults.maxMergePromptCharsPerCall

    /** Max source entries archived by one consolidation run. */
    var maxArchivesPerRun: Int = coreDefaults.maxArchivesPerRun

    /** Cap on agent-loop iterations for one prompt; there is no tool phase to fit. */
    var maxIterations: Int = coreDefaults.maxIterations

    /** Wall-clock budget for one consolidation task. */
    var runTimeoutMs: Long = coreDefaults.runTimeoutMs

    /** Wall-clock budget for a preview, which an HTTP request waits on synchronously. */
    var dryRunTimeoutMs: Long = coreDefaults.dryRunTimeoutMs

    /** Per-type threshold used when the user did not override that type. */
    var defaultThreshold: Int = coreDefaults.defaultThreshold

    /** Default daily scan cron (six-field Spring syntax). */
    var dailyCron: String = coreDefaults.dailyCron

    /** Max consolidation tasks one scan tick may queue for a single user. */
    var maxTasksPerTickPerUser: Int = coreDefaults.maxTasksPerTickPerUser

    /** Consolidation depth at which an entry stops being mergeable material. */
    var maxLevel: Int = coreDefaults.maxLevel

    /** First delay after a run that archived nothing; doubled per repeat. */
    var zeroGainBackoffBaseDays: Int = coreDefaults.zeroGainBackoffBaseDays

    /** Ceiling of that doubling. */
    var zeroGainBackoffMaxDays: Int = coreDefaults.zeroGainBackoffMaxDays
}
