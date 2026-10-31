package com.easy.easyai.api.llm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import kotlin.test.assertFailsWith

/**
 * The contract behind `ChatModel.callAsync`: one folded response out of the delta stream, and a
 * cancelled caller that actually cancels the provider request (the subscription is disposed) rather
 * than just abandoning the answer.
 */
class ChatModelAsyncCallTest {

    private val toolCall = AssistantMessage.ToolCall("call_1", "function", "ls", "{}")

    /** A model whose only real path is `stream`; `call` must not be reached by `callAsync`. */
    private fun streamingModel(chunks: Flux<ChatResponse>): ChatModel = object : ChatModel {
        override val options: ChatOptions = DefaultChatOptions()

        override fun call(prompt: Prompt): ChatResponse = error("the blocking path must not be used")

        override fun stream(prompt: Prompt): Flux<ChatResponse> = chunks
    }

    private fun textChunk(text: String, reasoning: Boolean = false) = ChatResponse(
        listOf(
            Generation(
                AssistantMessage(content = text, metadata = if (reasoning) mapOf("thinking" to true) else emptyMap()),
                ChatGenerationMetadata()
            )
        ),
        ChatResponseMetadata(id = "resp", model = "test-model")
    )

    @Test
    fun `the delta stream is folded into one response`() = runBlocking {
        val finalChunk = ChatResponse(
            listOf(
                Generation(
                    AssistantMessage(content = "", toolCalls = listOf(toolCall)),
                    ChatGenerationMetadata(finishReason = "tool_calls")
                )
            ),
            ChatResponseMetadata(id = "resp", model = "test-model", usage = Usage(promptTokens = 7, completionTokens = 3, totalTokens = 10))
        )

        val response = streamingModel(
            Flux.just(textChunk("先查"), textChunk("一下", reasoning = true), textChunk("目录。"), finalChunk)
        ).callAsync(Prompt("hi"))

        assertEquals("先查目录。", response.result?.output?.content)
        assertEquals(listOf(toolCall), response.result?.output?.toolCalls)
        assertEquals("tool_calls", response.result?.metadata?.finishReason)
        assertEquals(10, response.metadata.usage.totalTokens)
        assertEquals("resp", response.metadata.id)
        // Reasoning is accumulated out of band, never merged into the visible answer.
        assertEquals("一下", response.result?.output?.metadata?.get(ChatResponseAggregator.REASONING_CONTENT_KEY))
    }

    @Test
    fun `cancelling the caller disposes the provider stream`() = runBlocking {
        val disposed = CompletableDeferred<Unit>()
        val model = streamingModel(Flux.never<ChatResponse>().doOnCancel { disposed.complete(Unit) })

        assertFailsWith<TimeoutCancellationException> {
            withTimeout(100) { model.callAsync(Prompt("hi")) }
        }
        // The point of the whole exercise: the request is gone, not merely unanswered.
        withTimeout(1_000) { disposed.await() }
    }

    @Test
    fun `an empty stream folds to an empty response`() {
        assertTrue(ChatResponseAggregator.aggregate(emptyList()).results.isEmpty())
    }

    @Test
    fun `the last usage, finish reason and tool-call snapshot win`() {
        val first = ChatResponse(
            listOf(Generation(AssistantMessage(content = "a", toolCalls = listOf(toolCall)), ChatGenerationMetadata("stop"))),
            ChatResponseMetadata(id = "one", model = "m1", usage = Usage(totalTokens = 5))
        )
        val expectedSecond = toolCall.copy(id = "call_2")
        val second = ChatResponse(
            listOf(Generation(AssistantMessage(content = "b", toolCalls = listOf(expectedSecond)), ChatGenerationMetadata("tool_calls"))),
            ChatResponseMetadata(id = "two", model = "m2", usage = Usage(totalTokens = 9))
        )

        val folded = ChatResponseAggregator.aggregate(listOf(first, second))

        assertEquals("ab", folded.result?.output?.content)
        assertEquals(listOf(expectedSecond), folded.result?.output?.toolCalls)
        assertEquals("tool_calls", folded.result?.metadata?.finishReason)
        assertEquals(9, folded.metadata.usage.totalTokens)
        assertEquals("two", folded.metadata.id)
        assertEquals("m2", folded.metadata.model)
    }
}
