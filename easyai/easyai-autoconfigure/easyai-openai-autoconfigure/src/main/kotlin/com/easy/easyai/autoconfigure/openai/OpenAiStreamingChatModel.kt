package com.easy.easyai.autoconfigure.openai

import com.easy.easyai.api.llm.*
import com.easy.easyai.api.llm.ChatModel
import com.openai.client.OpenAIClientAsync
import com.openai.core.JsonValue
import com.openai.core.RequestOptions
import com.openai.errors.OpenAIServiceException
import com.openai.models.*
import com.openai.models.chat.completions.*
import com.openai.models.completions.CompletionUsage
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.*

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

    private val logger = LoggerFactory.getLogger(javaClass)

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

            val pending = toolCalls.values.filter { it.choiceIndex == choice.index() }
            if (finishReason != null && pending.isNotEmpty()) {
                val collected = pending.mapNotNull { assemble(it) }
                if (collected.isNotEmpty()) {
                    results.add(
                        Generation(
                            AssistantMessage(content = "", toolCalls = collected),
                            ChatGenerationMetadata(finishReason = finishReason)
                        )
                    )
                }
                // Consume the accumulators: a gateway that repeats the finish frame
                // (e.g. a trailing usage-only chunk) must not re-emit the same tool-call set.
                toolCalls.entries.removeAll { it.value.choiceIndex == choice.index() }
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

    /**
     * Turns an accumulated call into a wire-ready [AssistantMessage.ToolCall].
     *
     * Bailian compatible-mode omits the id on every streamed tool call — the frame that normally
     * carries it arrives with `"id":""`. The call is still executable without an id, so one is
     * synthesized instead of dropping the call: dropping it leaves the turn holding an intent
     * sentence and no call, which reads to the loop as a finished answer.
     */
    private fun assemble(accumulator: ToolCallAccumulator): AssistantMessage.ToolCall? {
        if (accumulator.name.isBlank()) {
            logger.warn("Dropping an incomplete tool call: no name, {} argument chars", accumulator.arguments.length)
            return null
        }
        val arguments = accumulator.arguments.toString()
        val streamedId = accumulator.id?.takeIf { it.isNotBlank() }
        val id = streamedId ?: ("call_" + UUID.randomUUID().toString().replace("-", ""))
        if (streamedId == null) {
            logger.debug("Tool call '{}' streamed without an id; using {} ({} argument chars)", accumulator.name, id, arguments.length)
        }
        return AssistantMessage.ToolCall(id, "function", accumulator.name, arguments)
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

    /** internal for same-module unit tests: the wire params are what a response-format bug shows up in. */
    internal fun createRequest(prompt: Prompt, options: OpenAiChatOptions): ChatCompletionCreateParams {
        val builder = ChatCompletionCreateParams.builder()
        prompt.instructions.forEach { message -> toMessageParams(message).forEach { builder.addMessage(it) } }

        options.model?.let { builder.model(it) }
        options.temperature?.let { builder.temperature(it) }
        applyMaxTokens(builder, options)
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

    /**
     * Output cap on the wire. The portable [OpenAiChatOptions.maxTokens] still goes out as the
     * legacy `max_tokens`: the SDK deprecates that field in favor of `max_completion_tokens`, but
     * this protocol is also the OpenAI-compatible gateway path (Bailian compatible-mode, vLLM,
     * Ollama), which key off `max_tokens` and ignore the new field — switching unconditionally
     * would drop the caller's cap and let generations run unbounded. Reasoning turns are the
     * exception: o-series rejects `max_tokens`, so [OpenAiChatModelFactory] routes them to
     * [OpenAiChatOptions.maxCompletionTokens] instead of `maxTokens`.
     */
    @Suppress("DEPRECATION")
    private fun applyMaxTokens(builder: ChatCompletionCreateParams.Builder, options: OpenAiChatOptions) {
        options.maxTokens?.let { builder.maxTokens(it.toLong()) }
        options.maxCompletionTokens?.let { builder.maxCompletionTokens(it.toLong()) }
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

    /**
     * Reads the schema into the SDK's free-form schema object.
     *
     * Not `readValue(schemaJson, Schema::class.java)`: `Schema` is a Jackson 2 type whose only creator
     * takes `Map<String, JsonValue>`, and this Jackson 3 mapper cannot see the annotation the SDK uses
     * to restore `JsonValue` — so deserializing any schema failed, before the request left the process.
     * Plain values through [JsonValue.from] is how [toChatCompletionTool] already feeds tool schemas.
     */
    private fun parseSchema(schemaJson: String): ResponseFormatJsonSchema.JsonSchema.Schema {
        @Suppress("UNCHECKED_CAST")
        val schemaMap = objectMapper.readValue(schemaJson, Map::class.java) as Map<String, Any?>
        val builder = ResponseFormatJsonSchema.JsonSchema.Schema.builder()
        schemaMap.forEach { (key, value) -> builder.putAdditionalProperty(key, JsonValue.from(value)) }
        return builder.build()
    }

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

    /**
     * Maps SDK errors to the retry-classification exceptions the agent loop understands.
     *
     * The SDK message already opens with the status code, so it is not prefixed a second time here —
     * and when the gateway's body is not `{"error": {...}}` that message degrades to `"400: Unknown"`,
     * which is why the structured fields travel alongside it and [OpenAiErrorLoggingHttpClient] logs
     * the raw body they were parsed out of.
     */
    private fun Throwable.toAiException(): Throwable {
        val service = (this as? OpenAIServiceException) ?: (this.cause as? OpenAIServiceException) ?: return this
        val detail = buildString {
            append(service.message)
            service.code().orElse(null)?.let { append(" code=").append(it) }
            service.type().orElse(null)?.let { append(" type=").append(it) }
            service.param().orElse(null)?.let { append(" param=").append(it) }
        }
        val status = service.statusCode()
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
