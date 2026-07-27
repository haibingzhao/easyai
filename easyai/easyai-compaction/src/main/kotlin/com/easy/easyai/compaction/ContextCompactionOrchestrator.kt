package com.easy.easyai.compaction

import com.easy.easyai.compaction.estimator.TokenEstimator
import com.easy.easyai.compaction.estimator.UsageAwareTokenEstimator
import com.easy.easyai.compaction.model.CompactedRange
import com.easy.easyai.compaction.model.CompactionContext
import com.easy.easyai.compaction.strategy.CompactionStrategy
import com.easy.easyai.compaction.strategy.StrategyOutput
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.CompactionTriggerType
import com.easy.easyai.core.event.AgentEvent
import com.easy.easyai.core.event.CompactionEndEvent
import com.easy.easyai.core.event.CompactionStartEvent
import com.easy.easyai.core.model.*
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ChatModel

/**
 * Orchestrates context compaction:
 * 1. Selects message ranges (prefix, compacted, recent)
 * 2. Extracts the last previousSummary for incremental update (Round 2+ only)
 * 3. Invokes compaction strategy with conversation messages + previousSummary context
 * 4. Builds replacement messages (summary + recent)
 * 5. Notifies listeners for persistence cleanup
 * 6. Pushes compaction events to the stream
 *
 * Data preparation follows OpenCode/KiloCode incremental update pattern:
 * - Round 1: summary strategy (fast, free), no previousSummary
 * - Round 2+: LLM strategy with only the last summary, no original message loading
 * - Data size is bounded: 1 summary + new messages per round
 *
 * @param config Compaction configuration
 * @param strategy Compaction strategy to use for summary generation
 * @param tokenEstimator Token estimator for token counting
 * @param compactionListener Optional listener for persistence cleanup
 * @param originalMessageLoader Reserved for future use; not called in current implementation
 */
