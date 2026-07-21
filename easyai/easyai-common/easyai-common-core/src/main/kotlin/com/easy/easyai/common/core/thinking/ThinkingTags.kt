package com.easy.easyai.common.core.thinking

/**
 * Classification of thinking content patterns for processing.
 */
enum class ThinkingTagType {
    TAG,
    PREFIX,
    NO_PREFIX
}

/**
 * Centralized definitions for thinking content patterns across different LLM providers.
 */
object ThinkingTags {

    /**
     * Comprehensive mapping of thinking tag patterns.
     */
    val TAG_DEFINITIONS = mapOf(
        "think" to ("<think>" to "</think>"),
        "analysis" to ("<analysis>" to "</analysis>"),
        "thought" to ("<thought>" to "</thought>"),
        "final" to ("<final>" to "</final>"),
        "scratchpad" to ("<scratchpad>" to "</scratchpad>"),
        "chain_of_thought" to ("<chain_of_thought>" to "</chain_of_thought>"),
        "reasoning" to ("<reasoning>" to "</reasoning>"),
        "legacy_prefix" to ("//THINKING:" to ""),
        "no_prefix" to ("" to "(?=\\{)")
    )
}
