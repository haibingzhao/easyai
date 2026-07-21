package com.easy.easyai.core.agent

import com.easy.easyai.core.event.AgentEvent
import com.easy.easyai.core.event.MessageListener
import com.easy.easyai.core.event.ProducerScope
import com.easy.easyai.core.model.*
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolCallResult
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolExecutionEngine
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PendingToolCallExecutorTest {

    private fun createTestTool(
        name: String,
        skipOnResume: Boolean = false,
        result: String = "ok"
    ): ToolDefinition = object : BaseToolDefinition(ToolMetadata(name = name, description = "Test tool: $name")) {
        override val skipOnResume: Boolean get() = skipOnResume
        override fun parameterType(): Class<*> = Map::class.java
        override suspend fun doExecute(
            agentContext: AgentContext,
            toolCallId: String,
            messageId: String?,
            args: Map<String, Any?>,
            coroutineScope: CoroutineScope,
            onUpdate: suspend (ToolUpdate) -> Unit
        ): ToolResult = ToolResult(content = listOf(TextContent(result)))
    }

    private fun createMockScope(): ProducerScope<AgentEvent, List<AssistantMessage>> {
        val scope = mockk<ProducerScope<AgentEvent, List<AssistantMessage>>>(relaxed = true)
        coEvery { scope.push(any()) } returns Unit
        return scope
    }

    private fun createMockContext(): AgentContext = AgentContext(
        agentId = "test-agent",
        sessionId = "test-session",
        projectId = "test-project"
    )

    private fun createMockToolExecutionEngine(resultText: String = "ok"): ToolExecutionEngine {
        val engine = mockk<ToolExecutionEngine>()
        coEvery {
            engine.executeToolCalls(
                agentContext = any(),
                toolCalls = any(),
                tools = any(),
                eventStream = any(),
                scope = any(),
                turnId = any(),
                messageId = any()
            )
        } returns listOf(
            ToolCallResult(
                toolCallId = "call1",
                resultText = resultText,
                isError = false,
                durationMs = 100
            )
        )
        return engine
    }

    @Nested
    inner class `No pending toolCalls` {

        @Test
        fun `does nothing when no AssistantMessage exists`() = runBlocking {
            val transcript = mutableListOf<EasyAiMessage>(
                UserMessage(content = listOf(TextContent("hello")))
            )
            val executor = PendingToolCallExecutor(
                toolExecutor = createMockToolExecutionEngine(),
                tools = emptyList(),
                messageListener = null,
                agentContext = createMockContext()
            )
            executor.executePendingToolCallsIfNeeded(transcript, createMockScope())
        }

        @Test
        fun `does nothing when AssistantMessage has no toolCalls`() = runBlocking {
            val transcript = mutableListOf<EasyAiMessage>(
                AssistantMessage(
                    content = listOf(TextContent("I'll help")),
                    stopReason = StopReason.STOP
                )
            )
            val executor = PendingToolCallExecutor(
                toolExecutor = createMockToolExecutionEngine(),
                tools = emptyList(),
                messageListener = null,
                agentContext = createMockContext()
            )
            executor.executePendingToolCallsIfNeeded(transcript, createMockScope())
        }
    }

    @Nested
    inner class `Resume scenario - executes directly without permission check` {

        @Test
        fun `executes pending toolCall directly`() = runBlocking {
            val tool = createTestTool("bash")
            val engine = createMockToolExecutionEngine("command output")
            val transcript = mutableListOf<EasyAiMessage>(
                AssistantMessage(
                    content = listOf(
                        TextContent("Running command"),
                        ToolCallContent(id = "call1", name = "bash", arguments = """{"command":"ls"}""")
                    ),
                    stopReason = StopReason.TOOL_USE
                )
            )
            val executor = PendingToolCallExecutor(
                toolExecutor = engine,
                tools = listOf(tool),
                messageListener = null,
                agentContext = createMockContext()
            )

            executor.executePendingToolCallsIfNeeded(transcript, createMockScope())

            // Verify tool was executed
            coVerify(exactly = 1) {
                engine.executeToolCalls(
                    agentContext = any(),
                    toolCalls = any(),
                    tools = any(),
                    eventStream = any(),
                    scope = any(),
                    turnId = any(),
                    messageId = any()
                )
            }
            // Verify ToolResultMessage was added to transcript
            assertEquals(2, transcript.size)
            assertTrue(transcript[1] is ToolResultMessage)
        }

        @Test
        fun `executes all pending toolCalls`() = runBlocking {
            val tool = createTestTool("bash")
            val engine = mockk<ToolExecutionEngine>()
            // Return results for both calls in a single batch invocation
            coEvery {
                engine.executeToolCalls(
                    agentContext = any(),
                    toolCalls = any(),
                    tools = any(),
                    eventStream = any(),
                    scope = any(),
                    turnId = any(),
                    messageId = any()
                )
            } returns listOf(
                ToolCallResult(toolCallId = "call1", resultText = "result1", isError = false, durationMs = 50),
                ToolCallResult(toolCallId = "call2", resultText = "result2", isError = false, durationMs = 50)
            )

            val transcript = mutableListOf<EasyAiMessage>(
                AssistantMessage(
                    content = listOf(
                        ToolCallContent(id = "call1", name = "bash", arguments = """{"command":"ls"}"""),
                        ToolCallContent(id = "call2", name = "bash", arguments = """{"command":"pwd"}""")
                    ),
                    stopReason = StopReason.TOOL_USE
                )
            )
            val executor = PendingToolCallExecutor(
                toolExecutor = engine,
                tools = listOf(tool),
                messageListener = null,
                agentContext = createMockContext()
            )

            executor.executePendingToolCallsIfNeeded(transcript, createMockScope())

            // Engine called once with all toolCalls (batch execution)
            coVerify(exactly = 1) {
                engine.executeToolCalls(
                    agentContext = any(),
                    toolCalls = any(),
                    tools = any(),
                    eventStream = any(),
                    scope = any(),
                    turnId = any(),
                    messageId = any()
                )
            }
        }
    }

    @Nested
    inner class `Deny scenario - toolCall already has result` {

        @Test
        fun `skips toolCall that already has a result`() = runBlocking {
            val tool = createTestTool("bash")
            val engine = createMockToolExecutionEngine()
            val transcript = mutableListOf<EasyAiMessage>(
                AssistantMessage(
                    content = listOf(
                        ToolCallContent(id = "call1", name = "bash", arguments = """{"command":"rm file"}""")
                    ),
                    stopReason = StopReason.TOOL_USE
                ),
                // Deny scenario: ToolResultMessage already exists with the denied result
                ToolResultMessage(
                    toolResults = listOf(
                        ToolResultEntry(
                            toolCallId = "call1",
                            toolName = "bash",
                            result = "[Permission denied: User denied]",
                            isError = true
                        )
                    )
                )
            )
            val executor = PendingToolCallExecutor(
                toolExecutor = engine,
                tools = listOf(tool),
                messageListener = null,
                agentContext = createMockContext()
            )

            executor.executePendingToolCallsIfNeeded(transcript, createMockScope())

            // Tool should NOT be executed - result already exists
            coVerify(exactly = 0) { engine.executeToolCalls(any(), any(), any(), any(), any(), any(), any()) }
        }
    }

    @Nested
    inner class `skipOnResume tools` {

        @Test
        fun `filters out skipOnResume tools`() = runBlocking {
            val skipTool = createTestTool("ask_question", skipOnResume = true)
            val engine = createMockToolExecutionEngine()
            val transcript = mutableListOf<EasyAiMessage>(
                AssistantMessage(
                    content = listOf(
                        ToolCallContent(id = "call1", name = "ask_question", arguments = """{"question":"What?"}""")
                    ),
                    stopReason = StopReason.TOOL_USE
                )
            )
            val executor = PendingToolCallExecutor(
                toolExecutor = engine,
                tools = listOf(skipTool),
                messageListener = null,
                agentContext = createMockContext()
            )

            executor.executePendingToolCallsIfNeeded(transcript, createMockScope())

            // skipOnResume tool should NOT be executed
            coVerify(exactly = 0) { engine.executeToolCalls(any(), any(), any(), any(), any(), any(), any()) }
        }
    }

    @Nested
    inner class `Skipped placeholder cleanup` {

        @Test
        fun `removes skipped entries and re-executes unresolved toolCalls`() = runBlocking {
            val tool = createTestTool("bash")
            val engine = mockk<ToolExecutionEngine>()
            // Both call1 and call2 executed in a single batch invocation
            coEvery {
                engine.executeToolCalls(
                    agentContext = any(),
                    toolCalls = any(),
                    tools = any(),
                    eventStream = any(),
                    scope = any(),
                    turnId = any(),
                    messageId = any()
                )
            } returns listOf(
                ToolCallResult(toolCallId = "call1", resultText = "result1", isError = false, durationMs = 50),
                ToolCallResult(toolCallId = "call2", resultText = "result2", isError = false, durationMs = 50)
            )
            val messageListener = mockk<MessageListener>(relaxed = true)
            coEvery { messageListener.onMessageAdded(any()) } returns Unit
            coEvery { messageListener.onMessageUpdated(any(), any()) } returns Unit

            val transcript = mutableListOf<EasyAiMessage>(
                AssistantMessage(
                    content = listOf(
                        ToolCallContent(id = "call1", name = "bash", arguments = """{"command":"ls"}"""),
                        ToolCallContent(id = "call2", name = "bash", arguments = """{"command":"rm file"}""")
                    ),
                    stopReason = StopReason.TOOL_USE
                ),
                // ToolResultMessage with skipped placeholder for call2 (from permission pause)
                ToolResultMessage(
                    toolResults = listOf(
                        ToolResultEntry(
                            toolCallId = "call2",
                            toolName = "bash",
                            result = "Skipped: waiting for permission on bash",
                            isError = false,
                            isSkipped = true
                        )
                    )
                )
            )
            val executor = PendingToolCallExecutor(
                toolExecutor = engine,
                tools = listOf(tool),
                messageListener = messageListener,
                agentContext = createMockContext()
            )

            executor.executePendingToolCallsIfNeeded(transcript, createMockScope())

            // Both call1 and call2 executed in one batch call (skipped placeholder is filtered out)
            coVerify(exactly = 1) { engine.executeToolCalls(any(), any(), any(), any(), any(), any(), any()) }
        }
    }
}
