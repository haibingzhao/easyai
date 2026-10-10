package com.easy.easyai.core.agent

import com.easy.easyai.core.event.AgentEvent
import com.easy.easyai.core.event.MessageListener
import com.easy.easyai.core.event.ToolFoldEvent
import com.easy.easyai.core.message.DefaultMessageConverter
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.TextContent
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
import com.easy.easyai.api.llm.ChatGenerationMetadata
import com.easy.easyai.api.llm.ChatModel
import com.easy.easyai.api.llm.ChatResponse
import com.easy.easyai.api.llm.Generation
import com.easy.easyai.api.llm.Prompt
import com.easy.easyai.api.llm.ToolResponseMessage
import reactor.core.publisher.Flux
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.easy.easyai.api.llm.AssistantMessage as LlmAssistantMessage

/**
 * Verifies the AgentLoop wiring of cross-run tool folding: the model sees the folded projection
 * while the transcript keeps originals, the recall tool appears only while folds exist, and a
 * `tool_fold` event reports each folded turn.
 */
internal class AgentLoopToolFoldTest {

    private fun toolCall(id: String) = LlmAssistantMessage.ToolCall(id, "function", "probe", "{\"q\":\"x\"}")

    private fun callResponse(id: String): ChatResponse = ChatResponse(
        listOf(
            Generation(
                LlmAssistantMessage(content = "", toolCalls = listOf(toolCall(id))),
                ChatGenerationMetadata(finishReason = "tool_calls")
            )
        )
    )

    private fun textResponse(text: String): ChatResponse = ChatResponse(
        listOf(
            Generation(
                LlmAssistantMessage(content = text),
                ChatGenerationMetadata(finishReason = "stop")
            )
        )
    )

    private fun probeTool(output: () -> String): ToolDefinition = object : BaseToolDefinition(
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
            content = listOf(ToolResultContent(toolCallId = toolCallId, toolName = "probe", output = output()))
        )
    }

    private inner class Fixture(
        responses: List<ChatResponse>,
        toolFoldEnabled: Boolean,
        keepRecentRuns: Int = 0
    ) {
        val transcript = mutableListOf<EasyAiMessage>()
        val prompts = CopyOnWriteArrayList<Prompt>()
        val events = CopyOnWriteArrayList<AgentEvent>()
        private val context = AgentContext(
            agentId = "test",
            sessionId = "session",
            userId = "alice",
            tools = listOf(probeTool { BULKY_OUTPUT }),
            maxIterations = 5,
            toolFoldEnabled = toolFoldEnabled,
            toolFoldKeepRecentRuns = keepRecentRuns
        )
        private val services: AgentService

        init {
            val model = mockk<ChatModel>()
            every { model.stream(any<Prompt>()) } answers {
                prompts.add(firstArg())
                Flux.just(responses[prompts.size - 1])
            }
            val promptService = mockk<PromptTemplateService>()
            every { promptService.build(any(), any()) } returns "test prompt"
            services = DefaultAgentService(
                chatModelFactories = emptyList(),
                messageConverter = DefaultMessageConverter(),
                toolExecutor = DefaultToolExecutionEngine(),
                promptTemplateService = promptService,
                defaultChatModel = model,
                messageListener = object : MessageListener {
                    override suspend fun onMessageAdded(messages: List<EasyAiMessage>) = Unit
                },
                eventListeners = listOf(object : AgentEventListener {
                    override suspend fun handle(
                        agentContext: AgentContext,
                        event: AgentEvent,
                        push: suspend (AgentEvent) -> Unit
                    ) {
                        events.add(event)
                    }
                })
            )
        }

        suspend fun run(message: String) {
            AgentRunner(Agent(context, services), transcript)
                .prompt(listOf(UserMessage(message))).result()
        }

        fun promptText(index: Int): String = prompts[index].instructions.joinToString("\n") { message ->
            when (message) {
                is ToolResponseMessage -> message.responses.joinToString("|") { it.responseData }
                else -> message.text ?: ""
            }
        }

        fun toolNames(index: Int): List<String> =
            prompts[index].options?.toolCallbacks?.map { it.name } ?: emptyList()
    }

    @Nested
    inner class FoldedPrompt {

        @Test
        fun `previous run tool output is folded for the next request while transcript keeps originals`() = runTest {
            val fixture = Fixture(listOf(callResponse("c1"), textResponse("first answer"), textResponse("second answer")), toolFoldEnabled = true)
            fixture.run("first request")
            assertEquals(2, fixture.prompts.size)
            assertContains(fixture.promptText(1), BULKY_OUTPUT)

            fixture.run("second request")
            assertEquals(3, fixture.prompts.size)

            val lastPrompt = fixture.promptText(2)
            assertFalse(lastPrompt.contains(BULKY_OUTPUT), "folded prompt must not carry the original body")
            assertContains(lastPrompt, "chars folded")
            assertContains(lastPrompt, "recall_tool_result")
            // Transcript always holds the original
            assertContains(fixture.transcript.filterIsInstance<com.easy.easyai.core.model.ToolResultMessage>()
                .joinToString("\n") { it.toolResults.joinToString("|") { entry -> entry.result } }, BULKY_OUTPUT)

            val foldEvents = fixture.events.filterIsInstance<ToolFoldEvent>()
            assertEquals(1, foldEvents.size)
            assertEquals(1, foldEvents.single().foldedRunCount)
            assertTrue(foldEvents.single().foldedToolCallCount >= 1)
            assertTrue(foldEvents.single().tokensSavedEstimate > 0)
        }

        @Test
        fun `recall tool is exposed only while folds exist`() = runTest {
            val fixture = Fixture(listOf(callResponse("c1"), textResponse("first answer"), textResponse("second answer")), toolFoldEnabled = true)
            fixture.run("first request")
            fixture.run("second request")

            assertFalse(fixture.toolNames(0).contains(RECALL_TOOL), "no folds on the first request")
            assertTrue(fixture.toolNames(2).contains(RECALL_TOOL), "folds must expose the recall tool")
        }

        @Test
        fun `disabled folding keeps every tool result verbatim`() = runTest {
            val fixture = Fixture(listOf(callResponse("c1"), textResponse("first answer"), textResponse("second answer")), toolFoldEnabled = false)
            fixture.run("first request")
            fixture.run("second request")

            assertContains(fixture.promptText(2), BULKY_OUTPUT)
            assertFalse(fixture.events.any { it is ToolFoldEvent })
            assertFalse(fixture.toolNames(2).contains(RECALL_TOOL))
        }

        @Test
        fun `keepRecentRuns holds the last completed run unfolded`() = runTest {
            val fixture = Fixture(
                listOf(callResponse("c1"), textResponse("first answer"), callResponse("c2"), textResponse("second answer"), textResponse("third answer")),
                toolFoldEnabled = true,
                keepRecentRuns = 1
            )
            fixture.run("first request")
            fixture.run("second request")
            fixture.run("third request")

            // keepRecentRuns = 1: run 2 stays verbatim, only run 1 folds once run 3 starts.
            val lastPrompt = fixture.promptText(fixture.prompts.size - 1)
            assertContains(lastPrompt, BULKY_OUTPUT, message = "the kept run must stay unfolded")
            assertContains(lastPrompt, "chars folded", message = "the oldest run must still fold")
        }
    }

    companion object {
        private const val BULKY_PREFIX = "BODY_START"
        private val BULKY_OUTPUT = BULKY_PREFIX + "x".repeat(1_200)
        private const val RECALL_TOOL = "recall_tool_result"
    }
}
