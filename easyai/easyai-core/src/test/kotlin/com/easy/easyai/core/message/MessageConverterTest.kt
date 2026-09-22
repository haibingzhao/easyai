package com.easy.easyai.core.message

import com.easy.easyai.core.model.*
import com.easy.easyai.core.storage.ObjectContent
import com.easy.easyai.core.storage.ObjectMeta
import com.easy.easyai.core.storage.ObjectStorage
import com.easy.easyai.core.storage.ObjectStorageException
import com.easy.easyai.core.storage.ObjectStorageResolver
import com.easy.easyai.core.storage.StoredFileReference
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.ai.chat.messages.AssistantMessage as SpringAiAssistantMsg
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.metadata.ChatGenerationMetadata
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.springframework.ai.chat.messages.UserMessage as SpringAiUserMsg

class MessageConverterTest {

    private val converter = DefaultMessageConverter()

    @Nested
    inner class `toSpringAiMessages` {

        @Test
        fun `converts UserMessage to Spring AI UserMessage`() = runTest {
            val messages = listOf(UserMessage("Hello"))
            val result = converter.toSpringAiMessages(messages)

            assertEquals(1, result.size)
            assertTrue(result[0] is org.springframework.ai.chat.messages.UserMessage)
            assertEquals("Hello", result[0].text)
        }

        @Test
        fun `converts AssistantMessage with text to Spring AI AssistantMessage`() = runTest {
            val messages = listOf(AssistantMessage(id = "test-id", content = listOf(TextContent("Response"))))
            val result = converter.toSpringAiMessages(messages)

            assertEquals(1, result.size)
            assertTrue(result[0] is SpringAiAssistantMsg)
        }

        @Test
        fun `converts AssistantMessage with tool calls to Spring AI AssistantMessage`() = runTest {
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
        fun `converts AssistantMessage with tool calls and ToolResultMessage to AssistantMessage + ToolResponseMessage`() = runTest {
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
        fun `filters out empty user messages`() = runTest {
            val messages = listOf(UserMessage(""))
            val result = converter.toSpringAiMessages(messages)
            assertTrue(result.isEmpty())
        }

        @Test
        fun `inlines text file content when total size within limit`(@TempDir tempDir: Path) = runTest {
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
        fun `anchors folder marker at end of text for directory attachments`() = runTest {
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
        fun `anchors folder references inline at their recorded offsets`() = runTest {
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
        fun `anchors file content inline at its recorded offset`(@TempDir tempDir: Path) = runTest {
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
        fun `anchors mixed file and folder refs in ascending offset order`(@TempDir tempDir: Path) = runTest {
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
        fun `renders one inline marker per folder reference including duplicates`() = runTest {
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
        fun `converts to path-only references when total size exceeds limit`(@TempDir tempDir: Path) = runTest {
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
        fun `images are still inlined as Media even when text total exceeds limit`(@TempDir tempDir: Path) = runTest {
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
        fun `passes oversized tool results through unchanged at send time`() = runTest {
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
        fun `leaves tool results within the limit unchanged`() = runTest {
            val messages = listOf(
                ToolResultMessage(toolResults = listOf(ToolResultEntry("call1", "read", "file content")))
            )
            val result = converter.toSpringAiMessages(messages)

            val toolResponse = result.single() as org.springframework.ai.chat.messages.ToolResponseMessage
            assertEquals("file content", toolResponse.responses.single().responseData)
        }
    }

    @Nested
    inner class `stored chat images` {
        private val userId = "user-1"
        private val path = StoredFileReference.create(userId, "session-1", "png")
        private val key = StoredFileReference.parse(path, userId).key
        private val bytes = byteArrayOf(1, 2, 3)
        private val ref = FileRefContent(path, "screenshot.png", "image/png", displayOffset = 5)
        private val message = UserMessage(content = listOf(TextContent("Look "), ref))
        private val messages = listOf(message)
        private val storage = mockk<ObjectStorage>()
        private val resolver = mockk<ObjectStorageResolver>()
        private val storedConverter = DefaultMessageConverter(
            allowedBaseDir = Path.of("/local-images"),
            objectStorageResolver = resolver
        )

        init {
            coEvery { resolver.resolve(userId) } returns storage
            coEvery { storage.head(key) } returns ObjectMeta(key, bytes.size.toLong())
            coEvery { storage.get(key) } returns ObjectContent(ObjectMeta(key, bytes.size.toLong()), bytes)
            coEvery { storage.presignedGetUrl(key, StoredFileReference.URL_TTL_SECONDS) } returns null
        }

        @Test
        fun `uses fresh HTTPS signatures on every request without changing the persisted message`() = runTest {
            val firstUrl = "https://objects.example/image.png?signature=first%2Fvalue"
            val secondUrl = "https://objects.example/image.png?signature=second%2Fvalue"
            val original = message.copy(content = message.content.toList())
            coEvery { storage.presignedGetUrl(key, 3600L) } returnsMany listOf(firstUrl, secondUrl)

            val first = storedConverter.toSpringAiMessages(messages, userId).single() as SpringAiUserMsg
            val second = storedConverter.toSpringAiMessages(messages, userId).single() as SpringAiUserMsg

            assertEquals(firstUrl, first.media.single().data)
            assertEquals(secondUrl, second.media.single().data)
            assertEquals("image/png", first.media.single().mimeType.toString())
            assertEquals("Look ", first.text)
            assertEquals(original, message)
            assertSame(ref, message.content[1])
            assertEquals(path, ref.filePath)
            coVerify(exactly = 0) { storage.get(any()) }
            coVerifyOrder {
                resolver.resolve(userId)
                storage.head(key)
                storage.presignedGetUrl(key, 3600L)
                resolver.resolve(userId)
                storage.head(key)
                storage.presignedGetUrl(key, 3600L)
            }
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = [
            "http://objects.example/image.png?signature=keep-http",
            "file:///local/image.png",
            "https://objects.example/invalid uri",
            "https:/missing-host.png",
            "ftp://objects.example/image.png"
        ])
        fun `non HTTPS or unavailable signatures read request scoped bytes`(url: String?) = runTest {
            coEvery { storage.presignedGetUrl(key, 3600L) } returns url
            val original = message.copy(content = message.content.toList())

            repeat(2) {
                val result = storedConverter.toSpringAiMessages(messages, userId).single() as SpringAiUserMsg
                assertContentEquals(bytes, assertIs<ByteArray>(result.media.single().data))
                assertEquals("image/png", result.media.single().mimeType.toString())
            }

            assertEquals(original, message)
            assertSame(ref, message.content[1])
            coVerify(exactly = 2) { storage.get(key) }
            coVerify(exactly = 2) { storage.head(key) }
            coVerify(exactly = 2) { storage.presignedGetUrl(key, 3600L) }
        }

        @Test
        fun `signing failure falls back to reading the object`() = runTest {
            coEvery { storage.presignedGetUrl(key, 3600L) } throws ObjectStorageException("Signing unavailable")
            val result = storedConverter.toSpringAiMessages(messages, userId).single() as SpringAiUserMsg
            assertContentEquals(bytes, result.media.single().dataAsByteArray)
            coVerify(exactly = 1) { storage.get(key) }
        }

        @Test
        fun `missing object fails before signing or reading`() = runTest {
            coEvery { storage.head(key) } returns null
            assertFailsWith<ObjectStorageException> { storedConverter.toSpringAiMessages(messages, userId) }
            coVerify(exactly = 0) { storage.presignedGetUrl(any(), any()) }
            coVerify(exactly = 0) { storage.get(any()) }
        }

        @Test
        fun `object disappearing after head fails instead of dropping the image`() = runTest {
            coEvery { storage.get(key) } returns null
            assertFailsWith<ObjectStorageException> { storedConverter.toSpringAiMessages(messages, userId) }
        }

        @Test
        fun `missing resolver and disabled storage both fail explicitly`() = runTest {
            assertFailsWith<ObjectStorageException> { converter.toSpringAiMessages(messages, userId) }
            coEvery { resolver.resolve(userId) } returns null
            assertFailsWith<ObjectStorageException> { storedConverter.toSpringAiMessages(messages, userId) }
            coVerify(exactly = 0) { storage.head(any()) }
        }

        @ParameterizedTest
        @ValueSource(strings = ["resolve", "head", "get"])
        fun `storage failures propagate without dropping the image`(operation: String) = runTest {
            val failure = ObjectStorageException("Storage unavailable")
            when (operation) {
                "resolve" -> coEvery { resolver.resolve(userId) } throws failure
                "head" -> coEvery { storage.head(key) } throws failure
                "get" -> coEvery { storage.get(key) } throws failure
            }
            assertSame(failure, assertFailsWith<ObjectStorageException> {
                storedConverter.toSpringAiMessages(messages, userId)
            })
        }

        @ParameterizedTest
        @ValueSource(strings = ["resolve", "head", "sign", "get"])
        fun `cancellation propagates from every storage operation`(operation: String) = runTest {
            val cancellation = CancellationException("Cancelled")
            when (operation) {
                "resolve" -> coEvery { resolver.resolve(userId) } throws cancellation
                "head" -> coEvery { storage.head(key) } throws cancellation
                "sign" -> coEvery { storage.presignedGetUrl(key, 3600L) } throws cancellation
                "get" -> coEvery { storage.get(key) } throws cancellation
            }
            assertSame(cancellation, assertFailsWith<CancellationException> {
                storedConverter.toSpringAiMessages(messages, userId)
            })
            if (operation != "get") {
                coVerify(exactly = 0) { storage.get(any()) }
            }
        }

        @ParameterizedTest
        @ValueSource(longs = [-1, 6291457])
        fun `invalid head size is rejected before signing or downloading`(size: Long) = runTest {
            coEvery { storage.head(key) } returns ObjectMeta(key, size)
            assertFailsWith<ObjectStorageException> { storedConverter.toSpringAiMessages(messages, userId) }
            coVerify(exactly = 0) { storage.presignedGetUrl(any(), any()) }
            coVerify(exactly = 0) { storage.get(any()) }
        }

        @Test
        fun `accepts exactly six MB for both URL and byte media`() = runTest {
            val limit = StoredFileReference.MAX_IMAGE_BYTES
            val meta = ObjectMeta(key, limit.toLong())
            coEvery { storage.head(key) } returns meta
            coEvery { storage.presignedGetUrl(key, 3600L) } returnsMany listOf("https://objects.example/image.png", null)
            coEvery { storage.get(key) } returns ObjectContent(meta, ByteArray(limit))
            val signed = storedConverter.toSpringAiMessages(messages, userId).single() as SpringAiUserMsg
            assertEquals("https://objects.example/image.png", signed.media.single().data)
            val downloaded = storedConverter.toSpringAiMessages(messages, userId).single() as SpringAiUserMsg
            assertEquals(limit, downloaded.media.single().dataAsByteArray.size)
        }

        @Test
        fun `rechecks actual bytes when object grows after head`() = runTest {
            coEvery { storage.get(key) } returns ObjectContent(
                ObjectMeta(key, 3), ByteArray(StoredFileReference.MAX_IMAGE_BYTES + 1)
            )
            assertFailsWith<ObjectStorageException> { storedConverter.toSpringAiMessages(messages, userId) }
        }

        @Test
        fun `rechecks metadata when object grows after head`() = runTest {
            coEvery { storage.get(key) } returns ObjectContent(
                ObjectMeta(key, StoredFileReference.MAX_IMAGE_BYTES.toLong() + 1), bytes
            )
            assertFailsWith<ObjectStorageException> { storedConverter.toSpringAiMessages(messages, userId) }
        }

        @Test
        fun `rejects other users even for inline refs in a shared bucket before resolving storage`() = runTest {
            coEvery { resolver.resolve(any()) } returns storage
            val foreignRef = ref.copy(
                filePath = StoredFileReference.create("other-user", "session-1", "png"), source = "inline"
            )
            assertFailsWith<IllegalArgumentException> {
                storedConverter.toSpringAiMessages(listOf(UserMessage(content = listOf(foreignRef))), userId)
            }
            coVerify(exactly = 0) { resolver.resolve(any()) }
            coVerify(exactly = 0) { storage.head(any()) }
        }

        @Test
        fun `rejects malformed stored references instead of falling through to local paths`() = runTest {
            val invalidRefs = listOf(
                ref.copy(filePath = path.replace("chat-images/", "skills/")),
                ref.copy(filePath = path.replace("/session-1/", "/%2e%2e/")),
                ref.copy(filePath = path.replace(".png", ".svg")),
                ref.copy(mimeType = "text/plain")
            )
            for (invalid in invalidRefs) {
                assertFailsWith<IllegalArgumentException> {
                    storedConverter.toSpringAiMessages(listOf(UserMessage(content = listOf(invalid))), userId)
                }
            }
            coVerify(exactly = 0) { resolver.resolve(any()) }
        }

        @Test
        fun `omitted user ID resolves the system owner`() = runTest {
            val systemPath = StoredFileReference.create("system", "session-1", "png")
            val systemKey = StoredFileReference.parse(systemPath, "system").key
            coEvery { resolver.resolve("system") } returns storage
            coEvery { storage.head(systemKey) } returns ObjectMeta(systemKey, 3)
            coEvery { storage.presignedGetUrl(systemKey, 3600L) } returns "https://objects.example/system.png"
            val result = storedConverter.toSpringAiMessages(
                listOf(UserMessage(content = listOf(ref.copy(filePath = systemPath))))
            ).single() as SpringAiUserMsg
            assertEquals("https://objects.example/system.png", result.media.single().data)
            coVerify(exactly = 1) { resolver.resolve("system") }
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
        fun `extracts text content`() = runTest {
            val response = createMockResponse("Hello world")
            val result = converter.fromSpringAiResponse(response)

            assertEquals("Hello world", result.text())
            assertEquals(StopReason.STOP, result.stopReason)
        }

        @Test
        fun `extracts tool calls`() = runTest {
            val tc = SpringAiAssistantMsg.ToolCall("call1", "function", "read", """{"path":"test.txt"}""")
            val response = createMockResponse("", listOf(tc), finishReason = "tool_calls")
            val result = converter.fromSpringAiResponse(response)

            assertEquals(1, result.toolCalls().size)
            assertEquals("call1", result.toolCalls()[0].id)
            assertEquals("read", result.toolCalls()[0].name)
            assertEquals(StopReason.TOOL_USE, result.stopReason)
        }

        @Test
        fun `handles length finish reason`() = runTest {
            val response = createMockResponse("truncated", finishReason = "length")
            val result = converter.fromSpringAiResponse(response)

            assertEquals(StopReason.LENGTH, result.stopReason)
        }
    }
}
