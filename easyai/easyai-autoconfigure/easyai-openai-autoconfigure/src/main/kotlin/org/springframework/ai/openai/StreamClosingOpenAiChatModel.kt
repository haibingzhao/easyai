package org.springframework.ai.openai

import com.easy.easyai.autoconfigure.openai.OpenAiChatModelFactory
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.OpenAIClientAsync
import com.openai.core.http.AsyncStreamResponse
import com.openai.errors.OpenAIInvalidDataException
import com.openai.models.chat.completions.*
import com.openai.models.chat.completions.ChatCompletionChunk.Choice
import com.openai.models.chat.completions.ChatCompletionChunk.Choice.Delta
import com.openai.models.chat.completions.ChatCompletionChunk.Choice.FinishReason
import com.openai.models.completions.CompletionUsage
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.metadata.*
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.model.MessageAggregator
import org.springframework.ai.chat.observation.ChatModelObservationContext
import org.springframework.ai.chat.observation.ChatModelObservationDocumentation
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.content.Media
import org.springframework.ai.observation.conventions.AiProvider
import org.springframework.ai.support.UsageCalculator
import org.springframework.ai.util.JacksonUtils
import org.springframework.core.io.ByteArrayResource
import org.springframework.util.Assert
import org.springframework.util.MimeTypeUtils
import org.springframework.util.StringUtils
import reactor.core.publisher.Flux
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.stream.Stream

/**
 * A [ChatModel] decorator around [OpenAiChatModel] that closes the underlying OpenAI SDK
 * stream when the downstream subscription is cancelled.
 *
 * Works around spring-projects/spring-ai#6654: [OpenAiChatModel] (Spring AI 2.0.0) drops the
 * `AsyncStreamResponse` handle inside its private `internalStream` and registers no
 * `onCancel`/`onDispose` hook on the `FluxSink`. When a downstream subscriber cancels (e.g.
 * the easyai stall-timeout in `AgentLoopRunner`), the SDK never learns about it: the response
 * is never closed, the backing OkHttp connection stays "in use" and eventually triggers
 * "A connection ... was leaked" warnings from the connection pool.
 *
 * This class delegates non-streaming [call]s to the wrapped model but re-implements the
 * streaming pipeline (mirroring `OpenAiChatModel.internalStream` from Spring AI 2.0.0),
 * retaining the stream handle and closing it via `FluxSink.onDispose` so cancellation
 * propagates to the SDK and releases the HTTP connection.
 *
 * NOTE: placed in the `org.springframework.ai.openai` package to reuse the package-private
 * `OpenAiChatModel.createRequest` request-building logic. Remove this class and return plain
 * [OpenAiChatModel] from [OpenAiChatModelFactory] once the upstream fix ships.
 */
