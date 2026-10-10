package com.easy.easyai.autoconfigure.openai

import com.easy.easyai.api.llm.AssistantMessage
import com.easy.easyai.api.llm.DefaultChatOptions
import com.easy.easyai.api.llm.Prompt
import com.easy.easyai.api.llm.ToolCallback
import com.easy.easyai.api.llm.ToolResponseMessage
import com.openai.core.JsonValue
import com.openai.models.chat.completions.ChatCompletionChunk
import com.openai.models.chat.completions.ChatCompletionChunk.Choice
import com.openai.models.chat.completions.ChatCompletionChunk.Choice.Delta
import com.openai.models.completions.CompletionUsage
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests the chunk → own-`ChatResponse` mapping of [OpenAiStreamingChatModel], feeding real
 * `com.openai` [ChatCompletionChunk] objects through the (module-internal) `mapChunk`:
 * text deltas, reasoning→`thinking` tagging, fragmented tool-call accumulation across chunks,
 * and usage accounting.
 */
class OpenAiStreamingChatModelTest {

    private val model = OpenAiStreamingChatModel(mockk(), OpenAiChatOptions(model = "gpt-test"))

    private fun chunk(vararg choices: Choice, usage: CompletionUsage? = null): ChatCompletionChunk {
        val builder = ChatCompletionChunk.builder().id("chatcmpl-1").model("gpt-test")
            .created(1_700_000_000L)
        builder.choices(choices.toList())
        usage?.let { builder.usage(it) }
        return builder.build()
    }

    private fun textDelta(text: String): Choice =
        Choice.builder().index(0L).delta(Delta.builder().content(text).build())
            .finishReason(Optional.empty()).build()

    private fun reasoningDelta(text: String): Choice =
        Choice.builder().index(0L)
            .delta(Delta.builder().putAdditionalProperty("reasoning_content", JsonValue.from(text)).build())
            .finishReason(Optional.empty()).build()

    @Nested
    inner class `content and reasoning` {

        @Test
        fun `text delta maps to a content generation`() {
            val responses = model.mapChunk(chunk(textDelta("Hello")), HashMap())
            val generation = responses.single().results.single()
            assertEquals("Hello", generation.output.content)
            assertTrue(generation.output.metadata.isEmpty())
        }

        @Test
        fun `reasoning delta is tagged as thinking metadata`() {
            val responses = model.mapChunk(chunk(reasoningDelta("pondering")), HashMap())
            val generation = responses.single().results.single()
            assertEquals("pondering", generation.output.content)
            assertEquals(true, generation.output.metadata["thinking"])
        }

        @Test
        fun `a finish reason sharing the chunk with content is kept`() {
            val choice = Choice.builder().index(0L)
                .delta(Delta.builder().content("done").build())
                .finishReason(Choice.FinishReason.LENGTH)
                .build()

            val generation = model.mapChunk(chunk(choice), HashMap()).single().results.single()

            assertEquals("done", generation.output.content)
            assertEquals("length", generation.metadata.finishReason, "the loop reads results[0].metadata")
        }

        @Test
        fun `a finish reason lands on the first generation of a multi-part chunk`() {
            val choice = Choice.builder().index(0L)
                .delta(
                    Delta.builder().content("done")
                        .putAdditionalProperty("reasoning_content", JsonValue.from("pondering")).build()
                )
                .finishReason(Choice.FinishReason.STOP)
                .build()

            val results = model.mapChunk(chunk(choice), HashMap()).single().results

            assertEquals(2, results.size, "reasoning and content are separate generations")
            assertEquals("stop", results[0].metadata.finishReason)
        }
    }

