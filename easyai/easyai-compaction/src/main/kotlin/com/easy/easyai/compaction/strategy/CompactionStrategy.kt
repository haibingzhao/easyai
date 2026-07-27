package com.easy.easyai.compaction.strategy

import com.easy.easyai.compaction.estimator.TokenEstimator
import com.easy.easyai.compaction.model.CompactionContext
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.Usage
import com.easy.easyai.core.model.UserMessage
import org.springframework.ai.chat.model.ChatModel

/**
 * Strategy interface for generating compaction summaries.
 * Implementations use Agent-based LLM calls for high-quality summarization and variable extraction.
 */
interface CompactionStrategy {
    /**
     * Generate a summary of the compacted messages.
     *
     * @param messages Messages that will be compacted (not including prefix/recent)
     * @param context Compaction context including range and previous summary
     * @param chatModel Optional session-specific ChatModel for LLM-based strategies.
     *   When provided, takes precedence over any constructor-injected ChatModel.
     * @return Summary text that will be wrapped in a UserMessage and sent to LLM
     */
    suspend fun compact(
        messages: List<EasyAiMessage>,
        context: CompactionContext,
        chatModel: ChatModel? = null
    ): String

    /**
     * Generate a summary with token usage information and extracted variables.
     * Default implementation calls [compact] and estimates tokens using [tokenEstimator].
     * Agent-based strategies should override to return actual usage and variables.
     *
     * @param messages Messages that will be compacted
     * @param context Compaction context
     * @param chatModel Optional session-specific ChatModel
     * @param tokenEstimator Optional token estimator for fallback token estimation
     * @return [StrategyOutput] containing summary text, usage info, and extracted variables
     */
    suspend fun compactWithUsage(
        messages: List<EasyAiMessage>,
        context: CompactionContext,
        chatModel: ChatModel? = null,
        tokenEstimator: TokenEstimator? = null
    ): StrategyOutput {
        val summary = compact(messages, context, chatModel)
        val estimatedTokens = tokenEstimator?.estimate(
            listOf(UserMessage(content = listOf(TextContent(summary))))
        ) ?: 0
        return StrategyOutput(summary, Usage(outputTokens = estimatedTokens))
    }
}