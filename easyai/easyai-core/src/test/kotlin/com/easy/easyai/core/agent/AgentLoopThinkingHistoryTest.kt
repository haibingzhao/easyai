package com.easy.easyai.core.agent

import com.easy.easyai.api.llm.AssistantMessage as LlmAssistantMessage
import com.easy.easyai.api.llm.ChatGenerationMetadata
import com.easy.easyai.api.llm.ChatModel
import com.easy.easyai.api.llm.ChatResponse
import com.easy.easyai.api.llm.Generation
import com.easy.easyai.api.llm.Prompt
import com.easy.easyai.core.event.AgentEvent
import com.easy.easyai.core.event.MessageListener
import com.easy.easyai.core.message.DefaultMessageConverter
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.ThinkingContent
import com.easy.easyai.core.model.ToolResultContent
import com.easy.easyai.core.model.UserMessage
import com.easy.easyai.core.prompt.PromptTemplateService
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.DefaultToolExecutionEngine
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the agent-level thinking history gate end-to-end inside the loop: the Anthropic
 * signature chunk is persisted with the reasoning block, and `thinkingHistoryEnabled` decides
 * whether earlier turns' thinking is replayed in the next request.
 */
internal class AgentLoopThinkingHistoryTest {

    private fun toolCall(id: String) = LlmAssistantMessage.ToolCall(id, "function", "probe", "{\"q\":\"x\"}")

    private fun thinkingResponse(text: String): ChatResponse = ChatResponse(
        listOf(Generation(LlmAssistantMessage(content = text, metadata = mapOf("thinking" to true))))
    )

    private fun signatureResponse(signature: String): ChatResponse = ChatResponse(
        listOf(Generation(LlmAssistantMessage(content = "", metadata = mapOf("signature" to signature))))
    )

    private fun callResponse(id: String): ChatResponse = ChatResponse(
        listOf(
            Generation(
                LlmAssistantMessage(content = "", toolCalls = listOf(toolCall(id))),
                ChatGenerationMetadata(finishReason = "tool_calls")
            )
        )
    )

    private fun stopResponse(text: String): ChatResponse = ChatResponse(
        listOf(
            Generation(LlmAssistantMessage(content = text), ChatGenerationMetadata(finishReason = "stop"))
        )
    )

    private fun probeTool(): ToolDefinition = object : BaseToolDefinition(
        ToolMetadata(name = "probe", description = "Test tool")
    ) {
        override fun parameterType(): Class<*> = Map::class.java
        override suspend fun doExecute(
            agentContext: AgentContext,
            toolCallId: String,
            messageId: String?,
            args: Map<String, Any?>,
            coroutineScope: CoroutineScope,
            onUpdate: suspend (ToolUpdate) -> Unit
        ): ToolResult = ToolResult(
            content = listOf(ToolResultContent(toolCallId = toolCallId, toolName = "probe", output = "ok"))
        )
    }

    private inner class Fixture(
        private val turns: List<List<ChatResponse>>,
        thinkingHistoryEnabled: Boolean
    ) {
        val transcript = mutableListOf<EasyAiMessage>()
        val prompts = CopyOnWriteArrayList<Prompt>()
        private var callIndex = 0
        private val context = AgentContext(
            agentId = "test",
            sessionId = "session",
            userId = "alice",
            tools = listOf(probeTool()),
            maxIterations = 5,
            thinkingHistoryEnabled = thinkingHistoryEnabled
        )

        fun assistantMessages(promptIndex: Int): List<LlmAssistantMessage> =
            prompts[promptIndex].instructions.filterIsInstance<LlmAssistantMessage>()

        suspend fun run(message: String) {
            val model = mockk<ChatModel>()
            every { model.stream(any<Prompt>()) } answers {
                prompts.add(firstArg())
                Flux.just(*turns[callIndex++].toTypedArray())
            }
            val services = DefaultAgentService(
                chatModelFactories = emptyList(),
                messageConverter = DefaultMessageConverter(),
                toolExecutor = DefaultToolExecutionEngine(),
                promptTemplateService = mockk<PromptTemplateService>().also {
                    every { it.build(any(), any()) } returns "test prompt"
                },
                defaultChatModel = model,
                messageListener = object : MessageListener {
                    override suspend fun onMessageAdded(messages: List<EasyAiMessage>) = Unit
                }
            )
            AgentRunner(Agent(context, services), transcript)
                .prompt(listOf(UserMessage(message))).result()
        }
    }

    @Nested
    inner class `signature capture` {

        @Test
        fun `the thinking signature chunk is persisted with the reasoning block`() = runTest {
            val fixture = Fixture(
                turns = listOf(
                    listOf(thinkingResponse("pondering"), signatureResponse("sig-9"), stopResponse("answer"))
                ),
                thinkingHistoryEnabled = false
            )
            fixture.run("first request")

            val assistant = fixture.transcript.filterIsInstance<AssistantMessage>().single()
            val thinking = assistant.content.filterIsInstance<ThinkingContent>().single()
            assertEquals("pondering", thinking.thinking)
            assertEquals("sig-9", thinking.thinkingSignature)
        }
    }

    @Nested
    inner class `replay gate` {

        @Test
        fun `disabled thinking keeps thinkingBlocks out of the next request`() = runTest {
            val fixture = replayFixture(thinkingHistoryEnabled = false)
            fixture.run("first request")

            val history = fixture.assistantMessages(promptIndex = 1)
            assertTrue(history.isNotEmpty(), "the previous assistant turn must be replayed")
            assertTrue(history.all { it.thinkingBlocks.isEmpty() }, "disabled gate must strip thinking")
        }

        @Test
        fun `enabled thinking replays signed reasoning in the next request`() = runTest {
            val fixture = replayFixture(thinkingHistoryEnabled = true)
            fixture.run("first request")

            val block = fixture.assistantMessages(promptIndex = 1).flatMap { it.thinkingBlocks }.single()
            assertEquals("pondering", block.text)
            assertEquals("sig-9", block.signature)
        }

        private fun replayFixture(thinkingHistoryEnabled: Boolean) = Fixture(
            turns = listOf(
                listOf(thinkingResponse("pondering"), signatureResponse("sig-9"), callResponse("c1")),
                listOf(stopResponse("final answer"))
            ),
            thinkingHistoryEnabled = thinkingHistoryEnabled
        )
    }
}