class ContextCompactionOrchestrator(
    private val config: CompactionConfig,
    private val strategy: CompactionStrategy,
    private val tokenEstimator: TokenEstimator,
    private val compactionListener: CompactionListener? = null,
    private val originalMessageLoader: OriginalMessageLoader? = null
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val triggerChecker = CompactionTriggerChecker(config, tokenEstimator)

    /**
     * Execute compaction on the given message list.
     *
     * @param messages Current transcript messages
     * @param turnId Current turn ID
     * @param modelContextLength Model's context window size in tokens
     * @param triggerType Type of compaction trigger
     * @param eventScope Optional producer scope for pushing events
     * @param messageTimestamps Map of messageId to createdAt timestamp (epoch millis).
     *   Used to determine the summary message's createdAt for correct ordering.
     *   If empty, falls back to current time.
     * @param chatModel Optional session-specific ChatModel for LLM-based strategies.
     * @return Compacted message list (summary + recent messages), or original messages if compaction failed
     */
    suspend fun compact(
        agentContext: AgentContext,
        messages: List<EasyAiMessage>,
        turnId: Int,
        modelContextLength: Int,
        triggerType: CompactionTriggerType = CompactionTriggerType.Auto,
        eventScope: EventPusher? = null,
        messageTimestamps: Map<String, Long> = emptyMap(),
        chatModel: ChatModel? = null
    ): List<EasyAiMessage> {
        if (!config.enabled) {
            logger.debug("Compaction disabled by config")
            return messages
        }

        // Update token estimator ratio using full message list for accurate calibration
        if (tokenEstimator is UsageAwareTokenEstimator) {
            tokenEstimator.updateRatio(messages)
        }

        // Send compaction start event
        val reason = when (triggerType) {
            CompactionTriggerType.Auto -> "auto"
            CompactionTriggerType.Manual -> "manual"
            is CompactionTriggerType.Overflow -> "overflow"
        }
        eventScope?.push(CompactionStartEvent(turnId, reason, messages.size, agentContext.sessionId ?: "default"))

        val compactionStartTime = System.currentTimeMillis()

        logger.info(
            "[Turn {}] Starting context compaction (reason={}, messages={})",
            turnId, reason, messages.size
        )

        return try {
            val result = executeCompaction(agentContext, messages, turnId, modelContextLength, messageTimestamps, chatModel)

            // Send compaction end event using values computed inside executeCompaction
            eventScope?.push(
                CompactionEndEvent(
                    turnId = turnId,
                    summary = result.summary,
                    compactedCount = result.compactedRange.messageCount,
                    tokensSaved = result.tokensSaved,
                    sessionId = agentContext.sessionId ?: "default",
                    tailStartMessageId = result.tailStartMessageId,
                    currentTokens = result.currentTokens,
                    durationMs = result.durationMs,
                    usage = result.usage
                )
            )

            logger.info(
                "[Turn {}] Compaction complete: {} messages compacted, ~{} tokens saved",
                turnId, result.compactedRange.messageCount, result.tokensSaved
            )

            result.messages
        } catch (e: Exception) {
            logger.error("[Turn {}] Compaction failed, keeping original messages", turnId, e)
            messages
        }
    }

    /**
     * Functional interface for pushing events from the orchestrator.
     * Decouples from the ProducerScope generic type.
     */
    fun interface EventPusher {
        suspend fun push(event: AgentEvent)
    }

    private data class CompactionResult(
        val messages: List<EasyAiMessage>,
        val summary: String,
        val compactedRange: CompactedRange,
        val tailStartMessageId: String?,
        val estimatedTokensBefore: Int,
        val currentTokens: Int,
        val tokensSaved: Int,
        val durationMs: Long,
        val usage: Usage = Usage()
    )

    private suspend fun executeCompaction(
        agentContext: AgentContext,
        messages: List<EasyAiMessage>,
        turnId: Int,
        modelContextLength: Int,
        messageTimestamps: Map<String, Long>,
        chatModel: ChatModel? = null
    ): CompactionResult {
        val executionStartTime = System.currentTimeMillis()

        // Step 1: Select message ranges
        val selection = selectMessages(messages, modelContextLength)
        val (prefixMessages, compactedMessages, recentMessages) = selection

        if (compactedMessages.isEmpty()) {
            logger.warn("No messages to compact, returning original")
            val contextTokens = tokenEstimator.estimateContextTokens(messages)
            val durationMs = System.currentTimeMillis() - executionStartTime
            return CompactionResult(
                messages = messages,
                summary = "",
                compactedRange = CompactedRange(emptyList(), 0),
                tailStartMessageId = null,
                estimatedTokensBefore = contextTokens,
                currentTokens = contextTokens,
                tokensSaved = 0,
                durationMs = durationMs
            )
        }

        // Step 2: Derive compaction round from summary message count in compacted range
        val previousSummaryCount = compactedMessages.count { msg ->
            (msg as? UserMessage)?.metadata?.get("isCompactionSummary") == "true"
        }
        val compactionRound = previousSummaryCount + 1

        logger.info(
            "Round {}: agent compaction mode, {} messages to compact (includes {} previous summaries)",
            compactionRound, compactedMessages.size, previousSummaryCount
        )

        // Step 5: Build compaction context
        val compactedRange = CompactedRange(
            messageIds = compactedMessages.map { it.id },
            estimatedTokensBefore = tokenEstimator.estimateContextTokens(messages),
            userRoleCount = compactedMessages.count { it.role == Role.USER },
            assistantRoleCount = compactedMessages.count { it.role == Role.ASSISTANT }
        )

        val context = CompactionContext(
            range = compactedRange,
            previousSummary = null, // Agent sees previous summary directly in transcript
            currentTurnId = turnId,
            compactionRound = compactionRound,
            modelConfig = agentContext.modelConfig
        )

        // Step 4: Generate summary via agent-based strategy (with usage + variable tracking)
        // All compactedMessages are passed directly as the agent's transcript
        val strategyOutput: StrategyOutput = strategy.compactWithUsage(
            compactedMessages, context, chatModel, tokenEstimator
        )
        val summary = strategyOutput.summary

        // Update in-memory session variables so the current request's system prompt
        // reflects the latest variables extracted during compaction
        if (strategyOutput.variables.isNotEmpty()) {
            agentContext.sessionVariables.loadAll(strategyOutput.variables)
        }

        val strategyName = "agent"

        // Step 7: Build replacement messages
        val summaryMessage = UserMessage(
            content = listOf(TextContent(summary)),
            metadata = mapOf("isCompactionSummary" to "true")
        )

        val resultMessages = prefixMessages + summaryMessage + recentMessages

        // Calculate token metrics for result and listener.
        // Use char-based estimate() for tokensSaved: it measures the actual reduction
        // (compacted messages → summary), unlike estimateContextTokens() which returns
        // the last AssistantMessage's usage and is identical before/after compaction
        // because the tail (containing the last AssistantMessage) is preserved.
        val compactedTokens = tokenEstimator.estimate(compactedMessages)
        val summaryTokens = tokenEstimator.estimate(listOf(summaryMessage))
        val tokensSaved = (compactedTokens - summaryTokens).coerceAtLeast(0)

        // Derive currentTokens from estimatedTokensBefore - tokensSaved.
        // Cannot use estimateContextTokens(resultMessages) here because it reads the last
        // AssistantMessage's usage, which is preserved in the tail and thus unchanged.
        val currentTokens = (compactedRange.estimatedTokensBefore - tokensSaved).coerceAtLeast(0)
        val durationMs = System.currentTimeMillis() - executionStartTime

        // Step 8: Notify listener for persistence cleanup
        if (compactedMessages.isNotEmpty()) {
            // Determine the createdAt timestamp for the summary message.
            // Use the last compacted message's createdAt so the summary appears
            // between prefix and recent messages when loaded for LLM (ORDER BY created_at ASC).
            val lastCompactedCreatedAt = compactedMessages
                .mapNotNull { messageTimestamps[it.id] }
                .maxOrNull()
                ?: System.currentTimeMillis()

            compactionListener?.let { listener ->
                listener.onMessagesCompacted(
                    agentContext = agentContext,
                    compactedMessageIds = compactedMessages.map { it.id },
                    summaryMessage = summaryMessage,
                    lastCompactedCreatedAt = lastCompactedCreatedAt,
                    tokensSaved = tokensSaved,
                    currentTokens = currentTokens,
                    compactionRound = compactionRound,
                    strategyName = strategyName,
                    durationMs = durationMs,
                    compactionUsage = strategyOutput.usage,
                    sessionVariables = strategyOutput.variables
                )
            }
        }

        return CompactionResult(
            messages = resultMessages,
            summary = summary,
            compactedRange = compactedRange,
            tailStartMessageId = recentMessages.firstOrNull()?.id,
            estimatedTokensBefore = compactedRange.estimatedTokensBefore,
            currentTokens = currentTokens,
            tokensSaved = tokensSaved,
            durationMs = durationMs,
            usage = strategyOutput.usage
        )
    }

    /**
     * Select messages for compaction:
     * - Preserve system messages at the beginning (prefix)
     * - Select middle messages for compaction
     * - Preserve recent turns based on tailTurns config
     */
    private fun selectMessages(
        messages: List<EasyAiMessage>,
        modelContextLength: Int
    ): Triple<List<EasyAiMessage>, List<EasyAiMessage>, List<EasyAiMessage>> {
        val preserveRecentTokens = triggerChecker.calculatePreserveRecentTokens(modelContextLength)

        // Find first user message (conversation start)
        var firstUserIndex = messages.indexOfFirst { it is UserMessage }
        if (firstUserIndex < 0) firstUserIndex = 0

        // Prefix: everything up to and including the first user message
        val prefixMessages = messages.take(firstUserIndex + 1)

        // Remaining messages after first user
        val remainingMessages = messages.drop(firstUserIndex + 1)

        if (remainingMessages.isEmpty()) {
            return Triple(prefixMessages, emptyList(), emptyList())
        }

        // Select recent messages based on tail turns and token limit
        val (recentMessages, compactedMessages) = selectRecentMessages(
            remainingMessages,
            tailTurns = config.tailTurns,
            maxRecentTokens = preserveRecentTokens
        )

        return Triple(prefixMessages, compactedMessages, recentMessages)
    }

    private fun selectRecentMessages(
        messages: List<EasyAiMessage>,
        tailTurns: Int,
        maxRecentTokens: Int
    ): Pair<List<EasyAiMessage>, List<EasyAiMessage>> {
        // Count turns from the end (a turn = user message + assistant response)
        var turnsCollected = 0
        var recentIndex = messages.size - 1

        while (recentIndex >= 0 && turnsCollected < tailTurns) {
            if (messages[recentIndex] is UserMessage) {
                turnsCollected++
            }
            recentIndex--
        }

        // recentIndex is now at the last message before the tail turns
        val splitIndex = recentIndex + 1
        var recentMessages = messages.drop(splitIndex)

        // Check if recent messages exceed token limit, trim if necessary.
        // Dropped messages must be included in compactedMessages so nothing is lost.
        var recentTokens = tokenEstimator.estimate(recentMessages)
        while (recentTokens > maxRecentTokens && recentMessages.size > 1) {
            recentMessages = recentMessages.drop(1)
            recentTokens = tokenEstimator.estimate(recentMessages)
        }

        // All messages not in recentMessages are compacted (includes token-trimmed ones)
        val compactedMessages = messages.take(messages.size - recentMessages.size)

        return Pair(recentMessages, compactedMessages)
    }
}