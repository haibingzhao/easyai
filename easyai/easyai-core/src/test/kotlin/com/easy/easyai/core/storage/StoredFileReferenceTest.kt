package com.easy.easyai.core.storage

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

internal class StoredFileReferenceTest {
    private val userId = "user-1"
    private val userSegment = "dXNlci0x"
    private val sessionId = "f0451528-e47c-4e3c-b8ce-9d9c71c4bb4d"
    private val imageId = "ce4b5be8-95ea-427d-b3d9-7710e029e268"
    private val reference = "storage://chat-images/$userSegment/$sessionId/$imageId.png"

    @Nested
    inner class `creation and round trip` {
        @ParameterizedTest
        @ValueSource(strings = ["png", "jpg", "jpeg", "gif", "webp"])
        fun `creates canonical keys with supported extensions`(extension: String) {
            val path = StoredFileReference.create(userId, sessionId, extension)
            val parsed = StoredFileReference.parse(path, userId)
            assertTrue(StoredFileReference.isStored(path))
            assertEquals(path.removePrefix("storage://"), parsed.key)
            assertEquals(sessionId, parsed.sessionId)
            assertTrue(parsed.key.startsWith("chat-images/$userSegment/$sessionId/"))
            val filename = parsed.key.substringAfterLast('/')
            assertEquals(extension, filename.substringAfterLast('.'))
            assertEquals(4, UUID.fromString(filename.substringBefore('.')).version())
            assertNotEquals(path, StoredFileReference.create(userId, sessionId, extension))
        }

        @ParameterizedTest
        @ValueSource(strings = ["session_01-ABC", "default", "a", "123"])
        fun `accepts safe session identifiers beyond UUIDs`(session: String) {
            val path = StoredFileReference.create(userId, session, "png")
            assertEquals(session, StoredFileReference.parse(path, userId).sessionId)
        }

        @ParameterizedTest
        @ValueSource(strings = ["用户/alpha@example.com", "a/b", "a_b", "../user", "user+name", "é"])
        fun `encodes the exact UTF8 owner as URL safe base64 without padding`(owner: String) {
            val path = StoredFileReference.create(owner, sessionId, "png")
            val parsed = StoredFileReference.parse(path, owner)
            val segment = parsed.key.split('/')[1]
            assertTrue(Regex("[A-Za-z0-9_-]+").matches(segment))
            assertFalse(segment.contains('='))
            assertEquals(owner, String(Base64.getUrlDecoder().decode(segment), Charsets.UTF_8))
        }

        @Test
        fun `similar owner names cannot collide even in shared storage`() {
            val slashOwner = StoredFileReference.create("a/b", sessionId, "png")
            val underscoreOwner = StoredFileReference.create("a_b", sessionId, "png")
            assertNotEquals(slashOwner.split('/')[3], underscoreOwner.split('/')[3])
            assertFailsWith<IllegalArgumentException> { StoredFileReference.parse(slashOwner, "a_b") }
        }

        @Test
        fun `parses known canonical keys without rewriting them`() {
            assertEquals(
                StoredFileReference("chat-images/$userSegment/$sessionId/$imageId.png", sessionId),
                StoredFileReference.parse(reference, userId)
            )
            assertEquals(3600L, StoredFileReference.URL_TTL_SECONDS)
            assertEquals(6 * 1024 * 1024, StoredFileReference.MAX_IMAGE_BYTES)
        }
    }

    @Nested
    inner class `strict validation` {
        @Test
        fun `prefix detection routes invalid storage references to validation`() {
            assertTrue(StoredFileReference.isStored("storage://"))
            assertTrue(StoredFileReference.isStored("storage://other/invalid"))
            assertFalse(StoredFileReference.isStored("/tmp/image.png"))
            assertFalse(StoredFileReference.isStored("https://example.com/image.png"))
            assertFalse(StoredFileReference.isStored("file:///tmp/image.png"))
        }

        @Test
        fun `rejects foreign users including the shared system owner`() {
            for (owner in listOf("user-2", "system", "USER-1", "")) {
                assertFailsWith<IllegalArgumentException> { StoredFileReference.parse(reference, owner) }
            }
        }

        @Test
        fun `rejects arbitrary URLs namespaces noncanonical encodings and path traversal`() {
            val invalid = listOf(
                reference.replace("storage://", "https://"),
                reference.replace("storage://", "http://"),
                reference.replace("storage://", "file://"),
                reference.replace("storage://", "STORAGE://"),
                reference.removePrefix("storage://"),
                reference.replace("chat-images/", "skills/"),
                reference.replace("chat-images/", "chat-images//"),
                reference.replace("chat-images/", "chat-images/../chat-images/"),
                reference.replace("/$sessionId/", "/../"),
                reference.replace("/$sessionId/", "/./"),
                reference.replace("/$sessionId/", "/%2e%2e/"),
                reference.replace("/$sessionId/", "/%252e%252e/"),
                reference.replace("/$sessionId/", "/safe%2F..%2F/"),
                reference.replace("/$sessionId/", "/safe\\..\\/"),
                reference.replace(userSegment, "$userSegment="),
                reference.replace(userSegment, "dXNlci0x%2F"),
                reference.replace(userSegment, "***"),
                reference.replace("$userSegment/", ""),
                reference.replace(imageId, "not-a-uuid"),
                reference.replace(imageId, imageId.uppercase()),
                reference.replace(".png", ".svg"),
                reference.replace(".png", ".PNG"),
                reference.replace(".png", ".png.exe"),
                "$reference/extra",
                "$reference?signature=temporary",
                "$reference#fragment",
                "$reference\n",
                " $reference",
                "storage://",
                ""
            )
            for (path in invalid) {
                assertFailsWith<IllegalArgumentException>(path) { StoredFileReference.parse(path, userId) }
            }
        }

        @ParameterizedTest
        @ValueSource(strings = ["", ".", "..", "a/b", "a\\b", "%2e%2e", "a b", "a\n", "a?x", "用户"])
        fun `creation rejects unsafe session IDs`(session: String) {
            assertFailsWith<IllegalArgumentException> { StoredFileReference.create(userId, session, "png") }
        }

        @ParameterizedTest
        @ValueSource(strings = ["", "svg", "bmp", "txt", "PNG", ".png", "png/../x", "png?x", "png\n"])
        fun `creation rejects all other extensions`(extension: String) {
            assertFailsWith<IllegalArgumentException> { StoredFileReference.create(userId, sessionId, extension) }
        }

        @Test
        fun `creation rejects empty or malformed Unicode owners rather than introducing collisions`() {
            assertFailsWith<IllegalArgumentException> { StoredFileReference.create("", sessionId, "png") }
            assertFailsWith<IllegalArgumentException> { StoredFileReference.create("\uD800", sessionId, "png") }
        }
    }
}
