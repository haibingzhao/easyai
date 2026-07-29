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
     * Compares the latest two AssistantMessage usage reports for monotonicity:
     * if latest >= previous, trusts latest; otherwise falls back to previous + delta.
     * Falls back to [estimate] when no usage data is available.
     */
    fun estimateContextTokens(messages: List<EasyAiMessage>): Int = estimate(messages)
}

/**
 * Token estimator that learns from actual LLM usage data.
 *
 * Uses a hybrid strategy:
 * - [estimate]: pure character-based estimation for all messages (used for sizing subsets).
 * - [estimateContextTokens]: compares the latest two AssistantMessage usage reports for
 *   monotonicity (context only grows without compaction). Trusts latest if >= previous;
 *   otherwise uses previous + char-based delta of intervening messages.
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

        // Defense: skip calibration when the usage report is implausibly low
        // (same gateway under-reporting anomaly guarded in [estimateContextTokens]).
        // Calibrating from an undercounted report would inflate charsPerToken and make
        // all subsequent char-based estimates systematically too low.
        val minPlausibleTokens = (countPromptChars(messages) / MAX_CHARS_PER_TOKEN).toInt()
        if (totalInputTokens < minPlausibleTokens) {
            logger.warn(
                "Skipping ratio calibration: usage report implausibly low " +
                    "(totalInputTokens={} < floor={} derived from {} prompt chars), " +
                    "gateway likely under-reported usage",
                totalInputTokens, minPlausibleTokens, countPromptChars(messages)
            )
            return
        }

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
     *
     * Strategy: compare the latest two AssistantMessage usage reports. Without compaction,
     * context only grows, so inputTokens must be monotonically non-decreasing.
     *
     * - latest >= previous → trust latest (normal growth)
     * - latest < previous → anomaly (gateway under-reported), use previous + estimate(between)
     * - only one usage report → trust it directly
     * - no usage → fall back to char-based [estimate]
     *
     * Formula (normal case):
     *   base = inputTokens + cacheReadTokens + cacheWriteTokens + outputTokens  (all exact)
     *   delta = estimate(messages strictly after that assistant message)         (small, char-based)
     *   total = base + delta
     */
    override fun estimateContextTokens(messages: List<EasyAiMessage>): Int {
        val withUsage = messages.filterIsInstance<AssistantMessage>()
            .filter { it.usage.inputTokens + it.usage.cacheReadTokens + it.usage.cacheWriteTokens > 0 }

        if (withUsage.isEmpty()) return estimate(messages)

        val latest = withUsage.last()
        val latestInput = latest.usage.inputTokens + latest.usage.cacheReadTokens + latest.usage.cacheWriteTokens

        // First call: trust directly
        if (withUsage.size == 1) {
            return latestInput + latest.usage.outputTokens + deltaAfter(latest, messages)
        }

        val previous = withUsage[withUsage.size - 2]
        val previousInput = previous.usage.inputTokens + previous.usage.cacheReadTokens + previous.usage.cacheWriteTokens

        return if (latestInput >= previousInput) {
            // Normal: context grows monotonically, trust latest
            latestInput + latest.usage.outputTokens + deltaAfter(latest, messages)
        } else {
            // Anomaly: gateway under-reported, use previous + estimate(messages between)
            logger.warn(
                "Usage anomaly: latest totalInput={} < previous totalInput={}, using previous as base",
                latestInput, previousInput
            )
            previousInput + previous.usage.outputTokens + deltaAfter(previous, messages)
        }
    }

    private fun deltaAfter(assistant: AssistantMessage, messages: List<EasyAiMessage>): Int {
        val idx = messages.indexOfLast { it.id == assistant.id }
        return if (idx >= 0 && idx < messages.size - 1) {
            estimate(messages.subList(idx + 1, messages.size))
        } else 0
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
         * Maximum plausible characters per token, used as the divisor for the
         * usage-report plausibility floor. Even the most token-efficient content
         * (code, JSON, base64) rarely exceeds 8 chars/token, so a reported token
         * total below (promptChars / 8) must be a gateway undercount.
         */
        private const val MAX_CHARS_PER_TOKEN = 8.0

        /**
         * Count characters of content that is actually sent to the LLM as INPUT:
         * text + tool call arguments + tool result output.
         * Excludes [ThinkingContent] because thinking blocks are not sent back to
         * the LLM as input (see DefaultMessageConverter.toSpringAiMessages).
         * Used to derive the plausibility floor for usage reports.
         */
        private fun countPromptChars(messages: List<EasyAiMessage>): Int = messages.sumOf { msg ->
            msg.content.sumOf { block ->
                when (block) {
                    is TextContent -> block.text.length
                    is ToolCallContent -> block.arguments.length
                    is ToolResultContent -> block.output.length
                    else -> 0
                }
            }
        }

        /**
         * Count characters in content blocks (text + thinking + tool content).
         * Tool call arguments and tool result output are included because they are
         * part of the prompt sent to the LLM.
         */
        private fun countContentChars(content: List<ContentBlock>): Int = content.sumOf { block ->
            when (block) {
                is TextContent -> block.text.length
                is ThinkingContent -> block.thinking.length
                is ToolCallContent -> block.arguments.length
                is ToolResultContent -> block.output.length
                else -> 0
            }
        }
    }
}
