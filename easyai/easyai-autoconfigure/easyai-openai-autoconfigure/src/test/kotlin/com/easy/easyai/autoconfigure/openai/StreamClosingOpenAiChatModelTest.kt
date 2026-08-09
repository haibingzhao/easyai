package com.easy.easyai.autoconfigure.openai

import com.openai.client.OpenAIClient
import com.openai.client.OpenAIClientAsync
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
}
