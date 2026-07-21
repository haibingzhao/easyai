package com.easy.easyai.compaction

import com.easy.easyai.core.model.EasyAiMessage

/**
 * Loads original messages that were previously compacted into summary messages.
 *
 * Used by the orchestrator in subsequent compaction rounds to provide
 * the full original conversation to the LLM strategy, avoiding double-lossy compression.
 */
interface OriginalMessageLoader {
    /**
     * Load original messages that were compacted by the given summary messages.
     *
     * @param summaryMessageIds IDs of summary messages whose compactedMessageIds metadata
     *   contains the IDs of the original messages to load.
     * @return The original messages (including those marked as compacted in DB).
     */
    suspend fun loadOriginalMessages(summaryMessageIds: List<String>): List<EasyAiMessage>
}
