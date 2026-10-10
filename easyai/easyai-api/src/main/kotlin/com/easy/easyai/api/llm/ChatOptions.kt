package com.easy.easyai.api.llm

/**
 * Protocol-neutral view of the options every adapter understands. This replaces the slice of
 * Spring AI's `ChatOptions` / `ToolCallingChatOptions` /
 * `StructuredOutputChatOptions` that easyai actually reads.
 *
 * Each protocol adapter defines its own concrete implementation carrying provider-specific knobs
 * (e.g. DashScope's `enableSearch`/`seed`, Anthropic's thinking budget, OpenAI's response format);
 * the ReAct loop only ever touches these common fields plus [toolCallbacks] and [outputSchema].
 * [DefaultChatOptions] is the generic implementation used when no model config is available.
 */
interface ChatOptions {
    val model: String?
    val temperature: Double?
    val maxTokens: Int?

    /**
     * Tool descriptors for this turn. Adapters translate them into the provider's tool/function
     * wire format; they are NEVER invoked here — the ReAct loop in easyai-core is the only tool
     * executor, so these are pure carriers.
     */
    val toolCallbacks: List<ToolCallback>

    /** JSON schema to enforce at the API level this turn, when the protocol/model supports it. */
    val outputSchema: String?
}

/** Generic [ChatOptions] used as the fallback when a session has no resolved model config. */
data class DefaultChatOptions(
    override val model: String? = null,
    override val temperature: Double? = null,
    override val maxTokens: Int? = null,
    override val toolCallbacks: List<ToolCallback> = emptyList(),
    override val outputSchema: String? = null
) : ChatOptions
