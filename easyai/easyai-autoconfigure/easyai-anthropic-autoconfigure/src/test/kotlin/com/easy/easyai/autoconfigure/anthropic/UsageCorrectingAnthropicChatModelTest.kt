package com.easy.easyai.autoconfigure.anthropic

import com.anthropic.models.messages.MessageDeltaUsage
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.chat.messages.AssistantMessage
import reactor.core.publisher.Flux
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Tests for [UsageCorrectingAnthropicChatModel], the decorator that fixes under-reported
 * input token counts from Anthropic-protocol gateways (e.g. Aliyun Bailian) that put the
 * final input count in message_delta.input_tokens instead of message_start.
 */
class UsageCorrectingAnthropicChatModelTest {

    private val delegate = mockk<AnthropicChatModel>()
    private val model = UsageCorrectingAnthropicChatModel(delegate)

    private fun prompt(): Prompt = Prompt(listOf(UserMessage("hello")))

    private fun usageChunk(promptTokens: Int, completionTokens: Int, nativeUsage: Any?): ChatResponse {
        val usage = DefaultUsage(promptTokens, completionTokens, promptTokens + completionTokens, nativeUsage)
        val metadata = ChatResponseMetadata.builder()
            .id("msg-1")
            .model("test-model")
            .usage(usage)
            .keyValue("citations", listOf("citation-1"))
            .build()
        val generation = Generation(AssistantMessage.builder().content("hi").build())
        return ChatResponse(listOf(generation), metadata)
    }

    private fun stream(chunk: ChatResponse): List<ChatResponse> {
        every { delegate.stream(any<Prompt>()) } returns Flux.just(chunk)
        return model.stream(prompt()).collectList().block()!!
    }

    /** SDK builder requires every field set; Optional.empty() simulates a JsonMissing field. */
    private fun messageDeltaUsage(
        inputTokens: Optional<Long>,
        outputTokens: Long,
        cacheRead: Optional<Long> = Optional.empty(),
        cacheCreation: Optional<Long> = Optional.empty()
    ): MessageDeltaUsage = MessageDeltaUsage.builder()
        .inputTokens(inputTokens)
        .outputTokens(outputTokens)
        .cacheReadInputTokens(cacheRead)
        .cacheCreationInputTokens(cacheCreation)
        .outputTokensDetails(Optional.empty())
        .serverToolUse(Optional.empty())
        .build()

    @Nested
    inner class `Gateway with input_tokens on message_delta` {

        @Test
        fun `corrects promptTokens from message_delta input_tokens`() {
            // Real-world observation: Aliyun Bailian reports 11501 on message_start but
            // the true input count 83729 on message_delta.
            val native = messageDeltaUsage(Optional.of(83729L), outputTokens = 346L)
            val result = stream(usageChunk(promptTokens = 11501, completionTokens = 346, nativeUsage = native))

            val corrected = result.single().metadata.usage
            assertEquals(83729, corrected.promptTokens)
            assertEquals(346, corrected.completionTokens)
            assertEquals(84075, corrected.totalTokens)
        }

        @Test
        fun `preserves metadata entries and response content`() {
            val native = messageDeltaUsage(Optional.of(83729L), outputTokens = 346L)
            val result = stream(usageChunk(promptTokens = 11501, completionTokens = 346, nativeUsage = native))

            val chunk = result.single()
            assertEquals("msg-1", chunk.metadata.id)
            assertEquals("test-model", chunk.metadata.model)
            assertEquals(listOf("citation-1"), chunk.metadata.get<List<String>>("citations"))
            assertEquals("hi", chunk.results.first().output.text)
        }

        @Test
        fun `keeps cache token counts`() {
            val native = messageDeltaUsage(
                Optional.of(83729L),
                outputTokens = 346L,
                cacheRead = Optional.of(1000L),
                cacheCreation = Optional.of(200L)
            )
            val usage = DefaultUsage(11501, 346, 11847, native, 1000L, 200L)
            val metadata = ChatResponseMetadata.builder().usage(usage).build()
            every { delegate.stream(any<Prompt>()) } returns
                Flux.just(ChatResponse(listOf(Generation(AssistantMessage.builder().content("hi").build())), metadata))

            val corrected = model.stream(prompt()).collectList().block()!!.single().metadata.usage
            assertEquals(83729, corrected.promptTokens)
            assertEquals(1000L, corrected.cacheReadInputTokens)
            assertEquals(200L, corrected.cacheWriteInputTokens)
        }
    }

    @Nested
    inner class `Official Anthropic API` {

        @Test
        fun `passes through when message_delta has no input_tokens`() {
            // Official API never sends input_tokens on message_delta — Optional.empty().
            val native = messageDeltaUsage(Optional.empty(), outputTokens = 346L)
            val chunk = usageChunk(promptTokens = 11501, completionTokens = 346, nativeUsage = native)

            assertSame(chunk, stream(chunk).single())
        }
    }

    @Nested
    inner class `Non-message_delta chunks` {

        @Test
        fun `passes through content chunks without native usage`() {
            val chunk = usageChunk(promptTokens = 0, completionTokens = 0, nativeUsage = null)

            assertSame(chunk, stream(chunk).single())
        }

        @Test
        fun `passes through when native input_tokens is not larger`() {
            val native = messageDeltaUsage(Optional.of(11501L), outputTokens = 346L)
            val chunk = usageChunk(promptTokens = 11501, completionTokens = 346, nativeUsage = native)

            assertSame(chunk, stream(chunk).single())
        }
    }
}
