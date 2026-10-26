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
    private val owners = listOf("alice")

    @Test
    fun `stores image remotely without creating local session files`() = runTest {
        coEvery { resolver.resolve(listOf("alice")) } returns storage
        coEvery { storage.put(any(), bytes, "image/png") } answers { ObjectMeta(firstArg(), 3) }
        val service = FileStorageService(root.toString(), resolver)

        val reference = service.saveImage("session-1", bytes, "png", "alice", owners, "image/png")

        assertTrue(StoredFileReference.isStored(reference))
        val parsed = StoredFileReference.parse(reference, "alice")
        assertEquals("session-1", parsed.sessionId)
        coVerify(exactly = 1) { storage.put(parsed.key, bytes, "image/png") }
        assertFalse(Files.exists(service.imagesRoot.resolve("session-1")))
    }

    @Test
    fun `keeps local files when storage is unconfigured and for text attachments`() = runTest {
        coEvery { resolver.resolve(listOf("alice")) } returns null
        val service = FileStorageService(root.toString(), resolver)
        val path = service.saveImage("session-1", bytes, "png", "alice", owners, "image/png")
        assertContentEquals(bytes, Files.readAllBytes(Path.of(path)))
        assertEquals("session-1", service.sessionIdFor(path, "alice"))
        val text = service.saveImage("session-1", bytes, "txt", "alice", owners, "text/plain")
        assertNotNull(service.getFile(text))
        coVerify(exactly = 1) { resolver.resolve(listOf("alice")) }
    }

    @Test
    fun `does not fall back to disk on resolver or upload failure`() = runTest {
        val service = FileStorageService(root.toString(), resolver)
        coEvery { resolver.resolve(listOf("alice")) } throws ObjectStorageException("unavailable")
        assertFailsWith<ObjectStorageException> {
            service.saveImage("session-1", bytes, "png", "alice", owners, "image/png")
        }
        coEvery { resolver.resolve(listOf("alice")) } returns storage
        coEvery { storage.put(any(), any(), any()) } throws ObjectStorageException("upload failed")
        assertFailsWith<ObjectStorageException> {
            service.saveImage("session-1", bytes, "png", "alice", owners, "image/png")
        }
        assertFalse(Files.exists(service.imagesRoot.resolve("session-1")))
    }

    @Test
    fun `signs each load and uses authenticated proxy for local or unavailable signatures`() = runTest {
        val service = FileStorageService(root.toString(), resolver)
        val reference = StoredFileReference.create("alice", "session-1", "png")
        val key = StoredFileReference.parse(reference, "alice").key
        coEvery { resolver.resolve(listOf("alice")) } returns storage
        coEvery { storage.head(key) } returns ObjectMeta(key, 3)
        coEvery { storage.presignedGetUrl(key, 3600L) } returnsMany listOf(
            "https://example.test/image?signature=one", "https://example.test/image?signature=two", "file:///tmp/image.png", null
        )
        assertEquals("https://example.test/image?signature=one", service.resolveImageUrl(reference, "alice", owners))
        assertEquals("https://example.test/image?signature=two", service.resolveImageUrl(reference, "alice", owners))
        assertEquals(service.proxyUrl(reference), service.resolveImageUrl(reference, "alice", owners))
        assertEquals(service.proxyUrl(reference), service.resolveImageUrl(reference, "alice", owners))
        coEvery { storage.presignedGetUrl(key, 3600L) } throws ObjectStorageException("signing failed")
        assertEquals(service.proxyUrl(reference), service.resolveImageUrl(reference, "alice", owners))
    }

    @Test
    fun `rejects other users before resolving shared storage`() = runTest {
        val service = FileStorageService(root.toString(), resolver)
        val reference = StoredFileReference.create("alice", "session-1", "png")
        assertFailsWith<IllegalArgumentException> { service.resolveImageUrl(reference, "bob", listOf("bob")) }
        assertFailsWith<IllegalArgumentException> { service.readStoredImage(reference, "bob", listOf("bob")) }
        coVerify(exactly = 0) { resolver.resolve(any<Collection<String>>()) }
    }

    @Test
    fun `reads bounded stored images and reports missing objects`() = runTest {
        val service = FileStorageService(root.toString(), resolver)
        val reference = StoredFileReference.create("alice", "session-1", "png")
        val key = StoredFileReference.parse(reference, "alice").key
        coEvery { resolver.resolve(listOf("alice")) } returns storage
        coEvery { storage.head(key) } returns ObjectMeta(key, 3)
        coEvery { storage.get(key) } returns ObjectContent(ObjectMeta(key, 3), bytes)
        assertContentEquals(bytes, service.readStoredImage(reference, "alice", owners))
        coEvery { storage.head(key) } returns ObjectMeta(key, 7L * 1024 * 1024)
        assertFailsWith<IllegalArgumentException> { service.readStoredImage(reference, "alice", owners) }
        coEvery { storage.head(key) } returns null
        assertNull(service.readStoredImage(reference, "alice", owners))
        coVerify(exactly = 1) { storage.get(key) }
    }

    @Test
    fun `preserves cancellation during signing`() = runTest {
        val service = FileStorageService(root.toString(), resolver)
        val reference = StoredFileReference.create("alice", "session-1", "png")
        coEvery { resolver.resolve(listOf("alice")) } returns storage
        coEvery { storage.head(any()) } answers { ObjectMeta(firstArg(), 3) }
        coEvery { storage.presignedGetUrl(any(), any()) } throws CancellationException("cancelled")
        assertFailsWith<CancellationException> { service.resolveImageUrl(reference, "alice", owners) }
    }

    @Test
    fun `missing objects and disabled storage do not produce display URLs`() = runTest {
        val service = FileStorageService(root.toString(), resolver)
        val reference = StoredFileReference.create("alice", "session-1", "png")
        coEvery { resolver.resolve(listOf("alice")) } returns storage
        coEvery { storage.head(any()) } returns null
        assertNull(service.resolveImageUrl(reference, "alice", owners))
        coEvery { resolver.resolve(listOf("alice")) } returns null
        assertNull(service.resolveImageUrl(reference, "alice", owners))
        coVerify(exactly = 0) { storage.presignedGetUrl(any(), any()) }
    }

    @Test
    fun `rejects local symlinks into another session`() = runTest {
        val service = FileStorageService(root.toString())
        val other = Path.of(service.saveImage("other-session", bytes, "png", owners = owners))
        val ownDir = Files.createDirectories(service.imagesRoot.resolve("session-1"))
        val alias = Files.createSymbolicLink(ownDir.resolve("image.png"), other)
        assertNull(service.getFile(alias.toString()))
        Files.createSymbolicLink(service.imagesRoot.resolve("linked-session"), other.parent)
        assertFailsWith<IllegalArgumentException> { service.saveImage("linked-session", bytes, "png", owners = owners) }
    }

    @Test
    fun `rejects invalid paths image types and oversized images`() = runTest {
        val service = FileStorageService(root.toString())
        assertFailsWith<IllegalArgumentException> { service.saveImage("../escape", bytes, "png", owners = owners) }
        assertFailsWith<IllegalArgumentException> { service.saveImage("s", bytes, "../png", owners = owners) }
        assertFailsWith<IllegalArgumentException> { service.saveImage("s", bytes, "svg", owners = owners, mimeType = "image/svg+xml") }
        assertFailsWith<IllegalArgumentException> { service.saveImage("s", ByteArray(6 * 1024 * 1024 + 1), "png", owners = owners) }
        assertNull(service.sessionIdFor(root.resolve("outside.png").toString(), "alice"))
        assertNull(service.getFile(root.resolve("outside.png").toString()))
    }

    @Test
    fun `writes uploads through the chat boundary owners instead of the author chain`() = runTest {
        val groupStorage = mockk<ObjectStorage>()
        val systemStorage = mockk<ObjectStorage>()
        val groupOwners = listOf("alice", "g_family", "system")
        coEvery { resolver.resolve(groupOwners) } returns groupStorage
        coEvery { resolver.resolve(listOf("alice")) } returns systemStorage
        coEvery { groupStorage.put(any(), bytes, "image/jpeg") } answers { ObjectMeta(firstArg(), 3) }
        val service = FileStorageService(root.toString(), resolver)

        val reference = service.saveImage("session-1", bytes, "jpg", "alice", groupOwners, "image/jpeg")

        coVerify(exactly = 1) {
            groupStorage.put(StoredFileReference.parse(reference, "alice").key, bytes, "image/jpeg")
        }
        coVerify(exactly = 0) { systemStorage.put(any(), any(), any()) }
    }

    @Test
    fun `reads legacy uploads from the author chain when the owner-scoped storage lacks the object`() = runTest {
        val groupStorage = mockk<ObjectStorage>()
        val groupOwners = listOf("alice", "g_family", "system")
        val reference = StoredFileReference.create("alice", "session-1", "png")
        val key = StoredFileReference.parse(reference, "alice").key
        coEvery { resolver.resolve(groupOwners) } returns groupStorage
        coEvery { groupStorage.head(key) } returns null
        coEvery { resolver.resolve(listOf("alice")) } returns storage
        coEvery { storage.head(key) } returns ObjectMeta(key, 3)
        coEvery { storage.presignedGetUrl(key, 3600L) } returns "https://legacy.example/image.png"
        coEvery { storage.get(key) } returns ObjectContent(ObjectMeta(key, 3), bytes)
        val service = FileStorageService(root.toString(), resolver)

        assertEquals("https://legacy.example/image.png", service.resolveImageUrl(reference, "alice", groupOwners))
        assertContentEquals(bytes, service.readStoredImage(reference, "alice", groupOwners))
        coVerify(exactly = 0) { groupStorage.presignedGetUrl(any(), any()) }
    }
}
