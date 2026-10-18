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
 * because auto-configuration builds the core type from these values.
 */
@ConfigurationProperties(prefix = "easyai.memory.consolidation")
class MemoryConsolidationProperties {
    private val coreDefaults = MemoryConsolidationSettings()

    /** Deployment-wide kill switch. When false no agent/handler/planner is wired. */
    var available: Boolean = coreDefaults.available

    /** Max active entries fed to one prompt batch. */
    var maxEntriesPerBatch: Int = coreDefaults.maxEntriesPerBatch

    /** Max source entries archived by one consolidation run. */
    var maxArchivesPerRun: Int = coreDefaults.maxArchivesPerRun

    /** Cap on agent-loop iterations for one batch answer; there is no tool phase to fit. */
    var maxIterations: Int = coreDefaults.maxIterations

    /** Wall-clock budget for one consolidation task. */
    var runTimeoutMs: Long = coreDefaults.runTimeoutMs

    /** Per-type threshold used when the user did not override that type. */
    var defaultThreshold: Int = coreDefaults.defaultThreshold

    /** Default daily scan cron (six-field Spring syntax). */
    var dailyCron: String = coreDefaults.dailyCron

    /** Max consolidation tasks one scan tick may queue for a single user. */
    var maxTasksPerTickPerUser: Int = coreDefaults.maxTasksPerTickPerUser
}
