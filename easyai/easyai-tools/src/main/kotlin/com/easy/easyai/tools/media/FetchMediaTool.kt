package com.easy.easyai.tools.media

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.model.ToolResultContent
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.storage.ObjectStorage
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolExecutionMode
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.net.URI

/** Parameters for [FetchMediaTool]. */
data class FetchMediaParams(
    /** A single http(s) URL of the media file to persist. */
    val url: String? = null,
    /** Multiple http(s) URLs to persist in one call (max 8). */
    val urls: List<String>? = null,
    /** Optional MIME type override when the response headers are unreliable. */
    val mimeType: String? = null
)

/**
 * Persists media from external (often expiring) http(s) URLs as stable media references.
 *
 * Typical source: image links returned as plain text by MCP generation tools — those presigned URLs
 * die with their signature, while each download here lands in object storage (or the local media
 * directory when no storage is configured) and comes back as a `MediaResult` reference that never
 * expires. The bytes go to storage, never into the LLM context.
 */
class FetchMediaTool(
    metadata: ToolMetadata,
    private val storage: ObjectStorage,
    private val userId: String,
    private val fetch: suspend (String) -> Pair<ByteArray, String?> = { MediaFetch.download(it) }
) : BaseToolDefinition(metadata) {

    private val logger = LoggerFactory.getLogger(javaClass)

    override val executionMode = ToolExecutionMode.PARALLEL
    override fun parameterType() = FetchMediaParams::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val requested = buildList {
            (args["url"] as? String)?.takeIf { it.isNotBlank() }?.let { add(it.trim()) }
            (args["urls"] as? List<*>)?.filterIsInstance<String>()?.filter { it.isNotBlank() }?.map { it.trim() }?.let { addAll(it) }
        }.distinct()
        if (requested.isEmpty()) return errorResult(toolCallId, name, "'url' or 'urls' is required")
        if (requested.size > MAX_URLS) return errorResult(toolCallId, name, "too many URLs (max $MAX_URLS)")
        val mimeTypeOverride = (args["mimeType"] as? String)?.takeIf { it.isNotBlank() }

        onUpdate(ToolUpdate.Progress("Downloading ${requested.size} file(s)…"))
        val items = ArrayList<MediaItem>(requested.size)
        val failures = ArrayList<String>()
        for (url in requested) {
            try {
                val (bytes, contentType) = fetch(url)
                val mime = resolveMimeType(url, mimeTypeOverride, contentType)
                items.add(MediaArtifacts.store(storage, userId, bytes, mime))
            } catch (e: Exception) {
                logger.warn("fetch_media failed for '{}': {}", url, e.message)
                failures.add("${url.substringBefore('?')}: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        if (items.isEmpty()) return errorResult(toolCallId, name, "all downloads failed — ${failures.joinToString("; ")}")

        val result = MediaResult(
            kind = kindFor(items.first().mimeType),
            status = MediaResult.STATUS_COMPLETED,
            items = items,
            error = failures.joinToString("; ").takeIf { it.isNotBlank() }
        )
        return ToolResult(content = listOf(ToolResultContent(toolCallId, name, result.toJson(), mimeType = "application/json")))
    }

    private fun resolveMimeType(url: String, override: String?, contentType: String?): String {
        override?.let { return it }
        contentType?.substringBefore(';')?.trim()
            ?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?.let { return it }
        val path = runCatching { URI(url).path }.getOrNull() ?: url.substringBefore('?')
        return MIME_BY_EXTENSION[path.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"
    }

    private fun kindFor(mimeType: String): String = when {
        mimeType.startsWith("image/") -> "image"
        mimeType.startsWith("audio/") -> "audio"
        mimeType.startsWith("video/") -> "video"
        else -> "file"
    }

    companion object {
        const val MAX_URLS = 8

        private val MIME_BY_EXTENSION = mapOf(
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "webp" to "image/webp",
            "gif" to "image/gif",
            "mp3" to "audio/mpeg",
            "wav" to "audio/wav",
            "opus" to "audio/opus",
            "aac" to "audio/aac",
            "mp4" to "video/mp4",
            "webm" to "video/webm"
        )
    }
}

/**
 * Builder for [FetchMediaTool] — always offered when any storage target exists.
 *
 * Per-user object storage wins when configured; otherwise the deployment-local media directory
 * storage keeps the tool usable (unlike the generation tools, which hide themselves without
 * storage). Only an app with neither (no storage layer at all) loses the tool.
 */
@Component
class FetchMediaToolBuilder(
    @param:Autowired(required = false)
    @param:Qualifier("localMediaObjectStorage")
    private val localStorage: ObjectStorage? = null
) : ToolBuilder {

    override val metadata = ToolMetadata(
        name = "fetch_media",
        description = """Persist media files from http(s) URLs as permanent references.
- Takes one 'url' or up to ${FetchMediaTool.MAX_URLS} 'urls' pointing at images/audio/video (e.g. expiring presigned links returned by MCP generation tools)
- Downloads each and stores it; returns a MediaResult JSON with stable URLs the interface renders inline — use this instead of quoting raw URLs
- Optionally set 'mimeType' when the source's Content-Type is wrong
- Read-only with respect to the user's project; nothing is written into project files""",
        permissionCategory = "fetch_media",
        uiRenderer = "fetch_media",
        patternKeys = listOf("url")
    )

    override val defaultPermissionRules = listOf(
        PermissionRule("tool.execute.fetch_media", "*", PermissionAction.ALLOW)
    )

    override fun build(context: AgentContext, agentService: AgentService): ToolDefinition? {
        val userId = context.userId ?: AbstractMediaToolBuilder.SYSTEM_USER_ID
        val storage = agentService.objectStorageResolver
            ?.let { runBlocking { it.resolve(userId) } }
            ?: localStorage
            ?: return null
        return FetchMediaTool(metadata, storage, userId)
    }
}
