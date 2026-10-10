package com.easy.easyai.autoconfigure.dashscope

import com.alibaba.dashscope.aigc.generation.Generation as DashScopeGeneration
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation
import com.alibaba.dashscope.common.Status
import com.alibaba.dashscope.exception.ApiException
import com.easy.easyai.api.llm.AssistantMessage
import com.easy.easyai.api.llm.ChatGenerationMetadata
import com.easy.easyai.api.llm.ChatModel
import com.easy.easyai.api.llm.ChatOptions
import com.easy.easyai.api.llm.ChatResponse
import com.easy.easyai.api.llm.ChatResponseMetadata
import com.easy.easyai.api.llm.Generation
import com.easy.easyai.api.llm.NonTransientAiException
import com.easy.easyai.api.llm.Prompt
import com.easy.easyai.api.llm.ToolCallback
import com.easy.easyai.api.llm.TransientAiException
import com.easy.easyai.api.llm.Usage
import reactor.core.publisher.Flux

/**
 * easyai [ChatModel] over the DashScope (Aliyun Bailian) native protocol.
 *
 * The ReAct loop in easyai-core is the only tool executor: this adapter sends tool definitions and
 * reads tool calls back, and never invokes a [ToolCallback]. Streaming therefore has to satisfy the
 * loop's two contracts — thinking is non-empty text carrying a `thinking` metadata key, and tool
 * calls appear only as complete snapshots on a content-bearing chunk, so that the loop's last
 * content chunk holds finished arguments (see [DashScopeStreamingAccumulator]).
 *
 * @param vision selects the multimodal endpoint; decided at construction because [ChatOptions]
 *   cannot carry the model's declared capabilities at request time.
 */
