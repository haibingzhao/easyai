package com.easy.easyai.autoconfigure.dashscope

import com.alibaba.dashscope.aigc.generation.Generation as DashScopeGeneration
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation
import com.alibaba.dashscope.common.Status
import com.alibaba.dashscope.exception.ApiException
import org.springframework.ai.chat.metadata.ChatGenerationMetadata
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.retry.NonTransientAiException
import org.springframework.ai.retry.TransientAiException
import org.springframework.ai.tool.ToolCallback
import reactor.core.publisher.Flux

/**
 * Spring AI [ChatModel] over the DashScope (Aliyun Bailian) native protocol.
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

    override fun getOptions(): ChatOptions = defaultOptions

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
            throw e.toSpringAiException()
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
    }.onErrorResume { e -> Flux.error(e.toSpringAiException()) }

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
        provided.topP?.let { builder.topP(it) }
        provided.topK?.let { builder.topK(it) }
        val providedTools = (provided as? ToolCallingChatOptions)?.toolCallbacks.orEmpty()
        if (providedTools.isNotEmpty()) builder.toolCallbacks(providedTools)
        return builder.build()
    }

    private fun toolCallbacks(prompt: Prompt, options: DashScopeChatOptions): List<ToolCallback> {
        val fromPrompt = (prompt.options as? ToolCallingChatOptions)?.toolCallbacks.orEmpty()
        return if (fromPrompt.isNotEmpty()) fromPrompt else options.toolCallbacks.orEmpty()
    }

    private fun toResponses(
        chunk: DashScopeChunk,
        options: DashScopeChatOptions,
        accumulator: DashScopeStreamingAccumulator
    ): List<ChatResponse> {
        chunk.error?.let { throw it.toStatusException(chunk.requestId) }
        val frame = accumulator.accept(chunk)
        val resultMetadata = ChatGenerationMetadata.builder().let { builder ->
            frame.finishReason?.let { builder.finishReason(it) }
            builder.build()
        }

        val results = mutableListOf<Generation>()
        frame.reasoningDelta?.takeIf { it.isNotEmpty() }?.let { reasoning ->
            results.add(
                Generation(
                    AssistantMessage.builder()
                        .content(reasoning)
                        .properties(mapOf(THINKING_METADATA_KEY to true))
                        .build(),
                    resultMetadata
                )
            )
        }
        frame.textDelta?.takeIf { it.isNotEmpty() }?.let { text ->
            results.add(Generation(AssistantMessage(text), resultMetadata))
        }
        if (frame.toolCalls.isNotEmpty()) {
            results.add(
                Generation(
                    AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                            frame.toolCalls.map { call ->
                                AssistantMessage.ToolCall(call.id, "function", call.name, call.arguments)
                            }
                        )
                        .build(),
                    resultMetadata
                )
            )
        }
        if (results.isEmpty()) {
            // Usage and finish reason still ride on this frame; the loop reads them from an
            // otherwise empty chunk.
            results.add(Generation(AssistantMessage(""), resultMetadata))
        }

        val metadata = ChatResponseMetadata.builder()
        options.model?.let { metadata.model(it) }
        chunk.requestId?.let { metadata.id(it) }
        frame.usage?.let { usage ->
            metadata.usage(
                DefaultUsage(
                    usage.inputTokens,
                    usage.outputTokens,
                    usage.totalTokens,
                    usage,
                    usage.cacheReadTokens,
                    usage.cacheWriteTokens
                )
            )
        }
        return listOf(ChatResponse(results, metadata.build()))
    }

    private fun DashScopeError.toStatusException(requestId: String?): Throwable =
        ApiException(
            Status.builder()
                .statusCode(statusCode)
                .code(code ?: "")
                .message(message ?: "")
                .requestId(requestId ?: "")
                .build()
        ).toSpringAiException()

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
    private fun Throwable.toSpringAiException(): Throwable {
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
