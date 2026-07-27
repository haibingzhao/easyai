package com.easy.easyai.compaction

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.Usage
import com.easy.easyai.core.model.UserMessage

/**
 * Listener interface for compaction events.
 * Implementations handle side effects like persisting summary messages
 * and cleaning up compacted messages from storage.
 */
interface CompactionListener {
    /**
     * Called after compaction completes successfully.
     *
     * @param agentContext The current agent context (sessionId, agentId, modelId, mode) for persistence.
     * @param compactedMessageIds IDs of messages that were compacted (removed from context)
     * @param summaryMessage The summary UserMessage that replaces the compacted messages
     * @param lastCompactedCreatedAt The createdAt timestamp of the last compacted message.
     *   Used as the summaryMessage's createdAt for correct ordering when loading messages for LLM.
     *   The summary is positioned at this timestamp so it appears between prefix and recent messages.
     * @param tokensSaved Estimated number of tokens saved by compaction.
     * @param currentTokens Estimated token count of the context after compaction.
     * @param compactionRound The compaction round number (1 = first, 2+ = subsequent).
     * @param strategyName Name of the compaction strategy used ("agent").
     * @param durationMs Total wall-clock time of the compaction in milliseconds.
     * @param compactionUsage Full usage information from the compaction operation.
     */
    suspend fun onMessagesCompacted(
        agentContext: AgentContext,
        compactedMessageIds: List<String>,
        summaryMessage: UserMessage,
        lastCompactedCreatedAt: Long,
        tokensSaved: Int = 0,
        currentTokens: Int = 0,
        compactionRound: Int = 1,
        strategyName: String = "agent",
        durationMs: Long = 0,
        compactionUsage: Usage = Usage(),
        sessionVariables: Map<String, String> = emptyMap()
    )
}