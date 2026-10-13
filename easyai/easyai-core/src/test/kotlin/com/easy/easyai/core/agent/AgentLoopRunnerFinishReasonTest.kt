package com.easy.easyai.core.agent

import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.StopReason
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import com.easy.easyai.api.llm.AssistantMessage as LlmAssistantMessage
import com.easy.easyai.api.llm.ChatGenerationMetadata
import com.easy.easyai.api.llm.ChatModel
import com.easy.easyai.api.llm.ChatResponse
import com.easy.easyai.api.llm.Generation
import com.easy.easyai.api.llm.Prompt
import com.easy.easyai.api.llm.UserMessage
import reactor.core.publisher.Flux
import kotlin.test.assertEquals

/**
 * Providers close a choice with a frame carrying `finish_reason`, then send a trailing
 * usage-only frame whose `choices` is empty and whose reason is null. The reason has to
 * survive that trailing frame, otherwise every non-tool turn collapses into STOP and a
 * truncated answer becomes indistinguishable from a deliberate one.
 */
class AgentLoopRunnerFinishReasonTest {

    private val modelConfig = ModelProviderConfig(
        id = "cfg-1",
        name = "qwen-gateway",
        protocol = Protocol.OPENAI,
        isCustom = true,
        baseUrl = "https://gateway.example.com",
        modelId = "test-model"
    )

    private fun frame(text: String, finishReason: String? = null) =
        ChatResponse(
            listOf(Generation(LlmAssistantMessage(content = text), ChatGenerationMetadata(finishReason = finishReason)))
        )

    private suspend fun buildMessage(vararg frames: ChatResponse): AssistantMessage {
        val chatModel = mockk<ChatModel>()
        every { chatModel.stream(any<Prompt>()) } returns Flux.fromIterable(frames.toList())

        val runner = AgentLoopRunner(
            AgentContext(agentId = "investment-analyst", modelConfig = modelConfig),
            chatModel,
            mockk<AgentService>(relaxed = true)
        )
        val transcript = mutableListOf<EasyAiMessage>()
        return runner.callLLMAndBuildResponse(transcript, Prompt(listOf(UserMessage("hi"))), "m1", 0) {}
    }

    @Nested
    inner class TrailingUsageFrame {

        @Test
        fun `length survives the usage-only frame that follows it`() = runBlocking {
            val message = buildMessage(frame("结论"), frame("", finishReason = "length"), frame(""))

            assertEquals(StopReason.LENGTH, message.stopReason)
        }

        @Test
        fun `stop survives the usage-only frame that follows it`() = runBlocking {
            val message = buildMessage(frame("结论"), frame("", finishReason = "stop"), frame(""))

            assertEquals(StopReason.STOP, message.stopReason)
        }

        @Test
        fun `missing reason anywhere still falls back to stop`() = runBlocking {
            val message = buildMessage(frame("结论"), frame(""))

            assertEquals(StopReason.STOP, message.stopReason)
        }
    }

    @Nested
    inner class ToolCallFrames {

        private fun toolCallFrame(id: String, name: String, arguments: String) = ChatResponse(
            listOf(
                Generation(
                    LlmAssistantMessage(
                        content = "",
                        toolCalls = listOf(LlmAssistantMessage.ToolCall(id, "function", name, arguments))
                    ),
                    ChatGenerationMetadata(finishReason = "tool_calls")
                )
            )
        )

        @Test
        fun `calls survive a text frame streamed after them`() = runBlocking {
            // The call frame is not the last content-bearing one: a gateway may still stream
            // trailing text, and reading the calls off the final chunk would lose them.
            val message = buildMessage(toolCallFrame("c1", "bash", "{\"command\":\"ls\"}"), frame("稍等"))

            assertEquals(StopReason.TOOL_USE, message.stopReason)
            assertEquals(listOf("c1"), message.toolCalls().map { it.id })
        }

        @Test
        fun `the finish reason rides the call frame, not the trailing text frame`() = runBlocking {
            val message = buildMessage(toolCallFrame("c1", "bash", "{}"), frame("", finishReason = "stop"))

            assertEquals(StopReason.TOOL_USE, message.stopReason)
        }
    }
}
