package com.easy.easyai.tools.media

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.media.MediaProviderSettings
import com.easy.easyai.core.model.ToolResultContent
import com.easy.easyai.core.storage.ObjectStorage
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolExecutionMode
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.netty.http.client.HttpClient
import tools.jackson.databind.JsonNode
import kotlin.time.Duration.Companion.seconds

/** Parameters for [VideoGenTool]. */
data class VideoGenParams(
    /** Text description of the video to generate. Required unless [taskId] is given. */
    val prompt: String? = null,
    /** A task id from a previous `pending` result, to poll an in-flight job without re-submitting. */
    val taskId: String? = null
)

/**
 * Generates a video from a text prompt through an async provider (submit → poll → fetch), persisting
 * the finished file and returning a reference.
 *
 * Video jobs are minute-scale, so the tool uses *bounded polling*: it waits up to a cap and, if the
 * job is still running, returns `{status:"pending", taskId}` and teaches the model to re-invoke this
 * same tool with that `taskId` — no separate job subsystem. `PARALLEL` is essential: a long media
 * call must never serialize the whole tool batch.
 */
class VideoGenTool(
    metadata: ToolMetadata,
    private val settings: MediaProviderSettings,
    private val storage: ObjectStorage,
    private val userId: String
) : BaseToolDefinition(metadata) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val base = settings.baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_BASE }
    private val timeout = settings.timeoutSeconds.coerceIn(1, 1800).seconds

    private val client: WebClient = WebClient.builder()
        .clientConnector(ReactorClientHttpConnector(HttpClient.create().followRedirect(false)))
        .codecs { it.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY) }
        .build()

    override val executionMode = ToolExecutionMode.PARALLEL
    override fun parameterType() = VideoGenParams::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val explicitTaskId = (args["taskId"] as? String)?.takeIf { it.isNotBlank() }
        val prompt = (args["prompt"] as? String)?.takeIf { it.isNotBlank() }

        val taskId = explicitTaskId ?: run {
            if (prompt == null) return errorResult(toolCallId, name, "either 'prompt' or 'taskId' is required")
            submit(prompt) ?: return errorResult(toolCallId, name, "provider did not return a task id")
        }

        val bounded = withTimeoutOrNull(POLL_WINDOW_SECONDS.seconds) {
            var terminal: ToolResult? = null
            while (terminal == null) {
                val node = query(taskId)
                if (node != null) {
                    when (statusOf(node)) {
                        Status.SUCCEEDED -> {
                            val url = videoUrlOf(node)
                            terminal = if (url == null) {
                                errorResult(toolCallId, name, "task $taskId succeeded but had no video URL")
                            } else {
                                finish(toolCallId, url)
                            }
                        }
                        Status.FAILED ->
                            terminal = errorResult(toolCallId, name, "video task $taskId failed: ${failureReason(node)}")
                        Status.RUNNING -> {
                            onUpdate(ToolUpdate.Progress("Rendering video (task $taskId)…"))
                            delay(POLL_INTERVAL_MS)
                        }
                    }
                } else {
                    delay(POLL_INTERVAL_MS)
                }
            }
            terminal
        }

        // Bounded window elapsed without a terminal state: hand the task id back to the model.
        return bounded ?: ToolResult(
            content = listOf(
                ToolResultContent(
                    toolCallId, name,
                    MediaResult(MediaProviderSettings.SERVICE_KIND_VIDEO, MediaResult.STATUS_PENDING, taskId = taskId).toJson(),
                    mimeType = "application/json"
                )
            )
        )
    }

    private suspend fun finish(toolCallId: String, url: String): ToolResult {
        val bytes = download(url)
        val item = MediaArtifacts.store(storage, userId, bytes, "video/mp4")
        val result = MediaResult(MediaProviderSettings.SERVICE_KIND_VIDEO, MediaResult.STATUS_COMPLETED, listOf(item))
        return ToolResult(content = listOf(ToolResultContent(toolCallId, name, result.toJson(), mimeType = "application/json")))
    }

    private suspend fun submit(prompt: String): String? {
        val body = buildMap<String, Any> {
            put("model", settings.defaultModel.ifBlank { "video-generation" })
            put("prompt", prompt)
        }
        val json = withTimeout(timeout) {
            client.post()
                .uri("$base/video/generations")
                .contentType(MediaType.APPLICATION_JSON)
                .headers { applyAuth(it) }
                .bodyValue(body)
                .retrieve()
                .bodyToMono<String>()
                .awaitSingle()
        }
        val node = MediaArtifacts.readJson(json)
        return firstText(node, "task_id", "taskId", "id", "output.task_id", "data.task_id")
    }

    private suspend fun query(taskId: String): JsonNode? = try {
        val json = withTimeout(timeout) {
            client.get()
                .uri("$base/video/generations/$taskId")
                .headers { applyAuth(it) }
                .retrieve()
                .bodyToMono<String>()
                .awaitSingle()
        }
        MediaArtifacts.readJson(json)
    } catch (e: Exception) {
        logger.debug("Video task query for '{}' not ready yet: {}", taskId, e.message)
        null
    }

    private suspend fun download(url: String): ByteArray = withTimeout(timeout) {
        client.get().uri(url).retrieve().bodyToMono<ByteArray>().awaitSingle()
    }

    private fun applyAuth(headers: HttpHeaders) {
        if (settings.apiKey.isNotBlank()) headers.setBearerAuth(settings.apiKey)
        settings.accessKeyId.takeIf { it.isNotBlank() }?.let { headers.set("X-Access-Key-Id", it) }
        settings.accessKeySecret.takeIf { it.isNotBlank() }?.let { headers.set("X-Access-Key-Secret", it) }
    }

    private enum class Status { RUNNING, SUCCEEDED, FAILED }

    private fun statusOf(node: JsonNode): Status {
        val raw = (firstText(node, "status", "task_status", "output.task_status", "data.status") ?: "RUNNING").uppercase()
        return when {
            raw.contains("SUCCEED") || raw.contains("COMPLET") || raw.contains("FINISH") || raw == "SUCCESS" -> Status.SUCCEEDED
            raw.contains("FAIL") || raw.contains("ERROR") || raw.contains("CANCEL") -> Status.FAILED
            else -> Status.RUNNING
        }
    }

    private fun videoUrlOf(node: JsonNode): String? =
        firstText(node, "video_url", "url", "output.video_url", "output.video_url[0]", "data.video_url", "results[0].url")

    private fun failureReason(node: JsonNode): String? =
        firstText(node, "message", "output.message", "error.message", "reason")

    /** Reads the first present path; supports dotted paths and a single `[i]` index segment. */
    private fun firstText(node: JsonNode, vararg paths: String): String? {
        for (path in paths) {
            var current: JsonNode = node
            var ok = true
            for (segment in path.split('.')) {
                val name = segment.substringBefore('[')
                val index = segment.substringAfter('[', "").substringBefore(']').toIntOrNull()
                if (name.isNotEmpty()) current = current.path(name)
                if (index != null) current = current.path(index)
                if (current.isMissingNode || current.isNull) { ok = false; break }
            }
            if (ok && current.isTextual) return current.asText()
        }
        return null
    }

    companion object {
        private const val DEFAULT_BASE = "https://api.openai.com/v1"
        private const val MAX_IN_MEMORY = 64 * 1024 * 1024 // 64MB: fail fast rather than OOM on a huge video body
        private const val POLL_WINDOW_SECONDS = 480L // 8 min bounded wait before handing the task back
        private const val POLL_INTERVAL_MS = 5_000L
    }
}

@Component
class VideoGenToolBuilder : AbstractMediaToolBuilder(MediaProviderSettings.SERVICE_KIND_VIDEO) {
    override val metadata = ToolMetadata(
        name = "generate_video",
        description = """Generate a video from a text prompt.
- Provide a 'prompt' describing the desired video
- Video rendering is slow: if the job is still running the result comes back {"status":"pending","taskId":...}
- To check a pending job, call this tool again with just the 'taskId' from the previous result
- Returns a reference to stored video (a URL the interface plays inline) once complete
- Costs significant quota; use only when the user explicitly asks for generated video""",
        permissionCategory = "media",
        uiRenderer = "generate_video",
        isDefaultTool = false,
        patternKeys = listOf("prompt")
    )

    override fun createTool(
        settings: MediaProviderSettings,
        storage: ObjectStorage,
        userId: String
    ): ToolDefinition = VideoGenTool(metadata, settings, storage, userId)
}
