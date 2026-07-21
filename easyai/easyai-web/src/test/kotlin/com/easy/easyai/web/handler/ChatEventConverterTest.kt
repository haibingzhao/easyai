package com.easy.easyai.web.handler

import com.easy.easyai.core.event.*
import com.easy.easyai.core.model.*
import com.easy.easyai.core.tool.ToolResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import com.easy.easyai.web.model.ChatStreamEvent as SSEvent

class ChatEventConverterTest {

    @Nested
    inner class `Convert AgentStartEvent` {
        @Test
        fun `converts to Start chat event`() {
            val event = AgentStartEvent("session-1")
            val result = ChatEventConverter.convert(event)
            assertEquals(1, result.size)
            assertTrue(result[0] is SSEvent.Start)
            assertEquals("session-1", (result[0] as SSEvent.Start).sessionId)
        }
    }

    @Nested
    inner class `Convert MessageUpdateEvent` {
        @Test
        fun `converts to TextDelta chat event`() {
            val event = MessageUpdateEvent("msg-1", "Hello", 0, "session-1")
            val result = ChatEventConverter.convert(event)
            assertEquals(1, result.size)
            val delta = result[0] as SSEvent.TextDelta
            assertEquals(0, delta.contentIndex)
            assertEquals("Hello", delta.delta)
            assertEquals(0, delta.turnId)
        }
    }

    @Nested
    inner class `Convert ThinkingUpdateEvent` {
        @Test
        fun `converts to ThinkingDelta chat event`() {
            val event = ThinkingUpdateEvent("msg-1", "Let me think...", 0, "session-1")
            val result = ChatEventConverter.convert(event)
            assertEquals(1, result.size)
            val delta = result[0] as SSEvent.ThinkingDelta
            assertEquals(0, delta.contentIndex)
            assertEquals("Let me think...", delta.delta)
            assertEquals(0, delta.turnId)
        }
    }

    @Nested
    inner class `Convert ToolExecutionStartEvent` {
        @Test
        fun `converts to ToolExecutionStart chat event`() {
            val event = ToolExecutionStartEvent("tc-1", "read", mapOf("path" to "test.txt"), 0, "session-1")
            val result = ChatEventConverter.convert(event)
            assertEquals(1, result.size)
            val start = result[0] as SSEvent.ToolExecutionStart
            assertEquals("tc-1", start.toolCallId)
            assertEquals("read", start.toolName)
        }
    }

    @Nested
    inner class `Convert ToolExecutionEndEvent` {
        @Test
        fun `converts to ToolExecutionEnd chat event with success`() {
            val toolResult = ToolResult(content = listOf(TextContent("content")), isError = false)
            val event = ToolExecutionEndEvent("tc-1", "read", toolResult, turnId = 0, sessionId = "session-1")
            val result = ChatEventConverter.convert(event)
            assertEquals(1, result.size)
            val end = result[0] as SSEvent.ToolExecutionEnd
            assertEquals("tc-1", end.toolCallId)
            assertFalse(end.isError)
        }

        @Test
        fun `converts to ToolExecutionEnd chat event with error`() {
            val toolResult = ToolResult(content = listOf(TextContent("error")), isError = true)
            val event = ToolExecutionEndEvent("tc-1", "read", toolResult, turnId = 0, sessionId = "session-1", isError = true)
            val result = ChatEventConverter.convert(event)
            assertEquals(1, result.size)
            val end = result[0] as SSEvent.ToolExecutionEnd
            assertTrue(end.isError)
        }
    }

    @Nested
    inner class `Convert ErrorEvent` {
        @Test
        fun `converts to Error chat event`() {
            val error = RuntimeException("Something went wrong")
            val event = ErrorEvent(error, "session-1")
            val result = ChatEventConverter.convert(event)
            assertEquals(1, result.size)
            val err = result[0] as SSEvent.Error
            assertEquals("Something went wrong", err.errorMessage)
        }
    }

