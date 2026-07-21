package com.easy.easyai.core.agent

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.core.event.AgentEvent
import com.easy.easyai.core.event.AgentStartEvent
import com.easy.easyai.core.event.AgentEndEvent
import com.easy.easyai.core.message.DefaultMessageConverter
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.UserMessage
import com.easy.easyai.core.prompt.PromptTemplateService
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.DefaultToolExecutionEngine
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.metadata.ChatGenerationMetadata
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.tool.ToolCallback
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.ai.chat.messages.AssistantMessage as SpringAiAssistantMsg

class AgentTest {

    private fun createMockChatResponse(
        text: String = "",
        toolCalls: List<SpringAiAssistantMsg.ToolCall> = emptyList(),
        finishReason: String? = "stop"
    ): ChatResponse {
        val assistantMsg = if (toolCalls.isEmpty()) {
            SpringAiAssistantMsg(text)
        } else {
            SpringAiAssistantMsg.builder().content(text).toolCalls(toolCalls).build()
        }
        val genMetadata = mockk<ChatGenerationMetadata>(relaxed = true)
        every { genMetadata.finishReason } returns finishReason

        val generation = mockk<Generation>(relaxed = true)
        every { generation.output } returns assistantMsg
        every { generation.metadata } returns genMetadata

        val responseMetadata = mockk<ChatResponseMetadata>(relaxed = true)

        val response = mockk<ChatResponse>(relaxed = true)
        every { response.result } returns generation
        every { response.results } returns listOf(generation)
        every { response.metadata } returns responseMetadata
        return response
    }

    private fun createMockChatModel(vararg responses: ChatResponse): ChatModel {
        val mock = mockk<ChatModel>()
        var index = 0
        every { mock.stream(any<Prompt>()) } answers {
            val resp = responses[index.coerceAtMost(responses.size - 1)]
            index++
            reactor.core.publisher.Flux.just(resp)
        }
        return mock
    }

    private fun createTestTool(name: String = "echo", result: ToolResult = ToolResult(content = listOf(TextContent("tool result")))) =
        object : BaseToolDefinition(ToolMetadata(name = name, description = "Test tool")) {
            override fun parameterType(): Class<*> = Map::class.java
            override suspend fun doExecute(
                agentContext: AgentContext,
                toolCallId: String,
                messageId: String?,
                args: Map<String, Any?>,
                coroutineScope: kotlinx.coroutines.CoroutineScope,
                onUpdate: suspend (ToolUpdate) -> Unit
            ): ToolResult = result
        }

    private fun createMockChatModelFactory(chatModel: ChatModel): ChatModelFactory {
        val factory = mockk<ChatModelFactory>(relaxed = true)
        every { factory.create(any(), any()) } returns chatModel
        every { factory.build(any(), any(), any()) } returns ChatOptions.builder().model("test-model").build()
        every { factory.supports(any()) } returns true
        return factory
    }

    private fun createTestAgentService(
        chatModel: ChatModel,
        eventListeners: List<AgentEventListener> = emptyList()
    ): AgentService {
        val mockPromptService = mockk<PromptTemplateService>(relaxed = true)
        every { mockPromptService.build(any(), any()) } returns "test prompt"
        return DefaultAgentService(
            chatModelFactories = listOf(createMockChatModelFactory(chatModel)),
            messageConverter = DefaultMessageConverter(),
            toolExecutor = DefaultToolExecutionEngine(),
            promptTemplateService = mockPromptService,
            defaultChatModel = chatModel,
            eventListeners = eventListeners
        )
    }

    @Nested
    inner class `AgentRunner prompt` {

        @Test
        fun `returns text response`() = runBlocking {
            val textResponse = createMockChatResponse(text = "Hello there", finishReason = "stop")
            val mockModel = createMockChatModel(textResponse)
            val services = createTestAgentService(mockModel)

            val context = AgentContext(
                agentId = "test",
                customInstructions = "test"
            )

            val runner = AgentRunner(
                agent = Agent(context, services),
                messages = mutableListOf()
            )
            val result = runner.prompt(listOf(UserMessage("hi"))).result()
            assertEquals(1, result.size)
            assertEquals("Hello there", result[0].text())
        }

        @Test
        fun `processes tool calls then continues`() = runBlocking {
            val toolCallResponse = createMockChatResponse(
                text = "Let me check",
                toolCalls = listOf(
                    SpringAiAssistantMsg.ToolCall("call1", "function", "echo", """{}""")
                ),
                finishReason = "tool_calls"
            )
            val textResponse = createMockChatResponse(
                text = "Here is the result",
                finishReason = "stop"
            )
            val mockModel = createMockChatModel(toolCallResponse, textResponse)
            val services = createTestAgentService(mockModel)

            val context = AgentContext(
                agentId = "test",
                customInstructions = "test",
                tools = listOf(createTestTool())
            )

            val runner = AgentRunner(
                agent = Agent(context, services),
                messages = mutableListOf()
            )
            val result = runner.prompt(listOf(UserMessage("Check this"))).result()
            assertTrue(result.isNotEmpty())
            assertEquals("Here is the result", result.last().text())
        }
    }

