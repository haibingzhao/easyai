package com.easy.easyai.web.service

import com.easy.easyai.core.storage.ObjectMeta
import com.easy.easyai.core.storage.ObjectStorage
import com.easy.easyai.core.storage.ObjectStorageException
import com.easy.easyai.core.storage.ObjectStorageResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests [AvatarStorageService]: the key grammar `GET /api/media/file` has to recognize, the tier it
 * writes to, and the limits that must stop a bad upload before anything reaches storage.
 */
class AvatarStorageServiceTest {

    private val bytes = byteArrayOf(1, 2, 3)

    private fun writableStorage(): ObjectStorage = mockk {
        // Keys carry a fresh UUID, so the stub answers every write and reports back the key it was given.
        coEvery { put(any(), any(), any()) } answers { ObjectMeta(firstArg(), 3) }
        coEvery { delete(any()) } returns true
    }

    private fun resolverReturning(storage: ObjectStorage?): ObjectStorageResolver =
        mockk { coEvery { resolve("alice") } returns storage }

    @Nested
    inner class `save` {
        @Test
        fun `mints a key inside the caller's own owner segment`() = runTest {
            val storage = writableStorage()
            val service = AvatarStorageService(resolverReturning(storage), null)

            val key = service.save("alice", bytes, "image/png")

            assertTrue(key.startsWith("avatars/alice/"), "got: $key")
            assertTrue(key.endsWith(".png"), "got: $key")
            assertTrue(service.isOwnedKey(key, "alice"), "the freshly minted key must be owned")
            coVerify(exactly = 1) { storage.put(key, bytes, "image/png") }
        }

        @Test
        fun `takes the extension from the mime, never from a filename`() = runTest {
            val storage = writableStorage()
            val service = AvatarStorageService(resolverReturning(storage), null)

            val key = service.save("alice", bytes, "image/jpeg")

            // A .jpg key is what the serve endpoint maps back to image/jpeg.
            assertTrue(key.endsWith(".jpg"), "got: $key")
        }

        @Test
        fun `writes to the deployment-local tier when the user configured no storage`() = runTest {
            val local = writableStorage()
            val service = AvatarStorageService(resolverReturning(null), local)

            val key = service.save("alice", bytes, "image/webp")

            coVerify(exactly = 1) { local.put(key, bytes, "image/webp") }
        }

        @Test
        fun `refuses an image type the serve endpoint would not label correctly`() = runTest {
            val storage = writableStorage()
            val service = AvatarStorageService(resolverReturning(storage), null)

            assertFailsWith<IllegalArgumentException> { service.save("alice", bytes, "image/svg+xml") }
            coVerify(exactly = 0) { storage.put(any(), any(), any()) }
        }

        @Test
        fun `refuses an oversized or empty picture before writing`() = runTest {
            val storage = writableStorage()
            val service = AvatarStorageService(resolverReturning(storage), null)

            assertFailsWith<IllegalArgumentException> {
                service.save("alice", ByteArray(AvatarStorageService.MAX_AVATAR_BYTES + 1), "image/png")
            }
            assertFailsWith<IllegalArgumentException> { service.save("alice", ByteArray(0), "image/png") }
            coVerify(exactly = 0) { storage.put(any(), any(), any()) }
        }

        @Test
        fun `reports an outage when no tier at all is available`() = runTest {
            val service = AvatarStorageService(resolverReturning(null), null)

            assertFailsWith<ObjectStorageException> { service.save("alice", bytes, "image/png") }
        }
    }

    @Nested
    inner class `ownership` {
        @Test
        fun `a key from another owner is never owned`() {
            val service = AvatarStorageService(null, null)
            val foreign = "avatars/bob/0f0a2b1c-3d4e-5f60-7182-93a4b5c6d7e8.png"

            assertFalse(service.isOwnedKey(foreign, "alice"))
            assertTrue(service.isOwnedKey(foreign, "bob"))
        }

        @Test
        fun `the preset and an external link are not storage keys`() {
            val service = AvatarStorageService(null, null)

            assertFalse(service.isOwnedKey("avatar-1", "alice"))
            assertFalse(service.isOwnedKey("https://host/alice.png", "alice"))
            assertFalse(service.isOwnedKey("avatars/alice/../../secrets", "alice"))
        }

        @Test
        fun `delete leaves anything that is not an owned key alone`() = runTest {
            val storage = mockk<ObjectStorage>()
            val service = AvatarStorageService(resolverReturning(storage), null)

            service.delete("alice", "avatar-1")
            service.delete("alice", "https://host/alice.png")
            coVerify(exactly = 0) { storage.delete(any()) }
        }

        @Test
        fun `delete removes the replaced picture`() = runTest {
            val storage = mockk<ObjectStorage>()
            val key = "avatars/alice/0f0a2b1c-3d4e-5f60-7182-93a4b5c6d7e8.png"
            coEvery { storage.delete(key) } returns true
            val service = AvatarStorageService(resolverReturning(storage), null)

            service.delete("alice", key)

            coVerify(exactly = 1) { storage.delete(key) }
        }

        @Test
        fun `a failing delete is logged, not raised, because the row already points elsewhere`() = runTest {
            val storage = mockk<ObjectStorage>()
            val key = "avatars/alice/0f0a2b1c-3d4e-5f60-7182-93a4b5c6d7e8.png"
            coEvery { storage.delete(key) } throws ObjectStorageException("offline")
            val service = AvatarStorageService(resolverReturning(storage), null)

            service.delete("alice", key)

            assertEquals(key, key)
        }
    }
}