internal class StreamClosingOpenAiChatModel(
    private val delegate: OpenAiChatModel,
    private val openAiClientAsync: OpenAIClientAsync,
    private val observationRegistry: ObservationRegistry = ObservationRegistry.NOOP
) : ChatModel {

    override fun call(prompt: Prompt): ChatResponse = delegate.call(prompt)

    override fun getOptions(): OpenAiChatOptions = delegate.options

    override fun stream(prompt: Prompt): Flux<ChatResponse> {
        val requestPrompt = buildRequestPrompt(prompt)
        verifyPromptChatOptions(requestPrompt)
        return internalStream(requestPrompt)
    }

    /**
     * Streaming pipeline equivalent to `OpenAiChatModel.internalStream`, with the fix:
     * the SDK stream handle is retained and closed when the sink is disposed
     * (cancel, error or complete).
     */
    private fun internalStream(prompt: Prompt): Flux<ChatResponse> {
        return Flux.deferContextual { contextView ->
            val request = delegate.createRequest(prompt, true)
            val roleMap = ConcurrentHashMap<String, String>()
            val observationContext = ChatModelObservationContext.builder()
                .prompt(prompt)
                .provider(AiProvider.OPENAI.value())
                .streaming(true)
                .build()
            val observation = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION.observation(
                null, DEFAULT_OBSERVATION_CONVENTION, { observationContext }, observationRegistry
            )
            val parentObservation: Observation? =
                contextView.getOrDefault(ObservationThreadLocalAccessor.KEY, null)
            observation.parentObservation(parentObservation)
            // Briefly make the parent observation current while starting this one, so
            // Micrometer tracing derives the span's parent from the parent observation.
            (parentObservation?.openScope() ?: Observation.Scope.NOOP).use {
                observation.start()
            }

            // Convert from AsyncStreamResponse<ChatCompletionChunk> to Flux<ChatCompletionChunk>.
            // Fix for spring-ai#6654: retain the stream handle and close it on sink disposal,
            // so downstream cancellation cancels the HTTP call and releases the connection.
            val chunks = Flux.create { sink ->
                val stream: AsyncStreamResponse<ChatCompletionChunk> = openAiClientAsync.chat()
                    .completions()
                    .createStreaming(request)
                    .subscribe { chunk -> sink.next(chunk) }
                sink.onDispose { runCatching { stream.close() } }
                stream.onCompleteFuture().whenComplete { _, throwable ->
                    if (throwable != null) {
                        sink.error(throwable)
                    } else {
                        sink.complete()
                    }
                }
            }

            // Aggregate chunks that belong to tool calls together
            val isInsideTool = AtomicBoolean(false)
            val aggregatedChatCompletions = chunks
                .doOnNext { chunk ->
                    if (ChunkMerger.hasToolCall(chunk)) {
                        isInsideTool.set(true)
                    }
                }
                .bufferUntil { chunk ->
                    if (isInsideTool.get() && ChunkMerger.toolCallsDone(chunk)) {
                        isInsideTool.set(false)
                        true
                    } else {
                        !isInsideTool.get()
                    }
                }
                .map { ChunkMerger.mergeChunks(it) }
                .map { ChunkMerger.chunkToChatCompletion(it) }

            val chatResponses = aggregatedChatCompletions.map { chatCompletion ->
                val id = chatCompletion.id()
                val generations = chatCompletion.choices().map { choice ->
                    roleMap.putIfAbsent(
                        id,
                        if (choice.message()._role().asString().isPresent) {
                            choice.message()._role().asStringOrThrow()
                        } else {
                            ""
                        }
                    )
                    val metadata = mapOf<String, Any>(
                        "id" to id,
                        "role" to roleMap.getOrDefault(id, ""),
                        "index" to choice.index(),
                        "finishReason" to choice.finishReason().value(),
                        "refusal" to choice.message().refusal().orElse(""),
                        "annotations" to choice.message().annotations()
                            .orElseGet { emptyList<ChatCompletionMessage.Annotation>() },
                        REASONING_CONTENT to getReasoningContent(choice)
                    )
                    buildGeneration(choice, metadata, request)
                }
                val usageVal = chatCompletion.usage().orElse(null)
                val currentUsage: Usage = if (usageVal != null) getDefaultUsage(usageVal) else EmptyUsage()
                val accumulated = UsageCalculator.getCumulativeUsage(currentUsage, null)
                ChatResponse(generations, from(chatCompletion, accumulated))
            }

            val observedResponses = chatResponses
                .doOnError { observation.error(it) }
                .doFinally { observation.stop() }
                .contextWrite { ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observation) }

            MessageAggregator().aggregate(observedResponses) { observationContext.setResponse(it) }
        }
    }

    private fun buildRequestPrompt(prompt: Prompt): Prompt {
        return if (prompt.options == null) {
            prompt.mutate().chatOptions(getOptions()).build()
        } else {
            prompt
        }
    }

    private fun verifyPromptChatOptions(prompt: Prompt) {
        val chatOptions = prompt.options
        if (chatOptions != null && chatOptions.topK != null) {
            logger.warn("The topK option is not supported by OpenAI chat models. Ignoring.")
        }
    }

    private fun buildGeneration(
        choice: ChatCompletion.Choice,
        metadata: Map<String, Any>,
        request: ChatCompletionCreateParams
    ): Generation {
        val message = choice.message()
        val assistantMessageMetadata = LinkedHashMap<String, Any>(metadata)
        val toolCallAdditionalProperties = extractToolCallAdditionalProperties(message)
        if (toolCallAdditionalProperties.isNotEmpty()) {
            assistantMessageMetadata[TOOL_CALL_ADDITIONAL_PROPERTIES_METADATA_KEY] = toolCallAdditionalProperties
        }
        val toolCalls = message.toolCalls()
            .map { list ->
                list.filter { it.function().isPresent }.map { tc ->
                    val funcCall = tc.function().get()
                    val functionDef = funcCall.function()
                    AssistantMessage.ToolCall(funcCall.id(), "function", functionDef.name(), functionDef.arguments())
                }
            }
            .orElse(emptyList())

        val generationMetadataBuilder = ChatGenerationMetadata.builder()
            .finishReason(choice.finishReason().value().name)

        var textContent = message.content().orElse("")

        val media = ArrayList<Media>()

        if (message.audio().isPresent && StringUtils.hasText(message.audio().get().data())
            && request.audio().isPresent
        ) {
            val audioOutput = message.audio().get()
            val mimeType = String.format("audio/%s", request.audio().get().format().value().name.lowercase())
            val audioData = Base64.getDecoder().decode(audioOutput.data())
            val resource = ByteArrayResource(audioData)
            media.add(
                Media.builder()
                    .mimeType(MimeTypeUtils.parseMimeType(mimeType))
                    .data(resource)
                    .id(audioOutput.id())
                    .build()
            )
            if (!StringUtils.hasText(textContent)) {
                textContent = audioOutput.transcript()
            }
            generationMetadataBuilder.metadata("audioId", audioOutput.id())
            generationMetadataBuilder.metadata("audioExpiresAt", audioOutput.expiresAt())
        }

        val assistantMessage = AssistantMessage.builder()
            .content(textContent)
            .properties(assistantMessageMetadata)
            .toolCalls(toolCalls)
            .media(media)
            .build()
        return Generation(assistantMessage, generationMetadataBuilder.build())
    }

    private fun extractToolCallAdditionalProperties(message: ChatCompletionMessage): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        message.toolCalls().ifPresent { toolCalls ->
            toolCalls.forEach { toolCall ->
                toolCall.function().ifPresent { functionToolCall ->
                    val props = functionToolCall._additionalProperties()
                    if (props.isNotEmpty()) {
                        try {
                            result[functionToolCall.id()] = objectMapper.writeValueAsString(props)
                        } catch (ex: JsonProcessingException) {
                            throw RuntimeException(ex)
                        }
                    }
                }
            }
        }
        return result
    }

    private fun from(result: ChatCompletion, usage: Usage): ChatResponseMetadata {
        Assert.notNull(result, "OpenAI ChatCompletion must not be null")
        val metadataBuilder = ChatResponseMetadata.builder()
            .id(result.id())
            .usage(usage)
            .model(result.model())
            .keyValue("created", getCreated(result))

        result._additionalProperties().forEach { (key, jsonValue) ->
            try {
                val value = JacksonUtils.getDefaultJsonMapper().convertValue(jsonValue, Any::class.java)
                metadataBuilder.keyValue(key, value)
            } catch (e: Exception) {
                logger.error("Error parsing JSON value for key '{}': {}", key, jsonValue, e)
                metadataBuilder.keyValue(key, jsonValue)
            }
        }

        return metadataBuilder.build()
    }

    /**
     * Extract the created timestamp from a ChatCompletion result, returning 0 if the field is
     * absent. Some OpenAI-compatible providers (e.g. GitHub Copilot) do not include it.
     */
    private fun getCreated(result: ChatCompletion): Long {
        return try {
            result.created()
        } catch (ex: OpenAIInvalidDataException) {
            0L
        }
    }

    private fun getDefaultUsage(usage: CompletionUsage): DefaultUsage {
        val cacheRead = usage.promptTokensDetails().flatMap { it.cachedTokens() }.orElse(null)
        return DefaultUsage(
            Math.toIntExact(usage.promptTokens()),
            Math.toIntExact(usage.completionTokens()),
            Math.toIntExact(usage.totalTokens()),
            usage,
            cacheRead,
            null
        )
    }

    private fun getReasoningContent(choice: ChatCompletion.Choice): String {
        val additionalProperties = choice.message()._additionalProperties()
        val reasoningContent = additionalProperties["reasoning_content"]
        if (reasoningContent != null) {
            return reasoningContent.asString().orElse("")
        }
        val reasoning = additionalProperties["reasoning"]
        if (reasoning != null) {
            return reasoning.asString().orElse("")
        }
        return ""
    }

    /**
     * Merges streamed chunks so that multi-chunk tool calls are assembled into complete
     * ChatCompletion units. Adapted from the private `ChunkMerger` in
     * `OpenAiChatModel` (Spring AI 2.0.0).
     */
    private object ChunkMerger {

        fun hasToolCall(chunk: ChatCompletionChunk): Boolean {
            return chunk.choices().isNotEmpty() && chunk.choices()[0].delta().toolCalls().isPresent
        }

        fun toolCallsDone(chunk: ChatCompletionChunk): Boolean {
            return chunk.choices().isNotEmpty() &&
                FinishReason.TOOL_CALLS == chunk.choices()[0].finishReason().orElse(null)
        }

        fun mergeChunks(chunks: List<ChatCompletionChunk>): ChatCompletionChunk {
            val builder = chunks[0].toBuilder()
            val choices = LinkedHashMap<Long, Choice>()
            chunks[0].choices().forEach { choice -> choices[choice.index()] = choice }

            for (i in 1 until chunks.size) {
                val chunk = chunks[i]
                chunk.usage().ifPresent { builder.usage(it) }
                chunk.serviceTier().ifPresent { builder.serviceTier(it) }
                chunk.choices().forEach { choice ->
                    choices.compute(choice.index()) { _, existing ->
                        if (existing == null) choice else mergeChoices(existing, choice)
                    }
                }
            }
            return builder.choices(ArrayList(choices.values)).build()
        }

        private fun mergeChoices(c1: Choice, c2: Choice): Choice {
            return Choice.builder()
                .index(c1.index())
                .finishReason(c1.finishReason().or { c2.finishReason() })
                .logprobs(c1.logprobs().or { c2.logprobs() })
                .delta(mergeDeltas(c1.delta(), c2.delta()))
                .build()
        }

        private fun mergeDeltas(left: Delta, right: Delta): Delta {
            val tcs = Stream.of(left.toolCalls(), right.toolCalls())
                .flatMap { it.stream() }
                .reduce { tcs1, tcs2 ->
                    Assert.isTrue(tcs2.size <= 1, "no more than one tool call per message currently supported")
                    val toolCall = tcs2[0]
                    if (toolCall.id().isPresent) {
                        val result = ArrayList(tcs1)
                        result.add(toolCall)
                        result
                    } else {
                        val lastFromTc1 = tcs1[tcs1.size - 1]
                        val lastFromTc1F = lastFromTc1.function().get()

                        val concatenatedArgs = Stream
                            .of(lastFromTc1F.arguments(), toolCall.function().flatMap { it.arguments() })
                            .flatMap { it.stream() }
                            .reduce { args1, args2 -> args1 + args2 }
                            .orElse("")

                        val result = ArrayList(tcs1)
                        result[tcs1.size - 1] = lastFromTc1.toBuilder()
                            .putAllAdditionalProperties(toolCall._additionalProperties())
                            .function(lastFromTc1F.toBuilder().arguments(concatenatedArgs).build())
                            .build()
                        result
                    }
                }
                .orElse(emptyList())

            return left.toBuilder().toolCalls(tcs).build()
        }

        /** Convert a ChatCompletionChunk into a ChatCompletion. */
        fun chunkToChatCompletion(chunk: ChatCompletionChunk): ChatCompletion {
            val choices = chunk.choices().map { cccc ->
                val choiceBuilder = ChatCompletion.Choice.builder()

                choiceBuilder.index(cccc.index())

                choiceBuilder.finishReason(ChatCompletion.Choice.FinishReason.of(""))
                cccc.finishReason().ifPresent { finishReason ->
                    choiceBuilder.finishReason(
                        ChatCompletion.Choice.FinishReason.of(finishReason.value().name.lowercase())
                    )
                }

                if (cccc.logprobs().isPresent) {
                    val logprobs = cccc.logprobs().get()
                    choiceBuilder.logprobs(
                        ChatCompletion.Choice.Logprobs.builder()
                            .content(logprobs.content())
                            .refusal(logprobs.refusal())
                            .build()
                    )
                } else {
                    choiceBuilder.logprobs(
                        ChatCompletion.Choice.Logprobs.builder()
                            .content(emptyList())
                            .refusal(emptyList())
                            .build()
                    )
                }

                val msgBuilder = ChatCompletionMessage.builder()
                    .content(cccc.delta().content())
                    .refusal(cccc.delta().refusal())
                cccc.delta().toolCalls().ifPresent { ccctcs ->
                    msgBuilder.toolCalls(ccctcs.map { tc ->
                        val toolCallBuilder = ChatCompletionMessageFunctionToolCall.builder()
                        toolCallBuilder.putAllAdditionalProperties(tc._additionalProperties())
                        toolCallBuilder.id(tc.id().get())
                        toolCallBuilder.function(
                            ChatCompletionMessageFunctionToolCall.Function.builder()
                                .name(tc.function().get().name().get())
                                .arguments(tc.function().get().arguments().get())
                                .build()
                        )
                        ChatCompletionMessageToolCall.ofFunction(toolCallBuilder.build())
                    })
                }
                choiceBuilder.message(msgBuilder.build())
                choiceBuilder.build()
            }

            return ChatCompletion.builder()
                .id(chunk.id())
                .choices(choices)
                .created(getCreated(chunk))
                .model(chunk.model())
                .usage(
                    chunk.usage().orElse(
                        CompletionUsage.builder().promptTokens(0).completionTokens(0).totalTokens(0).build()
                    )
                )
                .putAllAdditionalProperties(chunk._additionalProperties())
                .build()
        }

        /** Extract the created timestamp from a ChatCompletionChunk, returning 0 if absent. */
        private fun getCreated(chunk: ChatCompletionChunk): Long {
            return try {
                chunk.created()
            } catch (ex: OpenAIInvalidDataException) {
                0L
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(StreamClosingOpenAiChatModel::class.java)

        private const val REASONING_CONTENT = "reasoningContent"

        const val TOOL_CALL_ADDITIONAL_PROPERTIES_METADATA_KEY = "openai.tool_calls.additional_properties"

        private val DEFAULT_OBSERVATION_CONVENTION = DefaultChatModelObservationConvention()

        private val objectMapper = ObjectMapper()
    }
}
