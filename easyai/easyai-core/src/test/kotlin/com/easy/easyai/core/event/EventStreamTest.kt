package com.easy.easyai.core.event

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventStreamTest {

    @Nested
    inner class `EventStream creation` {

        @Test
        fun `streams events and returns result`() = runBlocking {
            val stream = EventStream.create<AgentEvent, Int> {
                push(TurnStartEvent(0, "test"))
                push(TurnEndEvent(0, "test"))
                end(42)
            }

            val events = mutableListOf<AgentEvent>()
            stream.asFlow().collect { events.add(it) }
            assertEquals(2, events.size)
            assertTrue(events[0] is TurnStartEvent)
            assertTrue(events[1] is TurnEndEvent)
            assertEquals(42, stream.result())
        }

        @Test
        fun `emits events as they are pushed`() = runBlocking {
            val stream = EventStream.create<AgentEvent, String> {
                push(AgentStartEvent("test"))
                delay(50)
                push(AgentEndEvent("test", "done"))
                end("completed")
            }

            val received = mutableListOf<String>()
            stream.asFlow().collect { event ->
                when (event) {
                    is AgentStartEvent -> received.add("start")
                    is AgentEndEvent -> received.add("end")
                    else -> {}
                }
            }

            assertEquals(listOf("start", "end"), received)
            assertEquals("completed", stream.result())
        }

        @Test
        fun `propagates exceptions to result`() = runBlocking {
            val expected = RuntimeException("test error")
            val stream = EventStream.create<AgentEvent, String> {
                throw expected
            }

            var caught: Throwable? = null
            try {
                stream.result()
            } catch (e: Throwable) {
                caught = e
            }

            assertTrue(caught is RuntimeException)
        }
    }

    @Nested
    inner class `AgentEvent types` {

        @Test
        fun `agent start has correct type`() {
            val event = AgentStartEvent("session1")
            assertEquals("agent_start", event.type)
            assertEquals("session1", event.sessionId)
        }

        @Test
        fun `agent end has correct type`() {
            val event = AgentEndEvent("session1", "completed")
            assertEquals("agent_end", event.type)
        }

        @Test
        fun `turn events have correct types`() {
            assertEquals("turn_start", TurnStartEvent(1, "test").type)
            assertEquals("turn_end", TurnEndEvent(1, "test").type)
        }

        @Test
        fun `message events have correct types`() {
            assertEquals("message_start", MessageStartEvent("msg1", 0, "test").type)
            assertEquals("message_update", MessageUpdateEvent("msg1", "delta", 0, "test").type)
            assertEquals("thinking_update", ThinkingUpdateEvent("msg1", "delta", 0, "test").type)
            assertEquals("thinking_end", ThinkingEndEvent("msg1", 0, "test").type)
        }

        @Test
        fun `tool events have turnId`() {
            assertEquals("tool_execution_start", ToolExecutionStartEvent("call1", "tool", emptyMap(), 0, "test").type)
            val toolResult = com.easy.easyai.core.tool.ToolResult(content = listOf(com.easy.easyai.core.model.TextContent("result")))
            assertEquals("tool_execution_end", ToolExecutionEndEvent("call1", "tool", toolResult, turnId = 0, sessionId = "test").type)
            assertEquals("error", ErrorEvent(RuntimeException("test"), "test").type)
        }

        @Test
        fun `tool execution events have correct types`() {
            val start = ToolExecutionStartEvent("call1", "read", emptyMap(), 0, "test")
            assertEquals("tool_execution_start", start.type)

            val end = ToolExecutionEndEvent("call1", "read", com.easy.easyai.core.tool.ToolResult(content = emptyList()), turnId = 0, sessionId = "test")
            assertEquals("tool_execution_end", end.type)
        }

        @Test
        fun `error event has correct type`() {
            val err = ErrorEvent(RuntimeException("boom"), "test")
            assertEquals("error", err.type)
        }
    }
}
