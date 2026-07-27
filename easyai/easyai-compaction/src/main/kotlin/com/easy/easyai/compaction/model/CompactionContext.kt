package com.easy.easyai.compaction.model

import com.easy.easyai.api.model.ModelProviderConfig

/**
 * Context passed to compaction strategies during execution.
 *
 * @param range The range of messages being compacted
 * @param previousSummary Previous compaction summary (for incremental updates)
 * @param currentTurnId Current turn ID in the agent loop
 * @param compactionRound Current compaction round (1 = first, 2+ = subsequent)
 * @param modelConfig Model provider config from the parent agent context.
 *   Propagated so agent-based strategies can build correct ChatOptions (model name, protocol).
 */
data class CompactionContext(
    val range: CompactedRange,
    val previousSummary: String? = null,
    val currentTurnId: Int,
    val compactionRound: Int = 1,
    val modelConfig: ModelProviderConfig? = null
)