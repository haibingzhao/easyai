package com.easy.easyai.tools.media

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.ToolResultContent
import com.easy.easyai.core.storage.ObjectContent
import com.easy.easyai.core.storage.ObjectMeta
import com.easy.easyai.core.storage.ObjectStorage
import com.easy.easyai.core.storage.ObjectStorageResolver
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.nio.file.Files

/**
 * Tests [FetchMediaTool]: expiring URLs must come back as stable MediaResult references,
 * partial failures must not sink the successful items, and the builder must always find a
 * storage target when either per-user object storage or the local fallback exists.
 */
class FetchMediaToolTest {

    private class InMemoryStorage : ObjectStorage {
        val objects = mutableMapOf<String, ObjectContent>()
        override suspend fun head(key: String): ObjectMeta? = objects[key]?.meta
        override suspend fun get(key: String): ObjectContent? = objects[key]
        override suspend fun put(key: String, bytes: ByteArray, contentType: String): ObjectMeta {
            val meta = ObjectMeta(key = key, size = bytes.size.toLong())
            objects[key] = ObjectContent(meta, bytes)
            return meta
        }
        override suspend fun delete(key: String): Boolean = objects.remove(key) != null
        override suspend fun presignedGetUrl(key: String, ttlSeconds: Long): String? = null
    }

    private val metadata = ToolMetadata(name = "fetch_media", description = "test", permissionCategory = "fetch_media")

    private fun tool(
        storage: ObjectStorage,
        projectPath: Path? = Path.of("/proj"),
        fetch: suspend (String) -> Pair<ByteArray, String?>
    ) = FetchMediaTool(metadata, storage, "alice", projectPath = projectPath, fetch = fetch)

    private fun execute(tool: FetchMediaTool, args: Map<String, Any?>): ToolResult = runBlocking {
        tool.execute(
            agentContext = AgentContext(agentId = "test", userId = "alice", projectPath = Path.of("/proj")),
            toolCallId = "call-test",
            args = args,
            coroutineScope = CoroutineScope(Job())
        )
    }

    private fun output(result: ToolResult): String {
        val text = result.content.filterIsInstance<ToolResultContent>().joinToString("") { it.output }
        return text.ifBlank { result.content.filterIsInstance<TextContent>().joinToString("") { it.text } }
    }

    @Nested
    inner class `storing downloads` {

        @Test
        fun `single url becomes a stable media reference`() {
            val storage = InMemoryStorage()
            val result = execute(
                tool(storage) { "fake-png-bytes".toByteArray() to "image/png" },
                mapOf("url" to "https://example.com/a.png?Expires=1")
            )

            assertFalse(result.isError)
            val json = SharedObjectMapper.instance.readTree(output(result))
            assertEquals("completed", json.path("status").asString())
            assertEquals("image", json.path("kind").asString())
            val item = json.path("items")[0]
            val key = item.path("key").asString()
            assertTrue(key.startsWith("media/alice/"), key)
            assertTrue(key.endsWith(".png"), key)
            assertEquals("/api/media/file?key=$key", item.path("url").asString())
            assertTrue(storage.objects.getValue(key).bytes.contentEquals("fake-png-bytes".toByteArray()))
        }

        @Test
        fun `mime falls back to the url extension when the response says nothing`() {
            val storage = InMemoryStorage()
            val result = execute(
                tool(storage) { ByteArray(1) to null },
                mapOf("url" to "https://example.com/pic.jpg?v=2")
            )

            val item = SharedObjectMapper.instance.readTree(output(result)).path("items")[0]
            assertEquals("image/jpeg", item.path("mimeType").asString())
            assertTrue(item.path("key").asString().endsWith(".jpg"), item.path("key").asString())
        }

        @Test
        fun `explicit mimeType parameter overrides the response content type`() {
            val storage = InMemoryStorage()
            execute(
                tool(storage) { ByteArray(1) to "application/octet-stream" },
                mapOf("url" to "https://example.com/blob", "mimeType" to "image/webp")
            )

            assertTrue(storage.objects.keys.first().endsWith(".webp"), storage.objects.keys.toString())
        }
    }