internal class DashScopeChatModel(
    private val generation: DashScopeGeneration?,
    private val multiModalConversation: MultiModalConversation?,
    private val defaultOptions: DashScopeChatOptions,
    private val vision: Boolean
) : ChatModel {

    override val options: ChatOptions get() = defaultOptions

    override fun call(prompt: Prompt): ChatResponse {
        val options = resolveOptions(prompt)
        val specs = DashScopeMessageConverter.convert(prompt.instructions, vision)
        val tools = toolCallbacks(prompt, options)
        val chunk = try {
            if (vision) {
                val param = DashScopeRequestBuilder.multiModalParam(specs, tools, options, streaming = false)
                DashScopeResponseMapper.fromMultiModal(requireMultiModal().call(param))
            } else {
                val param = DashScopeRequestBuilder.textParam(specs, tools, options, streaming = false)
                DashScopeResponseMapper.fromGeneration(requireText().call(param))
            }
        } catch (e: ApiException) {
            throw e.toAiException()
        }
        return toResponses(chunk, options, DashScopeStreamingAccumulator()).first()
    }

    override fun stream(prompt: Prompt): Flux<ChatResponse> = Flux.defer {
        val options = resolveOptions(prompt)
        val specs = DashScopeMessageConverter.convert(prompt.instructions, vision)
        val tools = toolCallbacks(prompt, options)
        // A fresh accumulator per subscription so snapshots cannot leak across a retry.
        val accumulator = DashScopeStreamingAccumulator()
        val chunks: Flux<DashScopeChunk> = if (vision) {
            val param = DashScopeRequestBuilder.multiModalParam(specs, tools, options, streaming = true)
            Flux.from(requireMultiModal().streamCall(param)).map(DashScopeResponseMapper::fromMultiModal)
        } else {
            val param = DashScopeRequestBuilder.textParam(specs, tools, options, streaming = true)
            Flux.from(requireText().streamCall(param)).map(DashScopeResponseMapper::fromGeneration)
        }
        // Flux.defer turns a synchronous streamCall() throw into a stream error, so this single
        // seam covers both the subscribe-time and the mid-stream failure paths.
        chunks.concatMap { chunk -> Flux.fromIterable(toResponses(chunk, options, accumulator)) }
    }.onErrorResume { e -> Flux.error(e.toAiException()) }

    /**
     * Callers outside the agent loop pass portable [ChatOptions], which carry no DashScope
     * credentials, so their sampling knobs are merged onto the factory defaults instead of
     * replacing them.
     */
    private fun resolveOptions(prompt: Prompt): DashScopeChatOptions {
        val provided = prompt.options ?: return defaultOptions
        if (provided is DashScopeChatOptions) return provided
        val builder = DashScopeChatOptions.builderFrom(defaultOptions)
        provided.model?.let { builder.model(it) }
        provided.temperature?.let { builder.temperature(it) }
        provided.maxTokens?.let { builder.maxTokens(it) }
        if (provided.toolCallbacks.isNotEmpty()) builder.toolCallbacks(provided.toolCallbacks)
        provided.outputSchema?.let { builder.outputSchema(it) }
        return builder.build()
    }

    private fun toolCallbacks(prompt: Prompt, options: DashScopeChatOptions): List<ToolCallback> {
        val fromPrompt = prompt.options?.toolCallbacks.orEmpty()
        return if (fromPrompt.isNotEmpty()) fromPrompt else options.toolCallbacks
    }

    private fun toResponses(
        chunk: DashScopeChunk,
        options: DashScopeChatOptions,
        accumulator: DashScopeStreamingAccumulator
    ): List<ChatResponse> {
        chunk.error?.let { throw it.toStatusException(chunk.requestId) }
        val frame = accumulator.accept(chunk)
        val resultMetadata = ChatGenerationMetadata(finishReason = frame.finishReason)

        val results = mutableListOf<Generation>()
        frame.reasoningDelta?.takeIf { it.isNotEmpty() }?.let { reasoning ->
            results.add(
                Generation(
                    AssistantMessage(content = reasoning, metadata = mapOf(THINKING_METADATA_KEY to true)),
                    resultMetadata
                )
            )
        }
        frame.textDelta?.takeIf { it.isNotEmpty() }?.let { text ->
            results.add(Generation(AssistantMessage(content = text), resultMetadata))
        }
        if (frame.toolCalls.isNotEmpty()) {
            results.add(
                Generation(
                    AssistantMessage(
                        content = "",
                        toolCalls = frame.toolCalls.map { call ->
                            AssistantMessage.ToolCall(call.id, "function", call.name, call.arguments)
                        }
                    ),
                    resultMetadata
                )
            )
        }
        if (results.isEmpty()) {
            // Usage and finish reason still ride on this frame; the loop reads them from an
            // otherwise empty chunk.
            results.add(Generation(AssistantMessage(content = ""), resultMetadata))
        }

        val usage = frame.usage?.let { u ->
            // DashScope follows the OpenAI accounting: input_tokens already contains
            // prompt_tokens_details.cached_tokens, but the Usage contract wants the non-cached
            // count. cache_creation_input_tokens is left as reported — whether the provider folds
            // it into input_tokens is not documented and it is absent on the implicit-cache path.
            val promptTokens = (u.inputTokens - (u.cacheReadTokens ?: 0L)).coerceAtLeast(0L).toInt()
            Usage(
                promptTokens = promptTokens,
                completionTokens = u.outputTokens,
                totalTokens = promptTokens + u.outputTokens,
                nativeUsage = u,
                cacheReadInputTokens = u.cacheReadTokens,
                cacheWriteInputTokens = u.cacheWriteTokens
            )
        } ?: Usage()
        val metadata = ChatResponseMetadata(
            id = chunk.requestId ?: "",
            model = options.model ?: "",
            usage = usage
        )
        return listOf(ChatResponse(results, metadata))
    }

    private fun DashScopeError.toStatusException(requestId: String?): Throwable =
        ApiException(
            Status.builder()
                .statusCode(statusCode)
                .code(code ?: "")
                .message(message ?: "")
                .requestId(requestId ?: "")
                .build()
        ).toAiException()

    /**
     * The SDK throws its own [ApiException], which never reaches Spring AI's retry exception types,
     * so without this the agent loop would neither retry a Bailian 5xx/429 nor count it toward the
     * endpoint breaker. 429 and 5xx become [TransientAiException], other HTTP statuses
     * [NonTransientAiException], and a transport-level failure (negative status code, which the SDK
     * reports with the originating IOException as its cause) is transient too.
     *
     * The status code leads the message and the provider code/message/request id follow, because
     * [com.easy.easyai.core.agent.LlmErrorClassifier] detects context overflow and content-safety
     * rejection from the message text.
     */
    private fun Throwable.toAiException(): Throwable {
        val api = this as? ApiException ?: return this
        val status = api.status ?: return this
        val detail = "${status.statusCode}: code=${status.code} message=${status.message} requestId=${status.requestId}"
        return if (status.statusCode < 0 || status.statusCode == 429 || status.statusCode >= 500) {
            TransientAiException(detail, this)
        } else {
            NonTransientAiException(detail, this)
        }
    }

    private fun requireText(): DashScopeGeneration =
        requireNotNull(generation) { "DashScope text endpoint is not configured" }

    private fun requireMultiModal(): MultiModalConversation =
        requireNotNull(multiModalConversation) { "DashScope multimodal endpoint is not configured" }

    companion object {
        /** The agent loop routes non-empty text carrying this key into the thinking stream. */
        const val THINKING_METADATA_KEY = "thinking"
    }
}
