package com.easy.easyai.api.llm

/**
 * Token accounting for one LLM call, mirroring the fields the agent loop and the compaction
 * estimator read. Anthropic-style accounting splits cached input into [cacheReadInputTokens] and
 * [cacheWriteInputTokens]; [promptTokens] is the non-cached input count, so the cached portion is
 * counted exactly once by callers that sum the three. Adapters for providers that report an
 * inclusive prompt count (OpenAI's `prompt_tokens` covers `cached_tokens`, and DashScope follows
 * it) must subtract the cached part before populating [promptTokens]. [nativeUsage] optionally
 * carries the provider SDK's raw usage object for adapters that need provider-specific fields.
 */
data class Usage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val nativeUsage: Any? = null,
    val cacheReadInputTokens: Long? = null,
    val cacheWriteInputTokens: Long? = null
)

/** Per-generation metadata; today only the provider finish reason. */
data class ChatGenerationMetadata(
    val finishReason: String? = null
)

/** One candidate completion: the assistant [output] plus its [metadata]. */
data class Generation(
    val output: AssistantMessage,
    val metadata: ChatGenerationMetadata = ChatGenerationMetadata()
)

/** Response-level metadata: provider request id, resolved model name, and aggregate [usage]. */
data class ChatResponseMetadata(
    val id: String = "",
    val model: String = "",
    val usage: Usage = Usage()
)

/**
 * A streaming chunk (or a complete [ChatModel.call] response). The agent loop reads [results] for
 * content/tool-calls and [metadata] for usage/finish accounting; [result] is the first generation,
 * matching the single-candidate usage of every supported provider.
 */
data class ChatResponse(
    val results: List<Generation>,
    val metadata: ChatResponseMetadata = ChatResponseMetadata()
) {
    val result: Generation? get() = results.firstOrNull()
}
