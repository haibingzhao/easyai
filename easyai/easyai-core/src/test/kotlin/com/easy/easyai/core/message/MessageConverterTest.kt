package com.easy.easyai.core.message

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.model.*
import com.easy.easyai.core.storage.ObjectContent
import com.easy.easyai.core.storage.ObjectMeta
import com.easy.easyai.core.storage.ObjectStorage
import com.easy.easyai.core.storage.ObjectStorageException
import com.easy.easyai.core.storage.ObjectStorageResolver
import com.easy.easyai.core.storage.StoredFileReference
import com.easy.easyai.core.tool.ToolContextProjector
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
import tools.jackson.databind.node.ObjectNode
import com.easy.easyai.api.llm.AssistantMessage as SpringAiAssistantMsg
import com.easy.easyai.api.llm.ChatResponse
import com.easy.easyai.api.llm.ChatResponseMetadata
import com.easy.easyai.api.llm.Generation
import com.easy.easyai.api.llm.ChatGenerationMetadata
import com.easy.easyai.api.llm.MediaSource
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
import com.easy.easyai.api.llm.UserMessage as SpringAiUserMsg

class MessageConverterTest {

    /**
     * Inline stand-in for the render_visual context projector (whose production copy lives in
     * easyai-tools and cannot be referenced from easyai-core tests). Replaces the bulky `code`
     * argument with a size-bearing placeholder so the elision wiring is exercised end to end.
     */
    private val renderVisualProjector = ToolContextProjector { args ->
        val mapper = SharedObjectMapper.instance
        val node = mapper.readTree(args)
        val code = node.get("code")
        if (node is ObjectNode && code != null && code.isString) {
            val bytes = code.stringValue().toByteArray(Charsets.UTF_8).size
            node.put("code", "[fragment elided from context: $bytes bytes, rendered inline in the UI]")
            mapper.writeValueAsString(node)
        } else args
    }

    private val converter = DefaultMessageConverter(
        contextProjectors = mapOf("render_visual" to renderVisualProjector)
    )

    private fun writePng(path: Path): Path {
        val image = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        javax.imageio.ImageIO.write(image, "png", path.toFile())
        return path
    }

