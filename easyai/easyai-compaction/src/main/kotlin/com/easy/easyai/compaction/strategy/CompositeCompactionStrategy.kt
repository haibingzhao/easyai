package com.easy.easyai.compaction.strategy

import com.easy.easyai.compaction.estimator.TokenEstimator
import com.easy.easyai.compaction.model.CompactionContext
import com.easy.easyai.core.model.EasyAiMessage
import org.springframework.ai.chat.model.ChatModel

/**
 * Composite compaction strategy that selects between two strategies based on compaction round.
 *
 * - Round 1 (no previousSummary): uses [firstRoundStrategy] (fast, free, e.g. SummaryStrategy)
 * - Round 2+ (has previousSummary): uses [subsequentStrategy] (high quality, e.g. LlmSummaryStrategy)
 *
 * @param firstRoundStrategy Strategy for the first compaction round
 * @param subsequentStrategy Strategy for subsequent compaction rounds
 */
class CompositeCompactionStrategy(
    private val firstRoundStrategy: CompactionStrategy,
    private val subsequentStrategy: CompactionStrategy
) : CompactionStrategy {

    override fun compact(messages: List<EasyAiMessage>, context: CompactionContext, chatModel: ChatModel?): String {
        return selectStrategy(context).compact(messages, context, chatModel)
    }

    override fun compactWithUsage(
        messages: List<EasyAiMessage>,
        context: CompactionContext,
        chatModel: ChatModel?,
        tokenEstimator: TokenEstimator?
    ): StrategyOutput {
        return selectStrategy(context).compactWithUsage(messages, context, chatModel, tokenEstimator)
    }

    private fun selectStrategy(context: CompactionContext): CompactionStrategy {
        // No previousSummary → Round 1 → fast/cheap strategy
        // Has previousSummary → Round 2+ → high quality strategy
        return if (context.previousSummary == null) firstRoundStrategy else subsequentStrategy
    }
}
