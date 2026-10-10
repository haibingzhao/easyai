package com.easy.easyai.autoconfigure.anthropic

import com.easy.easyai.api.llm.ChatOptions
import com.easy.easyai.api.llm.ToolCallback

/**
 * Anthropic-protocol [ChatOptions]: the common fields plus Anthropic-specific knobs
 * (`thinkingBudget` for extended thinking, `effort` mapped to the SDK `OutputConfig.Effort`).
 * Produced by `AnthropicChatModelFactory.build` per turn and consumed by [AnthropicStreamingChatModel].
 */
data class AnthropicChatOptions(
    override val model: String? = null,
    override val temperature: Double? = null,
    override val maxTokens: Int? = null,
    val thinkingBudget: Long? = null,
    val effort: String? = null,
    val stopSequences: List<String> = emptyList(),
    override val toolCallbacks: List<ToolCallback> = emptyList(),
    override val outputSchema: String? = null,
    val timeoutSeconds: Long? = null
) : ChatOptions