    @Nested
    inner class `toLlmMessages` {

        @Test
        fun `converts UserMessage to Spring AI UserMessage`() = runTest {
            val messages = listOf(UserMessage("Hello"))
            val result = converter.toLlmMessages(messages)

            assertEquals(1, result.size)
            assertTrue(result[0] is com.easy.easyai.api.llm.UserMessage)
            assertEquals("Hello", result[0].text)
        }

        @Test
        fun `converts AssistantMessage with text to Spring AI AssistantMessage`() = runTest {
            val messages = listOf(AssistantMessage(id = "test-id", content = listOf(TextContent("Response"))))
            val result = converter.toLlmMessages(messages)

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
            val result = converter.toLlmMessages(messages)

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
            val result = converter.toLlmMessages(messages)

            assertEquals(2, result.size)
            assertTrue(result[0] is SpringAiAssistantMsg)
            assertTrue(result[1] is com.easy.easyai.api.llm.ToolResponseMessage)
            val toolResponse = result[1] as com.easy.easyai.api.llm.ToolResponseMessage
            assertEquals(1, toolResponse.responses.size)
            assertEquals("call1", toolResponse.responses[0].id)
            assertEquals("file content", toolResponse.responses[0].responseData)
        }

        @Test
        fun `filters out empty user messages`() = runTest {
            val messages = listOf(UserMessage(""))
            val result = converter.toLlmMessages(messages)
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
            val result = converter.toLlmMessages(messages)
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
            val result = converter.toLlmMessages(messages)

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
            val result = converter.toLlmMessages(messages)

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
            val result = converter.toLlmMessages(messages)

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
            val result = converter.toLlmMessages(messages)

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
            val result = converter.toLlmMessages(messages)

            assertEquals(1, result.size)
            val text = result[0].text!!
            assertFalse(text.contains("<folders>"), "Aggregated fallback block should be gone")
            // Inline markers are positional: each occurrence gets its own marker, no dedup
            assertEquals(2, Regex("\\[folder a: /proj/a\\]").findAll(text).count(), "Duplicate path: each occurrence gets its own marker")
            assertEquals(1, Regex("\\[folder b: /proj/b\\]").findAll(text).count())
            assertEquals(1, text.split("directory listing").size - 1, "Explore instruction should appear exactly once")
        }

        @Test
        fun `anchors image markers inline at their recorded offsets`(@TempDir tempDir: Path) = runTest {
            val img1 = writePng(tempDir.resolve("a.png"))
            val img2 = writePng(tempDir.resolve("b.png"))
            val messages = listOf(
                UserMessage(content = listOf(
                    TextContent("compare  and  please"),
                    FileRefContent(filePath = img1.toString(), name = "a.png", mimeType = "image/png", source = "inline", displayOffset = 8),
                    FileRefContent(filePath = img2.toString(), name = "b.png", mimeType = "image/png", source = "inline", displayOffset = 13)
                ))
            )
            val result = converter.toLlmMessages(messages).single() as SpringAiUserMsg

            assertEquals(2, result.media.size)
            val text = result.text!!
            assertTrue(
                text.contains("compare [image 1: a.png] and [image 2: b.png] please"),
                "Expected markers at the sentence positions, got: $text"
            )
            assertTrue(text.contains("N-th image"), "Expected numbering instruction, got: $text")
        }

        @Test
        fun `numbers image markers by media position including legacy base64 images`(@TempDir tempDir: Path) = runTest {
            val img = writePng(tempDir.resolve("i.png"))
            val messages = listOf(
                UserMessage(content = listOf(
                    TextContent("see "),
                    ImageContent(byteArrayOf(1, 2), "image/png"),
                    FileRefContent(filePath = img.toString(), name = "i.png", mimeType = "image/png", displayOffset = 4)
                ))
            )
            val result = converter.toLlmMessages(messages).single() as SpringAiUserMsg

            assertEquals(2, result.media.size)
            assertTrue(
                result.text!!.contains("see [image 2: i.png]"),
                "Legacy image occupies media slot 1, so the ref must be numbered 2, got: ${result.text}"
            )
        }

        @Test
        fun `keeps original attachment order for refs sharing one anchor offset`(@TempDir tempDir: Path) = runTest {
            val txt = tempDir.resolve("n.txt")
            Files.writeString(txt, "T")
            val img = writePng(tempDir.resolve("i.png"))
            val messages = listOf(
                UserMessage(content = listOf(
                    TextContent("hello"),
                    FolderRefContent(filePath = "/proj/d", name = "d", displayOffset = 5),
                    FileRefContent(filePath = img.toString(), name = "i.png", mimeType = "image/png", displayOffset = 5),
                    FileRefContent(filePath = txt.toString(), name = "n.txt", mimeType = "text/plain", displayOffset = 5)
                ))
            )
            val text = (converter.toLlmMessages(messages).single() as SpringAiUserMsg).text!!

            val folderAt = text.indexOf("[folder d: /proj/d]")
            val imageAt = text.indexOf("[image 1: i.png]")
            val fileAt = text.indexOf("<file name=\"n.txt\">")
            assertTrue(
                folderAt in 0 until imageAt && imageAt in 0 until fileAt,
                "Expected folder, image, file order at the shared anchor, got: $text"
            )
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
            val result = lowLimitConverter.toLlmMessages(messages)
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
            val result = lowLimitConverter.toLlmMessages(messages)
            assertEquals(1, result.size)
            val springAiMsg = result[0] as com.easy.easyai.api.llm.UserMessage
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
            val result = converter.toLlmMessages(messages)

            val toolResponse = result.single() as com.easy.easyai.api.llm.ToolResponseMessage
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
            val result = converter.toLlmMessages(messages)

            val toolResponse = result.single() as com.easy.easyai.api.llm.ToolResponseMessage
            assertEquals("file content", toolResponse.responses.single().responseData)
        }
    }

