package com.easy.easyai.core.agent

/**
 * Trigger type for context compaction.
 */
sealed interface CompactionTriggerType {
    /**
     * Automatic trigger based on token threshold.
     */
    data object Auto : CompactionTriggerType

    /**
     * Manual trigger initiated by user.
     */
    data object Manual : CompactionTriggerType

    /**
     * Overflow trigger when LLM returns context overflow error.
     * @param modelName The model that reported the overflow
     */
    data class Overflow(val modelName: String) : CompactionTriggerType
}