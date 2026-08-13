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
                    FileRefContent(filePath = file.toString(), name = "small.txt", mimeType = "text/plain", source = "inline")
                ))
            )
            val result = converter.toSpringAiMessages(messages)
            assertEquals(1, result.size)
            val text = result[0].text!!
            assertTrue(text.contains("Hello from file"), "Expected file content to be inlined, got: $text")
            assertTrue(text.contains("<file name=\"small.txt\">"), "Expected XML wrapper")
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
                    FileRefContent(filePath = file1.toString(), name = "a.txt", mimeType = "text/plain", source = "inline"),
                    FileRefContent(filePath = file2.toString(), name = "b.txt", mimeType = "text/plain", source = "inline")
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
                    FileRefContent(filePath = textFile.toString(), name = "big.txt", mimeType = "text/plain", source = "inline"),
                    FileRefContent(filePath = imageFile.toString(), name = "tiny.png", mimeType = "image/png", source = "inline")
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
