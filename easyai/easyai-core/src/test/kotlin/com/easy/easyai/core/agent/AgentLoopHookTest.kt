package com.easy.easyai.core.agent

import com.easy.easyai.core.event.AgentEvent
import com.easy.easyai.core.event.MessageListener
import com.easy.easyai.core.message.DefaultMessageConverter
import com.easy.easyai.core.message.MessageConverter
import com.easy.easyai.core.model.*
import com.easy.easyai.core.prompt.PromptTemplateService
import com.easy.easyai.core.tool.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the 3-phase hook execution in AgentLoop.executeToolCallsWithHooks.
 *
 * Phase 1: Run all beforeToolCall hooks (permission checks)
 * Phase 2: Batch-execute allowed toolCalls via engine (supports parallel)
 * Phase 3: Run afterToolCall hooks — only on actually executed tools
 */
class AgentLoopHookTest {

    private fun createMockChatModelWithToolCalls(
        toolCalls: List<org.springframework.ai.chat.messages.AssistantMessage.ToolCall>,
        followUpText: String = "done"
    ): org.springframework.ai.chat.model.ChatModel {
        val toolCallResponse = mockk<org.springframework.ai.chat.model.ChatResponse>(relaxed = true)
        val genMetadata = mockk<org.springframework.ai.chat.metadata.ChatGenerationMetadata>(relaxed = true)
        io.mockk.every { genMetadata.finishReason } returns "tool_calls"
        val generation = mockk<org.springframework.ai.chat.model.Generation>(relaxed = true)
        val assistantMsg = org.springframework.ai.chat.messages.AssistantMessage.builder()
            .content("")
            .toolCalls(toolCalls)
            .build()
        io.mockk.every { generation.output } returns assistantMsg
        io.mockk.every { generation.metadata } returns genMetadata
        io.mockk.every { toolCallResponse.result } returns generation
        io.mockk.every { toolCallResponse.results } returns listOf(generation)

        val textResponse = mockk<org.springframework.ai.chat.model.ChatResponse>(relaxed = true)
        val textGenMetadata = mockk<org.springframework.ai.chat.metadata.ChatGenerationMetadata>(relaxed = true)
        io.mockk.every { textGenMetadata.finishReason } returns "stop"
        val textGeneration = mockk<org.springframework.ai.chat.model.Generation>(relaxed = true)
        val textAssistantMsg = org.springframework.ai.chat.messages.AssistantMessage(followUpText)
        io.mockk.every { textGeneration.output } returns textAssistantMsg
        io.mockk.every { textGeneration.metadata } returns textGenMetadata
        io.mockk.every { textResponse.result } returns textGeneration
        io.mockk.every { textResponse.results } returns listOf(textGeneration)

        val mock = mockk<org.springframework.ai.chat.model.ChatModel>()
        var index = 0
        io.mockk.every { mock.stream(any<org.springframework.ai.chat.prompt.Prompt>()) } answers {
            val resp = if (index == 0) toolCallResponse else textResponse
            index++
            reactor.core.publisher.Flux.just(resp)
        }
        return mock
    }

    private fun createTestTool(name: String) = object : BaseToolDefinition(ToolMetadata(name = name, description = "Test tool: $name")) {
        override fun parameterType(): Class<*> = Map::class.java
        override suspend fun doExecute(
            agentContext: AgentContext,
            toolCallId: String,
            messageId: String?,
            args: Map<String, Any?>,
            coroutineScope: kotlinx.coroutines.CoroutineScope,
            onUpdate: suspend (ToolUpdate) -> Unit
        ): ToolResult = ToolResult(content = listOf(TextContent("result of $name")))
    }

    private fun createHookedAgentService(
        chatModel: org.springframework.ai.chat.model.ChatModel,
        toolEngine: ToolExecutionEngine,
        beforeHook: BeforeToolCallHook,
        afterHook: AfterToolCallHook
    ): AgentService = object : AgentService {
        override val chatModelFactories = emptyList<com.easy.easyai.api.config.ChatModelFactory>()
        override val messageConverter: MessageConverter = DefaultMessageConverter()
        override val toolExecutor: ToolExecutionEngine = toolEngine
        override val messageListener: MessageListener? = null
        override val beforeToolCall: BeforeToolCallHook = beforeHook
        override val afterToolCall: AfterToolCallHook = afterHook
        override val transformContextService: TransformContextService = DefaultTransformContextService()
        override val defaultChatModel = chatModel
        override val promptTemplateService: PromptTemplateService = mockk<PromptTemplateService>(relaxed = true).also {
            io.mockk.every { it.build(any(), any()) } returns "test prompt"
        }

        override fun createChatModel(
            config: com.easy.easyai.api.model.ModelProviderConfig,
            toolCallbacks: List<org.springframework.ai.tool.ToolCallback>
        ) = chatModel

        override fun buildChatOptions(
            config: com.easy.easyai.api.model.ModelProviderConfig,
            toolCallbacks: List<org.springframework.ai.tool.ToolCallback>
        ) = org.springframework.ai.chat.prompt.ChatOptions.builder().model("test-model").build()
    }