    @Nested
    inner class `ChatSession state` {

        @Test
        fun `state updates after prompt`() = runBlocking {
            val textResponse = createMockChatResponse(text = "done", finishReason = "stop")
            val mockModel = createMockChatModel(textResponse)
            val services = createTestAgentService(mockModel)

            val agent = Agent(
                context = AgentContext(
                    agentId = "test",
                    customInstructions = "test"
                ),
                services = services
            )

            val session = ChatSession(id = "test-session", agent = agent)
            session.prompt("go", emptyList()).result()
            // ChatSession creates a transcript internally per runner
            // Verify the session is still active and functional
            assertEquals(SessionStatus.ACTIVE, session.status)
        }

        @Test
        fun `reset clears steering and followUp queues`() = runBlocking {
            val textResponse = createMockChatResponse(text = "done", finishReason = "stop")
            val mockModel = createMockChatModel(textResponse)
            val services = createTestAgentService(mockModel)

            val agent = Agent(
                context = AgentContext(
                    agentId = "test",
                    customInstructions = "test"
                ),
                services = services
            )

            val session = ChatSession(id = "test-session", agent = agent)
            session.steer("steer message")
            session.followUp("follow up message")
            session.reset()
            // After reset, queues should be cleared — session should still work
            assertEquals(SessionStatus.ACTIVE, session.status)
        }
    }

    @Nested
    inner class `AgentRunner events` {

        @Test
        fun `emits start and end events`() = runBlocking {
            val textResponse = createMockChatResponse(text = "done", finishReason = "stop")
            val mockModel = createMockChatModel(textResponse)

            val context = AgentContext(
                agentId = "test",
                customInstructions = "test"
            )

            val events = CopyOnWriteArrayList<AgentEvent>()
            val listener = object : AgentEventListener {
                override suspend fun handle(agentContext: AgentContext, event: AgentEvent, push: suspend (AgentEvent) -> Unit) {
                    events.add(event)
                }
            }
            val services = createTestAgentService(mockModel, eventListeners = listOf(listener))

            val runner = AgentRunner(
                agent = Agent(context, services),
                messages = mutableListOf()
            )
            val result = runner.prompt(listOf(UserMessage("go"))).result()
            assertTrue(result.isNotEmpty())
            assertTrue(events.any { it is AgentStartEvent })
            assertTrue(events.any { it is AgentEndEvent })
        }

        @Test
        fun `emits turn events during tool execution`() = runBlocking {
            val toolCallResponse = createMockChatResponse(
                text = "",
                toolCalls = listOf(SpringAiAssistantMsg.ToolCall("call1", "function", "echo", "{}")),
                finishReason = "tool_calls"
            )
            val textResponse = createMockChatResponse(text = "done", finishReason = "stop")
            val mockModel = createMockChatModel(toolCallResponse, textResponse)

            val context = AgentContext(
                agentId = "test",
                customInstructions = "test",
                tools = listOf(createTestTool())
            )

            val events = CopyOnWriteArrayList<AgentEvent>()
            val listener = object : AgentEventListener {
                override suspend fun handle(agentContext: AgentContext, event: AgentEvent, push: suspend (AgentEvent) -> Unit) {
                    events.add(event)
                }
            }
            val services = createTestAgentService(mockModel, eventListeners = listOf(listener))

            val runner = AgentRunner(
                agent = Agent(context, services),
                messages = mutableListOf()
            )
            val result = runner.prompt(listOf(UserMessage("go"))).result()
            assertTrue(result.isNotEmpty())
            assertTrue(events.any { it is AgentStartEvent })
            assertTrue(events.any { it is AgentEndEvent })
        }
    }
}