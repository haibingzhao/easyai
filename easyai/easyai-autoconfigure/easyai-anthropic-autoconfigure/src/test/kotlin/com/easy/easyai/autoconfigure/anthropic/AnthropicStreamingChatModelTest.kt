package com.easy.easyai.autoconfigure.anthropic

import com.anthropic.models.messages.MessageDeltaUsage
import com.anthropic.models.messages.RawContentBlockDelta
import com.anthropic.models.messages.RawContentBlockDeltaEvent
import com.anthropic.models.messages.RawContentBlockStopEvent
import com.anthropic.models.messages.RawMessageDeltaEvent
import com.anthropic.models.messages.RawMessageStreamEvent
import com.anthropic.models.messages.SignatureDelta
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextDelta
import com.anthropic.models.messages.ThinkingDelta
import com.easy.easyai.api.llm.AssistantMessage
import com.easy.easyai.api.llm.ChatResponse
import com.easy.easyai.api.llm.DefaultChatOptions
import com.easy.easyai.api.llm.Prompt
import com.easy.easyai.api.llm.ToolCallback
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests the SSE-event → own-`ChatResponse` mapping of [AnthropicStreamingChatModel] by feeding real
 * `com.anthropic` [RawMessageStreamEvent]s through the (module-internal) `mapEvent`: text, thinking
 * streaming (the incremental fix spring-ai 2.0.1 could not do), signature, tool-call accumulation
 * across `input_json_delta` blocks, and the `message_delta` input-token usage correction that
 * replaces the old `UsageCorrectingAnthropicChatModel` decorator.
 */
class AnthropicStreamingChatModelTest {

    private val model = AnthropicStreamingChatModel(mockk(), mockk(), AnthropicChatOptions(model = "claude-test"))

    private fun deltaEvent(delta: RawContentBlockDelta): RawMessageStreamEvent =
        RawMessageStreamEvent.ofContentBlockDelta(
            RawContentBlockDeltaEvent.builder().index(0L).delta(delta).build()
        )

    private fun messageDelta(input: Optional<Long>, output: Long): RawMessageStreamEvent =
        RawMessageStreamEvent.ofMessageDelta(
            RawMessageDeltaEvent.builder()
                .delta(
                    RawMessageDeltaEvent.Delta.builder()
                        .stopReason(StopReason.MAX_TOKENS)
                        .stopSequence(Optional.empty())
                        .stopDetails(Optional.empty())
                        .build()
                )
                .usage(
                    MessageDeltaUsage.builder()
                        .inputTokens(input)
                        .outputTokens(output)
                        .cacheReadInputTokens(Optional.empty())
                        .cacheCreationInputTokens(Optional.empty())
                        .outputTokensDetails(Optional.empty())
                        .serverToolUse(Optional.empty())
                        .build()
                )
                .build()
        )

    private fun generationsOf(responses: List<ChatResponse>) = responses.flatMap { it.results }.map { it.output }

    @Nested
    inner class `content block deltas` {

        @Test
        fun `text delta maps to a content generation`() {
            val out = model.mapEvent(deltaEvent(RawContentBlockDelta.ofText(TextDelta.builder().text("Hello").build())), AnthropicStreamingChatModel.StreamState())
            val generation = generationsOf(out).single()
            assertEquals("Hello", generation.content)
            assertTrue(generation.metadata.isEmpty())
        }

        @Test
        fun `thinking delta is tagged as thinking metadata`() {
            val out = model.mapEvent(deltaEvent(RawContentBlockDelta.ofThinking(ThinkingDelta.builder().thinking("pondering").build())), AnthropicStreamingChatModel.StreamState())
            val generation = generationsOf(out).single()
            assertEquals("pondering", generation.content)
            assertEquals(true, generation.metadata["thinking"])
        }

        @Test
        fun `signature delta is tagged as signature metadata`() {
            val out = model.mapEvent(deltaEvent(RawContentBlockDelta.ofSignature(SignatureDelta.builder().signature("sig-1").build())), AnthropicStreamingChatModel.StreamState())
            val generation = generationsOf(out).single()
            assertEquals("sig-1", generation.metadata["signature"])
        }
    }

