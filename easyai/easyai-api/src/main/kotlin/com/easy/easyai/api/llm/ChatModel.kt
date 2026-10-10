package com.easy.easyai.api.llm

import reactor.core.publisher.Flux

/**
 * easyai's own chat-model SPI, replacing Spring AI's `chat.model.ChatModel`.
 *
 * Implementations are thin streaming mappers over the official provider SDKs (`com.openai`,
 * `com.anthropic`, DashScope). They translate a [Prompt] into the provider's request shape and map
 * the streamed provider events back into [ChatResponse] chunks — including emitting reasoning as
 * generations tagged `metadata["thinking"] = true` so the agent loop can stream and persist it.
 * Tool execution is NOT done here: the ReAct loop in easyai-core parses tool calls and runs them.
 */
interface ChatModel {

    /** The default options this model was constructed with. */
    val options: ChatOptions

    /** Blocking single-shot call. */
    fun call(prompt: Prompt): ChatResponse

    /** Streaming call; the agent loop consumes this as a Kotlin `Flow` via `asFlow()`. */
    fun stream(prompt: Prompt): Flux<ChatResponse>
}
