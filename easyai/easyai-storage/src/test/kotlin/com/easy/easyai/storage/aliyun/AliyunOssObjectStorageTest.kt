package com.easy.easyai.storage.aliyun

import com.aliyun.oss.HttpMethod
import com.aliyun.oss.OSS
import com.aliyun.oss.OSSException
import com.aliyun.oss.model.GeneratePresignedUrlRequest
import com.aliyun.oss.model.OSSObject
import com.aliyun.oss.model.ObjectMetadata
import com.aliyun.oss.model.PutObjectResult
import com.easy.easyai.core.storage.ObjectStorageException
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URL
import java.util.Date
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [AliyunOssObjectStorage] — the production implementation of the `ObjectStorage` contract.
 *
 * The SDK client is mocked, so these assert the *mapping*: which call each `ObjectStorage` method
 * makes, what it does with "no such key", and that a blocking SDK call is pushed off the caller's
 * coroutine dispatcher. The bytes themselves are verified by [com.easy.easyai.storage.local] against
 * the local implementation, which shares the same contract.
 */
class AliyunOssObjectStorageTest {

    private val client = mockk<OSS>(relaxed = true)
    private val bucket = "easyai-market"
    private val storage get() = AliyunOssObjectStorage(client, bucket)

    private fun notFound(key: String): OSSException =
        OSSException("the key is gone", "NoSuchKey", "req-1", bucket, "header", key, HttpMethod.GET.name)

    private fun accessDenied(key: String): OSSException =
        OSSException("forbidden", "AccessDenied", "req-2", bucket, "header", key, HttpMethod.GET.name)

    private fun objectWith(bytes: ByteArray, modified: Date = Date(1_700_000_000_000L)): OSSObject =
        OSSObject().apply {
            objectContent = ByteArrayInputStream(bytes)
            objectMetadata = ObjectMetadata().apply {
                setContentLength(bytes.size.toLong())
                setLastModified(modified)
            }
        }

    @Nested
    inner class `call mapping` {

        @Test
        fun `head asks the bucket for the key and reports its size and time`() = runTest {
            val modified = Date(1_700_000_000_000L)
            every { client.getObjectMetadata(bucket, "skills/pdf/1.0.0.zip") } returns ObjectMetadata().apply {
                setContentLength(2048L)
                setLastModified(modified)
            }

            val meta = storage.head("skills/pdf/1.0.0.zip")

            assertEquals(2048L, meta?.size)
            assertEquals(modified.time, meta?.lastModified)
            assertEquals("skills/pdf/1.0.0.zip", meta?.key)
            verify(exactly = 1) { client.getObjectMetadata(bucket, "skills/pdf/1.0.0.zip") }
        }

        @Test
        fun `get streams the object body and labels it with the same key`() = runTest {
            val bytes = ByteArray(1024) { (it % 253).toByte() }
            every { client.getObject(bucket, "skills/pdf/1.0.0.zip") } returns objectWith(bytes)

            val content = storage.get("skills/pdf/1.0.0.zip")

            assertContentEquals(bytes, content?.bytes)
            assertEquals(bytes.size.toLong(), content?.meta?.size)
            assertEquals("skills/pdf/1.0.0.zip", content?.meta?.key)
        }

        @Test
        fun `put uploads exactly the bytes it was given`() = runTest {
            val bytes = ByteArray(512) { (it % 97).toByte() }
            val stream = slot<InputStream>()
            val metadata = slot<ObjectMetadata>()
            every {
                client.putObject(eq(bucket), eq("skills/pdf/2.0.0.zip"), capture(stream), capture(metadata))
            } returns PutObjectResult().apply { setETag("etag-from-oss") }

            val meta = storage.put("skills/pdf/2.0.0.zip", bytes, "application/zip")

            assertContentEquals(bytes, stream.captured.readBytes(), "the payload must not be altered")
            assertEquals(bytes.size.toLong(), metadata.captured.contentLength)
            assertEquals("application/zip", metadata.captured.contentType)
            assertEquals("etag-from-oss", meta.etag)
            assertEquals(bytes.size.toLong(), meta.size)
        }

        @Test
        fun `delete checks existence first so a missing key reports false`() = runTest {
            every { client.doesObjectExist(bucket, "skills/pdf/1.0.0.zip") } returns true

            assertTrue(storage.delete("skills/pdf/1.0.0.zip"))
            verify(exactly = 1) { client.deleteObject(bucket, "skills/pdf/1.0.0.zip") }

            every { client.doesObjectExist(bucket, "skills/pdf/0.0.1.zip") } returns false

            assertFalse(storage.delete("skills/pdf/0.0.1.zip"), "a key that was never there cannot be deleted")
            verify(exactly = 0) { client.deleteObject(bucket, "skills/pdf/0.0.1.zip") }
        }

        @Test
        fun `presigning asks for a window of the requested length`() = runTest {
            val requested = slot<GeneratePresignedUrlRequest>()
            every { client.doesObjectExist(bucket, "skills/pdf/1.0.0.zip") } returns true
            every { client.generatePresignedUrl(capture(requested)) } returns
                URL("https://$bucket.oss.example.com/skills/pdf/1.0.0.zip?sig=abc")
            val before = System.currentTimeMillis()

            val url = storage.presignedGetUrl("skills/pdf/1.0.0.zip", 900)

            assertEquals("https://$bucket.oss.example.com/skills/pdf/1.0.0.zip?sig=abc", url)
            assertEquals(bucket, requested.captured.bucketName)
            assertEquals("skills/pdf/1.0.0.zip", requested.captured.key)
            val ttlMillis = requested.captured.expiration.time - before
            assertTrue(ttlMillis in 899_000L..901_000L, "expiration was $ttlMillis ms out")
        }

        @Test
        fun `an absent key is never signed`() = runTest {
            every { client.doesObjectExist(bucket, "skills/ghost/1.0.0.zip") } returns false

            assertNull(storage.presignedGetUrl("skills/ghost/1.0.0.zip", 60))
            verify(exactly = 0) { client.generatePresignedUrl(any()) }
        }
    }

