package com.easy.easyai.api.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
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

    /**
     * Blocking single-shot call.
     *
     * Blocking means *uncancellable*: a thread parked inside a provider SDK call cannot be called
     * back, so cancelling the coroutine around it leaves the request running to completion — the
     * answer is thrown away, the tokens are not. Coroutines must use [callAsync] instead.
     */
    fun call(prompt: Prompt): ChatResponse

    /** Streaming call; the agent loop consumes this as a Kotlin `Flow` via `asFlow()`. */
    fun stream(prompt: Prompt): Flux<ChatResponse>

    /**
     * Cancellation-aware single-shot call: the streaming path folded into one response.
     *
     * Cancelling the caller cancels the provider request — the subscription is disposed, which is
     * the same hook the agent loop relies on to stop a stream when the client disconnects, so every
     * adapter already closes (or cancels) its HTTP response there. A budget around it
     * (`withTimeout`) therefore ends the *spend*, not just the wait.
     *
     * Default implementation on purpose: it derives the folded response from [stream], so a new
     * adapter gets cancellation for free and only overrides this if its blocking path is the more
     * capable one.
     */
    suspend fun callAsync(prompt: Prompt): ChatResponse =
        ChatResponseAggregator.aggregate(stream(prompt).asFlow().toList())
}
