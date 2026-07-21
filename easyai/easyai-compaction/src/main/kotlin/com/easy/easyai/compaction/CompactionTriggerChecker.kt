package com.easy.easyai.compaction

import com.easy.easyai.compaction.estimator.TokenEstimator
import com.easy.easyai.core.agent.CompactionTriggerType
import com.easy.easyai.core.model.EasyAiMessage

/**
 * Checks whether compaction should be triggered based on message token estimates.
 *
 * @param config The compaction configuration to use for threshold checks
 * @param tokenEstimator Token estimator for estimating token count
 */
class CompactionTriggerChecker(
    private val config: CompactionConfig,
    private val tokenEstimator: TokenEstimator
) {

    /**
     * Check if compaction should be triggered.
     *
     * @param messages Current message list
     * @param modelContextLength Total context window size of the current model
     * @param triggerType Type of trigger being checked
     * @return true if compaction should proceed
     */
    fun shouldCompact(
        messages: List<EasyAiMessage>,
        modelContextLength: Int,
        triggerType: CompactionTriggerType = CompactionTriggerType.Auto
    ): Boolean {
        // Quick pre-check: skip expensive estimation if message count is too low
        if (messages.size < config.minMessagesForCompaction) return false

        return when (triggerType) {
            CompactionTriggerType.Manual -> true
            CompactionTriggerType.Auto -> {
                val estimatedTokens = tokenEstimator.estimateContextTokens(messages)
                val usableTokens = modelContextLength - config.reservedTokens
                val threshold = usableTokens * config.threshold
                estimatedTokens > threshold
            }
            is CompactionTriggerType.Overflow -> true
        }
    }

    /**
     * Calculate the usable token limit after subtracting the reserved buffer.
     */
    fun usableTokens(modelContextLength: Int): Int = modelContextLength - config.reservedTokens

    /**
     * Calculate the number of tokens to preserve for recent turns.
     * Dynamically computes 25% of usable tokens, clamped to [2000, 16000].
     */
    fun calculatePreserveRecentTokens(modelContextLength: Int): Int {
        val usable = usableTokens(modelContextLength)
        return (usable * config.preserveRecentTokensRatio).toInt().coerceIn(2_000..16_000)
    }
}