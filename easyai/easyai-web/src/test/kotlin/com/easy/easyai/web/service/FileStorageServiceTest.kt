package com.easy.easyai.web.service

import com.easy.easyai.core.storage.ObjectContent
import com.easy.easyai.core.storage.ObjectMeta
import com.easy.easyai.core.storage.ObjectStorage
import com.easy.easyai.core.storage.ObjectStorageException
import com.easy.easyai.core.storage.ObjectStorageResolver
import com.easy.easyai.core.storage.StoredFileReference
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileStorageServiceTest {
    @TempDir
    lateinit var root: Path

    private val resolver = mockk<ObjectStorageResolver>()
    private val storage = mockk<ObjectStorage>()
    private val bytes = byteArrayOf(1, 2, 3)

    @Test
    fun `stores image remotely without creating local session files`() = runTest {
        coEvery { resolver.resolve("alice") } returns storage
        coEvery { storage.put(any(), bytes, "image/png") } answers { ObjectMeta(firstArg(), 3) }
        val service = FileStorageService(root.toString(), resolver)

        val reference = service.saveImage("session-1", bytes, "png", "alice", "image/png")

        assertTrue(StoredFileReference.isStored(reference))
        val parsed = StoredFileReference.parse(reference, "alice")
        assertEquals("session-1", parsed.sessionId)
        coVerify(exactly = 1) { storage.put(parsed.key, bytes, "image/png") }
        assertFalse(Files.exists(service.imagesRoot.resolve("session-1")))
    }

    @Test
    fun `keeps local files when storage is unconfigured and for text attachments`() = runTest {
        coEvery { resolver.resolve("alice") } returns null
        val service = FileStorageService(root.toString(), resolver)
        val path = service.saveImage("session-1", bytes, "png", "alice", "image/png")
        assertContentEquals(bytes, Files.readAllBytes(Path.of(path)))
        assertEquals("session-1", service.sessionIdFor(path, "alice"))
        val text = service.saveImage("session-1", bytes, "txt", "alice", "text/plain")
        assertNotNull(service.getFile(text))
        coVerify(exactly = 1) { resolver.resolve("alice") }
    }

    @Test
    fun `does not fall back to disk on resolver or upload failure`() = runTest {
        val service = FileStorageService(root.toString(), resolver)
        coEvery { resolver.resolve("alice") } throws ObjectStorageException("unavailable")
        assertFailsWith<ObjectStorageException> {
            service.saveImage("session-1", bytes, "png", "alice", "image/png")
        }
        coEvery { resolver.resolve("alice") } returns storage
        coEvery { storage.put(any(), any(), any()) } throws ObjectStorageException("upload failed")
        assertFailsWith<ObjectStorageException> {
            service.saveImage("session-1", bytes, "png", "alice", "image/png")
        }
        assertFalse(Files.exists(service.imagesRoot.resolve("session-1")))
    }

    @Test
    fun `signs each load and uses authenticated proxy for local or unavailable signatures`() = runTest {
        val service = FileStorageService(root.toString(), resolver)
        val reference = StoredFileReference.create("alice", "session-1", "png")
        val key = StoredFileReference.parse(reference, "alice").key
        coEvery { resolver.resolve("alice") } returns storage
        coEvery { storage.head(key) } returns ObjectMeta(key, 3)
        coEvery { storage.presignedGetUrl(key, 3600L) } returnsMany listOf(
            "https://example.test/image?signature=one", "https://example.test/image?signature=two", "file:///tmp/image.png", null
        )
        assertEquals("https://example.test/image?signature=one", service.resolveImageUrl(reference, "alice"))
        assertEquals("https://example.test/image?signature=two", service.resolveImageUrl(reference, "alice"))
        assertEquals(service.proxyUrl(reference), service.resolveImageUrl(reference, "alice"))
        assertEquals(service.proxyUrl(reference), service.resolveImageUrl(reference, "alice"))
        coEvery { storage.presignedGetUrl(key, 3600L) } throws ObjectStorageException("signing failed")
        assertEquals(service.proxyUrl(reference), service.resolveImageUrl(reference, "alice"))
    }

    @Test
    fun `rejects other users before resolving shared storage`() = runTest {
        val service = FileStorageService(root.toString(), resolver)
        val reference = StoredFileReference.create("alice", "session-1", "png")
        assertFailsWith<IllegalArgumentException> { service.resolveImageUrl(reference, "bob") }
        assertFailsWith<IllegalArgumentException> { service.readStoredImage(reference, "bob") }
        coVerify(exactly = 0) { resolver.resolve(any()) }
    }

    @Test
    fun `reads bounded stored images and reports missing objects`() = runTest {
        val service = FileStorageService(root.toString(), resolver)
        val reference = StoredFileReference.create("alice", "session-1", "png")
        val key = StoredFileReference.parse(reference, "alice").key
        coEvery { resolver.resolve("alice") } returns storage
        coEvery { storage.head(key) } returns ObjectMeta(key, 3)
        coEvery { storage.get(key) } returns ObjectContent(ObjectMeta(key, 3), bytes)
        assertContentEquals(bytes, service.readStoredImage(reference, "alice"))
        coEvery { storage.head(key) } returns ObjectMeta(key, 7L * 1024 * 1024)
        assertFailsWith<IllegalArgumentException> { service.readStoredImage(reference, "alice") }
        coEvery { storage.head(key) } returns null
        assertNull(service.readStoredImage(reference, "alice"))
        coVerify(exactly = 1) { storage.get(key) }
    }

    @Test
    fun `preserves cancellation during signing`() = runTest {
        val service = FileStorageService(root.toString(), resolver)
        val reference = StoredFileReference.create("alice", "session-1", "png")
        coEvery { resolver.resolve("alice") } returns storage
        coEvery { storage.head(any()) } answers { ObjectMeta(firstArg(), 3) }
        coEvery { storage.presignedGetUrl(any(), any()) } throws CancellationException("cancelled")
        assertFailsWith<CancellationException> { service.resolveImageUrl(reference, "alice") }
    }

    @Test
    fun `missing objects and disabled storage do not produce display URLs`() = runTest {
        val service = FileStorageService(root.toString(), resolver)
        val reference = StoredFileReference.create("alice", "session-1", "png")
        coEvery { resolver.resolve("alice") } returns storage
        coEvery { storage.head(any()) } returns null
        assertNull(service.resolveImageUrl(reference, "alice"))
        coEvery { resolver.resolve("alice") } returns null
        assertNull(service.resolveImageUrl(reference, "alice"))
        coVerify(exactly = 0) { storage.presignedGetUrl(any(), any()) }
    }

    @Test
    fun `rejects local symlinks into another session`() = runTest {
        val service = FileStorageService(root.toString())
        val other = Path.of(service.saveImage("other-session", bytes, "png"))
        val ownDir = Files.createDirectories(service.imagesRoot.resolve("session-1"))
        val alias = Files.createSymbolicLink(ownDir.resolve("image.png"), other)
        assertNull(service.getFile(alias.toString()))
        Files.createSymbolicLink(service.imagesRoot.resolve("linked-session"), other.parent)
        assertFailsWith<IllegalArgumentException> { service.saveImage("linked-session", bytes, "png") }
    }

    @Test
    fun `rejects invalid paths image types and oversized images`() = runTest {
        val service = FileStorageService(root.toString())
        assertFailsWith<IllegalArgumentException> { service.saveImage("../escape", bytes, "png") }
        assertFailsWith<IllegalArgumentException> { service.saveImage("s", bytes, "../png") }
        assertFailsWith<IllegalArgumentException> { service.saveImage("s", bytes, "svg", mimeType = "image/svg+xml") }
        assertFailsWith<IllegalArgumentException> { service.saveImage("s", ByteArray(6 * 1024 * 1024 + 1), "png") }
        assertNull(service.sessionIdFor(root.resolve("outside.png").toString(), "alice"))
        assertNull(service.getFile(root.resolve("outside.png").toString()))
    }
}
