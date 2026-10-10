package com.easy.easyai.autoconfigure.anthropic

import com.anthropic.client.AnthropicClient
import com.anthropic.client.AnthropicClientAsync
import com.anthropic.core.JsonValue
import com.anthropic.core.RequestOptions
import com.anthropic.core.http.AsyncStreamResponse
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.*
import com.easy.easyai.api.llm.*
import com.easy.easyai.api.llm.Message
import com.easy.easyai.api.llm.Usage
import reactor.core.publisher.Flux
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.*

/**
 * Anthropic-protocol [ChatModel] built directly on the official `com.anthropic` SDK — the
 * replacement for spring-ai's `AnthropicChatModel` plus the `UsageCorrectingAnthropicChatModel`
 * decorator (the `message_delta` input-token correction now lives here, since we parse the events).
 *
 * Maps the raw SSE events into own [ChatResponse]s:
 * - `thinking_delta` → a generation tagged `metadata["thinking"] = true` (real-time thinking stream,
 *   which spring-ai 2.0.1 could NOT deliver incrementally);
 * - `signature_delta` → a generation tagged `metadata["signature"]` so the loop stops the thinking timer;
 * - `text_delta` → a content generation;
 * - `input_json_delta` fragments accumulate per block index and are emitted as complete tool calls on
 *   `message_delta`;
 * - `message_start` captures id/model/input-tokens; `message_delta` carries the final (corrected)
 *   usage + stop reason.
 * Tool execution is NOT performed here — the ReAct loop runs the parsed tool calls.
 */