    @Nested
    inner class `message delta usage correction` {

        @Test
        fun `corrects prompt tokens up to the message_delta input count`() {
            // message_start reported 11501 but the gateway's true count arrives on message_delta.
            val state = AnthropicStreamingChatModel.StreamState().apply { inputTokens = 11501L }
            val out = model.mapEvent(messageDelta(Optional.of(83729L), 346L), state).single()
            assertEquals(83729, out.metadata.usage.promptTokens)
            assertEquals(346, out.metadata.usage.completionTokens)
        }

        @Test
        fun `keeps the larger message_start input when message_delta omits it`() {
            val state = AnthropicStreamingChatModel.StreamState().apply { inputTokens = 500L }
            val out = model.mapEvent(messageDelta(Optional.empty(), 30L), state).single()
            assertEquals(500, out.metadata.usage.promptTokens)
            assertEquals(30, out.metadata.usage.completionTokens)
        }

        @Test
        fun `emits the stop reason on the final generation`() {
            val state = AnthropicStreamingChatModel.StreamState().apply { inputTokens = 10L }
            val out = model.mapEvent(messageDelta(Optional.of(10L), 5L), state).single()
            assertTrue(!out.results.single().metadata.finishReason.isNullOrEmpty())
        }
    }

    @Nested
    inner class `tool use across blocks` {

        @Test
        fun `accumulates input_json fragments and emits the complete call on message_delta`() {
            // A content_block_start tool-use event would have registered id/name and an empty
            // argument buffer; simulate that state, then stream input_json deltas, then the block
            // stop (finalizes) and message_delta (emits).
            val state = AnthropicStreamingChatModel.StreamState().apply {
                toolIds[0L] = "toolu_1"
                toolNames[0L] = "read"
                toolArgs[0L] = StringBuilder("{\"path\":")
            }
            model.mapEvent(
                RawMessageStreamEvent.ofContentBlockDelta(
                    RawContentBlockDeltaEvent.builder().index(0L).inputJsonDelta("\"a.txt\"}").build()
                ),
                state
            )
            // Finalize the block.
            model.mapEvent(
                RawMessageStreamEvent.ofContentBlockStop(RawContentBlockStopEvent.builder().index(0L).build()),
                state
            )
            // Emit on message_delta.
            val generations = generationsOf(model.mapEvent(messageDelta(Optional.empty(), 5L), state))
            val calls = generations.flatMap { it.toolCalls }
            assertEquals(listOf(AssistantMessage.ToolCall("toolu_1", "function", "read", "{\"path\":\"a.txt\"}")), calls)
        }
    }

