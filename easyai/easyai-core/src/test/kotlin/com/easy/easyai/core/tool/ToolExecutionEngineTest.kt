package com.easy.easyai.core.tool

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.event.AgentEvent
import com.easy.easyai.core.event.ProducerScope
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.ToolCallContent
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ToolExecutionEngineTest {

    private fun createTestTool(name: String, mode: ToolExecutionMode = ToolExecutionMode.SEQUENTIAL, result: ToolResult = ToolResult(content = listOf(TextContent("result")))) =
        object : BaseToolDefinition(ToolMetadata(name = name, description = "Test tool")) {
            override val executionMode: ToolExecutionMode get() = mode
            override fun parameterType(): Class<*> = Map::class.java
            override suspend fun doExecute(
                agentContext: AgentContext,
                toolCallId: String, messageId: String?, args: Map<String, Any?>, coroutineScope: CoroutineScope, onUpdate: suspend (ToolUpdate) -> Unit
            ): ToolResult = result
        }

    private fun createMockScope(): ProducerScope<AgentEvent, List<AssistantMessage>> {
        val scope = mockk<ProducerScope<AgentEvent, List<AssistantMessage>>>(relaxed = true)
        coEvery { scope.push(any()) } returns Unit
        return scope
    }

    private fun createMockContext(): AgentContext = AgentContext(
        agentId = "test-agent",
        sessionId = "test-session"
    )

    @Nested
    inner class `Sequential tool execution` {

        @Test
        fun `executes tools in order`() = runBlocking {
            val engine = DefaultToolExecutionEngine()
            val tool = createTestTool("test_tool")
            val mockScope = createMockScope()

            val results = engine.executeToolCalls(
                agentContext = createMockContext(),
                toolCalls = listOf(ToolCallContent("call1", "test_tool", "{}")),
                tools = listOf(tool),
                eventStream = mockScope,
                scope = this,
                turnId = 0
            )

            assertEquals(1, results.size)
            assertEquals("call1", results[0].toolCallId)
            assertEquals("result", results[0].resultText)
        }
    }

    @Nested
    inner class `Parallel tool execution` {

        @Test
        fun `executes multiple tools in parallel`() = runBlocking {
            val engine = DefaultToolExecutionEngine()
            val tool = createTestTool("test_tool", ToolExecutionMode.PARALLEL)
            val mockScope = createMockScope()

            val results = engine.executeToolCalls(
                agentContext = createMockContext(),
                toolCalls = listOf(
                    ToolCallContent("call1", "test_tool", "{}"),
                    ToolCallContent("call2", "test_tool", "{}")
                ),
                tools = listOf(tool),
                eventStream = mockScope,
                scope = this,
                turnId = 0
            )

            assertEquals(2, results.size)
        }
    }

    @Nested
    inner class `Error handling` {

        @Test
        fun `returns error result on tool failure`() = runBlocking {
            val failingTool = object : BaseToolDefinition(ToolMetadata(name = "failing", description = "Always fails")) {
                override fun parameterType(): Class<*> = Map::class.java
                override suspend fun doExecute(
                    agentContext: AgentContext,
                    toolCallId: String, messageId: String?, args: Map<String, Any?>, coroutineScope: CoroutineScope, onUpdate: suspend (ToolUpdate) -> Unit
                ): ToolResult = throw RuntimeException("tool failed")
            }
            val engine = DefaultToolExecutionEngine()
            val mockScope = createMockScope()

            val results = engine.executeToolCalls(
                agentContext = createMockContext(),
                toolCalls = listOf(ToolCallContent("call1", "failing", "{}")),
                tools = listOf(failingTool),
                eventStream = mockScope,
                scope = this,
                turnId = 0
            )

            assertEquals(1, results.size)
            assertEquals(true, results[0].isError)
        }
    }

    @Nested
    inner class `Unknown tool` {

        @Test
        fun `returns error result on unknown tool name`() = runBlocking {
            val engine = DefaultToolExecutionEngine()
            val knownTool = createTestTool("known_tool")
            val mockScope = createMockScope()

            val results = engine.executeToolCalls(
                agentContext = createMockContext(),
                toolCalls = listOf(ToolCallContent("call1", "nonexistent", "{}")),
                tools = listOf(knownTool),
                eventStream = mockScope,
                scope = this,
                turnId = 0
            )

            assertEquals(1, results.size)
            assertEquals(true, results[0].isError)
            assertEquals("call1", results[0].toolCallId)
            assert(results[0].resultText.contains("nonexistent"))
            assert(results[0].resultText.contains("known_tool"))
        }
    }
}