    @Nested
    inner class `thinking replay` {

        @Test
        fun `maps persisted thinking blocks onto the llm assistant message`() = runTest {
            val messages = listOf(
                AssistantMessage(
                    id = "asst1",
                    content = listOf(
                        ThinkingContent("pondering", thinkingSignature = "sig-abc"),
                        ThinkingContent("redacted chunk", thinkingSignature = "opaque-data", redacted = true),
                        TextContent("answer")
                    ),
                    stopReason = StopReason.STOP
                )
            )
            val result = converter.toLlmMessages(messages)

            val assistant = result.single() as SpringAiAssistantMsg
            assertEquals(2, assistant.thinkingBlocks.size)
            val first = assistant.thinkingBlocks[0]
            assertEquals("pondering", first.text)
            assertEquals("sig-abc", first.signature)
            assertFalse(first.redacted)
            val second = assistant.thinkingBlocks[1]
            assertEquals("redacted chunk", second.text)
            assertEquals("opaque-data", second.signature)
            assertTrue(second.redacted)
            assertEquals("answer", assistant.content)
        }

        @Test
        fun `assistant messages without thinking carry no blocks`() = runTest {
            val messages = listOf(
                AssistantMessage(id = "asst1", content = listOf(TextContent("answer")), stopReason = StopReason.STOP)
            )
            val result = converter.toLlmMessages(messages)
            val assistant = result.single() as SpringAiAssistantMsg
            assertTrue(assistant.thinkingBlocks.isEmpty())
        }
    }

