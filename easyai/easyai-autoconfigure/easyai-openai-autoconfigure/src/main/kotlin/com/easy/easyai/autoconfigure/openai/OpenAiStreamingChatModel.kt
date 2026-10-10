package com.easy.easyai.autoconfigure.openai

import com.easy.easyai.api.llm.AssistantMessage
import com.easy.easyai.api.llm.ChatGenerationMetadata
import com.easy.easyai.api.llm.ChatOptions
import com.easy.easyai.api.llm.ChatResponse
import com.easy.easyai.api.llm.ChatResponseMetadata
import com.easy.easyai.api.llm.Generation
import com.easy.easyai.api.llm.Media
import com.easy.easyai.api.llm.MediaSource
import com.easy.easyai.api.llm.Message
import com.easy.easyai.api.llm.NonTransientAiException
import com.easy.easyai.api.llm.Prompt
import com.easy.easyai.api.llm.SystemMessage
import com.easy.easyai.api.llm.TransientAiException
import com.easy.easyai.api.llm.ToolResponseMessage
import com.easy.easyai.api.llm.Usage
import com.easy.easyai.api.llm.UserMessage
import com.easy.easyai.api.llm.ChatModel
import com.openai.client.OpenAIClientAsync
import com.openai.core.JsonValue
import com.openai.core.RequestOptions
import com.openai.errors.OpenAIServiceException
import com.openai.models.FunctionDefinition
import com.openai.models.FunctionParameters
import com.openai.models.ReasoningEffort
import com.openai.models.ResponseFormatJsonObject
import com.openai.models.ResponseFormatJsonSchema
import com.openai.models.ResponseFormatText
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam
import com.openai.models.chat.completions.ChatCompletionChunk
import com.openai.models.chat.completions.ChatCompletionContentPart
import com.openai.models.chat.completions.ChatCompletionContentPartImage
import com.openai.models.chat.completions.ChatCompletionContentPartText
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionFunctionTool
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionMessageToolCall
import com.openai.models.chat.completions.ChatCompletionStreamOptions
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam
import com.openai.models.chat.completions.ChatCompletionTool
import com.openai.models.chat.completions.ChatCompletionToolMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import com.openai.models.completions.CompletionUsage
import reactor.core.publisher.Flux
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.Base64

/**
 * OpenAI-protocol [ChatModel], built directly on the official `com.openai` SDK — the replacement
 * for spring-ai's `OpenAiChatModel` + the old reasoning shim decorator.
 *
 * Streams chat completions and maps each SSE chunk into own [ChatResponse]s:
 * - text deltas → an [AssistantMessage] generation;
 * - `reasoning_content` deltas → a generation tagged `metadata["thinking"] = true` so the agent
 *   loop streams and persists thinking (the fix for reasoning not landing on the OpenAI path);
 * - fragmented `tool_calls` are accumulated by index across chunks and emitted as complete
 *   [AssistantMessage.ToolCall]s on the finish chunk;
 * - usage is read from the final chunk (`stream_options.include_usage`).
 * Tool execution is NOT performed here — the ReAct loop parses the tool calls and runs them.
 */
