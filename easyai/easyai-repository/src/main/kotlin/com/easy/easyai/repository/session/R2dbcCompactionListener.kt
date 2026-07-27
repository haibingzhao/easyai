package com.easy.easyai.repository.session

import com.easy.easyai.compaction.CompactionListener
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.CustomContent
import com.easy.easyai.core.model.Usage
import com.easy.easyai.core.model.UserMessage
import com.easy.easyai.core.model.generateMessageId
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import com.easy.easyai.common.util.SharedObjectMapper

/**
 * R2DBC-based implementation of CompactionListener.
 * Handles persistence when context compaction occurs:
 * 1. Saves the compaction summary message with createdAt = lastCompactedCreatedAt
 *    (so it sorts correctly between prefix and recent messages for LLM context)
 * 2. Marks old messages as compacted (soft delete via compactedAt timestamp)
 *
 * Enriches the summary message's metadata with compaction metadata:
 * - compactionStrategy: strategy name ("summary" | "llm")
 * - compactedMessageIds: JSON array of compacted message IDs
 * - compactionRound: round number as string
 */
class R2dbcCompactionListener(
    private val store: R2dbcAsyncSessionStore,
    private val objectMapper: ObjectMapper = SharedObjectMapper.instance
) : CompactionListener {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun onMessagesCompacted(
        agentContext: AgentContext,
        compactedMessageIds: List<String>,
        summaryMessage: UserMessage,
        lastCompactedCreatedAt: Long,
        tokensSaved: Int,
        currentTokens: Int,
        compactionRound: Int,
        strategyName: String,
        durationMs: Long,
        compactionUsage: Usage,
        sessionVariables: Map<String, String>
    ) {
        val sessionId = agentContext.sessionId ?: error("agentContext.sessionId must not be null for compaction")

        if (compactedMessageIds.isEmpty()) {
            logger.warn("Compaction called with empty message list for session {}", sessionId)
            return
        }

        val compactedAt = System.currentTimeMillis()

        // Enrich summary message metadata with compaction metadata
        val enrichedMetadata = mutableMapOf(
            "tokensSaved" to tokensSaved.toString(),
            "durationMs" to durationMs.toString(),
            "currentTokens" to currentTokens.toString(),
            "compactionStrategy" to strategyName,
            "compactedMessageIds" to objectMapper.writeValueAsString(compactedMessageIds),
            "compactionRound" to compactionRound.toString()
        )
        // Persist session variables in the summary message metadata (Phase 3: variable storage migration)
        if (sessionVariables.isNotEmpty()) {
            enrichedMetadata["sessionVariables"] = objectMapper.writeValueAsString(sessionVariables)
        }
        val enrichedSummary = summaryMessage.copy(
            metadata = summaryMessage.metadata + enrichedMetadata
        )

        // 1. Mark old messages as compacted first (soft delete)
        //    This ensures crash safety: if we crash before saving summary,
        //    loadActiveMessages() won't return duplicates (original messages are excluded).
        //    Worst case: summary is lost but original messages remain for re-compaction.
        store.markCompacted(sessionId, compactedMessageIds, compactedAt)

        // 2. Save summary message with createdAt = lastCompactedCreatedAt
        //    This ensures correct ordering: prefix < summary < recent (ORDER BY created_at ASC)
        store.saveCompactionSummary(agentContext, enrichedSummary, createdAt = lastCompactedCreatedAt + 1, usage = compactionUsage)

        // 3. Save compaction indicator message with CUSTOM role
        //    This is used for frontend display only - excluded from LLM context
        val compactionIndicator = UserMessage(
            id = generateMessageId(),
            content = listOf(
                CustomContent(
                    customType = "compaction",
                    metadata = mapOf(
                        "compactionStrategy" to strategyName,
                        "compactedCount" to compactedMessageIds.size,
                        "tokensSaved" to tokensSaved,
                        "currentTokens" to currentTokens,
                        "compactedAt" to compactedAt,
                        "summaryMessageId" to summaryMessage.id,
                        "durationMs" to durationMs,
                        "compactionOutputTokens" to compactionUsage.outputTokens
                    ),
                    durationMs = durationMs
                )
            ),
            metadata = mapOf("isCompactionIndicator" to "true")
        )
        store.saveCompactionIndicator(agentContext, compactionIndicator, createdAt = compactedAt)

        logger.info(
            "Compaction complete for session {}: summary saved at t={}, {} messages marked compacted at t={}, {} tokens saved",
            sessionId, lastCompactedCreatedAt, compactedMessageIds.size, compactedAt, tokensSaved
        )
    }
}