    @Nested
    inner class `presigned url sanitization` {
        private val userId = "user-1"
        private val key = "chat-images/user-1/session-1/img.png"
        private val expiredUrl =
            "https://oss.example/$key?Expires=1000000000&OSSAccessKeyId=ak&Signature=old%2Bsig%3D"
        private val freshUrl =
            "https://oss.example/$key?Expires=4000000000&OSSAccessKeyId=ak&Signature=fresh%2Bsig%3D"
        private val reSignedUrl =
            "https://oss.example/$key?Expires=9999999999&OSSAccessKeyId=ak&Signature=new%2Bsig%3D"
        private val storage = mockk<ObjectStorage>()
        private val resolver = mockk<ObjectStorageResolver>()
        private val sanitizer = DefaultMessageConverter(objectStorageResolver = resolver)

        init {
            coEvery { resolver.resolve(listOf(userId)) } returns storage
            coEvery { storage.presignedGetUrl(key, StoredFileReference.URL_TTL_SECONDS) } returns reSignedUrl
        }

        @Test
        fun `re-signs expired presigned urls inside replayed tool call arguments`() = runTest {
            val messages = listOf(
                AssistantMessage(
                    id = "a1",
                    content = listOf(ToolCallContent("call1", "image_edit", """{"image_url": "$expiredUrl"}"""))
                )
            )

            val result = sanitizer.toLlmMessages(messages, userId)

            val toolCall = (result.single() as SpringAiAssistantMsg).toolCalls.single()
            assertEquals("""{"image_url": "$reSignedUrl"}""", toolCall.arguments)
            coVerify(exactly = 1) { storage.presignedGetUrl(key, StoredFileReference.URL_TTL_SECONDS) }
        }

        @Test
        fun `keeps presigned urls that expire outside the refresh margin`() = runTest {
            val messages = listOf(
                ToolResultMessage(toolResults = listOf(ToolResultEntry("call1", "image_edit", "saved $freshUrl")))
            )

            val result = sanitizer.toLlmMessages(messages, userId)

            val response = (result.single() as com.easy.easyai.api.llm.ToolResponseMessage).responses.single()
            assertEquals("saved $freshUrl", response.responseData)
            coVerify(exactly = 0) { storage.presignedGetUrl(any(), any()) }
        }

        @Test
        fun `preserves punctuation and prose glued after a presigned url`() = runTest {
            val messages = listOf(
                ToolResultMessage(toolResults = listOf(ToolResultEntry("call1", "image_edit", "see ($expiredUrl) 请查看")))
            )

            val result = sanitizer.toLlmMessages(messages, userId)

            val response = (result.single() as com.easy.easyai.api.llm.ToolResponseMessage).responses.single()
            assertEquals("see ($reSignedUrl) 请查看", response.responseData)
        }

        @Test
        fun `re-signs each url of a json-escaped run separately`() = runTest {
            val secondKey = "chat-images/user-1/session-1/img2.png"
            val expiredSecond =
                "https://oss.example/$secondKey?Expires=1000000000&OSSAccessKeyId=ak&Signature=two%2Bsig%3D"
            val reSignedSecond =
                "https://oss.example/$secondKey?Expires=9999999999&OSSAccessKeyId=ak&Signature=two2%2Bsig%3D"
            coEvery { storage.presignedGetUrl(secondKey, StoredFileReference.URL_TTL_SECONDS) } returns reSignedSecond
            val escaped = "\\\"$expiredUrl\\\" \\\"$expiredSecond\\\""
            val messages = listOf(
                ToolResultMessage(toolResults = listOf(ToolResultEntry("call1", "image_edit", escaped)))
            )

            val result = sanitizer.toLlmMessages(messages, userId)

            val response = (result.single() as com.easy.easyai.api.llm.ToolResponseMessage).responses.single()
            assertEquals("\\\"$reSignedUrl\\\" \\\"$reSignedSecond\\\"", response.responseData)
            coVerify(exactly = 1) { storage.presignedGetUrl(key, StoredFileReference.URL_TTL_SECONDS) }
            coVerify(exactly = 1) { storage.presignedGetUrl(secondKey, StoredFileReference.URL_TTL_SECONDS) }
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
            coEvery { resolver.resolve(listOf(userId)) } returns storage
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

            val first = storedConverter.toLlmMessages(messages, userId).single() as SpringAiUserMsg
            val second = storedConverter.toLlmMessages(messages, userId).single() as SpringAiUserMsg

            assertEquals(firstUrl, (first.media.single().source as MediaSource.Url).uri.toString())
            assertEquals(secondUrl, (second.media.single().source as MediaSource.Url).uri.toString())
            assertEquals("image/png", first.media.single().mimeType.toString())
            assertTrue(
                first.text!!.startsWith("Look [image 1: screenshot.png ($firstUrl)]"),
                "Expected the first turn's marker to carry its fresh signature, got: ${first.text}"
            )
            assertTrue(
                second.text!!.startsWith("Look [image 1: screenshot.png ($secondUrl)]"),
                "Expected the second turn's marker to carry its own fresh signature, got: ${second.text}"
            )
            assertFalse(second.text!!.contains("signature=first"), "Stale signatures must not be replayed")
            assertEquals(original, message)
            assertSame(ref, message.content[1])
            assertEquals(path, ref.filePath)
            coVerify(exactly = 0) { storage.get(any()) }
            coVerifyOrder {
                resolver.resolve(listOf(userId))
                storage.head(key)
                storage.presignedGetUrl(key, 3600L)
                resolver.resolve(listOf(userId))
                storage.head(key)
                storage.presignedGetUrl(key, 3600L)
            }
        }

        @Test
        fun `marker carries the fresh signature, never the persisted accessibleUrl`() = runTest {
            val freshUrl = "https://objects.example/image.png?signature=fresh%2Fvalue"
            coEvery { storage.presignedGetUrl(key, 3600L) } returns freshUrl
            val persisted = ref.copy(accessibleUrl = "/stale/local-copy.png")

            val result = storedConverter.toLlmMessages(
                listOf(UserMessage(content = listOf(TextContent("Look "), persisted))), userId
            ).single() as SpringAiUserMsg

            assertTrue(
                result.text!!.contains("[image 1: screenshot.png ($freshUrl)]"),
                "Expected the marker to carry this turn's signature, got: ${result.text}"
            )
            assertFalse(result.text!!.contains("/stale/local-copy.png"), "Persisted accessibleUrl must not be replayed")
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
                val result = storedConverter.toLlmMessages(messages, userId).single() as SpringAiUserMsg
                assertContentEquals(bytes, (result.media.single().source as MediaSource.Bytes).data)
                assertEquals("image/png", result.media.single().mimeType.toString())
                assertTrue(
                    result.text!!.contains("[image 1: screenshot.png]"),
                    "Byte-fallback signatures must stay out of the marker, got: ${result.text}"
                )
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
            val result = storedConverter.toLlmMessages(messages, userId).single() as SpringAiUserMsg
            assertContentEquals(bytes, (result.media.single().source as MediaSource.Bytes).data)
            coVerify(exactly = 1) { storage.get(key) }
        }

        @Test
        fun `missing object fails before signing or reading`() = runTest {
            coEvery { storage.head(key) } returns null
            assertFailsWith<ObjectStorageException> { storedConverter.toLlmMessages(messages, userId) }
            coVerify(exactly = 0) { storage.presignedGetUrl(any(), any()) }
            coVerify(exactly = 0) { storage.get(any()) }
        }

        @Test
        fun `object disappearing after head fails instead of dropping the image`() = runTest {
            coEvery { storage.get(key) } returns null
            assertFailsWith<ObjectStorageException> { storedConverter.toLlmMessages(messages, userId) }
        }

        @Test
        fun `missing resolver and disabled storage both fail explicitly`() = runTest {
            assertFailsWith<ObjectStorageException> { converter.toLlmMessages(messages, userId) }
            coEvery { resolver.resolve(listOf(userId)) } returns null
            assertFailsWith<ObjectStorageException> { storedConverter.toLlmMessages(messages, userId) }
            coVerify(exactly = 0) { storage.head(any()) }
        }

        @ParameterizedTest
        @ValueSource(strings = ["resolve", "head", "get"])
        fun `storage failures propagate without dropping the image`(operation: String) = runTest {
            val failure = ObjectStorageException("Storage unavailable")
            when (operation) {
                "resolve" -> coEvery { resolver.resolve(listOf(userId)) } throws failure
                "head" -> coEvery { storage.head(key) } throws failure
                "get" -> coEvery { storage.get(key) } throws failure
            }
            assertSame(failure, assertFailsWith<ObjectStorageException> {
                storedConverter.toLlmMessages(messages, userId)
            })
        }

        @ParameterizedTest
        @ValueSource(strings = ["resolve", "head", "sign", "get"])
        fun `cancellation propagates from every storage operation`(operation: String) = runTest {
            val cancellation = CancellationException("Cancelled")
            when (operation) {
                "resolve" -> coEvery { resolver.resolve(listOf(userId)) } throws cancellation
                "head" -> coEvery { storage.head(key) } throws cancellation
                "sign" -> coEvery { storage.presignedGetUrl(key, 3600L) } throws cancellation
                "get" -> coEvery { storage.get(key) } throws cancellation
            }
            assertSame(cancellation, assertFailsWith<CancellationException> {
                storedConverter.toLlmMessages(messages, userId)
            })
            if (operation != "get") {
                coVerify(exactly = 0) { storage.get(any()) }
            }
        }

        @ParameterizedTest
        @ValueSource(longs = [-1, 6291457])
        fun `invalid head size is rejected before signing or downloading`(size: Long) = runTest {
            coEvery { storage.head(key) } returns ObjectMeta(key, size)
            assertFailsWith<ObjectStorageException> { storedConverter.toLlmMessages(messages, userId) }
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
            val signed = storedConverter.toLlmMessages(messages, userId).single() as SpringAiUserMsg
            assertEquals("https://objects.example/image.png", (signed.media.single().source as MediaSource.Url).uri.toString())
            val downloaded = storedConverter.toLlmMessages(messages, userId).single() as SpringAiUserMsg
            assertEquals(limit, (downloaded.media.single().source as MediaSource.Bytes).data.size)
        }

        @Test
        fun `rechecks actual bytes when object grows after head`() = runTest {
            coEvery { storage.get(key) } returns ObjectContent(
                ObjectMeta(key, 3), ByteArray(StoredFileReference.MAX_IMAGE_BYTES + 1)
            )
            assertFailsWith<ObjectStorageException> { storedConverter.toLlmMessages(messages, userId) }
        }

        @Test
        fun `rechecks metadata when object grows after head`() = runTest {
            coEvery { storage.get(key) } returns ObjectContent(
                ObjectMeta(key, StoredFileReference.MAX_IMAGE_BYTES.toLong() + 1), bytes
            )
            assertFailsWith<ObjectStorageException> { storedConverter.toLlmMessages(messages, userId) }
        }

        @Test
        fun `rejects other users even for inline refs in a shared bucket before resolving storage`() = runTest {
            coEvery { resolver.resolve(any<Collection<String>>()) } returns storage
            val foreignRef = ref.copy(
                filePath = StoredFileReference.create("other-user", "session-1", "png"), source = "inline"
            )
            assertFailsWith<IllegalArgumentException> {
                storedConverter.toLlmMessages(listOf(UserMessage(content = listOf(foreignRef))), userId)
            }
            coVerify(exactly = 0) { resolver.resolve(any<Collection<String>>()) }
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
                    storedConverter.toLlmMessages(listOf(UserMessage(content = listOf(invalid))), userId)
                }
            }
            coVerify(exactly = 0) { resolver.resolve(any<Collection<String>>()) }
        }

        @Test
        fun `omitted user ID resolves the system owner`() = runTest {
            val systemPath = StoredFileReference.create("system", "session-1", "png")
            val systemKey = StoredFileReference.parse(systemPath, "system").key
            coEvery { resolver.resolve(listOf("system")) } returns storage
            coEvery { storage.head(systemKey) } returns ObjectMeta(systemKey, 3)
            coEvery { storage.presignedGetUrl(systemKey, 3600L) } returns "https://objects.example/system.png"
            val result = storedConverter.toLlmMessages(
                listOf(UserMessage(content = listOf(ref.copy(filePath = systemPath))))
            ).single() as SpringAiUserMsg
            assertEquals("https://objects.example/system.png", (result.media.single().source as MediaSource.Url).uri.toString())
            coVerify(exactly = 1) { resolver.resolve(listOf("system")) }
        }
    }

    @Nested
    inner class `render_visual fragment elision` {

        @Test
        fun `elides the code fragment but keeps title in replayed render_visual calls`() = runTest {
            val messages = listOf(
                AssistantMessage(
                    id = "a1",
                    content = listOf(
                        ToolCallContent("call1", "render_visual", """{"title":"My Chart","code":"<svg></svg>"}""")
                    )
                )
            )

            val toolCall = (converter.toLlmMessages(messages).single() as SpringAiAssistantMsg).toolCalls.single()

            assertTrue(toolCall.arguments.contains("\"title\":\"My Chart\""), "title must survive, got: ${toolCall.arguments}")
            assertTrue(
                toolCall.arguments.contains("[fragment elided from context: 11 bytes, rendered inline in the UI]"),
                "code must be replaced by a size-bearing placeholder, got: ${toolCall.arguments}"
            )
            assertFalse(toolCall.arguments.contains("<svg>"), "original fragment must not be replayed, got: ${toolCall.arguments}")
        }

        @Test
        fun `leaves other tools arguments untouched`() = runTest {
            val args = """{"path":"test.txt"}"""
            val messages = listOf(
                AssistantMessage(id = "a1", content = listOf(ToolCallContent("call1", "read", args)))
            )

            val toolCall = (converter.toLlmMessages(messages).single() as SpringAiAssistantMsg).toolCalls.single()

            assertEquals(args, toolCall.arguments)
        }

        @Test
        fun `does not modify the persisted message`() = runTest {
            val toolCallContent = ToolCallContent("call1", "render_visual", """{"title":"T","code":"<svg></svg>"}""")
            val message = AssistantMessage(id = "a1", content = listOf(toolCallContent))

            converter.toLlmMessages(listOf(message))

            assertTrue(
                toolCallContent.arguments.contains("<svg></svg>"),
                "persisted arguments must keep the full fragment for UI rendering, got: ${toolCallContent.arguments}"
            )
        }

        @Test
        fun `passes malformed render_visual arguments through unchanged`() = runTest {
            val broken = "{not json"
            val messages = listOf(
                AssistantMessage(id = "a1", content = listOf(ToolCallContent("call1", "render_visual", broken)))
            )

            val toolCall = (converter.toLlmMessages(messages).single() as SpringAiAssistantMsg).toolCalls.single()

            assertEquals(broken, toolCall.arguments)
        }
    }

    @Nested
    inner class `fromChatResponse` {

        private fun createMockResponse(
            text: String,
            toolCalls: List<SpringAiAssistantMsg.ToolCall> = emptyList(),
            finishReason: String? = "stop"
        ): ChatResponse = ChatResponse(
            listOf(
                Generation(
                    SpringAiAssistantMsg(content = text, toolCalls = toolCalls),
                    ChatGenerationMetadata(finishReason = finishReason)
                )
            )
        )

        @Test
        fun `extracts text content`() = runTest {
            val response = createMockResponse("Hello world")
            val result = converter.fromChatResponse(response)

            assertEquals("Hello world", result.text())
            assertEquals(StopReason.STOP, result.stopReason)
        }

        @Test
        fun `extracts tool calls`() = runTest {
            val tc = SpringAiAssistantMsg.ToolCall("call1", "function", "read", """{"path":"test.txt"}""")
            val response = createMockResponse("", listOf(tc), finishReason = "tool_calls")
            val result = converter.fromChatResponse(response)

            assertEquals(1, result.toolCalls().size)
            assertEquals("call1", result.toolCalls()[0].id)
            assertEquals("read", result.toolCalls()[0].name)
            assertEquals(StopReason.TOOL_USE, result.stopReason)
        }

        @Test
        fun `handles length finish reason`() = runTest {
            val response = createMockResponse("truncated", finishReason = "length")
            val result = converter.fromChatResponse(response)

            assertEquals(StopReason.LENGTH, result.stopReason)
        }
    }
}