    @Nested
    inner class `arguments` {

        @Test
        fun `missing url and urls is an error`() {
            val result = execute(tool(InMemoryStorage()) { ByteArray(1) to null }, emptyMap())
            assertTrue(result.isError)
        }

        @Test
        fun `url and urls merge and deduplicate`() {
            var calls = 0
            val result = execute(
                tool(InMemoryStorage()) { calls++; ByteArray(1) to "image/png" },
                mapOf("url" to "https://example.com/a.png", "urls" to listOf("https://example.com/a.png", "https://example.com/b.png"))
            )

            assertFalse(result.isError)
            assertEquals(2, calls)
        }

        @Test
        fun `more than eight urls is rejected`() {
            val urls = (1..9).map { "https://example.com/$it.png" }
            val result = execute(tool(InMemoryStorage()) { ByteArray(1) to null }, mapOf("urls" to urls))
            assertTrue(result.isError)
        }
    }

    @Nested
    inner class `failures` {

        @Test
        fun `a failed url is reported while successes survive`() {
            val result = execute(
                tool(InMemoryStorage()) { url ->
                    if (url.contains("/bad.png")) throw IllegalStateException("HTTP 403") else ByteArray(3) to "image/png"
                },
                mapOf("urls" to listOf("https://example.com/good.png", "https://example.com/bad.png?sig=x"))
            )

            assertFalse(result.isError)
            val json = SharedObjectMapper.instance.readTree(output(result))
            assertEquals(1, json.path("items").size())
            assertTrue(json.path("error").asString().contains("https://example.com/bad.png"), json.toString())
            assertFalse(json.path("error").asString().contains("sig="), "signed query must be stripped from errors")
        }

        @Test
        fun `all urls failing is an error result`() {
            val result = execute(
                tool(InMemoryStorage()) { throw IllegalStateException("boom") },
                mapOf("url" to "https://example.com/a.png")
            )
            assertTrue(result.isError)
            assertTrue(output(result).contains("boom"), output(result))
        }
    }

    @Nested
    inner class `ssrf guard` {

        @Test
        fun `internal and non-http urls are refused`() {
            for (url in listOf(
                "http://localhost/x.png",
                "http://127.0.0.1/x.png",
                "http://169.254.169.254/latest",
                "http://10.0.0.5/x.png",
                "ftp://example.com/x",
                "/relative/path.png"
            )) {
                assertFailsWith<IllegalArgumentException>("expected refusal for $url") { MediaFetch.validateNotInternal(url) }
            }
        }
    }

