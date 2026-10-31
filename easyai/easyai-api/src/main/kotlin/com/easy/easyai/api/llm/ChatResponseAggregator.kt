package com.easy.easyai.api.llm

/**
 * Folds a stream of [ChatResponse] chunks into the single response a non-streaming caller expects.
 *
 * Every supported provider streams *deltas*: text and thinking fragments are appended, tool calls
 * arrive as complete snapshots (never partial objects, and the last snapshot wins), and id/model/
 * usage/finish reason are carried by whichever chunk last reported them. Aggregating on this
 * contract is what lets [ChatModel.callAsync] reuse the streaming path — and therefore its
 * cancellation behaviour — instead of a separate blocking HTTP call.
 *
 * Reasoning is not merged into [AssistantMessage.content]: thinking fragments are accumulated into
 * `metadata["reasoningContent"]`, matching the key the provider adapters use per chunk. The agent
 * loop still reads reasoning from the streaming path, so a non-streaming caller that needs thinking
 * blocks must read that key itself.
 */
object ChatResponseAggregator {

    /** Marker the adapters put on reasoning generations. */
    private const val THINKING_KEY = "thinking"

    /** Where the accumulated reasoning text lands on the folded assistant message. */
    const val REASONING_CONTENT_KEY = "reasoningContent"

    fun aggregate(chunks: List<ChatResponse>): ChatResponse {
        if (chunks.isEmpty()) return ChatResponse(emptyList())
        val text = StringBuilder()
        val thinking = StringBuilder()
        var toolCalls: List<AssistantMessage.ToolCall> = emptyList()
        var usage = Usage()
        var finishReason: String? = null
        var id = ""
        var model = ""
        for (chunk in chunks) {
            if (chunk.metadata.id.isNotEmpty()) id = chunk.metadata.id
            if (chunk.metadata.model.isNotEmpty()) model = chunk.metadata.model
            val chunkUsage = chunk.metadata.usage
            if (chunkUsage.promptTokens > 0 || chunkUsage.completionTokens > 0 || chunkUsage.totalTokens > 0) {
                usage = chunkUsage
            }
            for (generation in chunk.results) {
                val output = generation.output
                if (output.metadata.containsKey(THINKING_KEY)) thinking.append(output.content.orEmpty())
                else text.append(output.content.orEmpty())
                if (output.toolCalls.isNotEmpty()) toolCalls = output.toolCalls
                generation.metadata.finishReason?.let { finishReason = it }
            }
        }
        val metadata = ChatResponseMetadata(id = id, model = model, usage = usage)
        val assistant = AssistantMessage(
            content = text.toString(),
            toolCalls = toolCalls,
            metadata = if (thinking.isNotEmpty()) {
                mapOf(THINKING_KEY to true, REASONING_CONTENT_KEY to thinking.toString())
            } else {
                emptyMap()
            }
        )
        return ChatResponse(
            listOf(Generation(assistant, ChatGenerationMetadata(finishReason = finishReason))),
            metadata
        )
    }
}