    @Nested
    inner class `Convert MessageEndEvent` {
        @Test
        fun `emits MessageEnd event with usage when message has token usage`() {
            val message = AssistantMessage(
                id = "msg-u",
                content = listOf(TextContent("Hello")),
                stopReason = StopReason.STOP,
                usage = Usage(inputTokens = 10, outputTokens = 20, cacheReadTokens = 5, cacheWriteTokens = 3, durationMs = 1500)
            )
            val event = MessageEndEvent("msg-u", 0, "session-1", message, usage = message.usage)
            val result = ChatEventConverter.convert(event)
            // Should contain TextEnd + MessageEnd
            assertEquals(2, result.size)
            assertTrue(result[0] is SSEvent.TextEnd)
            val messageEnd = result[1] as SSEvent.MessageEnd
            assertEquals("msg-u", messageEnd.messageId)
            assertNotNull(messageEnd.usage)
            assertEquals(10, messageEnd.usage!!.inputTokens)
            assertEquals(20, messageEnd.usage!!.outputTokens)
            assertEquals(30, messageEnd.usage!!.totalTokens)
            assertEquals(5, messageEnd.usage!!.cacheReadTokens)
            assertEquals(3, messageEnd.usage!!.cacheWriteTokens)
            assertEquals(1500, messageEnd.usage!!.durationMs)
        }

        @Test
        fun `emits MessageEnd event without usage when message has zero tokens`() {
            val message = AssistantMessage(
                id = "msg-z",
                content = listOf(TextContent("Hello")),
                stopReason = StopReason.STOP,
                usage = Usage()
            )
            val event = MessageEndEvent("msg-z", 0, "session-1", message, usage = message.usage)
            val result = ChatEventConverter.convert(event)
            // Should contain TextEnd + MessageEnd (MessageEnd has null usage since tokens are 0)
            assertEquals(2, result.size)
            assertTrue(result[0] is SSEvent.TextEnd)
            val messageEnd = result[1] as SSEvent.MessageEnd
            assertEquals("msg-z", messageEnd.messageId)
            assertNull(messageEnd.usage)
        }

        @Test
        fun `emits toolcall_start and toolcall_end for ToolCallContent blocks`() {
            val message = AssistantMessage(
                id = "msg-tc",
                content = listOf(
                    TextContent("I'll run tools"),
                    ToolCallContent(id = "tc-1", name = "bash", arguments = "{}"),
                    ToolCallContent(id = "tc-2", name = "read", arguments = """{"path":"x"}""")
                ),
                stopReason = StopReason.TOOL_USE
            )
            val event = MessageEndEvent("msg-tc", 0, "session-1", message)
            val result = ChatEventConverter.convert(event)
            // TextEnd + (ToolCallStart+ToolCallEnd)×2 + MessageEnd = 6
            assertEquals(6, result.size)
            // TextEnd for the TextContent block
            assertTrue(result[0] is SSEvent.TextEnd)
            // First tool: toolcall_start + toolcall_end
            val tc1Start = result[1] as SSEvent.ToolCallStart
            assertEquals("tc-1", tc1Start.id)
            assertEquals("bash", tc1Start.toolName)
            assertEquals(1, tc1Start.contentIndex)
            val tc1End = result[2] as SSEvent.ToolCallEnd
            assertEquals("tc-1", tc1End.id)
            assertEquals(1, tc1End.contentIndex)
            // Second tool: toolcall_start + toolcall_end
            val tc2Start = result[3] as SSEvent.ToolCallStart
            assertEquals("tc-2", tc2Start.id)
            assertEquals("read", tc2Start.toolName)
            assertEquals(2, tc2Start.contentIndex)
            val tc2End = result[4] as SSEvent.ToolCallEnd
            assertEquals("tc-2", tc2End.id)
            assertEquals(2, tc2End.contentIndex)
            // MessageEnd
            assertTrue(result[5] is SSEvent.MessageEnd)
        }

        @Test
        fun `emits no toolcall events when message has only TextContent`() {
            val message = AssistantMessage(
                id = "msg-text",
                content = listOf(TextContent("Just text")),
                stopReason = StopReason.STOP
            )
            val event = MessageEndEvent("msg-text", 0, "session-1", message)
            val result = ChatEventConverter.convert(event)
            // TextEnd + MessageEnd = 2
            assertEquals(2, result.size)
            assertTrue(result.none { it is SSEvent.ToolCallStart })
            assertTrue(result.none { it is SSEvent.ToolCallEnd })
        }
    }

    @Nested
    inner class `Create Done event` {
        @Test
        fun `creates Done event with usage info`() {
            val messages = listOf(
                AssistantMessage(
                    id = "msg-1",
                    content = listOf(TextContent("Hello")),
                    stopReason = StopReason.STOP,
                    usage = Usage(inputTokens = 10, outputTokens = 20)
                )
            )
            val result = ChatEventConverter.createDoneEvent(messages)
            assertTrue(result is SSEvent.Done)
            val done = result as SSEvent.Done
            assertNotNull(done.usage)
            assertEquals(10, done.usage!!.inputTokens)
            assertEquals(20, done.usage.outputTokens)
            assertEquals(30, done.usage.totalTokens)
        }

        @Test
        fun `creates Done event without usage for zero tokens`() {
            val messages = listOf(
                AssistantMessage(
                    id = "msg-2",
                    content = listOf(TextContent("Hello")),
                    stopReason = StopReason.STOP
                )
            )
            val result = ChatEventConverter.createDoneEvent(messages)
            assertTrue(result is SSEvent.Done)
            assertNull((result as SSEvent.Done).usage)
        }
    }
}