    @Nested
    inner class `tool call accumulation` {

        private fun toolCallFragment(index: Long, id: String?, name: String?, args: String?): Delta.ToolCall {
            val builder = Delta.ToolCall.builder().index(index)
            id?.let { builder.id(it) }
            val fn = Delta.ToolCall.Function.builder()
            name?.let { fn.name(it) }
            args?.let { fn.arguments(it) }
            builder.function(fn.build())
            return builder.build()
        }

        @Test
        fun `accumulates fragmented arguments and emits complete call on finish`() {
            val acc = HashMap<String, OpenAiStreamingChatModel.ToolCallAccumulator>()

            val c1 = Choice.builder().index(0L).delta(
                Delta.builder().toolCalls(listOf(toolCallFragment(0L, "call_9", "read", "{\"path\":"))).build()
            ).finishReason(Optional.empty()).build()
            val c2 = Choice.builder().index(0L).delta(
                Delta.builder().toolCalls(listOf(toolCallFragment(0L, null, null, "\"a.txt\"}"))).build()
            ).finishReason(Optional.empty()).build()
            val c3 = Choice.builder().index(0L)
                .delta(Delta.builder().build())
                .finishReason(Choice.FinishReason.TOOL_CALLS)
                .build()

            // Fragments before finish carry no completed tool calls yet.
            assertTrue(model.mapChunk(chunk(c1), acc).flatMap { it.results }.all { it.output.toolCalls.isEmpty() })
            assertTrue(model.mapChunk(chunk(c2), acc).flatMap { it.results }.all { it.output.toolCalls.isEmpty() })

            // The finish chunk emits the fully-assembled tool call.
            val calls = model.mapChunk(chunk(c3), acc).flatMap { it.results }.flatMap { it.output.toolCalls }
            assertEquals(listOf(AssistantMessage.ToolCall("call_9", "function", "read", "{\"path\":\"a.txt\"}")), calls)

            // A gateway repeating the finish frame must not re-emit the same call set.
            val repeats = model.mapChunk(chunk(c3), acc).flatMap { it.results }.flatMap { it.output.toolCalls }
            assertTrue(repeats.isEmpty(), "accumulators are consumed on emission")
        }
    }

    @Nested
    inner class `usage accounting` {

        @Test
        fun `carries usage on the metadata`() {
            val usage = CompletionUsage.builder()
                .promptTokens(100)
                .completionTokens(20)
                .totalTokens(120)
                .build()
            val responses = model.mapChunk(chunk(textDelta("hi"), usage = usage), HashMap())
            val u = responses.single().metadata.usage
            assertEquals(100, u.promptTokens)
            assertEquals(20, u.completionTokens)
            assertEquals(120, u.totalTokens)
        }

        @Test
        fun `subtracts cached tokens from the inclusive prompt count`() {
            // OpenAI's prompt_tokens covers cached_tokens; the Usage contract wants them split so
            // callers summing promptTokens + cacheReadInputTokens count the prompt exactly once.
            // The compaction estimator is such a caller: an inclusive promptTokens made it size the
            // context ~2x too large on cache hits and compact at ~40% of the real window.
            val usage = CompletionUsage.builder()
                .promptTokens(100)
                .completionTokens(20)
                .totalTokens(120)
                .promptTokensDetails(
                    CompletionUsage.PromptTokensDetails.builder().cachedTokens(80L).build()
                )
                .build()

            val u = model.mapChunk(chunk(textDelta("hi"), usage = usage), HashMap())
                .single().metadata.usage

            assertEquals(20, u.promptTokens)
            assertEquals(80L, u.cacheReadInputTokens)
            assertEquals(40, u.totalTokens)
        }

        @Test
        fun `a fully cached prompt never yields a negative count`() {
            val usage = CompletionUsage.builder()
                .promptTokens(80)
                .completionTokens(5)
                .totalTokens(85)
                .promptTokensDetails(
                    CompletionUsage.PromptTokensDetails.builder().cachedTokens(80L).build()
                )
                .build()

            val u = model.mapChunk(chunk(textDelta("hi"), usage = usage), HashMap())
                .single().metadata.usage

            assertEquals(0, u.promptTokens)
            assertEquals(80L, u.cacheReadInputTokens)
            assertEquals(5, u.totalTokens)
        }
    }

    @Nested
    inner class `request message params` {

        @Test
        fun `a parallel tool response message becomes one tool param per call id`() {
            val message = ToolResponseMessage(
                responses = listOf(
                    ToolResponseMessage.ToolResponse("call_a", "read", "A"),
                    ToolResponseMessage.ToolResponse("call_b", "read", "B")
                )
            )

            val params = model.toMessageParams(message)

            assertEquals(2, params.size)
            assertEquals("call_a", params[0].asTool().toolCallId())
            assertEquals("A", params[0].asTool().content().asText())
            assertEquals("call_b", params[1].asTool().toolCallId())
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
            assertEquals("gpt-test", resolved.model, "unset fields keep the factory defaults")
        }
    }
}
