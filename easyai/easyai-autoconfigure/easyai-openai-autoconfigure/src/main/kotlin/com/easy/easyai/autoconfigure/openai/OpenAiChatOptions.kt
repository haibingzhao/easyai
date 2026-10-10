package com.easy.easyai.autoconfigure.openai

import com.easy.easyai.api.llm.ChatOptions
import com.easy.easyai.api.llm.ToolCallback

/** How structured output should be expressed on the OpenAI wire for this turn. */
enum class OpenAiResponseFormatKind { TEXT, JSON_OBJECT, JSON_SCHEMA }

/**
 * OpenAI-protocol [ChatOptions]: the common fields plus the OpenAI-specific knobs
 * (`maxCompletionTokens`, `reasoningEffort`, `responseFormatKind`, `strict`, `parallelToolCalls`).
 * Produced by [OpenAiChatModelFactory.build] per turn and consumed by [OpenAiStreamingChatModel].
 */
data class OpenAiChatOptions(
    override val model: String? = null,
    override val temperature: Double? = null,
    override val maxTokens: Int? = null,
    val maxCompletionTokens: Int? = null,
    val reasoningEffort: String? = null,
    val responseFormatKind: OpenAiResponseFormatKind? = null,
    val stop: List<String> = emptyList(),
    val parallelToolCalls: Boolean? = null,
    override val toolCallbacks: List<ToolCallback> = emptyList(),
    override val outputSchema: String? = null,
    val strict: Boolean = false,
    val timeoutSeconds: Long? = null
) : ChatOptions
