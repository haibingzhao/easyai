package com.easy.easyai.core.message

import com.easy.easyai.core.model.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.chat.messages.AssistantMessage as SpringAiAssistantMsg
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.metadata.ChatGenerationMetadata
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageConverterTest {

    private val converter = DefaultMessageConverter()

    @Nested
    inner class `toSpringAiMessages` {

        @Test
        fun `converts UserMessage to Spring AI UserMessage`() {
            val messages = listOf(UserMessage("Hello"))
            val result = converter.toSpringAiMessages(messages)

            assertEquals(1, result.size)
            assertTrue(result[0] is org.springframework.ai.chat.messages.UserMessage)
            assertEquals("Hello", result[0].text)
        }

        @Test
        fun `converts AssistantMessage with text to Spring AI AssistantMessage`() {
            val messages = listOf(AssistantMessage(id = "test-id", content = listOf(TextContent("Response"))))
            val result = converter.toSpringAiMessages(messages)

            assertEquals(1, result.size)
            assertTrue(result[0] is SpringAiAssistantMsg)
        }

        @Test
        fun `converts AssistantMessage with tool calls to Spring AI AssistantMessage`() {
            val messages = listOf(
                AssistantMessage(
                    id = "test-id",
                    content = listOf(
                        TextContent("Calling tool"),
                        ToolCallContent("call1", "read", """{"path": "test.txt"}""")
                    )
                )
            )
            val result = converter.toSpringAiMessages(messages)

            assertEquals(1, result.size)
            val assistantMsg = result[0] as SpringAiAssistantMsg
            assertEquals(1, assistantMsg.toolCalls.size)
            assertEquals("call1", assistantMsg.toolCalls[0].id)
        }

        @Test
        fun `converts AssistantMessage with tool calls and ToolResultMessage to AssistantMessage + ToolResponseMessage`() {
            val messages = listOf(
                AssistantMessage(
                    id = "test-id",
                    content = listOf(
                        TextContent("Calling tool"),
                        ToolCallContent("call1", "read", """{"path": "test.txt"}""")
                    )
                ),
                ToolResultMessage(
                    id = "tool-result-id",
                    toolResults = listOf(
                        ToolResultEntry("call1", "read", "file content")
                    )
                )
            )
            val result = converter.toSpringAiMessages(messages)

            assertEquals(2, result.size)
            assertTrue(result[0] is SpringAiAssistantMsg)
            assertTrue(result[1] is org.springframework.ai.chat.messages.ToolResponseMessage)
            val toolResponse = result[1] as org.springframework.ai.chat.messages.ToolResponseMessage
            assertEquals(1, toolResponse.responses.size)
            assertEquals("call1", toolResponse.responses[0].id)
            assertEquals("file content", toolResponse.responses[0].responseData)
        }

        @Test
        fun `filters out empty user messages`() {
            val messages = listOf(UserMessage(""))
            val result = converter.toSpringAiMessages(messages)
            assertTrue(result.isEmpty())
        }

        @Test
        fun `inlines text file content when total size within limit`(@TempDir tempDir: Path) {
            val file = tempDir.resolve("small.txt")
            Files.writeString(file, "Hello from file")
            val messages = listOf(
                UserMessage(content = listOf(
                    TextContent("Please read this"),
                    FileRefContent(filePath = file.toString(), name = "small.txt", mimeType = "text/plain", source = "inline", displayOffset = 16)
                ))
            )
            val result = converter.toSpringAiMessages(messages)
            assertEquals(1, result.size)
            val text = result[0].text!!
            assertTrue(text.contains("Hello from file"), "Expected file content to be inlined, got: $text")
            assertTrue(text.contains("<file name=\"small.txt\">"), "Expected XML wrapper")
        }

        @Test
        fun `anchors folder marker at end of text for directory attachments`() {
            val messages = listOf(
                UserMessage(content = listOf(
                    TextContent("Save the summary here"),
                    FolderRefContent(filePath = "/proj/summary", name = "summary", displayOffset = 21)
                ))
            )
            val result = converter.toSpringAiMessages(messages)

            assertEquals(1, result.size)
            val text = result[0].text!!
            // Directory attachments (no inline position) anchor at the end of the text
            assertTrue(
                text.startsWith("Save the summary here[folder summary: /proj/summary]"),
                "Expected end-anchored marker, got: $text"
            )
            assertTrue(text.contains("directory listing"), "Expected LLM instruction to explore via tools")
            assertFalse(text.contains("<folders>"), "Aggregated fallback block should be gone")
        }

        @Test
        fun `anchors folder references inline at their recorded offsets`() {
            val base = "save x to  and y to , ok"
            val messages = listOf(
                UserMessage(content = listOf(
                    TextContent(base),
                    FolderRefContent(filePath = "/proj/a", name = "a", displayOffset = 10),
                    FolderRefContent(filePath = "/proj/b", name = "b", displayOffset = 20)
                ))
            )
            val result = converter.toSpringAiMessages(messages)

            assertEquals(1, result.size)
            val text = result[0].text!!
            // Markers sit exactly where the user placed them — sentence-to-folder mapping preserved
            assertTrue(
                text.contains("save x to [folder a: /proj/a] and y to [folder b: /proj/b], ok"),
                "Expected inline anchored markers, got: $text"
            )
            assertFalse(text.contains("<folders>"), "Anchored folders should not fall back to the trailing list")
            assertEquals(1, text.split("directory listing").size - 1, "Explore instruction should appear exactly once")
        }

        @Test
        fun `anchors file content inline at its recorded offset`(@TempDir tempDir: Path) {
            val file = tempDir.resolve("a.txt")
            Files.writeString(file, "HELLO")
            val messages = listOf(
                UserMessage(content = listOf(
                    TextContent("read  now"),
                    FileRefContent(filePath = file.toString(), name = "a.txt", mimeType = "text/plain", source = "inline", displayOffset = 5)
                ))
            )
            val result = converter.toSpringAiMessages(messages)

            assertEquals(1, result.size)
            val text = result[0].text!!
            assertTrue(
                text.contains("read <file name=\"a.txt\">\n<![CDATA[HELLO]]>\n</file> now"),
                "Expected file content anchored inline at offset, got: $text"
            )
        }

        @Test
        fun `anchors mixed file and folder refs in ascending offset order`(@TempDir tempDir: Path) {
            val file = tempDir.resolve("f.txt")
            Files.writeString(file, "X")
            val messages = listOf(
                UserMessage(content = listOf(
                    TextContent("a  b  c"),
                    FileRefContent(filePath = file.toString(), name = "f.txt", mimeType = "text/plain", source = "inline", displayOffset = 2),
                    FolderRefContent(filePath = "/proj/d", name = "d", displayOffset = 5)
                ))
            )
            val result = converter.toSpringAiMessages(messages)

            assertEquals(1, result.size)
            val text = result[0].text!!
            assertTrue(
                text.contains("a <file name=\"f.txt\">\n<![CDATA[X]]>\n</file> b [folder d: /proj/d] c"),
                "Expected both refs anchored at their positions, got: $text"
            )
        }

        @Test
        fun `renders one inline marker per folder reference including duplicates`() {
            val messages = listOf(
                UserMessage(content = listOf(
                    TextContent("Compare  with  and again "),
                    FolderRefContent(filePath = "/proj/a", name = "a", displayOffset = 8),
                    FolderRefContent(filePath = "/proj/b", name = "b", displayOffset = 16),
                    FolderRefContent(filePath = "/proj/a", name = "a", displayOffset = 25)
                ))
            )
            val result = converter.toSpringAiMessages(messages)

            assertEquals(1, result.size)
            val text = result[0].text!!
            assertFalse(text.contains("<folders>"), "Aggregated fallback block should be gone")
            // Inline markers are positional: each occurrence gets its own marker, no dedup
            assertEquals(2, Regex("\\[folder a: /proj/a\\]").findAll(text).count(), "Duplicate path: each occurrence gets its own marker")
            assertEquals(1, Regex("\\[folder b: /proj/b\\]").findAll(text).count())
            assertEquals(1, text.split("directory listing").size - 1, "Explore instruction should appear exactly once")
        }

        @Test
        fun `converts to path-only references when total size exceeds limit`(@TempDir tempDir: Path) {
            // Set a very low limit (100 bytes) so our small files exceed it
            val lowLimitConverter = DefaultMessageConverter(maxTotalInlineFileBytes = 100L)
            val file1 = tempDir.resolve("a.txt")
            val file2 = tempDir.resolve("b.txt")
            Files.writeString(file1, "A".repeat(60))
            Files.writeString(file2, "B".repeat(60))
            val messages = listOf(
                UserMessage(content = listOf(
                    TextContent("Read these files"),
                    FileRefContent(filePath = file1.toString(), name = "a.txt", mimeType = "text/plain", source = "inline", displayOffset = 16),
                    FileRefContent(filePath = file2.toString(), name = "b.txt", mimeType = "text/plain", source = "inline", displayOffset = 16)
                ))
            )
            val result = lowLimitConverter.toSpringAiMessages(messages)
            assertEquals(1, result.size)
            val text = result[0].text!!
            // Should NOT contain the inlined CDATA content
            assertFalse(text.contains("CDATA"), "File content should NOT be inlined when total exceeds limit")
            // Should contain the path-only summary
            assertTrue(text.contains("<attached-files>"), "Expected attached-files summary block")
            assertTrue(text.contains("a.txt"), "Expected file name in summary")
            assertTrue(text.contains("b.txt"), "Expected file name in summary")
            assertTrue(text.contains("Use the read tool"), "Expected LLM instruction to use read tool")
        }

        @Test
        fun `images are still inlined as Media even when text total exceeds limit`(@TempDir tempDir: Path) {
            val lowLimitConverter = DefaultMessageConverter(maxTotalInlineFileBytes = 10L)
            val textFile = tempDir.resolve("big.txt")
            Files.writeString(textFile, "X".repeat(50))
            // Create a tiny PNG (1x1 transparent pixel) using ImageIO
            val imageFile = tempDir.resolve("tiny.png")
            val bi = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            javax.imageio.ImageIO.write(bi, "png", imageFile.toFile())
            val messages = listOf(
                UserMessage(content = listOf(
                    TextContent("Check these"),
                    FileRefContent(filePath = textFile.toString(), name = "big.txt", mimeType = "text/plain", source = "inline", displayOffset = 12),
                    FileRefContent(filePath = imageFile.toString(), name = "tiny.png", mimeType = "image/png", source = "inline", displayOffset = 12)
                ))
            )
            val result = lowLimitConverter.toSpringAiMessages(messages)
            assertEquals(1, result.size)
            val springAiMsg = result[0] as org.springframework.ai.chat.messages.UserMessage
            // Image should still be in Media
            assertEquals(1, springAiMsg.media.size, "Image should still be present as Media")
            // Text file should be path-only
            val text = springAiMsg.text!!
            assertFalse(text.contains("CDATA"), "Text file should NOT be inlined")
            assertTrue(text.contains("big.txt"), "Expected text file name in summary")
        }
    }

    @Nested
    inner class `tool result pass-through` {

        @Test
        fun `passes oversized tool results through unchanged at send time`() {
            // Tool results are guarded at generation time (AgentLoop / PendingToolCallExecutor),
            // so the converter must not re-process them here.
            val big = "k".repeat(250_000)
            val messages = listOf(
                ToolResultMessage(toolResults = listOf(ToolResultEntry("call1", "search", big)))
            )
            val result = converter.toSpringAiMessages(messages)

            val toolResponse = result.single() as org.springframework.ai.chat.messages.ToolResponseMessage
            val response = toolResponse.responses.single()
            assertEquals("call1", response.id)
            assertEquals(big, response.responseData, "send time must not truncate or alter the result")
            assertFalse(response.responseData.contains("[output truncated:"))
        }

        @Test
        fun `leaves tool results within the limit unchanged`() {
            val messages = listOf(
                ToolResultMessage(toolResults = listOf(ToolResultEntry("call1", "read", "file content")))
            )
            val result = converter.toSpringAiMessages(messages)

            val toolResponse = result.single() as org.springframework.ai.chat.messages.ToolResponseMessage
            assertEquals("file content", toolResponse.responses.single().responseData)
        }
    }

    @Nested
    inner class `fromSpringAiResponse` {

        private fun createMockResponse(
            text: String,
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
            every { response.metadata } returns responseMetadata
            return response
        }

        @Test
        fun `extracts text content`() {
            val response = createMockResponse("Hello world")
            val result = converter.fromSpringAiResponse(response)

            assertEquals("Hello world", result.text())
            assertEquals(StopReason.STOP, result.stopReason)
        }

        @Test
        fun `extracts tool calls`() {
            val tc = SpringAiAssistantMsg.ToolCall("call1", "function", "read", """{"path":"test.txt"}""")
            val response = createMockResponse("", listOf(tc), finishReason = "tool_calls")
            val result = converter.fromSpringAiResponse(response)

            assertEquals(1, result.toolCalls().size)
            assertEquals("call1", result.toolCalls()[0].id)
            assertEquals("read", result.toolCalls()[0].name)
            assertEquals(StopReason.TOOL_USE, result.stopReason)
        }

        @Test
        fun `handles length finish reason`() {
            val response = createMockResponse("truncated", finishReason = "length")
            val result = converter.fromSpringAiResponse(response)

            assertEquals(StopReason.LENGTH, result.stopReason)
        }
    }
}
