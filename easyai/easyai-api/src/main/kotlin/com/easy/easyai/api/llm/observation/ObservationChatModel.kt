package com.easy.easyai.api.llm.observation

import com.easy.easyai.api.llm.ChatModel
import com.easy.easyai.api.llm.ChatOptions
import com.easy.easyai.api.llm.ChatResponse
import com.easy.easyai.api.llm.Prompt
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import reactor.core.publisher.Flux

/**
 * Thin [ChatModel] decorator that wraps a protocol mapper's calls in an [Observation] carrying an
 * [EasyAiChatModelObservationContext], so the observability filter can emit GenAI tracing spans after
 * spring-ai's own observation plumbing is gone. When the registry is [ObservationRegistry.NOOP] the
 * decoration is inert overhead-free passthrough.
 */
class ObservationChatModel(
    private val delegate: ChatModel,
    private val observationRegistry: ObservationRegistry
) : ChatModel {

    override val options: ChatOptions get() = delegate.options

    override fun call(prompt: Prompt): ChatResponse {
        val context = EasyAiChatModelObservationContext(prompt)
        val observation = Observation.start(OBSERVATION_NAME, { context }, observationRegistry)
        return try {
            val response = delegate.call(prompt)
            context.response = response
            response
        } catch (e: Throwable) {
            observation.error(e)
            throw e
        } finally {
            observation.stop()
        }
    }

    override fun stream(prompt: Prompt): Flux<ChatResponse> = Flux.defer {
        // Started per subscription: an unsubscribed stream would leak a never-stopped span,
        // and a resubscribed one would otherwise share the first attempt's observation.
        val context = EasyAiChatModelObservationContext(prompt)
        val observation = Observation.start(OBSERVATION_NAME, { context }, observationRegistry)
        delegate.stream(prompt)
            .doOnNext { chunk -> chunk.result?.let { context.response = chunk } }
            .doOnError { observation.error(it) }
            .doFinally { observation.stop() }
    }

    companion object {
        private const val OBSERVATION_NAME = "easyai.chat"
    }
}
