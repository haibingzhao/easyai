package com.easy.easyai.autoconfigure.openai

import com.openai.client.OpenAIClient
import com.openai.client.OpenAIClientAsync
import com.openai.core.JsonField
import com.openai.core.http.AsyncStreamResponse
import com.openai.models.chat.completions.ChatCompletionChunk
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.services.async.ChatServiceAsync
import com.openai.services.async.chat.ChatCompletionServiceAsync
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.StreamClosingOpenAiChatModel
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [StreamClosingOpenAiChatModel], the workaround decorator for
 * spring-projects/spring-ai#6654 (AsyncStreamResponse never closed on Reactor cancellation,
 * leaking the underlying OkHttp connection).
 */
class StreamClosingOpenAiChatModelTest {

    private lateinit var streamResponse: AsyncStreamResponse<ChatCompletionChunk>
    private lateinit var handlerSlot: CapturingSlot<AsyncStreamResponse.Handler<ChatCompletionChunk>>
    private lateinit var completeFuture: CompletableFuture<Void?>
    private lateinit var model: StreamClosingOpenAiChatModel

    @BeforeEach
    fun setUp() {
        streamResponse = mockk()
        completeFuture = CompletableFuture()
        handlerSlot = slot()

        every { streamResponse.subscribe(capture(handlerSlot)) } returns streamResponse
        every { streamResponse.onCompleteFuture() } returns completeFuture

        val completions = mockk<ChatCompletionServiceAsync>()
        every { completions.createStreaming(any<ChatCompletionCreateParams>()) } returns streamResponse
        val chat = mockk<ChatServiceAsync>()
        every { chat.completions() } returns completions
        val asyncClient = mockk<OpenAIClientAsync>()
        every { asyncClient.chat() } returns chat

        val delegate = OpenAiChatModel.builder()
            .openAiClient(mockk<OpenAIClient>())
            .openAiClientAsync(asyncClient)
            .options(OpenAiChatOptions.builder().model("test-model").build())
            .build()

        model = StreamClosingOpenAiChatModel(delegate, asyncClient)
    }

    private fun prompt(): Prompt =
        Prompt(listOf(UserMessage("hello")), OpenAiChatOptions.builder().model("test-model").build())

    private fun textChunk(text: String): ChatCompletionChunk =
        ChatCompletionChunk.builder()
            .id("chunk-1")
            .created(System.currentTimeMillis() / 1000)
            .model("test-model")
            .addChoice(
                ChatCompletionChunk.Choice.builder()
                    .index(0)
                    .finishReason(ChatCompletionChunk.Choice.FinishReason.STOP)
                    .delta(ChatCompletionChunk.Choice.Delta.builder().content(text).build())
                    .build()
            )
            .build()

    @Nested
    inner class Cancellation {

        @Test
        fun `cancelling the subscription closes the underlying SDK stream`() {
            val disposable = model.stream(prompt()).subscribe()

            verify(exactly = 1) { streamResponse.subscribe(any()) }

            disposable.dispose()

            verify(exactly = 1) { streamResponse.close() }
        }
    }

    @Nested
    inner class NormalStreaming {

        @Test
        fun `emitted chunks are mapped to ChatResponse and completion terminates the stream`() {
            val received = CopyOnWriteArrayList<ChatResponse>()
            val completed = CountDownLatch(1)

            model.stream(prompt()).subscribe(
                { received.add(it) },
                { completed.countDown() },
                { completed.countDown() }
            )

            handlerSlot.captured.onNext(textChunk("Hello "))
            handlerSlot.captured.onNext(textChunk("world"))
            completeFuture.complete(null)

            assertTrue(completed.await(5, TimeUnit.SECONDS), "stream should complete")
            assertEquals(2, received.size)
            assertEquals("Hello ", received[0].results[0].output.text)
            assertEquals("world", received[1].results[0].output.text)
            // onDispose also fires on normal completion; close() must be idempotent-safe here
            verify(exactly = 1) { streamResponse.close() }
        }

        @Test
        fun `SDK stream failure is propagated as an error`() {
            val completed = CountDownLatch(1)
            val errors = CopyOnWriteArrayList<Throwable>()

            model.stream(prompt()).subscribe(
                { },
                { error ->
                    errors.add(error)
                    completed.countDown()
                },
                { completed.countDown() }
            )

            val failure = RuntimeException("endpoint exploded")
            completeFuture.completeExceptionally(failure)

            assertTrue(completed.await(5, TimeUnit.SECONDS), "stream should terminate")
            assertEquals(1, errors.size)
            verify(exactly = 1) { streamResponse.close() }
        }
    }

