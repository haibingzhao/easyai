package com.easy.easyai.tools.media

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.storage.ObjectStorage
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.util.*

/**
 * One produced artifact, referenced (never inlined) in [MediaResult.items].
 *
 * [url] is the stable `/api/media/file` path the browser loads; [key] is the storage object key kept
 * so history can re-sign on replay. [durationMs] is audio/video only.
 */
data class MediaItem(
    val url: String,
    val key: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long? = null
)

/**
 * The JSON contract returned to the model and the frontend through [com.easy.easyai.core.model.ToolResultContent.output].
 *
 * Only this small object crosses the SSE boundary — a verified fact is that `ToolResult.details` and
 * non-text content blocks never reach the browser, so the media reference must live here. The bytes
 * themselves are in object storage behind each [MediaItem.url]; nothing is ever inlined as base64.
 *
 * [status] is `completed` for a ready artifact or `pending` for a still-running async job, in which
 * case [taskId] lets the model re-query the same tool.
 */
data class MediaResult(
    val kind: String,
    val status: String,
    val items: List<MediaItem> = emptyList(),
    val taskId: String? = null,
    val error: String? = null
) {
    fun toJson(): String = SharedObjectMapper.instance.writeValueAsString(this)

    companion object {
        const val STATUS_COMPLETED = "completed"
        const val STATUS_PENDING = "pending"
        const val STATUS_FAILED = "failed"
    }
}

/**
 * Shared plumbing for the generation tools: object-storage persistence and small JSON/base64 helpers.
 *
 * Artifacts land under `media/{user}/{date}/{uuid}.{ext}` in the caller's resolved storage and are
 * served back through the stable `/api/media/file` endpoint, which reads from storage on each request —
 * so a stored reference never expires the way a presigned URL would.
 */
internal object MediaArtifacts {

    /** Write [bytes] into [storage]; returns the object key. Blocking IO is inside the storage impl. */
    suspend fun put(storage: ObjectStorage, userId: String, bytes: ByteArray, mimeType: String): String {
        val ext = extensionFor(mimeType)
        val date = LocalDate.now().toString()
        val key = "media/${sanitize(userId)}/$date/${UUID.randomUUID()}.$ext"
        storage.put(key, bytes, mimeType)
        return key
    }

    /** Persist [bytes] and wrap the result as a single [MediaItem]. */
    suspend fun store(
        storage: ObjectStorage,
        userId: String,
        bytes: ByteArray,
        mimeType: String,
        durationMs: Long? = null
    ): MediaItem {
        val key = put(storage, userId, bytes, mimeType)
        return MediaItem(url = fileUrl(key), key = key, mimeType = mimeType, sizeBytes = bytes.size.toLong(), durationMs = durationMs)
    }

    /** Stable, browser-reachable path for a stored artifact; the server reads it back from storage. */
    fun fileUrl(key: String): String = "/api/media/file?key=$key"

    fun extensionFor(mimeType: String): String = when {
        mimeType.contains("png") -> "png"
        mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
        mimeType.contains("webp") -> "webp"
        mimeType.contains("gif") -> "gif"
        mimeType.contains("mpeg3") || mimeType.contains("mp3") -> "mp3"
        mimeType.contains("wav") -> "wav"
        mimeType.contains("opus") -> "opus"
        mimeType.contains("aac") -> "aac"
        mimeType.contains("mp4") || mimeType.contains("mpeg4") -> "mp4"
        mimeType.contains("webm") -> "webm"
        else -> "bin"
    }

    /** Decode a base64 payload (OpenAI image `b64_json`) to bytes. */
    fun decodeBase64(value: String): ByteArray = Base64.getDecoder().decode(value)

    fun readJson(body: String): JsonNode = SharedObjectMapper.instance.readTree(body)

    private fun sanitize(userId: String): String =
        userId.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }.joinToString("")
}
