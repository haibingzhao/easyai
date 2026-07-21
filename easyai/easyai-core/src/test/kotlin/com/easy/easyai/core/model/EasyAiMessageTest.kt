package com.easy.easyai.core.model

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EasyAiMessageTest {

    @Nested
    inner class `Role enum` {

        @Test
        fun `has all expected roles`() {
            val roles = Role.entries.toSet()
            assertEquals(setOf(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.TOOL, Role.ERROR, Role.CUSTOM), roles)
        }
    }

    @Nested
    inner class `TextContent` {

        @Test
        fun `returns text type`() {
            val content = TextContent("hello")
            assertEquals("text", content.type)
        }

        @Test
        fun `stores text correctly`() {
            val content = TextContent("hello world")
            assertEquals("hello world", content.text)
        }
    }

    @Nested
    inner class `ThinkingContent` {

        @Test
        fun `returns thinking type`() {
            val content = ThinkingContent("thinking...")
            assertEquals("thinking", content.type)
        }

        @Test
        fun `supports redacted flag`() {
            val content = ThinkingContent("redacted", redacted = true)
            assertTrue(content.redacted)
        }
    }

    @Nested
    inner class `ToolCallContent` {

        @Test
        fun `returns toolCall type`() {
            val content = ToolCallContent("id1", "read", """{"path": "test.txt"}""")
            assertEquals("toolCall", content.type)
        }
    }

    @Nested
    inner class `UserMessage` {

        @Test
        fun `has USER role`() {
            val msg = UserMessage("hello")
            assertEquals(Role.USER, msg.role)
        }

        @Test
        fun `convenience constructor creates text content`() {
            val msg = UserMessage("hello")
            assertEquals(1, msg.content.size)
            val textBlock = msg.content[0] as com.easy.easyai.core.model.TextContent
            assertEquals("hello", textBlock.text)
        }
    }

    @Nested
    inner class `AssistantMessage` {

        @Test
        fun `has ASSISTANT role`() {
            val msg = AssistantMessage(id = "test-id", content = listOf(TextContent("response")))
            assertEquals(Role.ASSISTANT, msg.role)
        }

        @Test
        fun `text concatenates all text content`() {
            val msg = AssistantMessage(id = "test-id", content = listOf(
                TextContent("Hello"),
                ThinkingContent("thinking"),
                TextContent(" world")
            ))
            assertEquals("Hello world", msg.text())
        }

        @Test
        fun `toolCalls extracts tool call content`() {
            val tc = ToolCallContent("call1", "read", "{}")
            val msg = AssistantMessage(id = "test-id", content = listOf(
                TextContent("I'll read the file"),
                tc
            ))
            assertEquals(1, msg.toolCalls().size)
            assertEquals(tc, msg.toolCalls()[0])
        }
    }

    @Nested
    inner class `ImageContent` {

        @Test
        fun `equals compares byte array contents`() {
            val data1 = byteArrayOf(1, 2, 3)
            val data2 = byteArrayOf(1, 2, 3)
            val img1 = ImageContent(data1, "image/png")
            val img2 = ImageContent(data2, "image/png")
            assertEquals(img1, img2)
        }

        @Test
        fun `not equals when data differs`() {
            val img1 = ImageContent(byteArrayOf(1, 2), "image/png")
            val img2 = ImageContent(byteArrayOf(3, 4), "image/png")
            assertFalse(img1 == img2)
        }
    }

    @Nested
    inner class `ToolResultMessage` {

        @Test
        fun `has TOOL role`() {
            val msg = ToolResultMessage(
                id = "test-id",
                toolResults = listOf(
                    ToolResultEntry("call1", "read", "file content")
                )
            )
            assertEquals(Role.TOOL, msg.role)
        }

        @Test
        fun `returns tool result content blocks`() {
            val msg = ToolResultMessage(
                toolResults = listOf(ToolResultEntry("call1", "read", "result"))
            )
            assertEquals(1, msg.content.size)
            val block = msg.content[0] as ToolResultContent
            assertEquals("call1", block.toolCallId)
            assertEquals("read", block.toolName)
            assertEquals("result", block.output)
        }

        @Test
        fun `holds multiple tool results`() {
            val entries = listOf(
                ToolResultEntry("call1", "read", "content1"),
                ToolResultEntry("call2", "bash", "output")
            )
            val msg = ToolResultMessage(toolResults = entries)
            assertEquals(2, msg.toolResults.size)
            assertEquals("call1", msg.toolResults[0].toolCallId)
            assertEquals("bash", msg.toolResults[1].toolName)
        }
    }

    @Nested
    inner class `ToolResultEntry` {

        @Test
        fun `stores tool call details correctly`() {
            val entry = ToolResultEntry("call123", "grep", "match found")
            assertEquals("call123", entry.toolCallId)
            assertEquals("grep", entry.toolName)
            assertEquals("match found", entry.result)
        }
    }

    @Nested
    inner class `StopReason enum` {

        @Test
        fun `has all expected values`() {
            val reasons = StopReason.entries.toSet()
            assertEquals(
                setOf(StopReason.STOP, StopReason.LENGTH, StopReason.TOOL_USE, StopReason.ERROR, StopReason.ABORTED),
                reasons
            )
        }
    }

    @Nested
    inner class `Usage data class` {

        @Test
        fun `defaults to zero values`() {
            val usage = Usage()
            assertEquals(0, usage.inputTokens)
            assertEquals(0, usage.outputTokens)
            assertEquals(0, usage.cacheReadTokens)
            assertEquals(0, usage.cacheWriteTokens)
            assertEquals(0.0, usage.cost)
        }
    }
}
