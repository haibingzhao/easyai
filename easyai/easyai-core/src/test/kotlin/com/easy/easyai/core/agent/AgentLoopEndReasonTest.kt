package com.easy.easyai.core.agent

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.core.event.AgentEndEvent
import com.easy.easyai.core.event.AgentEvent
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import org.springframework.ai.chat.messages.AssistantMessage as SpringAiAssistantMsg

/**
 * Tests for AgentLoop.endReason determination across different exit scenarios.
 *
 * Verifies the fix for the boundary bug where abort at turnId == maxIterations
 * incorrectly reported endReason as "max_iterations" instead of "cancelled".
 */
class AgentLoopEndReasonTest {

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

    private fun createTestTool() =
        object : BaseToolDefinition(ToolMetadata(name = "echo", description = "Test tool")) {
            override fun parameterType(): Class<*> = Map::class.java
            override suspend fun doExecute(
                agentContext: AgentContext,
                toolCallId: String,
                messageId: String?,
                args: Map<String, Any?>,
                coroutineScope: kotlinx.coroutines.CoroutineScope,
                onUpdate: suspend (ToolUpdate) -> Unit
            ): ToolResult = ToolResult(content = listOf(TextContent("tool result")))
        }

    private fun createMockChatModelFactory(chatModel: ChatModel): ChatModelFactory {
        val factory = mockk<ChatModelFactory>(relaxed = true)
        every { factory.create(any(), any()) } returns chatModel
        every { factory.build(any(), any()) } returns ChatOptions.builder().model("test-model").build()
        every { factory.supports(any()) } returns true
        return factory
    }

    private fun createTestAgentService(chatModel: ChatModel): AgentService {
        val mockPromptService = mockk<PromptTemplateService>(relaxed = true)
        every { mockPromptService.build(any(), any()) } returns "test prompt"
        return DefaultAgentService(
            chatModelFactories = listOf(createMockChatModelFactory(chatModel)),
            messageConverter = DefaultMessageConverter(),
            toolExecutor = DefaultToolExecutionEngine(),
            promptTemplateService = mockPromptService,
            defaultChatModel = chatModel,
            eventListeners = emptyList()
        )
    }

    private fun captureEndReason(events: List<AgentEvent>): String {
        return events.filterIsInstance<AgentEndEvent>().lastOrNull()?.endReason ?: "unknown"
    }

