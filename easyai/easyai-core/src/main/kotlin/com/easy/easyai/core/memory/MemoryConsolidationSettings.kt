package com.easy.easyai.core.memory

/**
 * Deployment-level defaults and hard limits for memory consolidation. Runtime opt-in and per-type
 * thresholds live in this type's row of the shared `scheduled_task_setting` table
 * ([com.easy.easyai.core.memory.consolidation.MemoryConsolidationUserSettings]); these values only
 * bound what any user-triggered or scheduled run may do.
 *
 * @property available deployment-wide kill switch (default on). When false no agent, handler or
 *   planner is wired, regardless of per-user settings.
 * @property maxGroupingPromptChars character budget of one grouping-pass prompt. That pass prints no
 *   memory bodies at all — only `docId + name + level + size + blurb` per entry — so its ceiling is set
 *   by what the model must *read*, which is cheap. Deliberately an order of magnitude above
 *   [maxMergePromptCharsPerCall]: cutting the grouping budget is what split one slice into batches that
 *   could never see each other and merge across them was then impossible.
 * @property maxSourcesPerGroup largest cluster one merged entry may absorb. A cluster is judged on
 *   names and blurbs, so the cap is what keeps the merge pass from being asked to distill an absurd
 *   amount of text into the body size limit.
 * @property maxGroupSourceChars summed body size of one cluster's sources, measured from the stored
 *   document sizes. A cluster over it is trimmed to fit; a cluster that cannot fit under it is dropped,
 *   because asking for a [MemoryContentLimits.CONTENT_TARGET_CHARS]-style digest of it would only
 *   invent a lossy summary.
 * @property clustersPerMergeCall clusters answered by one merge-pass prompt. Each answer re-emits every
 *   merged body, so the count bounds what the model must *emit* — the scarce side.
 * @property maxMergePromptCharsPerCall character budget of one merge-pass prompt (the source bodies it
 *   must rewrite). Clusters are never split across prompts, so this is a cap on whole clusters only.
 * @property maxArchivesPerRun cap on source entries archived by one run. Kept apart from the batch
 *   budgets on purpose: one number for both let the first batch spend the whole run's archive budget,
 *   so every later cluster was deferred even though the run had work left to do.
 * @property maxIterations cap on ReAct iterations for one prompt. A batch needs its answer plus whatever
 *   structured-output nudges the completion check may spend; there is no tool phase to fit.
 * @property runTimeoutMs wall-clock budget for one consolidation task: one grouping pass plus roughly
 *   `clusters / clustersPerMergeCall` merge calls, each of which re-emits whole bodies.
 * @property dryRunTimeoutMs wall-clock budget for a preview, which an HTTP request waits on
 *   synchronously, so it must stay well inside any gateway timeout. A preview is grouping-only.
 * @property defaultThreshold per-type "consolidate when active count reaches N" used when the user
 *   did not override that type.
 * @property dailyCron default scan cron (Spring six-field syntax) offered to the scheduler as this
 *   type's `defaultCron`; a user row may override it.
 * @property maxTasksPerTickPerUser hard cap on consolidation tasks one scan may queue for a single
 *   user, so a user with many over-threshold slices cannot enqueue an unbounded number of LLM runs.
 * @property maxLevel consolidation depth at which an entry stops being mergeable material. Merged
 *   entries stay active and keep counting toward [defaultThreshold], so without this ceiling a slice
 *   that gains a few new memories each day re-distills yesterday's digest one level higher forever.
 *   Entries at or above it remain searchable — they are simply never offered as sources again.
 * @property zeroGainBackoffBaseDays delay after a run that archived nothing, doubled per repeat up to
 *   [zeroGainBackoffMaxDays]. A model that finds nothing to merge leaves the active count unchanged, so
 *   a threshold-only trigger would re-ask the same question at every scan, forever.
 * @property zeroGainBackoffMaxDays ceiling of that doubling, so a permanently unmergeable slice is
 *   retried occasionally rather than never.
 */
data class MemoryConsolidationSettings(
    val available: Boolean = true,
    val maxGroupingPromptChars: Int = 120_000,
    val maxSourcesPerGroup: Int = 8,
    val maxGroupSourceChars: Int = 12_000,
    val clustersPerMergeCall: Int = 3,
    val maxMergePromptCharsPerCall: Int = 40_000,
    val maxArchivesPerRun: Int = 200,
    val maxIterations: Int = 6,
    val runTimeoutMs: Long = 25 * 60 * 1000,
    val dryRunTimeoutMs: Long = 2 * 60 * 1000,
    val defaultThreshold: Int = 20,
    val dailyCron: String = "0 0 3 * * *",
    val maxTasksPerTickPerUser: Int = 5,
    val maxLevel: Int = 3,
    val zeroGainBackoffBaseDays: Int = 7,
    val zeroGainBackoffMaxDays: Int = 30
)
