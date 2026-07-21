package com.easy.easyai.compaction.estimator

import com.easy.easyai.core.model.*
import org.slf4j.LoggerFactory

/**
 * Interface for estimating token counts from message lists.
 * Implementations should balance accuracy vs performance.
 */
interface TokenEstimator {
    /**
     * Estimate total token count for the given messages using character-based estimation.
     * Used for sizing individual messages or subsets (e.g., recent message trimming).
     */
    fun estimate(messages: List<EasyAiMessage>): Int

    /**
     * Estimate the current context window token count.
     * Uses the last AssistantMessage's usage data (inputTokens + cacheReadTokens + cacheWriteTokens + outputTokens)
     * for accuracy. inputTokens only counts non-cached tokens, so cache tokens must be added separately
     * to reflect the true context window occupancy.
     * Falls back to [estimate] when no usage data is available.
     */
    fun estimateContextTokens(messages: List<EasyAiMessage>): Int = estimate(messages)
}

/**
 * Simple character-based token estimator.
 * Assumes ~3.5 characters per token on average (English text).
 * This is fast but imprecise; use for quick estimates only.
 */
class CharBasedTokenEstimator(
    private val charsPerToken: Double = 3.5
) : TokenEstimator {

    override fun estimate(messages: List<EasyAiMessage>): Int {
        val totalChars = countChars(messages)
        return (totalChars / charsPerToken).toInt()
    }

    private fun countChars(messages: List<EasyAiMessage>): Int = messages.sumOf { msg ->
        msg.content.sumOf { block ->
            when (block) {
                is TextContent -> block.text.length
                is ThinkingContent -> block.thinking.length
                else -> 0
            }
        }
    }
}

/**
 * Token estimator that learns from actual LLM usage data.
 *
 * Uses a hybrid strategy:
 * - [estimate]: pure character-based estimation for all messages (used for sizing subsets).
 * - [estimateContextTokens]: uses the last AssistantMessage's actual usage data for accurate
 *   context window size estimation (used for compaction trigger decisions).
 * - [updateRatio]: calibrates the charsPerToken ratio from the full message list's character count
 *   vs the last AssistantMessage's inputTokens (which represents the full context).
 */
class UsageAwareTokenEstimator(
    private val defaultCharsPerToken: Double = 3.5
) : TokenEstimator {

    private val logger = LoggerFactory.getLogger(UsageAwareTokenEstimator::class.java)

    @Volatile
    private var charsPerToken = defaultCharsPerToken

    /**
     * Update the token-to-character ratio based on the full message list and usage data.
     *
     * Calibrates charsPerToken by comparing:
     * - Numerator: total character count of ALL messages in the context (all types)
     * - Denominator: the last AssistantMessage's inputTokens (which represents the full context token count)
     *
     * This ensures numerator and denominator have matching scope (both represent the full context).
     *
     * @param messages The complete message list (all types: User, Assistant, ToolResult, etc.)
     */
    fun updateRatio(messages: List<EasyAiMessage>) {
        val lastAssistantWithUsage = messages.filterIsInstance<AssistantMessage>()
            .lastOrNull {
                val u = it.usage
                u.inputTokens > 0 || u.cacheReadTokens > 0 || u.cacheWriteTokens > 0
            }
        if (lastAssistantWithUsage == null) {
            logger.debug("No messages with usage data available for ratio update")
            return
        }

        // Numerator: total chars of ALL messages in the context (matches full input scope)
        val totalChars = messages.sumOf { msg ->
            countContentChars(msg.content)
        }

        // Denominator: total input tokens including cache (inputTokens only counts non-cached tokens)
        val usage = lastAssistantWithUsage.usage
        val totalInputTokens = usage.inputTokens + usage.cacheReadTokens + usage.cacheWriteTokens

        if (totalChars > 0 && totalInputTokens > 0) {
            val newRatio = totalChars.toDouble() / totalInputTokens
            logger.debug("Updated charsPerToken ratio: {} -> {} (totalChars={}, totalInputTokens={})",
                charsPerToken, newRatio, totalChars, totalInputTokens)
            charsPerToken = newRatio
        }
    }

    /**
     * Estimate total token count for the given messages using pure character-based estimation.
     * All messages are estimated uniformly via chars / charsPerToken.
     */
    override fun estimate(messages: List<EasyAiMessage>): Int {
        val totalChars = messages.sumOf { msg -> countContentChars(msg.content) }
        return (totalChars / charsPerToken).toInt()
    }

    /**
     * Estimate the current context window token count.
     * Uses the last AssistantMessage's full usage data:
     *   inputTokens + cacheReadTokens + cacheWriteTokens + outputTokens
     * inputTokens only counts non-cached tokens, so cache tokens must be added separately
     * to reflect the true context window occupancy.
     * Falls back to character-based [estimate] when no usage data is available.
     */
    override fun estimateContextTokens(messages: List<EasyAiMessage>): Int {
        val lastAssistantWithUsage = messages.filterIsInstance<AssistantMessage>()
            .lastOrNull {
                val u = it.usage
                u.inputTokens > 0 || u.outputTokens > 0 || u.cacheReadTokens > 0 || u.cacheWriteTokens > 0
            }
        if (lastAssistantWithUsage != null) {
            val usage = lastAssistantWithUsage.usage
            return usage.inputTokens + usage.cacheReadTokens + usage.cacheWriteTokens + usage.outputTokens
        }
        return estimate(messages)
    }

    /**
     * Reset to default ratio (useful for testing or when switching models).
     */
    fun reset() {
        logger.debug("Resetting charsPerToken ratio to default: {}", defaultCharsPerToken)
        charsPerToken = defaultCharsPerToken
    }

    companion object {
        /**
         * Count characters in content blocks (text + thinking).
         */
        private fun countContentChars(content: List<ContentBlock>): Int = content.sumOf { block ->
            when (block) {
                is TextContent -> block.text.length
                is ThinkingContent -> block.thinking.length
                else -> 0
            }
        }
    }
}
