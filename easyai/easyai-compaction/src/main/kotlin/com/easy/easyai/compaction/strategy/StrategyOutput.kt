package com.easy.easyai.compaction.strategy

import com.easy.easyai.core.model.Usage

/**
 * Result of a compaction strategy execution.
 * Contains the generated summary, token usage information, and extracted session variables.
 *
 * @property summary The generated summary text
 * @property usage Full usage information from the compaction operation
 * @property variables Session variables extracted during compaction (key-value pairs)
 */
data class StrategyOutput(
    val summary: String,
    val usage: Usage = Usage(),
    val variables: Map<String, String> = emptyMap()
)
