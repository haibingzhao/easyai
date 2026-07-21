package com.easy.easyai.compaction

/**
 * Configuration for context compaction.
 *
 * @property enabled Whether compaction is enabled (default: true)
 * @property threshold Trigger threshold ratio (0.8 = 80% of context window)
 * @property reservedTokens Reserved buffer tokens for response generation
 * @property tailTurns Number of recent conversation turns to preserve
 * @property preserveRecentTokensRatio Ratio of context to preserve as recent turns (0.25 = 25%)
 * @property minMessagesForCompaction Minimum message count before compaction checks begin
 * @property checkInterval Check compaction every N turns (avoids expensive estimation every turn)
 */
data class CompactionConfig(
    val enabled: Boolean = true,
    val threshold: Double = 0.8,
    val reservedTokens: Int = 10_000,
    val tailTurns: Int = 2,
    val preserveRecentTokensRatio: Double = 0.25,
    val minMessagesForCompaction: Int = 10,
    val checkInterval: Int = 5
)