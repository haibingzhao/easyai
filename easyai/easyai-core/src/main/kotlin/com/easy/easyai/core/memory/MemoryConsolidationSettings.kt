package com.easy.easyai.core.memory

/**
 * Deployment-level defaults and hard limits for memory consolidation. Runtime opt-in and per-type
 * thresholds live in this type's row of the shared `scheduled_task_setting` table
 * ([com.easy.easyai.core.memory.consolidation.MemoryConsolidationUserSettings]); these values only
 * bound what any user-triggered or scheduled run may do.
 *
 * @property available deployment-wide kill switch (default on). When false no agent, handler or
 *   planner is wired, regardless of per-user settings.
 * @property maxEntriesPerBatch cap on entries fed to one prompt batch; a larger slice is split into
 *   sequential batches. Kept apart from [maxArchivesPerRun] on purpose: one number for both let the
 *   first batch spend the whole run's archive budget, so every later cluster was deferred even though
 *   the run had work left to do.
 * @property maxArchivesPerRun cap on source entries archived by one run.
 * @property maxPromptCharsPerBatch character budget of one batch prompt (entry bodies plus their
 *   framing). The budget cuts batches first and [maxEntriesPerBatch] only caps the entry count, because
 *   an entry may carry [MemoryContentLimits.CONTENT_HARD_CAP_CHARS] characters and a count-only split
 *   would then emit one prompt far past any model's context window. It is kept well under that on
 *   purpose: one batch is answered by one JSON object carrying every merged body, so the same budget
 *   also bounds how much the model must emit before its answer is truncated.
 * @property maxIterations cap on ReAct iterations for one batch prompt. A batch needs its answer plus
 *   whatever structured-output nudges the completion check may spend; there is no tool phase to fit.
 * @property runTimeoutMs wall-clock budget for one consolidation task.
 * @property dryRunTimeoutMs wall-clock budget for a preview, which an HTTP request waits on
 *   synchronously, so it must stay well inside any gateway timeout.
 * @property defaultThreshold per-type "consolidate when active count reaches N" used when the user
 *   did not override that type.
 * @property dailyCron default scan cron (Spring six-field syntax) offered to the scheduler as this
 *   type's `defaultCron`; a user row may override it.
 * @property maxTasksPerTickPerUser hard cap on consolidation tasks one scan may queue for a single
 *   user, so a user with many over-threshold slices cannot enqueue an unbounded number of LLM runs.
 */
data class MemoryConsolidationSettings(
    val available: Boolean = true,
    val maxEntriesPerBatch: Int = 200,
    val maxArchivesPerRun: Int = 200,
    val maxPromptCharsPerBatch: Int = 20_000,
    val maxIterations: Int = 6,
    val runTimeoutMs: Long = 10 * 60 * 1000,
    val dryRunTimeoutMs: Long = 2 * 60 * 1000,
    val defaultThreshold: Int = 20,
    val dailyCron: String = "0 0 3 * * *",
    val maxTasksPerTickPerUser: Int = 5
)
