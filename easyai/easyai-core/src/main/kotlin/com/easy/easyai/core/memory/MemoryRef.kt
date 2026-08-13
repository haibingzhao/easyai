package com.easy.easyai.core.memory

/**
 * Reference to a single memory entry, used to track which memories were
 * accessed by the LLM via memory_read / memory_search tools.
 */
data class MemoryRef(
    val name: String,
    val description: String,
    val type: MemoryType,
    val scope: MemoryScope
)