    private fun toolCall(id: String, name: String) =
        org.springframework.ai.chat.messages.AssistantMessage.ToolCall(id, "function", name, """{}""")

    @Nested
    inner class `Phase 2 - batch execution` {

        @Test
        fun `all tools allowed - engine called once with all toolCalls`() = runBlocking {
            val engine = mockk<ToolExecutionEngine>()
            coEvery {
                engine.executeToolCalls(any(), any(), any(), any(), any(), any(), any())
            } returns listOf(
                ToolCallResult(toolCallId = "c1", resultText = "r1", isError = false, durationMs = 10),
                ToolCallResult(toolCallId = "c2", resultText = "r2", isError = false, durationMs = 10)
            )

            val chatModel = createMockChatModelWithToolCalls(
                listOf(toolCall("c1", "echo"), toolCall("c2", "echo"))
            )

            val services = createHookedAgentService(
                chatModel = chatModel,
                toolEngine = engine,
                beforeHook = BeforeToolCallHook { BeforeToolCallResult.Allow },
                afterHook = AfterToolCallHook { AfterToolCallResult.Default }
            )

            val context = AgentContext(
                agentId = "test",
                customInstructions = "test",
                tools = listOf(createTestTool("echo"))
            )
            val runner = AgentRunner(
                agent = Agent(context, services),
                messages = mutableListOf()
            )
            runner.prompt(listOf(UserMessage("run both"))).result()

            // Engine called exactly once with both toolCalls
            coVerify(exactly = 1) {
                engine.executeToolCalls(any(), any(), any(), any(), any(), any(), any())
            }
        }
    }

    @Nested
    inner class `Phase 1 - Block behavior` {

        @Test
        fun `blocked tool does not reach engine, allowed tool still executes`() = runBlocking {
            val engine = mockk<ToolExecutionEngine>()
            coEvery {
                engine.executeToolCalls(any(), any(), any(), any(), any(), any(), any())
            } returns listOf(
                ToolCallResult(toolCallId = "c2", resultText = "r2", isError = false, durationMs = 10)
            )

            val chatModel = createMockChatModelWithToolCalls(
                listOf(toolCall("c1", "dangerous"), toolCall("c2", "safe"))
            )

            val services = createHookedAgentService(
                chatModel = chatModel,
                toolEngine = engine,
                beforeHook = BeforeToolCallHook { ctx ->
                    if (ctx.toolName == "dangerous") BeforeToolCallResult.Block("not allowed")
                    else BeforeToolCallResult.Allow
                },
                afterHook = AfterToolCallHook { AfterToolCallResult.Default }
            )

            val context = AgentContext(
                agentId = "test",
                customInstructions = "test",
                tools = listOf(createTestTool("dangerous"), createTestTool("safe"))
            )
            val runner = AgentRunner(
                agent = Agent(context, services),
                messages = mutableListOf()
            )
            runner.prompt(listOf(UserMessage("go"))).result()

            // Engine called once with only the allowed tool
            coVerify(exactly = 1) {
                engine.executeToolCalls(any(), any(), any(), any(), any(), any(), any())
            }
        }
    }