internal class OpenAiStreamingChatModel(
    private val clientAsync: OpenAIClientAsync,
    private val defaultOptions: OpenAiChatOptions
) : ChatModel {

    override val options: ChatOptions get() = defaultOptions

    override fun call(prompt: Prompt): ChatResponse {
        // Aggregate the stream into a single response: concat text, keep accumulated tool calls,
        // take the last non-empty usage. `call` is legitimately blocking.
        val chunks = stream(prompt).collectList().block() ?: emptyList()
        return aggregate(chunks)
    }

    override fun stream(prompt: Prompt): Flux<ChatResponse> = Flux.defer {
        val options = resolveOptions(prompt)
        val request = createRequest(prompt, options)
        val requestOptions = RequestOptions.builder()
            .timeout(Duration.ofSeconds(options.timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS))
            .build()

        // Per-subscription accumulator for fragmented tool-call arguments keyed by choice+index.
        val toolCalls = LinkedHashMap<String, ToolCallAccumulator>()

        val chunks: Flux<ChatCompletionChunk> = Flux.create { sink ->
            val response = clientAsync.chat().completions().createStreaming(request, requestOptions)
            sink.onDispose { response.close() }
            response.subscribe { chunk -> sink.next(chunk) }
                .onCompleteFuture()
                .whenComplete { _, throwable ->
                    if (throwable != null) sink.error(throwable.toAiException()) else sink.complete()
                }
        }

        chunks.concatMap { chunk -> Flux.fromIterable(mapChunk(chunk, toolCalls)) }
    }

    /**
     * A portable [ChatOptions] (e.g. the no-model-config fallback in the agent loop, or an
     * [com.easy.easyai.api.llm.DefaultChatOptions] from InternalLlmService) carries only common
     * fields; they are merged onto the factory defaults instead of the whole options object being
     * discarded — dropping it would silently lose the turn's tool callbacks and sampling knobs.
     */
    internal fun resolveOptions(prompt: Prompt): OpenAiChatOptions {
        val provided = prompt.options ?: return defaultOptions
        if (provided is OpenAiChatOptions) return provided
        return defaultOptions.copy(
            model = provided.model ?: defaultOptions.model,
            temperature = provided.temperature ?: defaultOptions.temperature,
            maxTokens = provided.maxTokens ?: defaultOptions.maxTokens,
            toolCallbacks = provided.toolCallbacks.ifEmpty { defaultOptions.toolCallbacks },
            outputSchema = provided.outputSchema ?: defaultOptions.outputSchema
        )
    }

    // internal for same-module unit tests: pure chunk -> ChatResponse mapping with the given
    // per-stream tool-call accumulator.
    internal fun mapChunk(
        chunk: ChatCompletionChunk,
        toolCalls: MutableMap<String, ToolCallAccumulator>
    ): List<ChatResponse> {
        val usage = chunk.usage().map { toUsage(it) }.orElse(null)
        val metadata = ChatResponseMetadata(id = chunk.id(), model = chunk.model(), usage = usage ?: Usage())

        val results = ArrayList<Generation>()
        for (choice in chunk.choices()) {
            val delta = choice.delta()
            val finishReason = choice.finishReason().map { it.toString().lowercase() }.orElse(null)

            // Reasoning/thinking delta (wire field `reasoning_content`, or `reasoning`).
            val reasoning = deltaReasoning(delta)
            if (!reasoning.isNullOrEmpty()) {
                results.add(
                    Generation(
                        AssistantMessage(content = reasoning, metadata = mapOf(THINKING_METADATA_KEY to true)),
                        ChatGenerationMetadata()
                    )
                )
            }

            val content = delta.content().orElse(null)
            if (!content.isNullOrEmpty()) {
                results.add(Generation(AssistantMessage(content = content), ChatGenerationMetadata()))
            }

            // Accumulate tool-call fragments; emit the assembled calls only when the choice finishes.
            delta.toolCalls().ifPresent { fragments ->
                fragments.forEach { fragment ->
                    val key = "${chunk.id()}:${choice.index()}:${fragment.index()}"
                    val acc = toolCalls.getOrPut(key) { ToolCallAccumulator(choice.index()) }
                    fragment.id().ifPresent { acc.id = it }
                    fragment.function().ifPresent { fn ->
                        fn.name().ifPresent { acc.name = it }
                        fn.arguments().ifPresent { acc.arguments.append(it) }
                    }
                }
            }

            val collected = toolCalls.values.filter { it.choiceIndex == choice.index() && !it.id.isNullOrEmpty() }
            if (finishReason != null && collected.isNotEmpty()) {
                results.add(
                    Generation(
                        AssistantMessage(
                            content = "",
                            toolCalls = collected.map { AssistantMessage.ToolCall(it.id!!, "function", it.name, it.arguments.toString()) }
                        ),
                        ChatGenerationMetadata(finishReason = finishReason)
                    )
                )
                // Consume the emitted accumulators: a gateway that repeats the finish frame
                // (e.g. a trailing usage-only chunk) must not re-emit the same tool-call set.
                toolCalls.entries.removeAll { it.value.choiceIndex == choice.index() && !it.value.id.isNullOrEmpty() }
            }
        }

        if (results.isEmpty()) {
            // Usage-only or keepalive chunk; the loop reads usage/finish from an otherwise empty chunk.
            return listOf(ChatResponse(listOf(Generation(AssistantMessage(content = ""), ChatGenerationMetadata(finishReason = finishReasonOf(chunk)))), metadata))
        }
        // Providers that put content and finish_reason in the same chunk would otherwise lose the
        // reason, since the loop reads it from results[0].metadata and the empty-results fallback
        // above is not reached. Tool-call frames already carry it on their own generation.
        val chunkFinishReason = finishReasonOf(chunk)
        if (chunkFinishReason != null && results.none { it.metadata.finishReason != null }) {
            results[0] = results[0].copy(metadata = ChatGenerationMetadata(finishReason = chunkFinishReason))
        }
        return listOf(ChatResponse(results, metadata))
    }

    private fun finishReasonOf(chunk: ChatCompletionChunk): String? =
        chunk.choices().firstOrNull()?.finishReason()?.map { it.toString().lowercase() }?.orElse(null)

    private fun deltaReasoning(delta: ChatCompletionChunk.Choice.Delta): String? {
        val props = delta._additionalProperties()
        (props["reasoning_content"] ?: props["reasoning"])?.let { return it.asString().orElse("") }
        return null
    }

    private fun toUsage(u: CompletionUsage): Usage {
        val cacheRead = u.promptTokensDetails().flatMap { it.cachedTokens() }.map { it.toLong() }.orElse(null)
        val completionTokens = u.completionTokens().toInt()
        // OpenAI reports prompt_tokens inclusive of prompt_tokens_details.cached_tokens, while the
        // Usage contract wants the non-cached count. Passing the inclusive value through makes the
        // compaction estimator add the cached portion a second time, which inflated the window
        // estimate ~2x on cache hits and fired compaction at ~40% of the real context.
        val promptTokens = (u.promptTokens() - (cacheRead ?: 0L)).coerceAtLeast(0L).toInt()
        return Usage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = promptTokens + completionTokens,
            nativeUsage = u,
            cacheReadInputTokens = cacheRead
        )
    }

    private fun createRequest(prompt: Prompt, options: OpenAiChatOptions): ChatCompletionCreateParams {
        val builder = ChatCompletionCreateParams.builder()
        prompt.instructions.forEach { message -> toMessageParams(message).forEach { builder.addMessage(it) } }

        options.model?.let { builder.model(it) }
        options.temperature?.let { builder.temperature(it) }
        options.maxTokens?.let { builder.maxTokens(it.toLong()) }
        options.maxCompletionTokens?.let { builder.maxCompletionTokens(it.toLong()) }
        options.reasoningEffort?.let { builder.reasoningEffort(ReasoningEffort.of(it.lowercase())) }
        if (options.parallelToolCalls != null) builder.parallelToolCalls(options.parallelToolCalls)
        if (options.stop.isNotEmpty()) {
            builder.stop(
                if (options.stop.size == 1) ChatCompletionCreateParams.Stop.ofString(options.stop[0])
                else ChatCompletionCreateParams.Stop.ofStrings(options.stop)
            )
        }
        applyResponseFormat(builder, options)
        options.toolCallbacks.forEach { builder.addTool(toChatCompletionTool(it.name, it.description, it.inputSchema, options.strict)) }

        builder.streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build())
        return builder.build()
    }

    private fun applyResponseFormat(builder: ChatCompletionCreateParams.Builder, options: OpenAiChatOptions) {
        when (options.responseFormatKind) {
            OpenAiResponseFormatKind.TEXT -> builder.responseFormat(ResponseFormatText.builder().build())
            OpenAiResponseFormatKind.JSON_OBJECT -> builder.responseFormat(ResponseFormatJsonObject.builder().build())
            OpenAiResponseFormatKind.JSON_SCHEMA -> {
                val schema = options.outputSchema ?: return
                builder.responseFormat(
                    ResponseFormatJsonSchema.builder()
                        .jsonSchema(
                            ResponseFormatJsonSchema.JsonSchema.builder()
                                .name(JSON_SCHEMA_NAME)
                                .strict(options.strict)
                                .schema(parseSchema(schema))
                                .build()
                        )
                        .build()
                )
            }
            null -> Unit
        }
    }

    private fun parseSchema(schemaJson: String): ResponseFormatJsonSchema.JsonSchema.Schema =
        objectMapper.readValue(schemaJson, ResponseFormatJsonSchema.JsonSchema.Schema::class.java)

    /**
     * One wire message per conversation turn — except a [ToolResponseMessage], which packs the
     * results of a parallel tool round: OpenAI requires one `tool` message per `tool_call_id`.
     * internal for same-module unit tests.
     */
    internal fun toMessageParams(message: Message): List<ChatCompletionMessageParam> = when (message) {
        is SystemMessage -> listOf(
            ChatCompletionMessageParam.ofSystem(
                ChatCompletionSystemMessageParam.builder().content(message.content).build()
            )
        )
        is UserMessage -> listOf(toUserParam(message))
        is AssistantMessage -> listOf(toAssistantParam(message))
        is ToolResponseMessage -> message.responses.map { response ->
            ChatCompletionMessageParam.ofTool(
                ChatCompletionToolMessageParam.builder().toolCallId(response.id).content(response.responseData).build()
            )
        }
    }

    private fun toUserParam(message: UserMessage): ChatCompletionMessageParam {
        if (message.media.isEmpty()) {
            return ChatCompletionMessageParam.ofUser(
                ChatCompletionUserMessageParam.builder().content(message.content).build()
            )
        }
        val parts = ArrayList<ChatCompletionContentPart>()
        if (message.content.isNotEmpty()) {
            parts.add(ChatCompletionContentPart.ofText(ChatCompletionContentPartText.builder().text(message.content).build()))
        }
        message.media.forEach { parts.add(toContentPart(it)) }
        return ChatCompletionMessageParam.ofUser(
            ChatCompletionUserMessageParam.builder().contentOfArrayOfContentParts(parts).build()
        )
    }

    private fun toAssistantParam(message: AssistantMessage): ChatCompletionMessageParam {
        // message.thinkingBlocks is dropped: OpenAI chat-completions has no reasoning replay field.
        val builder = ChatCompletionAssistantMessageParam.builder()
        message.content?.let { builder.content(it) }
        if (message.toolCalls.isNotEmpty()) {
            builder.toolCalls(
                message.toolCalls.map { tc ->
                    ChatCompletionMessageToolCall.ofFunction(
                        ChatCompletionMessageFunctionToolCall.builder()
                            .id(tc.id)
                            .function(
                                ChatCompletionMessageFunctionToolCall.Function.builder()
                                    .name(tc.name)
                                    .arguments(tc.arguments)
                                    .build()
                            )
                            .build()
                    )
                }
            )
        }
        return ChatCompletionMessageParam.ofAssistant(builder.build())
    }

    private fun toContentPart(media: Media): ChatCompletionContentPart {
        val url = when (val src = media.source) {
            is MediaSource.Url -> src.uri.toString()
            is MediaSource.Bytes -> "data:${media.mimeType};base64," + Base64.getEncoder().encodeToString(src.data)
        }
        return ChatCompletionContentPart.ofImageUrl(
            ChatCompletionContentPartImage.builder()
                .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder().url(url).build())
                .build()
        )
    }

    private fun toChatCompletionTool(name: String, description: String, inputSchema: String, strict: Boolean): ChatCompletionTool {
        val parametersBuilder = FunctionParameters.builder()
        if (inputSchema.isNotEmpty()) {
            @Suppress("UNCHECKED_CAST")
            val schemaMap = objectMapper.readValue(inputSchema, Map::class.java) as Map<String, Any?>
            schemaMap.forEach { (key, value) -> parametersBuilder.putAdditionalProperty(key, JsonValue.from(value)) }
        }
        return ChatCompletionTool.ofFunction(
            ChatCompletionFunctionTool.builder()
                .function(
                    FunctionDefinition.builder()
                        .name(name)
                        .description(description)
                        .parameters(parametersBuilder.build())
                        .strict(strict)
                        .build()
                )
                .build()
        )
    }

    /** Mutable accumulator for a single fragmented tool call. */
    internal class ToolCallAccumulator(val choiceIndex: Long) {
        var id: String? = null
        var name: String = ""
        val arguments = StringBuilder()
    }

    private fun aggregate(chunks: List<ChatResponse>): ChatResponse {
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
            val u = chunk.metadata.usage
            if (u.promptTokens > 0 || u.completionTokens > 0 || u.totalTokens > 0) usage = u
            for (generation in chunk.results) {
                val output = generation.output
                if (output.metadata.containsKey(THINKING_METADATA_KEY)) thinking.append(output.content.orEmpty())
                else text.append(output.content.orEmpty())
                if (output.toolCalls.isNotEmpty()) toolCalls = output.toolCalls
                generation.metadata.finishReason?.let { finishReason = it }
            }
        }
        val metadata = ChatResponseMetadata(id = id, model = model, usage = usage)
        val assistant = AssistantMessage(
            content = text.toString(),
            toolCalls = toolCalls,
            metadata = if (thinking.isNotEmpty()) mapOf(THINKING_METADATA_KEY to true, REASONING_ACCUMULATED to thinking.toString()) else emptyMap()
        )
        return ChatResponse(
            listOf(Generation(assistant, ChatGenerationMetadata(finishReason = finishReason))),
            metadata
        )
    }

    /** Maps SDK errors to the retry-classification exceptions the agent loop understands. */
    private fun Throwable.toAiException(): Throwable {
        val service = (this as? OpenAIServiceException) ?: (this?.cause as? OpenAIServiceException) ?: return this
        val status = service.statusCode()
        val detail = "$status: ${service.message}"
        return if (status == 429 || status >= 500) {
            TransientAiException(detail, this)
        } else {
            NonTransientAiException(detail, this)
        }
    }

    companion object {
        private const val THINKING_METADATA_KEY = "thinking"
        private const val REASONING_ACCUMULATED = "reasoningContent"
        private const val JSON_SCHEMA_NAME = "json_schema"
        private const val DEFAULT_TIMEOUT_SECONDS = 600L
        private val objectMapper: ObjectMapper = JsonMapper.builder().build()
    }
}
