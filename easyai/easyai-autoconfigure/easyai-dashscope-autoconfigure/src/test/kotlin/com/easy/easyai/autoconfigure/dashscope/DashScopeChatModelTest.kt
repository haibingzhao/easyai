package com.easy.easyai.autoconfigure.dashscope

import com.alibaba.dashscope.aigc.generation.Generation
import com.alibaba.dashscope.aigc.generation.GenerationParam
import com.alibaba.dashscope.aigc.generation.GenerationResult
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult
import com.alibaba.dashscope.common.Status
import com.alibaba.dashscope.exception.ApiException
import com.alibaba.dashscope.exception.NoApiKeyException
import com.alibaba.dashscope.utils.JsonUtils
import io.reactivex.Flowable
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.retry.NonTransientAiException
import org.springframework.ai.retry.TransientAiException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for [DashScopeChatModel] against a mocked SDK client, pinned to what the ReAct loop needs:
 * thinking as metadata-tagged text, complete tool-call snapshots, usage on the closing frame, and
 * provider failures surfacing through the stream instead of throwing at subscription time.
 */
internal class DashScopeChatModelTest {

    private val generation = mockk<Generation>()
    private val options = DashScopeChatOptions.builder()
        .model("qwen3-max")
        .apiKey("sk-test")
        .resultFormat("message")
        .build()
    private val prompt = Prompt(listOf(UserMessage("你好")), options)

    private fun model(): DashScopeChatModel = DashScopeChatModel(generation, null, options, vision = false)

    private fun result(json: String): GenerationResult = JsonUtils.fromJson(json, GenerationResult::class.java)

    /** Streams the given raw SSE frames and returns every ChatResponse the adapter emitted. */
    private fun stream(vararg frames: String): List<ChatResponse> {
        every { generation.streamCall(any()) } returns
            Flowable.fromArray(*frames.map(::result).toTypedArray())
        return model().stream(prompt).collectList().block() ?: emptyList()
    }

    @Nested
    inner class `stream emission contract` {

        @Test
        fun `tags reasoning text so the loop routes it to the thinking stream`() {
            val responses = stream(
                """{"output":{"choices":[{"message":{"role":"assistant","content":"","reasoning_content":"先"}}]}}""",
                """{"output":{"choices":[{"message":{"role":"assistant","content":"","reasoning_content":"想"}}]}}"""
            )

            val results = responses.flatMap { it.results }
            assertEquals(listOf("先", "想"), results.map { it.output.text })
            assertTrue(results.all { it.output.metadata.containsKey(DashScopeChatModel.THINKING_METADATA_KEY) })
        }

        @Test
        fun `keeps answer text free of the thinking marker`() {
            val results = stream(
                """{"output":{"choices":[{"message":{"role":"assistant","content":"你好","reasoning_content":"先"}}]}}"""
            ).single().results

            assertEquals("先", results[0].output.text)
            assertEquals("你好", results[1].output.text)
            assertFalse(results[1].output.metadata.containsKey(DashScopeChatModel.THINKING_METADATA_KEY))
        }

        @Test
        fun `repeats the tool call snapshot on every frame and closes with the finish reason`() {
            val responses = stream(
                """{"output":{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{\"ci"}}]}}]}}""",
                """{"output":{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"ty\":\"Hang"}}]}}]}}""",
                """{"output":{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"zhou\"}"}}]}}]},"usage":{"input_tokens":11,"output_tokens":5,"total_tokens":16}}"""
            )

            // The loop harvests calls from the last content-bearing chunk, so every frame has to
            // carry the whole accumulated argument string.
            assertEquals(
                listOf("{\"ci", "{\"city\":\"Hang", "{\"city\":\"Hangzhou\"}"),
                responses.map { it.results.single().output.toolCalls.single().arguments() }
            )
            assertEquals("tool_calls", responses.last().results.single().metadata.finishReason)
            assertEquals(11, responses.last().metadata.usage.promptTokens)
        }

        @Test
        fun `emits an empty closing chunk that still carries usage`() {
            val responses = stream(
                """{"output":{"choices":[{"message":{"role":"assistant","content":"答案"}}]}}""",
                """{"output":{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":""}}]},"usage":{"input_tokens":7,"output_tokens":3,"total_tokens":10}}"""
            )

            val closing = responses.last()
            assertEquals("", closing.results.single().output.text)
            assertEquals("stop", closing.results.single().metadata.finishReason)
            assertEquals(7, closing.metadata.usage.promptTokens)
            assertEquals(3, closing.metadata.usage.completionTokens)
        }

        @Test
        fun `maps cache token details onto spring ai usage`() {
            val usage = stream(
                """{"output":{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}]},"usage":{"input_tokens":100,"output_tokens":20,"total_tokens":120,"prompt_tokens_details":{"cached_tokens":80,"cache_creation_input_tokens":10}}}"""
            ).single().metadata.usage

            assertEquals(80L, usage.cacheReadInputTokens)
            assertEquals(10L, usage.cacheWriteInputTokens)
        }
    }