    @Nested
    inner class `savePath` {

        @Test
        fun `single url with savePath writes file to disk`() {
            val tmpDir = Files.createTempDirectory("fetch-media-test")
            val target = tmpDir.resolve("downloaded.png")
            try {
                val storage = InMemoryStorage()
                val result = execute(
                    tool(storage) { "fake-png-bytes".toByteArray() to "image/png" },
                    mapOf("url" to "https://example.com/a.png", "savePath" to target.toString())
                )

                assertFalse(result.isError)
                assertTrue(Files.exists(target), "file should be written to disk")
                assertTrue(Files.readAllBytes(target).contentEquals("fake-png-bytes".toByteArray()))
                assertTrue(storage.objects.isNotEmpty(), "OSS storage should still receive the file")
            } finally {
                Files.walk(tmpDir).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }

        @Test
        fun `relative savePath resolves against project path`() {
            val tmpDir = Files.createTempDirectory("fetch-media-test")
            try {
                val storage = InMemoryStorage()
                val result = execute(
                    tool(storage, projectPath = tmpDir) { "bytes".toByteArray() to "image/png" },
                    mapOf("url" to "https://example.com/a.png", "savePath" to "output/pic.png")
                )

                assertFalse(result.isError)
                assertTrue(Files.exists(tmpDir.resolve("output/pic.png")))
            } finally {
                Files.walk(tmpDir).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }

        @Test
        fun `savePath without project path is an error`() {
            val storage = InMemoryStorage()
            val result = execute(
                tool(storage, projectPath = null) { "bytes".toByteArray() to "image/png" },
                mapOf("url" to "https://example.com/a.png", "savePath" to "/tmp/foo.png")
            )
            assertTrue(result.isError)
            assertTrue(output(result).contains("project path"), output(result))
        }
    }

    @Nested
    inner class `internal URL short-circuit` {

        @Test
        fun `internal URL skips download and re-storage`() {
            val storage = InMemoryStorage()
            runBlocking { storage.put("media/alice/2026-09-23/abc.png", "original-bytes".toByteArray(), "image/png") }
            var downloadCalls = 0

            val result = execute(
                tool(storage) { downloadCalls++; ByteArray(0) to "image/png" },
                mapOf("url" to "/api/media/file?key=media/alice/2026-09-23/abc.png")
            )

            assertFalse(result.isError)
            assertEquals(0, downloadCalls, "should not download when URL is internal")
            assertEquals(1, storage.objects.size, "should not re-store")
            val json = SharedObjectMapper.instance.readTree(output(result))
            assertEquals("media/alice/2026-09-23/abc.png", json.path("items")[0].path("key").asString())
        }

        @Test
        fun `internal URL with savePath reads from storage and writes to disk`() {
            val tmpDir = Files.createTempDirectory("fetch-media-internal")
            val target = tmpDir.resolve("saved.png")
            try {
                val storage = InMemoryStorage()
                runBlocking { storage.put("media/alice/2026-09-23/abc.png", "stored-bytes".toByteArray(), "image/png") }

                val result = execute(
                    tool(storage) { throw IllegalStateException("should not be called") },
                    mapOf("url" to "/api/media/file?key=media/alice/2026-09-23/abc.png", "savePath" to target.toString())
                )

                assertFalse(result.isError)
                assertTrue(Files.exists(target))
                assertTrue(Files.readAllBytes(target).contentEquals("stored-bytes".toByteArray()))
            } finally {
                Files.walk(tmpDir).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }

        @Test
        fun `storageKeyOf parses internal URLs`() {
            assertEquals("media/alice/x.png", FetchMediaTool.storageKeyOf("/api/media/file?key=media/alice/x.png"))
            assertEquals("media/alice/x.png", FetchMediaTool.storageKeyOf("http://localhost:8080/api/media/file?key=media/alice/x.png"))
            assertEquals("media/alice/x.png", FetchMediaTool.storageKeyOf("http://localhost:8080/api/media/file?token=abc&key=media/alice/x.png"))
            assertNull(FetchMediaTool.storageKeyOf("https://example.com/image.png"))
            assertNull(FetchMediaTool.storageKeyOf("https://example.com/api/media/file"))
            assertNull(FetchMediaTool.storageKeyOf("/api/other?key=foo"))
        }
    }

    @Nested
    inner class `builder storage selection` {

        private val context = AgentContext(agentId = "test", userId = "alice", projectPath = Path.of("/proj"))

        private fun agentServiceWith(resolver: ObjectStorageResolver?): AgentService =
            mockk<AgentService> { every { objectStorageResolver } returns resolver }

        @Test
        fun `per-user object storage keeps the tool available`() {
            val resolver = mockk<ObjectStorageResolver> {
                coEvery { resolve("alice") } returns InMemoryStorage()
            }
            val tool = FetchMediaToolBuilder(localStorage = null).build(context, agentServiceWith(resolver))
            assertNotNull(tool)
        }

        @Test
        fun `local fallback keeps the tool alive without user storage`() {
            val resolver = mockk<ObjectStorageResolver> {
                coEvery { resolve(any()) } returns null
            }
            val tool = FetchMediaToolBuilder(localStorage = InMemoryStorage()).build(context, agentServiceWith(resolver))
            assertNotNull(tool)
        }

        @Test
        fun `no storage layer at all hides the tool`() {
            val tool = FetchMediaToolBuilder(localStorage = null).build(context, agentServiceWith(null))
            assertNull(tool)
        }
    }
}
