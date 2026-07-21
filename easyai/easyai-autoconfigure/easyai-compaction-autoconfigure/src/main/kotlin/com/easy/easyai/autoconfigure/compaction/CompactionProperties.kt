package com.easy.easyai.autoconfigure.compaction

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for context compaction.
 *
 * Prefix: easyai.compaction
 */
@ConfigurationProperties(prefix = "easyai.compaction")
data class CompactionProperties(
    /**
     * Whether compaction is enabled.
     */
    var enabled: Boolean = true,

    /**
     * Trigger threshold ratio (0.8 = 80% of context window).
     */
    var threshold: Double = 0.8,

    /**
     * Reserved buffer tokens for response generation.
     */
    var reservedTokens: Int = 10_000,

    /**
     * Number of recent conversation turns to preserve.
     */
    var tailTurns: Int = 2,

    /**
     * Ratio of context to preserve as recent turns (0.25 = 25%).
     */
    var preserveRecentTokensRatio: Double = 0.25,

    /**
     * Minimum message count before compaction checks begin.
     */
    var minMessages: Int = 10,

    /**
     * Check compaction every N turns.
     */
    var checkInterval: Int = 3,

    /**
     * Strategy to use for compaction summaries.
     * - "auto": Layered progressive (Round 1 = SummaryStrategy, Round 2+ = LlmSummaryStrategy)
     * - "summary": Always use SummaryStrategy (fast, free)
     * - "llm": Always use LlmSummaryStrategy (high quality, requires ChatModel)
     */
    var strategy: String = "auto",

    /**
     * Context usage ratio that triggers memory flush before compaction (0.75 = 75%).
     * Only used when a MemoryStore is available.
     */
    var memoryFlushThreshold: Float = 0.75f
)