    @Nested
    inner class `failure translation` {

        @Test
        fun `a missing object reads as absent on the metadata and body paths`() = runTest {
            every { client.getObjectMetadata(bucket, "skills/pdf/1.0.0.zip") } throws notFound("skills/pdf/1.0.0.zip")
            every { client.getObject(bucket, "skills/pdf/1.0.0.zip") } throws notFound("skills/pdf/1.0.0.zip")

            assertNull(storage.head("skills/pdf/1.0.0.zip"))
            assertNull(storage.get("skills/pdf/1.0.0.zip"))
        }

        @Test
        fun `any other oss error surfaces instead of looking like an empty bucket`() = runTest {
            every { client.getObject(bucket, "skills/pdf/1.0.0.zip") } throws accessDenied("skills/pdf/1.0.0.zip")
            every { client.getObjectMetadata(bucket, "skills/pdf/1.0.0.zip") } throws accessDenied("skills/pdf/1.0.0.zip")

            assertFailsWith<ObjectStorageException> { storage.get("skills/pdf/1.0.0.zip") }
            assertFailsWith<ObjectStorageException> { storage.head("skills/pdf/1.0.0.zip") }
        }

        @Test
        fun `a refused upload is a failed upload, not a silent success`() = runTest {
            every { client.putObject(any(), any(), any<InputStream>(), any()) } throws accessDenied("skills/pdf/1.0.0.zip")

            assertFailsWith<ObjectStorageException> {
                storage.put("skills/pdf/1.0.0.zip", ByteArray(1), "application/zip")
            }
        }

        @Test
        fun `a broken presign is reported, so a client is never handed a half link`() = runTest {
            every { client.doesObjectExist(bucket, "skills/pdf/1.0.0.zip") } returns true
            every { client.generatePresignedUrl(any()) } throws RuntimeException("signing key revoked")

            assertFailsWith<ObjectStorageException> { storage.presignedGetUrl("skills/pdf/1.0.0.zip", 60) }
        }
    }

    @Nested
    inner class `blocking calls leave the caller's thread` {

        @Test
        fun `the sdk is driven from an io thread, not the coroutine's own`() = runTest {
            var threadOnCall = ""
            every { client.getObject(bucket, "skills/pdf/1.0.0.zip") } answers {
                threadOnCall = Thread.currentThread().name
                objectWith("payload".toByteArray())
            }

            storage.get("skills/pdf/1.0.0.zip")

            assertTrue(
                threadOnCall.startsWith("DefaultDispatcher"),
                "a blocking SDK call ran on '$threadOnCall' and can stall the loop"
            )
        }
    }
}