internal class AnthropicStreamingChatModel(
    private val client: AnthropicClient,
    private val clientAsync: AnthropicClientAsync,
    private val defaultOptions: AnthropicChatOptions
) : ChatModel {

    override val options: ChatOptions get() = defaultOptions

    override fun call(prompt: Prompt): ChatResponse {
        // Text-only aggregation by design: every current call() consumer (memory flush, internal
        // llm service, risk checker) asks for plain text. tool_use/thinking blocks are NOT mapped
        // here — the agent loop always goes through stream().
        val options = resolveOptions(prompt)
        val request = createRequest(prompt, options)
        val response = client.messages()
            .create(request, RequestOptions.builder().timeout(Duration.ofSeconds(options.timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS)).build())
        val text = StringBuilder()
        response.content().forEach { block ->
            if (block.isText()) text.append(block.asText().text())
        }
        val usage = response.usage()
        val metadata = ChatResponseMetadata(
            id = response.id(),
            model = response.model().asString(),
            usage = Usage(
                promptTokens = usage.inputTokens().toInt(),
                completionTokens = usage.outputTokens().toInt(),
                totalTokens = (usage.inputTokens() + usage.outputTokens()).toInt(),
                cacheReadInputTokens = usage.cacheReadInputTokens().orElse(null),
                cacheWriteInputTokens = usage.cacheCreationInputTokens().orElse(null)
            )
        )
        val finishReason = response.stopReason().map { normalizeStopReason(it.toString()) }.orElse(null)
        return ChatResponse(
            listOf(Generation(AssistantMessage(content = text.toString()), ChatGenerationMetadata(finishReason = finishReason))),
            metadata
        )
    }

    override fun stream(prompt: Prompt): Flux<ChatResponse> = Flux.defer {
        val options = resolveOptions(prompt)
        val request = createRequest(prompt, options)
        val requestOptions = RequestOptions.builder()
            .timeout(Duration.ofSeconds(options.timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS))
            .build()

        val state = StreamState()
        Flux.create { sink ->
            val response: AsyncStreamResponse<RawMessageStreamEvent> =
                clientAsync.messages().createStreaming(request, requestOptions)
            sink.onDispose { response.close() }
            response.subscribe { event -> mapEvent(event, state).forEach { sink.next(it) } }
                .onCompleteFuture()
                .whenComplete { _, throwable ->
                    if (throwable != null) sink.error(throwable.toAiException()) else sink.complete()
                }
        }
    }

    /**
     * A portable [ChatOptions] (e.g. the no-model-config fallback in the agent loop, or an
     * [com.easy.easyai.api.llm.DefaultChatOptions] from InternalLlmService) carries only common
     * fields; they are merged onto the factory defaults instead of the whole options object being
     * discarded — dropping it would silently lose the turn's tool callbacks and sampling knobs.
     */
    internal fun resolveOptions(prompt: Prompt): AnthropicChatOptions {
        val provided = prompt.options ?: return defaultOptions
        if (provided is AnthropicChatOptions) return provided
        return defaultOptions.copy(
            model = provided.model ?: defaultOptions.model,
            temperature = provided.temperature ?: defaultOptions.temperature,
            maxTokens = provided.maxTokens ?: defaultOptions.maxTokens,
            toolCallbacks = provided.toolCallbacks.ifEmpty { defaultOptions.toolCallbacks },
            outputSchema = provided.outputSchema ?: defaultOptions.outputSchema
        )
    }

    // internal for same-module unit tests: event -> own-ChatResponse mapping over per-stream state.
    internal fun mapEvent(event: RawMessageStreamEvent, state: StreamState): List<ChatResponse> {
        // ---- message_start: id / model / input tokens ----
        event.messageStart().ifPresent { start ->
            val message = start.message()
            state.id = message.id()
            state.model = message.model().asString()
            state.inputTokens = message.usage().inputTokens()
        }

        // ---- content_block_start: begin tracking a tool_use block ----
        event.contentBlockStart().ifPresent { start ->
            val block = start.contentBlock()
            block.toolUse().ifPresent { toolUse ->
                state.toolIds[start.index()] = toolUse.id()
                state.toolNames[start.index()] = toolUse.name()
                state.toolArgs[start.index()] = StringBuilder()
            }
        }

        // ---- content_block_delta: text / thinking / signature / input_json ----
        event.contentBlockDelta().ifPresent { deltaEvent ->
            val delta = deltaEvent.delta()
            if (delta.text().isPresent()) {
                state.pending.add(ChatResponse(listOf(Generation(AssistantMessage(content = delta.asText().text()))), state.metadata()))
            } else if (delta.isThinking()) {
                state.pending.add(
                    ChatResponse(
                        listOf(Generation(AssistantMessage(content = delta.asThinking().thinking(), metadata = mapOf(THINKING_METADATA_KEY to true)))),
                        state.metadata()
                    )
                )
            } else if (delta.isSignature()) {
                state.pending.add(
                    ChatResponse(
                        listOf(Generation(AssistantMessage(content = "", metadata = mapOf(SIGNATURE_METADATA_KEY to delta.asSignature().signature())))),
                        state.metadata()
                    )
                )
            } else if (delta.inputJson().isPresent) {
                val sb = state.toolArgs[deltaEvent.index()] ?: StringBuilder().also { state.toolArgs[deltaEvent.index()] = it }
                sb.append(delta.asInputJson().partialJson())
            }
        }

        // ---- content_block_stop: finalize a tool call ----
        event.contentBlockStop().ifPresent { stop ->
            val args = state.toolArgs.remove(stop.index())
            val id = state.toolIds.remove(stop.index())
            val name = state.toolNames.remove(stop.index())
            if (id != null && name != null) {
                state.completedToolCalls.add(AssistantMessage.ToolCall(id, "function", name, args?.toString().orEmpty()))
            }
        }

        // ---- message_delta: stop reason + corrected usage → emit final tool calls ----
        event.messageDelta().ifPresent { deltaEvent ->
            val stopReason = deltaEvent.delta().stopReason().map { normalizeStopReason(it.toString()) }.orElse(null)
            val usage = deltaEvent.usage()
            val inputTokens = maxOf(state.inputTokens, usage.inputTokens().orElse(0L))
            val outputTokens = usage.outputTokens()
            val metadata = ChatResponseMetadata(
                id = state.id,
                model = state.model,
                usage = Usage(
                    promptTokens = inputTokens.toInt(),
                    completionTokens = outputTokens.toInt(),
                    totalTokens = (inputTokens + outputTokens).toInt(),
                    nativeUsage = usage,
                    cacheReadInputTokens = usage.cacheReadInputTokens().orElse(null),
                    cacheWriteInputTokens = usage.cacheCreationInputTokens().orElse(null)
                )
            )
            state.metadata = metadata
            state.pending.add(
                ChatResponse(
                    listOf(
                        Generation(
                            AssistantMessage(content = "", toolCalls = state.completedToolCalls.toList()),
                            ChatGenerationMetadata(finishReason = stopReason)
                        )
                    ),
                    metadata
                )
            )
            // Consume the emitted calls: a gateway that repeats message_delta frames (e.g. rolling
            // usage updates) must not re-emit the same tool-use set.
            state.completedToolCalls.clear()
        }

        val out = state.pending.toList()
        state.pending.clear()
        return out
    }

    internal class StreamState {
        var id: String = ""
        var model: String = ""
        var inputTokens: Long = 0
        var metadata: ChatResponseMetadata? = null
        val toolIds = HashMap<Long, String>()
        val toolNames = HashMap<Long, String>()
        val toolArgs = HashMap<Long, StringBuilder>()
        val completedToolCalls = ArrayList<AssistantMessage.ToolCall>()
        val pending = ArrayList<ChatResponse>()
        fun metadata(): ChatResponseMetadata =
            ChatResponseMetadata(id = id, model = model, usage = Usage())
    }

    private fun createRequest(prompt: Prompt, options: AnthropicChatOptions): MessageCreateParams {
        val builder = MessageCreateParams.builder()
        options.model?.let { builder.model(it) }
        builder.maxTokens((options.maxTokens ?: DEFAULT_MAX_TOKENS).toLong())
        options.temperature?.let { builder.temperature(it) }
        if (options.stopSequences.isNotEmpty()) builder.stopSequences(options.stopSequences)

        // System messages collapse into the top-level system field.
        val systemText = prompt.instructions.filterIsInstance<SystemMessage>().joinToString("\n\n") { it.content }
        if (systemText.isNotEmpty()) builder.system(systemText)

        prompt.instructions.filter { it !is SystemMessage }.forEach { addMessage(builder, it) }

        // Extended thinking vs effort are mutually exclusive on Anthropic-compatible gateways.
        if (options.thinkingBudget != null) {
            builder.thinking(ThinkingConfigParam.ofEnabled(ThinkingConfigEnabled.builder().budgetTokens(options.thinkingBudget).build()))
            builder.maxTokens(maxOf(options.thinkingBudget + MIN_OUTPUT_RESERVE, (options.maxTokens ?: DEFAULT_MAX_TOKENS).toLong()))
        } else {
            builder.thinking(ThinkingConfigParam.ofDisabled(ThinkingConfigDisabled.builder().build()))
            options.effort?.let { builder.outputConfig(OutputConfig.builder().effort(effortOf(it)).build()) }
        }

        val tools = options.toolCallbacks.map { toAnthropicTool(it.name, it.description, it.inputSchema) }
        if (tools.isNotEmpty()) builder.tools(tools)
        return builder.build()
    }

    private fun addMessage(builder: MessageCreateParams.Builder, message: Message) {
        when (message) {
            is UserMessage ->
                if (message.media.isEmpty()) builder.addUserMessage(message.content)
                else {
                    val blocks = ArrayList<ContentBlockParam>()
                    if (message.content.isNotEmpty()) blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(message.content).build()))
                    message.media.forEach { blocks.add(toImageBlock(it)) }
                    builder.addUserMessageOfBlockParams(blocks)
                }
            is AssistantMessage -> {
                val blocks = assistantHistoryBlocks(message)
                if (blocks.isEmpty()) builder.addAssistantMessage("") else builder.addAssistantMessageOfBlockParams(blocks)
            }
            is ToolResponseMessage -> {
                val blocks = message.responses.map { response ->
                    ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder().toolUseId(response.id).content(response.responseData).build()
                    )
                }
                builder.addUserMessageOfBlockParams(blocks)
            }
            is SystemMessage -> Unit
        }
    }

    /**
     * Assistant-history → request blocks. Anthropic requires thinking blocks to lead the turn and
     * rejects unsigned ones; a block missing its signature (partial/aborted turn) is dropped
     * rather than sent. The redacted branch is a placeholder: the streaming side never captures
     * `redacted_thinking` blocks yet, so `ThinkingContent.redacted` is always false today.
     */
    internal fun assistantHistoryBlocks(message: AssistantMessage): List<ContentBlockParam> {
        val blocks = ArrayList<ContentBlockParam>()
        message.thinkingBlocks.forEach { tb ->
            val signature = tb.signature
            when {
                signature == null -> Unit
                tb.redacted -> blocks.add(
                    ContentBlockParam.ofRedactedThinking(RedactedThinkingBlockParam.builder().data(signature).build())
                )
                else -> blocks.add(
                    ContentBlockParam.ofThinking(
                        ThinkingBlockParam.builder().thinking(tb.text).signature(signature).build()
                    )
                )
            }
        }
        val assistantText = message.content
        if (!assistantText.isNullOrEmpty()) blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(assistantText).build()))
        message.toolCalls.forEach { tc ->
            blocks.add(ContentBlockParam.ofToolUse(toToolUseBlock(tc)))
        }
        return blocks
    }

    private fun toToolUseBlock(toolCall: AssistantMessage.ToolCall): ToolUseBlockParam {
        val inputBuilder = ToolUseBlockParam.Input.builder()
        if (toolCall.arguments.isNotEmpty()) {
            parseObject(toolCall.arguments).forEach { (k, v) -> inputBuilder.putAdditionalProperty(k, JsonValue.from(v)) }
        }
        return ToolUseBlockParam.builder().id(toolCall.id).name(toolCall.name).input(inputBuilder.build()).build()
    }

    private fun toImageBlock(media: Media): ContentBlockParam {
        val source = when (val src = media.source) {
            is MediaSource.Url -> ImageBlockParam.Source.ofUrl(UrlImageSource.builder().url(src.uri.toString()).build())
            is MediaSource.Bytes -> ImageBlockParam.Source.ofBase64(
                Base64ImageSource.builder()
                    .data(Base64.getEncoder().encodeToString(src.data))
                    .mediaType(imageMediaType(media.mimeType))
                    .build()
            )
        }
        return ContentBlockParam.ofImage(ImageBlockParam.builder().source(source).build())
    }

    private fun imageMediaType(mimeType: String): Base64ImageSource.MediaType = when (mimeType.substringAfter('/').lowercase()) {
        "jpeg", "jpg" -> Base64ImageSource.MediaType.IMAGE_JPEG
        "gif" -> Base64ImageSource.MediaType.IMAGE_GIF
        "webp" -> Base64ImageSource.MediaType.IMAGE_WEBP
        else -> Base64ImageSource.MediaType.IMAGE_PNG
    }

    internal fun toAnthropicTool(name: String, description: String, inputSchema: String): ToolUnion {
        val propertiesBuilder = Tool.InputSchema.Properties.builder()
        val schemaMap = if (inputSchema.isNotEmpty()) parseObject(inputSchema) else emptyMap()
        (schemaMap["properties"] as? Map<*, *>)?.forEach { (key, value) ->
            propertiesBuilder.putAdditionalProperty(key.toString(), JsonValue.from(value))
        }
        val inputSchemaBuilder = Tool.InputSchema.builder().properties(propertiesBuilder.build())
        (schemaMap["required"] as? List<*>)?.forEach { inputSchemaBuilder.addRequired(it.toString()) }
        // Forward every other top-level key so `$defs` (the `$ref` targets the schema generator
        // hoists for shared/recursive types, and the convention most MCP servers author with) and
        // `additionalProperties: false` reach the wire instead of being silently dropped.
        schemaMap.forEach { (key, value) ->
            if (value != null && key !in SCHEMA_KEYS_HANDLED_SEPARATELY) {
                inputSchemaBuilder.putAdditionalProperty(key, JsonValue.from(value))
            }
        }
        return ToolUnion.ofTool(
            Tool.builder().name(name).description(description).inputSchema(inputSchemaBuilder.build()).build()
        )
    }

    /** Maps Anthropic stop reasons onto the loop's finish-reason vocabulary. */
    private fun normalizeStopReason(raw: String): String = when (raw.lowercase()) {
        "tool_use" -> "tool_calls"
        "max_tokens" -> "length"
        "stop_sequence" -> "stop"
        else -> raw.lowercase()
    }

    private fun effortOf(effort: String): OutputConfig.Effort = when (effort.lowercase()) {
        "low" -> OutputConfig.Effort.LOW
        "medium" -> OutputConfig.Effort.MEDIUM
        "xhigh" -> OutputConfig.Effort.XHIGH
        "max" -> OutputConfig.Effort.MAX
        else -> OutputConfig.Effort.HIGH
    }

    private fun parseObject(json: String): Map<String, Any?> =
        objectMapper.readValue(json, STRING_OBJECT_MAP)

    private fun Throwable.toAiException(): Throwable {
        val service = (this as? AnthropicServiceException) ?: (this.cause as? AnthropicServiceException) ?: return this
        val status = service.statusCode()
        val detail = "$status: ${service.message}"
        return if (status == 429 || status >= 500) TransientAiException(detail, this) else NonTransientAiException(detail, this)
    }

    companion object {
        private const val THINKING_METADATA_KEY = "thinking"
        private const val SIGNATURE_METADATA_KEY = "signature"
        private const val DEFAULT_MAX_TOKENS = 8192
        private const val MIN_OUTPUT_RESERVE = 1024L
        private const val DEFAULT_TIMEOUT_SECONDS = 600L
        private val objectMapper: ObjectMapper = JsonMapper.builder().build()
        private val STRING_OBJECT_MAP = object : TypeReference<Map<String, Any?>>() {}

        /**
         * Top-level tool-schema keys never forwarded verbatim: `properties`/`required` have typed
         * builder setters, `type` is pinned to `object` by the SDK, and `$schema` is not part of
         * Anthropic's documented `input_schema`.
         */
        private val SCHEMA_KEYS_HANDLED_SEPARATELY = setOf("properties", "required", "type", "\$schema")
    }
}
