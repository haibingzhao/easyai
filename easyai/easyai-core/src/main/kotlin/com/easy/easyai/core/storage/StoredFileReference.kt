package com.easy.easyai.core.storage

import java.util.Base64
import java.util.UUID

/** Stable chat image reference, scoped to its owner even when storage is shared. */
data class StoredFileReference(val key: String, val sessionId: String) {
    companion object {
        const val URL_TTL_SECONDS: Long = 3600L
        const val MAX_IMAGE_BYTES: Int = 6 * 1024 * 1024

        private const val PREFIX = "storage://"
        private val SESSION_ID = Regex("[a-zA-Z0-9_-]+")
        private val EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp")
        private val REFERENCE = Regex(
            "storage://(chat-images/([A-Za-z0-9_-]+)/([a-zA-Z0-9_-]+)/" +
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(?:png|jpg|jpeg|gif|webp))"
        )

        @JvmStatic
        fun isStored(filePath: String): Boolean = filePath.startsWith(PREFIX)

        @JvmStatic
        fun create(userId: String, sessionId: String, extension: String): String {
            require(SESSION_ID.matches(sessionId)) { "Invalid chat image session ID" }
            require(extension in EXTENSIONS) { "Unsupported chat image extension" }
            return "${PREFIX}chat-images/${encodeUserId(userId)}/$sessionId/${UUID.randomUUID()}.$extension"
        }

        /** Rejects noncanonical paths and other users before any storage access. */
        @JvmStatic
        fun parse(filePath: String, userId: String): StoredFileReference {
            val match = requireNotNull(REFERENCE.matchEntire(filePath)) { "Invalid stored chat image reference" }
            require(match.groupValues[2] == encodeUserId(userId)) { "Stored chat image belongs to another user" }
            return StoredFileReference(key = match.groupValues[1], sessionId = match.groupValues[3])
        }

        private fun encodeUserId(userId: String): String {
            require(userId.isNotEmpty()) { "Chat image user ID must not be empty" }
            val bytes = userId.toByteArray(Charsets.UTF_8)
            require(String(bytes, Charsets.UTF_8) == userId) { "Invalid UTF-8 chat image user ID" }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
