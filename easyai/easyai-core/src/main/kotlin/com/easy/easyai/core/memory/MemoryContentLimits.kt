package com.easy.easyai.core.memory

/**
 * Size budget for memory entries, shared by every write path (on-demand `memory_write`, flush
 * extraction, consolidation merge) and by the manual edit endpoints. `memory_write` and the
 * consolidation writer reject an over-long body so the model can distill and retry; only the flush
 * path has no retry loop, so it truncates — inside the cap, its marker paid for out of the budget,
 * so a flushed entry stays editable on the Memories page, which applies the same cap.
 */
object MemoryContentLimits {
    /** Soft target advertised in prompts: distilled facts, one focused entry per fact. */
    const val CONTENT_TARGET_CHARS = 2000

    /** Hard cap: over it `memory_write` and the consolidation writer fail, flush truncates. */
    const val CONTENT_HARD_CAP_CHARS = 6000

    /** Descriptions are one-line summaries; the read paths only ever show their head anyway. */
    const val DESCRIPTION_CAP_CHARS = 200
}
