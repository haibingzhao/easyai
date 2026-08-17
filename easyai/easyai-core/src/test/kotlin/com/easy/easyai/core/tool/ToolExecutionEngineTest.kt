package com.easy.easyai.core.tool

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.event.AgentEvent
import com.easy.easyai.core.event.ProducerScope
import com.easy.easyai.core.event.ToolExecutionEndEvent
import com.easy.easyai.core.message.ToolResultGuard
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.ToolCallContent
import com.easy.easyai.core.model.ToolResultContent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolExecutionEngineTest {

    @AfterEach
    fun resetSpillDir() {
        ToolResultGuard.spillDirOverride = null
    }

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
    inner class `Result guarding` {

        @Test
        fun `emits guarded ToolExecutionEndEvent for oversized results`(@TempDir tempDir: Path) = runBlocking {
            ToolResultGuard.spillDirOverride = tempDir
            // Use distinguishable content: head of 'A's + unique marker + tail of 'B's
            val largeContent = "A".repeat(30_000) + "UNIQUE_MARKER_12345" + "B".repeat(30_000)
            val largeResult = ToolResult(
                content = listOf(ToolResultContent(toolCallId = "call1", toolName = "big_tool", output = largeContent))
            )
            val engine = DefaultToolExecutionEngine()
            val eventSlot = slot<AgentEvent>()
            val mockScope = mockk<ProducerScope<AgentEvent, List<AssistantMessage>>>(relaxed = true)
            coEvery { mockScope.push(capture(eventSlot)) } returns Unit

            val results = engine.executeToolCalls(
                agentContext = createMockContext(),
                toolCalls = listOf(ToolCallContent("call1", "big_tool", "{}")),
                tools = listOf(createTestTool("big_tool", result = largeResult)),
                eventStream = mockScope,
                scope = this,
                turnId = 0
            )

            // ToolCallResult.resultText is guarded (does not contain original large content)
            assertEquals(1, results.size)
            assertFalse(results[0].resultText.contains("UNIQUE_MARKER_12345"),
                "resultText must not contain the unique marker from original content")

            // ToolExecutionEndEvent was emitted with guarded content
            coVerify { mockScope.push(any<ToolExecutionEndEvent>()) }
            val endEvent = eventSlot.captured as ToolExecutionEndEvent
            val resultOutput = endEvent.result.content.filterIsInstance<ToolResultContent>().firstOrNull()?.output ?: ""
            assertTrue(resultOutput.contains("saved to"), "event result must be a spill notice")
            assertTrue(resultOutput.contains("RE-RUN the tool"), "event result must contain guidance")
            assertFalse(resultOutput.contains("UNIQUE_MARKER_12345"), "event result must not contain original content")
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
