package com.easy.easyai.compaction.strategy

import com.easy.easyai.core.model.Usage

/**
 * Result of a compaction strategy execution.
 * Contains the generated summary and token usage information.
 *
 * @property summary The generated summary text
 * @property usage Full usage information from the compaction operation:
 *   - LLM strategy: actual inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens from the API response.
 *   - Summary strategy: outputTokens estimated by tokenEstimator; other fields are 0.
 */
data class StrategyOutput(
    val summary: String,
    val usage: Usage = Usage()
)