    @Nested
    inner class `Phase 3 - afterToolCall scope` {

        @Test
        fun `afterToolCall is only called for executed tools, not blocked ones`() = runBlocking {
            val engine = mockk<ToolExecutionEngine>()
            coEvery {
                engine.executeToolCalls(any(), any(), any(), any(), any(), any(), any())
            } returns listOf(
                ToolCallResult(toolCallId = "c2", resultText = "r2", isError = false, durationMs = 10)
            )

            val chatModel = createMockChatModelWithToolCalls(
                listOf(toolCall("c1", "dangerous"), toolCall("c2", "safe"))
            )

            val afterToolCallIds = CopyOnWriteArrayList<String>()
            val services = createHookedAgentService(
                chatModel = chatModel,
                toolEngine = engine,
                beforeHook = BeforeToolCallHook { ctx ->
                    if (ctx.toolName == "dangerous") BeforeToolCallResult.Block("not allowed")
                    else BeforeToolCallResult.Allow
                },
                afterHook = AfterToolCallHook { ctx ->
                    afterToolCallIds.add(ctx.toolCallId)
                    AfterToolCallResult.Default
                }
            )

            val context = AgentContext(
                agentId = "test",
                customInstructions = "test",
                tools = listOf(createTestTool("dangerous"), createTestTool("safe"))
            )
            val runner = AgentRunner(
                agent = Agent(context, services),
                messages = mutableListOf()
            )
            runner.prompt(listOf(UserMessage("go"))).result()

            // afterToolCall should only be called for c2 (executed), not c1 (blocked)
            assertEquals(listOf("c2"), afterToolCallIds.toList())
        }

        @Test
        fun `afterToolCall terminate skips remaining hooks but does not affect execution`() = runBlocking {
            val engine = mockk<ToolExecutionEngine>()
            coEvery {
                engine.executeToolCalls(any(), any(), any(), any(), any(), any(), any())
            } returns listOf(
                ToolCallResult(toolCallId = "c1", resultText = "r1", isError = false, durationMs = 10),
                ToolCallResult(toolCallId = "c2", resultText = "r2", isError = false, durationMs = 10),
                ToolCallResult(toolCallId = "c3", resultText = "r3", isError = false, durationMs = 10)
            )

            val chatModel = createMockChatModelWithToolCalls(
                listOf(toolCall("c1", "echo"), toolCall("c2", "echo"), toolCall("c3", "echo"))
            )

            val afterToolCallIds = CopyOnWriteArrayList<String>()
            val services = createHookedAgentService(
                chatModel = chatModel,
                toolEngine = engine,
                beforeHook = BeforeToolCallHook { BeforeToolCallResult.Allow },
                afterHook = AfterToolCallHook { ctx ->
                    afterToolCallIds.add(ctx.toolCallId)
                    // First tool requests termination
                    if (ctx.toolCallId == "c1") AfterToolCallResult(terminate = true)
                    else AfterToolCallResult.Default
                }
            )

            val context = AgentContext(
                agentId = "test",
                customInstructions = "test",
                tools = listOf(createTestTool("echo"))
            )
            val runner = AgentRunner(
                agent = Agent(context, services),
                messages = mutableListOf()
            )
            runner.prompt(listOf(UserMessage("go"))).result()

            // All 3 tools executed (Phase 2 batch), but afterToolCall only ran for c1
            // (terminate skipped c2 and c3 hooks)
            coVerify(exactly = 1) {
                engine.executeToolCalls(any(), any(), any(), any(), any(), any(), any())
            }
            assertEquals(listOf("c1"), afterToolCallIds.toList())
        }
    }

    @Nested
    inner class `Phase 1 - PermissionRequest behavior` {

        @Test
        fun `permission request marks remaining tools as skipped and returns early`() = runBlocking {
            val engine = mockk<ToolExecutionEngine>()
            coEvery {
                engine.executeToolCalls(any(), any(), any(), any(), any(), any(), any())
            } returns listOf(
                ToolCallResult(toolCallId = "c1", resultText = "r1", isError = false, durationMs = 10)
            )

            val chatModel = createMockChatModelWithToolCalls(
                listOf(toolCall("c1", "dangerous"), toolCall("c2", "safe"), toolCall("c3", "other"))
            )

            val events = CopyOnWriteArrayList<AgentEvent>()
            val listener = object : AgentEventListener {
                override suspend fun handle(agentContext: AgentContext, event: AgentEvent, push: suspend (AgentEvent) -> Unit) {
                    events.add(event)
                }
            }

            val afterToolCallIds = CopyOnWriteArrayList<String>()
            val services = createHookedAgentService(
                chatModel = chatModel,
                toolEngine = engine,
                beforeHook = BeforeToolCallHook { ctx ->
                    when (ctx.toolName) {
                        "dangerous" -> BeforeToolCallResult.PermissionRequest(
                            permission = "write",
                            pattern = "**/dangerous/*",
                            toolCallId = ctx.toolCallId,
                            toolName = ctx.toolName,
                            arguments = ctx.arguments
                        )
                        else -> BeforeToolCallResult.Allow
                    }
                },
                afterHook = AfterToolCallHook { ctx ->
                    afterToolCallIds.add(ctx.toolCallId)
                    AfterToolCallResult.Default
                }
            ).let { baseService ->
                // Wrap to add event listener
                object : AgentService by baseService {
                    override val eventListeners = listOf(listener)
                }
            }

            val context = AgentContext(
                agentId = "test",
                customInstructions = "test",
                tools = listOf(createTestTool("dangerous"), createTestTool("safe"), createTestTool("other"))
            )
            val runner = AgentRunner(
                agent = Agent(context, services),
                messages = mutableListOf()
            )
            runner.prompt(listOf(UserMessage("go"))).result()

            // PermissionRequest on first tool (c1) → Phase 2 is never reached, engine not called
            coVerify(exactly = 0) {
                engine.executeToolCalls(any(), any(), any(), any(), any(), any(), any())
            }

            // PermissionRequestEvent should be emitted
            assertTrue(events.any { it is com.easy.easyai.core.event.PermissionRequestEvent })

            // afterToolCall should NOT be called for any tool (no tools were executed)
            assertTrue(afterToolCallIds.isEmpty())
        }
    }
}
