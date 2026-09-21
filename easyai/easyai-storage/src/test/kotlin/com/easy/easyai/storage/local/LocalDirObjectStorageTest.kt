package com.easy.easyai.storage.local

import com.easy.easyai.core.storage.ObjectStorageException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [LocalDirObjectStorage] — the offline stand-in for OSS, and therefore the reference
 * implementation the `ObjectStorage` contract is measured against.
 *
 * Two properties carry the security weight: bytes must come back exactly as they went in (callers
 * verify a SHA-256 over them), and no key may address a path outside the storage root.
 */
class LocalDirObjectStorageTest {

    @TempDir
    lateinit var root: Path

    private val storage get() = LocalDirObjectStorage(root)

    private val zipBytes: ByteArray get() = ByteArray(2048) { (it % 251).toByte() }

    @Nested
    inner class `round trips` {

        @Test
        fun `stored bytes come back unchanged with their own fingerprint`() = runTest {
            val key = "skills/pdf-report/1.0.0.zip"

            val written = storage.put(key, zipBytes, "application/zip")

            assertEquals(key, written.key)
            assertEquals(zipBytes.size.toLong(), written.size)
            assertEquals(64, written.etag?.length, "the local etag is a sha-256 hex of the bytes")

            val read = storage.get(key)
            assertContentEquals(zipBytes, read?.bytes)
            assertEquals(zipBytes.size.toLong(), read?.meta?.size)
            assertEquals(written.etag, read?.meta?.etag)
        }

        @Test
        fun `head describes an object without handing back its bytes`() = runTest {
            val key = "skills/pdf/0.0.1.zip"
            storage.put(key, zipBytes, "application/zip")

            val meta = storage.head(key)

            assertEquals(key, meta?.key)
            assertEquals(zipBytes.size.toLong(), meta?.size)
            assertTrue((meta?.lastModified ?: 0L) > 0L)
            assertNull(meta?.etag, "a fingerprint costs a full read, so head must not promise one")
        }

        @Test
        fun `a nested key creates the directories it needs`() = runTest {
            storage.put("skills/deep/nested/2.0.0.zip", "payload".toByteArray(), "application/zip")

            assertTrue(Files.isRegularFile(root.resolve("skills/deep/nested/2.0.0.zip")))
        }

        @Test
        fun `rewriting a key replaces the stored bytes`() = runTest {
            val key = "skills/pdf/1.0.0.zip"
            storage.put(key, "first".toByteArray(), "application/zip")

            storage.put(key, "second".toByteArray(), "application/zip")

            assertEquals("second", String(storage.get(key)?.bytes ?: ByteArray(0)))
        }

        @Test
        fun `delete removes the object and only reports success once`() = runTest {
            val key = "skills/pdf/1.0.0.zip"
            storage.put(key, zipBytes, "application/zip")

            assertTrue(storage.delete(key))
            assertFalse(storage.delete(key))
            assertNull(storage.get(key))
            assertNull(storage.head(key))
        }

        @Test
        fun `an unknown key is reported as absent, never as a failure`() = runTest {
            assertNull(storage.get("skills/ghost/1.0.0.zip"))
            assertNull(storage.head("skills/ghost/1.0.0.zip"))
            assertFalse(storage.delete("skills/ghost/1.0.0.zip"))
            assertNull(storage.presignedGetUrl("skills/ghost/1.0.0.zip", 60))
        }

        @Test
        fun `a directory is not an object`() = runTest {
            storage.put("skills/pdf/1.0.0.zip", zipBytes, "application/zip")

            assertNull(storage.head("skills/pdf"))
            assertNull(storage.get("skills/pdf"))
        }
    }

    @Nested
    inner class `keys cannot escape the root` {

        @Test
        fun `a relative escape is refused on every path`() = runTest {
            val key = "../outside.txt"

            assertNull(storage.head(key))
            assertNull(storage.get(key))
            assertFalse(storage.delete(key))
            assertNull(storage.presignedGetUrl(key, 60))
            assertFailsWith<ObjectStorageException> { storage.put(key, "x".toByteArray(), "text/plain") }
            assertFalse(Files.exists(root.parent.resolve("outside.txt")))
        }

        @Test
        fun `a deeply escaping key is refused too`() = runTest {
            val key = "skills/../../outside.zip"

            assertFailsWith<ObjectStorageException> { storage.put(key, zipBytes, "application/zip") }
            assertFalse(Files.exists(root.resolve("outside.zip")))
        }

        @Test
        fun `an absolute looking key is anchored inside the root`() = runTest {
            val probe = "tmp/easyai-abs-key-probe.zip"

            storage.put("/$probe", zipBytes, "application/zip")

            assertTrue(Files.isRegularFile(root.resolve(probe)), "the key must be taken relative to the root")
            assertFalse(Files.exists(Path.of("/$probe")), "a storage backend must never write to the real path")
            storage.delete(probe)
        }

        @Test
        fun `a blank key is no key at all`() = runTest {
            assertNull(storage.get("   "))
            assertNull(storage.head(""))
            assertFailsWith<ObjectStorageException> { storage.put("  ", zipBytes, "application/zip") }
        }
    }

    @Nested
    inner class `download links` {

        @Test
        fun `a presigned link for a local file is a file uri`() = runTest {
            val key = "skills/pdf/1.0.0.zip"
            storage.put(key, zipBytes, "application/zip")

            val url = storage.presignedGetUrl(key, 3600) ?: error("a stored object must have a download link")

            assertTrue(url.startsWith("file:"), "got: $url")
            val target = Path.of(URI(url))
            assertEquals(root.resolve(key).toAbsolutePath().normalize(), target)
            assertContentEquals(zipBytes, Files.readAllBytes(target), "the link must serve the stored bytes")
        }

        @Test
        fun `an absent object has nothing to link to`() = runTest {
            assertNull(storage.presignedGetUrl("skills/pdf/9.9.9.zip", 3600))
        }
    }
}