    @Nested
    inner class ToolCallStreaming {

        private fun toolCallChunk(
            finishReason: ChatCompletionChunk.Choice.FinishReason?,
            toolCalls: List<ChatCompletionChunk.Choice.Delta.ToolCall>,
        ): ChatCompletionChunk =
            ChatCompletionChunk.builder()
                .id("chunk-tool")
                .created(System.currentTimeMillis() / 1000)
                .model("test-model")
                .addChoice(
                    ChatCompletionChunk.Choice.builder()
                        .index(0)
                        // Non-final streaming chunks carry an unknown/empty finish reason.
                        .finishReason(finishReason ?: ChatCompletionChunk.Choice.FinishReason.of(""))
                        .delta(
                            ChatCompletionChunk.Choice.Delta.builder()
                                .toolCalls(toolCalls)
                                .build()
                        )
                        .build()
                )
                .build()

        private fun delta(
            index: Long? = null,
            // The SDK builder enforces a present index; simulate providers that emit a
            // JSON-null index by injecting a JsonNull field (asKnown()/asNumber() → empty).
            nullIndex: Boolean = false,
            id: String? = null,
            name: String? = null,
            arguments: String? = null
        ): ChatCompletionChunk.Choice.Delta.ToolCall {
            val builder = ChatCompletionChunk.Choice.Delta.ToolCall.builder()
            index?.let { builder.index(it) }
            if (nullIndex) builder.index(JsonField.ofNullable(null))
            id?.let { builder.id(it) }
            if (name != null || arguments != null) {
                val fn = ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder()
                name?.let { fn.name(it) }
                arguments?.let { fn.arguments(it) }
                builder.function(fn.build())
            }
            return builder.build()
        }

        /**
         * Feed [chunks] into the stream and flatten the merged tool calls of every emitted
         * ChatResponse. Relies on bufferUntil emitting exactly ONE aggregated response per
         * tool-call group (progressive emission would double-count here); assert on the
         * received-response count instead if that pipeline behavior ever changes.
         */
        private fun collectToolCalls(chunks: List<ChatCompletionChunk>): List<AssistantMessage.ToolCall> {
            val received = CopyOnWriteArrayList<ChatResponse>()
            val errors = CopyOnWriteArrayList<Throwable>()
            val completed = CountDownLatch(1)

            model.stream(prompt()).subscribe(
                { received.add(it) },
                { error ->
                    errors.add(error)
                    completed.countDown()
                },
                { completed.countDown() }
            )

            chunks.forEach { handlerSlot.captured.onNext(it) }
            completeFuture.complete(null)

            assertTrue(completed.await(5, TimeUnit.SECONDS), "stream should terminate")
            assertTrue(errors.isEmpty(), "stream should not error: $errors")
            return received.flatMap { r -> r.results[0].output.toolCalls }
        }

        @Test
        fun `tool call delta without arguments does not crash the stream`() {
            // Some OpenAI-compatible providers send the first tool-call delta with only
            // id + function name (arguments absent) and terminate with
            // finish_reason=tool_calls before any arguments chunk arrives.
            val nameOnly = ChatCompletionChunk.Choice.Delta.ToolCall.builder()
                .index(0)
                .id("call-1")
                .type(ChatCompletionChunk.Choice.Delta.ToolCall.Type.FUNCTION)
                .function(
                    ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder()
                        .name("some_tool")
                        .build()
                )
                .build()
            val withArgs = ChatCompletionChunk.Choice.Delta.ToolCall.builder()
                .index(0)
                .function(
                    ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder()
                        .arguments("{\"a\":1}")
                        .build()
                )
                .build()

            val received = CopyOnWriteArrayList<ChatResponse>()
            val errors = CopyOnWriteArrayList<Throwable>()
            val completed = CountDownLatch(1)

            model.stream(prompt()).subscribe(
                { received.add(it) },
                { error ->
                    errors.add(error)
                    completed.countDown()
                },
                { completed.countDown() }
            )

            handlerSlot.captured.onNext(toolCallChunk(null, listOf(nameOnly)))
            handlerSlot.captured.onNext(toolCallChunk(null, listOf(withArgs)))
            // Terminal chunk: finish_reason=tool_calls with an empty delta (no tool calls).
            handlerSlot.captured.onNext(
                toolCallChunk(ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS, emptyList())
            )
            completeFuture.complete(null)

            assertTrue(completed.await(5, TimeUnit.SECONDS), "stream should terminate")
            assertTrue(errors.isEmpty(), "stream should not error: $errors")
            val toolCalls = received.flatMap { r -> r.results[0].output.toolCalls }
            assertEquals(1, toolCalls.size)
            assertEquals("some_tool", toolCalls[0].name())
            assertEquals("{\"a\":1}", toolCalls[0].arguments())
        }

        @Test
        fun `qwen-style blank-id continuation deltas merge into a single complete tool call`() {
            // qwen/DashScope send continuation deltas with id:"" (empty string) plus index;
            // an id().isPresent-based merge would treat every fragment as a new tool call.
            val toolCalls = collectToolCalls(
                listOf(
                    toolCallChunk(null, listOf(delta(index = 0, id = "call_abc", name = "calc", arguments = ""))),
                    toolCallChunk(null, listOf(delta(index = 0, id = "", arguments = "{\"script\": \"now()"))),
                    toolCallChunk(null, listOf(delta(index = 0, id = "", arguments = ".toString()\"}"))),
                    toolCallChunk(ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS, emptyList())
                )
            )

            assertEquals(1, toolCalls.size)
            assertEquals("call_abc", toolCalls[0].id)
            assertEquals("calc", toolCalls[0].name())
            assertEquals("{\"script\": \"now().toString()\"}", toolCalls[0].arguments())
        }

        @Test
        fun `multiple sequential tool calls with blank ids and index merge into one call each`() {
            val toolCalls = collectToolCalls(
                listOf(
                    toolCallChunk(null, listOf(delta(index = 0, id = "call_a", name = "trading__global_news", arguments = ""))),
                    toolCallChunk(null, listOf(delta(index = 0, id = "", arguments = "{\"limit\": 10}"))),
                    toolCallChunk(null, listOf(delta(index = 1, id = "call_b", name = "calc", arguments = ""))),
                    toolCallChunk(null, listOf(delta(index = 1, id = "", arguments = "{\"script\": \"1+1\"}"))),
                    toolCallChunk(ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS, emptyList())
                )
            )

            assertEquals(2, toolCalls.size)
            assertEquals("call_a", toolCalls[0].id)
            assertEquals("trading__global_news", toolCalls[0].name())
            assertEquals("{\"limit\": 10}", toolCalls[0].arguments())
            assertEquals("call_b", toolCalls[1].id)
            assertEquals("calc", toolCalls[1].name())
            assertEquals("{\"script\": \"1+1\"}", toolCalls[1].arguments())
        }

        @Test
        fun `replay of captured qwen fragmentation merges every call into complete arguments`() {
            // Replay of the production capture (qwen3.8-flash): the first delta of each call
            // carries id+name with empty arguments; every continuation carries id:"" and only
            // an arguments fragment; index is unusable. Previously this produced one fragmented
            // tool call per delta (152 in the captured turn).
            val fragments = listOf(
                delta(nullIndex = true, id = "call_55f", name = "calc", arguments = ""),
                delta(nullIndex = true, id = "", arguments = "{\"script\": \"ZonedDateTime"),
                delta(nullIndex = true, id = "", arguments = ".now().toString()"),
                delta(nullIndex = true, id = "", arguments = "\"}"),
                delta(nullIndex = true, id = "call_d6b", name = "trading__global_news", arguments = ""),
                delta(nullIndex = true, id = "", arguments = "{\"from_date\": \"2026-08-18"),
                delta(nullIndex = true, id = "", arguments = "\", \"limit\": 10}"),
                delta(nullIndex = true, id = "call_c0a", name = "trading__global_news", arguments = ""),
                delta(nullIndex = true, id = "", arguments = "{\"from_date\": \"2026-08-27"),
                delta(nullIndex = true, id = "", arguments = "\", \"limit\": 5}")
            )
            val toolCalls = collectToolCalls(
                fragments.map { toolCallChunk(null, listOf(it)) } +
                    toolCallChunk(ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS, emptyList())
            )

            assertEquals(3, toolCalls.size)
            assertEquals("call_55f", toolCalls[0].id)
            assertEquals("calc", toolCalls[0].name())
            assertEquals("{\"script\": \"ZonedDateTime.now().toString()\"}", toolCalls[0].arguments())
            assertEquals("call_d6b", toolCalls[1].id)
            assertEquals("trading__global_news", toolCalls[1].name())
            assertEquals("{\"from_date\": \"2026-08-18\", \"limit\": 10}", toolCalls[1].arguments())
            assertEquals("call_c0a", toolCalls[2].id)
            assertEquals("trading__global_news", toolCalls[2].name())
            assertEquals("{\"from_date\": \"2026-08-27\", \"limit\": 5}", toolCalls[2].arguments())
        }

        @Test
        fun `name-only deltas without any id or index open new calls and synthesize ids`() {
            // Covers rule 3 (blank everything but a fresh name while the last slot is
            // already named) and chunkToChatCompletion's synthesized-id fallback for
            // providers that never emit ids at all.
            val toolCalls = collectToolCalls(
                listOf(
                    toolCallChunk(null, listOf(delta(nullIndex = true, name = "first_tool", arguments = "{\"a\":"))),
                    toolCallChunk(null, listOf(delta(nullIndex = true, arguments = "1}"))),
                    toolCallChunk(null, listOf(delta(nullIndex = true, name = "second_tool", arguments = "{\"b\":2}"))),
                    toolCallChunk(ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS, emptyList())
                )
            )

            assertEquals(2, toolCalls.size)
            assertTrue(toolCalls.all { it.id.startsWith("call_") }, "ids must be synthesized: $toolCalls")
            assertEquals("first_tool", toolCalls[0].name())
            assertEquals("{\"a\":1}", toolCalls[0].arguments())
            assertEquals("second_tool", toolCalls[1].name())
            assertEquals("{\"b\":2}", toolCalls[1].arguments())
        }

        @Test
        fun `fresh id arriving at an already-used index starts a new tool call`() {
            // Guards rule 1: providers that restart indexing from 0 per sequential call,
            // each announcing a fresh non-blank id, must not be fused into the previous slot.
            val toolCalls = collectToolCalls(
                listOf(
                    toolCallChunk(null, listOf(delta(index = 0, id = "call_a", name = "f1", arguments = ""))),
                    toolCallChunk(null, listOf(delta(index = 0, id = "", arguments = "{\"x\":1}"))),
                    toolCallChunk(null, listOf(delta(index = 0, id = "call_b", name = "f2", arguments = "{}"))),
                    toolCallChunk(ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS, emptyList())
                )
            )

            assertEquals(2, toolCalls.size)
            assertEquals("call_a", toolCalls[0].id)
            assertEquals("f1", toolCalls[0].name())
            assertEquals("{\"x\":1}", toolCalls[0].arguments())
            assertEquals("call_b", toolCalls[1].id)
            assertEquals("f2", toolCalls[1].name())
            assertEquals("{}", toolCalls[1].arguments())
        }

        @Test
        fun `garbage tool call indices degrade to heuristic routing instead of crashing`() {
            // Negative indices previously hit slots[-n] (IndexOutOfBoundsException) and
            // oversized ones padded unbounded placeholder slots; both must now behave as
            // "no index" and route by id.
            val negativeIndex = ChatCompletionChunk.Choice.Delta.ToolCall.builder()
                .index(-3)
                .id("call_neg")
                .function(ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder().name("f1").arguments("{}").build())
                .build()
            val oversizedIndex = ChatCompletionChunk.Choice.Delta.ToolCall.builder()
                .index(1_000_000_000L)
                .id("call_huge")
                .function(ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder().name("f2").arguments("{}").build())
                .build()

            val toolCalls = collectToolCalls(
                listOf(
                    toolCallChunk(null, listOf(negativeIndex)),
                    toolCallChunk(null, listOf(oversizedIndex)),
                    toolCallChunk(ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS, emptyList())
                )
            )

            assertEquals(2, toolCalls.size)
            assertEquals("call_neg", toolCalls[0].id)
            assertEquals("f1", toolCalls[0].name())
            assertEquals("call_huge", toolCalls[1].id)
            assertEquals("f2", toolCalls[1].name())
        }

        @Test
        fun `provider without index falls back to appending fragments to the last tool call`() {
            // Minimal providers omit both index and id on continuations; the merger must
            // fall back to appending argument fragments to the most recent tool call.
            val toolCalls = collectToolCalls(
                listOf(
                    toolCallChunk(null, listOf(delta(nullIndex = true, id = "call_x", name = "some_tool", arguments = "{\"a\":"))),
                    toolCallChunk(null, listOf(delta(nullIndex = true, arguments = "1}"))),
                    toolCallChunk(ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS, emptyList())
                )
            )

            assertEquals(1, toolCalls.size)
            assertEquals("call_x", toolCalls[0].id)
            assertEquals("some_tool", toolCalls[0].name())
            assertEquals("{\"a\":1}", toolCalls[0].arguments())
        }
    }
}
