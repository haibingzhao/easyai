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
     * Uses the last AssistantMessage's actual input token count as a precise base,
     * then adds a character-based estimate of messages added after that call.
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
 * - [estimateContextTokens]: uses the last AssistantMessage's actual input token count as base,
 *   plus a character-based estimate of messages added after that call (used for compaction triggers).
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
     * Strategy: find the most recent AssistantMessage with a plausible usage report,
     * use its exact token counts as the base (inputTokens covers system prompt + all
     * prior messages; outputTokens covers the assistant's own response), then add a
     * character-based estimate of messages added AFTER that call (tool results, new
     * user messages). This gives high accuracy with minimal estimation error.
     *
     * Formula:
     *   base = inputTokens + cacheReadTokens + cacheWriteTokens + outputTokens  (all exact)
     *   delta = estimate(messages strictly after that assistant message)         (small, char-based)
     *   total = base + delta
     *
     * ## Gateway under-reporting defense
     *
     * Some LLM proxy gateways occasionally return usage reports that dramatically
     * undercount the real prompt size. Defense:
     * 1. Plausibility floor: reported tokens must >= (promptChars / MAX_CHARS_PER_TOKEN).
     * 2. Walk-back: try older assistant messages if the newest fails the floor.
     * 3. Final fallback: character-based [estimate] when no plausible usage exists.
     */
    override fun estimateContextTokens(messages: List<EasyAiMessage>): Int {
        val minPlausibleTokens = (countPromptChars(messages) / MAX_CHARS_PER_TOKEN).toInt()

        // Walk newest → oldest, find the first plausible usage report
        val assistantMessages = messages.filterIsInstance<AssistantMessage>()
        for (i in assistantMessages.indices.reversed()) {
            val msg = assistantMessages[i]
            val u = msg.usage
            val totalInput = u.inputTokens + u.cacheReadTokens + u.cacheWriteTokens
            if (totalInput <= 0 || totalInput < minPlausibleTokens) continue

            // Found plausible usage.
            // Base = exact tokens: input (system prompt + prior messages) + output (assistant response)
            val base = totalInput + u.outputTokens

            // Delta = estimate of messages added strictly AFTER this assistant response
            // (tool results, new user messages, etc.)
            val assistantIndex = messages.indexOfLast { it.id == msg.id }
            val delta = if (assistantIndex >= 0 && assistantIndex < messages.size - 1) {
                estimate(messages.subList(assistantIndex + 1, messages.size))
            } else {
                0
            }
            return base + delta
        }

        // No plausible usage report found: fall back to character-based estimation.
        val fallback = estimate(messages)
        if (minPlausibleTokens > 0) {
            logger.warn(
                "All LLM usage reports implausibly low (floor={} derived from prompt chars), " +
                    "gateway likely under-reported usage; falling back to char-based estimate: {}",
                minPlausibleTokens, fallback
            )
        }
        return fallback
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
