package com.easy.easyai.autoconfigure.anthropic

import com.anthropic.models.messages.MessageDeltaUsage
import org.slf4j.LoggerFactory
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux

/**
 * A [ChatModel] decorator around [AnthropicChatModel] that corrects under-reported
 * input token counts in streaming responses.
 *
 * Works around a Spring AI 2.0.0 limitation: [AnthropicChatModel] builds the streaming
 * `DefaultUsage.promptTokens` from the `message_start` event and only reads
 * `output_tokens` from `message_delta`. Some Anthropic-protocol gateways (e.g. Aliyun
 * Bailian) report a partial input count on `message_start` and put the final input
 * count in `message_delta.input_tokens` — a field the official Anthropic API never
 * sends (always `JsonMissing`), so Spring AI ignores it. Without this fix, easyai
 * records only a fraction (observed ~1/7) of the actual input tokens.
 *
 * This decorator rewrites the usage on chunks whose native payload is a
 * [MessageDeltaUsage] carrying an `input_tokens` value larger than Spring AI's
 * reported count. On the official Anthropic API the field is absent and chunks pass
 * through untouched. Non-streaming [call] responses already carry the full count in
 * `MessageUsage` and are delegated as-is.
 */
internal class UsageCorrectingAnthropicChatModel(
    private val delegate: AnthropicChatModel
) : ChatModel {

    override fun call(prompt: Prompt): ChatResponse = delegate.call(prompt)

    override fun getOptions(): AnthropicChatOptions = delegate.options

    override fun stream(prompt: Prompt): Flux<ChatResponse> =
        delegate.stream(prompt).map { correctUsage(it) }

    private fun correctUsage(chunk: ChatResponse): ChatResponse {
        val usage = chunk.metadata.usage
        val nativeUsage = usage.nativeUsage as? MessageDeltaUsage ?: return chunk
        // orElse(0L) never returns null at runtime, but the SDK's Optional<Long> surfaces
        // as a platform type — the explicit fallback keeps the comparison non-null.
        val nativeInputTokens = nativeUsage.inputTokens().orElse(0L) ?: 0L
        if (nativeInputTokens <= usage.promptTokens) {
            return chunk // official Anthropic API (field absent) or count already accurate
        }

        logger.debug("Correcting gateway usage: promptTokens {} -> {}", usage.promptTokens, nativeInputTokens)
        // All fields sourced from the native message_delta payload (cache fields are also
        // read from message_delta by Spring AI, but reading them directly keeps this
        // decorator self-contained).
        val correctedUsage = DefaultUsage(
            nativeInputTokens.toInt(),
            usage.completionTokens,
            nativeInputTokens.toInt() + usage.completionTokens,
            nativeUsage,
            nativeUsage.cacheReadInputTokens().orElse(null),
            nativeUsage.cacheCreationInputTokens().orElse(null)
        )
        return ChatResponse(chunk.results, rebuildMetadata(chunk.metadata, correctedUsage))
    }

    /** Rebuild metadata preserving id/model/rateLimit/promptMetadata and all key-value entries. */
    private fun rebuildMetadata(metadata: ChatResponseMetadata, usage: DefaultUsage): ChatResponseMetadata {
        val builder = ChatResponseMetadata.builder()
            .id(metadata.id)
            .model(metadata.model)
            .rateLimit(metadata.rateLimit)
            .promptMetadata(metadata.promptMetadata)
            .usage(usage)
        for (entry in metadata.entrySet()) {
            builder.keyValue(entry.key, entry.value)
        }
        return builder.build()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(UsageCorrectingAnthropicChatModel::class.java)
    }
}