    @Nested
    inner class `endReason determination` {

        @Test
        fun `normal completion returns endReason normal`() = runBlocking {
            val textResponse = createMockChatResponse(text = "done", finishReason = "stop")
            val mockModel = createMockChatModel(textResponse)
            val services = createTestAgentService(mockModel)

            val context = AgentContext(
                agentId = "test",
                customInstructions = "test",
                maxIterations = 5
            )

            val events = CopyOnWriteArrayList<AgentEvent>()
            val listener = object : AgentEventListener {
                override suspend fun handle(agentContext: AgentContext, event: AgentEvent, push: suspend (AgentEvent) -> Unit) {
                    events.add(event)
                }
            }
            val servicesWithListener = DefaultAgentService(
                chatModelFactories = listOf(createMockChatModelFactory(mockModel)),
                messageConverter = DefaultMessageConverter(),
                toolExecutor = DefaultToolExecutionEngine(),
                promptTemplateService = mockk<PromptTemplateService>(relaxed = true).also {
                    every { it.build(any(), any()) } returns "test prompt"
                },
                defaultChatModel = mockModel,
                eventListeners = listOf(listener)
            )

            val runner = AgentRunner(
                agent = Agent(context, servicesWithListener),
                messages = mutableListOf()
            )
            runner.prompt(listOf(UserMessage("hi"))).result()
            assertEquals("normal", captureEndReason(events))
        }

        @Test
        fun `max iterations exhausted returns endReason max_iterations`() = runBlocking {
            // Both LLM calls return tool calls → loop runs until turnId >= maxIterations
            val toolCallResponse1 = createMockChatResponse(
                text = "calling tool 1",
                toolCalls = listOf(SpringAiAssistantMsg.ToolCall("call1", "function", "echo", "{}")),
                finishReason = "tool_calls"
            )
            val toolCallResponse2 = createMockChatResponse(
                text = "calling tool 2",
                toolCalls = listOf(SpringAiAssistantMsg.ToolCall("call2", "function", "echo", "{}")),
                finishReason = "tool_calls"
            )
            val mockModel = createMockChatModel(toolCallResponse1, toolCallResponse2)
            val services = createTestAgentService(mockModel)

            val context = AgentContext(
                agentId = "test",
                customInstructions = "test",
                tools = listOf(createTestTool()),
                maxIterations = 2  // Only 2 iterations allowed
            )

            val events = CopyOnWriteArrayList<AgentEvent>()
            val listener = object : AgentEventListener {
                override suspend fun handle(agentContext: AgentContext, event: AgentEvent, push: suspend (AgentEvent) -> Unit) {
                    events.add(event)
                }
            }
            val servicesWithListener = DefaultAgentService(
                chatModelFactories = listOf(createMockChatModelFactory(mockModel)),
                messageConverter = DefaultMessageConverter(),
                toolExecutor = DefaultToolExecutionEngine(),
                promptTemplateService = mockk<PromptTemplateService>(relaxed = true).also {
                    every { it.build(any(), any()) } returns "test prompt"
                },
                defaultChatModel = mockModel,
                eventListeners = listOf(listener)
            )

            val runner = AgentRunner(
                agent = Agent(context, servicesWithListener),
                messages = mutableListOf()
            )
            runner.prompt(listOf(UserMessage("do work"))).result()
            assertEquals("max_iterations", captureEndReason(events))
        }

        @Test
        fun `abort at maxIterations boundary returns endReason cancelled not max_iterations`() = runBlocking {
            // LLM returns tool calls every time, but abort is triggered during 2nd turn's tool execution.
            // After abort, turnId increments to maxIterations (2), but endReason must be "cancelled".
            val toolCallResponse = createMockChatResponse(
                text = "calling tool",
                toolCalls = listOf(SpringAiAssistantMsg.ToolCall("call1", "function", "echo", "{}")),
                finishReason = "tool_calls"
            )
            val mockModel = createMockChatModel(toolCallResponse, toolCallResponse)

            // Abort counter: fires after enough internal checks to reach the 2nd turn's tool execution.
            // Sequence of isAbortRequested() calls:
            //   while-top(1), before-tool(2), after-tool(3), while-top(4), before-tool(5)→TRUE
            var abortCheckCount = 0
            val abortSignal = {
                abortCheckCount++
                abortCheckCount >= 5
            }

            val services = createTestAgentService(mockModel)

            val context = AgentContext(
                agentId = "test",
                customInstructions = "test",
                tools = listOf(createTestTool()),
                maxIterations = 2  // boundary: abort fires exactly when turnId would reach maxIterations
            )

            val events = CopyOnWriteArrayList<AgentEvent>()
            val listener = object : AgentEventListener {
                override suspend fun handle(agentContext: AgentContext, event: AgentEvent, push: suspend (AgentEvent) -> Unit) {
                    events.add(event)
                }
            }
            val servicesWithListener = DefaultAgentService(
                chatModelFactories = listOf(createMockChatModelFactory(mockModel)),
                messageConverter = DefaultMessageConverter(),
                toolExecutor = DefaultToolExecutionEngine(),
                promptTemplateService = mockk<PromptTemplateService>(relaxed = true).also {
                    every { it.build(any(), any()) } returns "test prompt"
                },
                defaultChatModel = mockModel,
                eventListeners = listOf(listener)
            )

            val runner = AgentRunner(
                agent = Agent(context, servicesWithListener),
                messages = mutableListOf(),
                abortSignal = abortSignal
            )
            runner.prompt(listOf(UserMessage("do work"))).result()

            val endReason = captureEndReason(events)
            assertEquals("cancelled", endReason,
                "Abort at maxIterations boundary must report 'cancelled', not 'max_iterations'")
        }

        @Test
        fun `abort before maxIterations returns endReason cancelled`() = runBlocking {
            val toolCallResponse = createMockChatResponse(
                text = "calling tool",
                toolCalls = listOf(SpringAiAssistantMsg.ToolCall("call1", "function", "echo", "{}")),
                finishReason = "tool_calls"
            )
            val mockModel = createMockChatModel(toolCallResponse)

            // Abort immediately — caught at while-top check on the 2nd iteration
            var abortCheckCount = 0
            val abortSignal = {
                abortCheckCount++
                abortCheckCount >= 2  // abort on 2nd call (while-top of 2nd iteration)
            }

            val context = AgentContext(
                agentId = "test",
                customInstructions = "test",
                tools = listOf(createTestTool()),
                maxIterations = 10
            )

            val events = CopyOnWriteArrayList<AgentEvent>()
            val listener = object : AgentEventListener {
                override suspend fun handle(agentContext: AgentContext, event: AgentEvent, push: suspend (AgentEvent) -> Unit) {
                    events.add(event)
                }
            }
            val servicesWithListener = DefaultAgentService(
                chatModelFactories = listOf(createMockChatModelFactory(mockModel)),
                messageConverter = DefaultMessageConverter(),
                toolExecutor = DefaultToolExecutionEngine(),
                promptTemplateService = mockk<PromptTemplateService>(relaxed = true).also {
                    every { it.build(any(), any()) } returns "test prompt"
                },
                defaultChatModel = mockModel,
                eventListeners = listOf(listener)
            )

            val runner = AgentRunner(
                agent = Agent(context, servicesWithListener),
                messages = mutableListOf(),
                abortSignal = abortSignal
            )
            runner.prompt(listOf(UserMessage("do work"))).result()
            assertEquals("cancelled", captureEndReason(events))
        }
    }
}