    @Nested
    inner class `assistant history blocks` {

        @Test
        fun `signed thinking leads the turn and survives to the request blocks`() {
            val blocks = model.assistantHistoryBlocks(
                AssistantMessage(
                    content = "answer",
                    toolCalls = listOf(AssistantMessage.ToolCall("toolu_9", "function", "bash", "{}")),
                    thinkingBlocks = listOf(AssistantMessage.ThinkingBlock("pondering", signature = "sig-1"))
                )
            )
            assertTrue(blocks[0].isThinking())
            assertEquals("pondering", blocks[0].asThinking().thinking())
            assertEquals("sig-1", blocks[0].asThinking().signature())
            assertTrue(blocks[1].isText())
            assertTrue(blocks[2].isToolUse())
        }

        @Test
        fun `unsigned thinking is dropped because the api would reject it`() {
            val blocks = model.assistantHistoryBlocks(
                AssistantMessage(
                    content = "answer",
                    thinkingBlocks = listOf(AssistantMessage.ThinkingBlock("partial reasoning"))
                )
            )
            assertEquals(1, blocks.size)
            assertTrue(blocks.single().isText())
        }

        @Test
        fun `redacted thinking becomes a redacted block carrying its opaque data`() {
            val blocks = model.assistantHistoryBlocks(
                AssistantMessage(
                    thinkingBlocks = listOf(AssistantMessage.ThinkingBlock("redacted", signature = "opaque-data", redacted = true))
                )
            )
            val block = blocks.single()
            assertTrue(block.isRedactedThinking())
            assertEquals("opaque-data", block.asRedactedThinking().data())
        }

        @Test
        fun `portable DefaultChatOptions merge onto factory defaults instead of being dropped`() {
            val prompt = Prompt(
                emptyList(),
                DefaultChatOptions(
                    temperature = 0.2,
                    maxTokens = 99,
                    toolCallbacks = listOf(ToolCallback("probe", "d", "{}"))
                )
            )

            val resolved = model.resolveOptions(prompt)

            assertEquals(0.2, resolved.temperature)
            assertEquals(99, resolved.maxTokens)
            assertEquals(listOf("probe"), resolved.toolCallbacks.map { it.name })
            assertEquals("claude-test", resolved.model, "unset fields keep the factory defaults")
        }

        @Test
        fun `a repeated message_delta does not re-emit the tool-call set`() {
            val state = AnthropicStreamingChatModel.StreamState().apply {
                completedToolCalls.add(AssistantMessage.ToolCall("toolu_1", "function", "read", "{}"))
            }
            val first = generationsOf(model.mapEvent(messageDelta(Optional.of(10L), 5L), state))
            val second = generationsOf(model.mapEvent(messageDelta(Optional.of(12L), 6L), state))

            assertEquals(1, first.single().toolCalls.size)
            assertTrue(second.single().toolCalls.isEmpty(), "the second finish frame must be call-free")
        }

        @Test
        fun `stop reasons are normalized to the loop vocabulary`() {
            val state = AnthropicStreamingChatModel.StreamState()
            val finishReason = model.mapEvent(messageDelta(Optional.empty(), 5L), state)
                .flatMap { it.results }.single().metadata.finishReason
            assertEquals("length", finishReason, "MAX_TOKENS maps to length")
        }
    }

    @Nested
    inner class `tool input schema` {

        private val schema = "{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\"," +
            "\"type\":\"object\"," +
            "\"properties\":{\"node\":{\"\$ref\":\"#/\$defs/Node\"}}," +
            "\"required\":[\"node\"]," +
            "\"additionalProperties\":false," +
            "\"\$defs\":{\"Node\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}}}"

        @Test
        fun `ref targets and the additionalProperties guard reach the wire`() {
            val inputSchema = model.toAnthropicTool("tree", "desc", schema).tool().orElseThrow().inputSchema()
            val forwarded = inputSchema._additionalProperties()

            val defs = forwarded["\$defs"]?.convert(Map::class.java)
            assertEquals(setOf("Node"), defs?.keys, "a \$ref without its \$defs target dangles")
            assertEquals(false, forwarded["additionalProperties"]?.convert(Boolean::class.javaObjectType))
        }

        @Test
        fun `typed keys keep their setters and undocumented ones stay out`() {
            val inputSchema = model.toAnthropicTool("tree", "desc", schema).tool().orElseThrow().inputSchema()

            assertEquals(listOf("node"), inputSchema.required().orElseThrow())
            assertTrue(inputSchema.properties().orElseThrow()._additionalProperties().containsKey("node"))
            val forwarded = inputSchema._additionalProperties().keys
            assertTrue("type" !in forwarded, "the SDK pins type=object")
            assertTrue("\$schema" !in forwarded, "not part of Anthropic's input_schema")
            assertTrue("properties" !in forwarded && "required" !in forwarded, "already set through typed setters")
        }
    }
}