    @Nested
    inner class `failure handling` {

        private fun streamFailure(statusCode: Int, code: String, message: String): Throwable {
            every { generation.streamCall(any()) } throws
                ApiException(Status.builder().statusCode(statusCode).code(code).message(message).build())
            val failure = runCatching { model().stream(prompt).collectList().block() }.exceptionOrNull()
            return requireNotNull(failure) { "Expected the stream to fail" }
        }

        @Test
        fun `maps throttling onto Spring AI transient types so the loop retries it`() {
            val failure = streamFailure(429, "Throttling.RateQuota", "Requests have been too quick")

            assertTrue(failure is TransientAiException)
            assertTrue(failure.message!!.contains("429"))
            // The breaker must keep seeing 429 as a per-request condition, not an outage.
            assertTrue(failure.message!!.contains("Throttling.RateQuota"))
            assertTrue(failure.cause is ApiException)
        }

        @Test
        fun `maps a 5xx onto the transient type so it counts toward the breaker`() {
            assertTrue(streamFailure(503, "ServiceUnavailable", "server is busy") is TransientAiException)
        }

        @Test
        fun `maps a transport failure onto the transient type`() {
            assertTrue(streamFailure(-1, "NetworkError", "SocketTimeoutException: Read timed out") is TransientAiException)
        }

        @Test
        fun `keeps the provider reason in the message so overflow and moderation stay detectable`() {
            val overflow = streamFailure(400, "InvalidParameter", "Range of input length should be [1, 30000]")
            assertTrue(overflow is NonTransientAiException)
            assertTrue(overflow.message!!.lowercase().contains("range of input length"))

            val filtered = streamFailure(400, "DataInspectionFailed", "Input data may contain inappropriate content")
            assertTrue(filtered.message!!.contains("DataInspectionFailed"))
        }

        @Test
        fun `does not retry a client error such as an invalid key`() {
            assertTrue(streamFailure(401, "InvalidApiKey", "No access key is given") is NonTransientAiException)
        }

        @Test
        fun `surfaces an in-band error frame through the same classification`() {
            every { generation.streamCall(any()) } returns Flowable.just(
                result("""{"status_code":400,"code":"InvalidParameter","message":"Range of input length","output":{"choices":[]}}""")
            )

            val failure = assertFailsWith<NonTransientAiException> {
                model().stream(prompt).collectList().block()
            }

            assertTrue(failure.message!!.contains("400"))
            assertTrue(failure.message!!.contains("InvalidParameter"))
        }

        @Test
        fun `passes SDK argument failures through untranslated`() {
            every { generation.streamCall(any()) } throws NoApiKeyException()

            val failure = requireNotNull(
                runCatching { model().stream(prompt).collectList().block() }.exceptionOrNull()
            ) { "Expected the stream to fail" }

            // Reactor wraps checked throwables one level down; what matters is that the adapter did
            // not relabel a missing key as a retryable or non-retryable provider status.
            assertTrue(failure.cause is NoApiKeyException)
            assertFalse(failure is TransientAiException)
            assertFalse(failure is NonTransientAiException)
        }
    }

    @Nested
    inner class `request construction` {

        @Test
        fun `keeps factory credentials when the caller passes portable options`() {
            val param = slot<GenerationParam>()
            every { generation.call(capture(param)) } returns
                result("""{"output":{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}]}}""")

            model().call(Prompt(listOf(UserMessage("你好")), ChatOptions.builder().temperature(0.1).build()))

            assertEquals("qwen3-max", param.captured.model)
            assertEquals("sk-test", param.captured.apiKey)
            assertEquals(0.1f, param.captured.temperature)
        }

        @Test
        fun `asks the multimodal client for vision models`() {
            val conversation = mockk<MultiModalConversation>()
            every { conversation.call(any()) } returns JsonUtils.fromJson(
                """{"output":{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":[{"text":"图里有猫"}]}}]}}""",
                MultiModalConversationResult::class.java
            )

            val response = DashScopeChatModel(null, conversation, options, vision = true).call(prompt)

            assertEquals("图里有猫", response.results.single().output.text)
            verify(exactly = 0) { generation.call(any()) }
            verify { conversation.call(any()) }
        }
    }
}
