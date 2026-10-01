package com.easy.easyai.tools.media

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.media.MediaProviderResolver
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
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** Parameters for [VideoGenTool]. */
data class VideoGenParams(
    /** Text description of the video to generate. Required unless [taskId] is given. */
    val prompt: String? = null,
    /** A task id from a previous `pending` result, to poll an in-flight job without re-submitting. */
    val taskId: String? = null,
    /** Optional generation-model name; matches a configured entry's model id or display name. */
    val model: String? = null
)

/**
 * Generates a video from a text prompt through an async provider (submit → poll → fetch), persisting
 * the finished file and returning a reference.
 *
 * Video jobs are minute-scale, so the tool uses *bounded polling*: it waits up to a cap and, if the
 * job is still running, returns `{status:"pending", taskId}` and teaches the model to re-invoke this
 * same tool with that `taskId` — no separate job subsystem. `PARALLEL` is essential: a long media
 * call must never serialize the whole tool batch.
 *
 * The concrete provider is resolved per call from the optional [VideoGenParams.model] against the
 * user's enabled VIDEO entries (see [MediaProviderResolver.resolveEntry]).
 */
class VideoGenTool(
    metadata: ToolMetadata,
    private val resolver: MediaProviderResolver,
    private val storage: ObjectStorage,
    private val userId: String
) : BaseToolDefinition(metadata) {

    private val logger = LoggerFactory.getLogger(javaClass)

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
        val model = (args["model"] as? String)?.takeIf { it.isNotBlank() }
        val explicitTaskId = (args["taskId"] as? String)?.takeIf { it.isNotBlank() }
        val prompt = (args["prompt"] as? String)?.takeIf { it.isNotBlank() }

        val settings = resolver.resolveEntry(userId, MediaProviderSettings.SERVICE_KIND_VIDEO, model)
            ?: return errorResult(toolCallId, name, "no enabled video model is configured${model?.let { " for '$it'" } ?: ""}")
        val client = AsyncGenerationClient(
            settings,
            pathSegment = "video",
            artifactUrlPaths = listOf("video_url", "url", "output.video_url", "output.video_url[0]", "data.video_url", "results[0].url")
        )

        val taskId = explicitTaskId ?: run {
            if (prompt == null) return errorResult(toolCallId, name, "either 'prompt' or 'taskId' is required")
            try {
                client.submit("video-generation", prompt)
            } catch (e: Exception) {
                logger.warn("Video submit failed for user '{}': {}", userId, e.message)
                return errorResult(toolCallId, name, "Video generation failed: ${e.message}")
            } ?: return errorResult(toolCallId, name, "provider did not return a task id")
        }

        val outcome = try {
            client.awaitTask(taskId, { "Rendering video (task $it)…" }) { onUpdate(ToolUpdate.Progress(it)) }
        } catch (e: Exception) {
            logger.warn("Video generation failed for user '{}': {}", userId, e.message)
            return errorResult(toolCallId, name, "Video generation failed: ${e.message}")
        }

        return when (outcome) {
            is AsyncJobOutcome.Succeeded -> {
                try {
                    val bytes = client.download(outcome.url)
                    val item = MediaArtifacts.store(storage, userId, bytes, "video/mp4")
                    val result = MediaResult(MediaProviderSettings.SERVICE_KIND_VIDEO, MediaResult.STATUS_COMPLETED, listOf(item))
                    ToolResult(content = listOf(ToolResultContent(toolCallId, name, result.toJson(), mimeType = "application/json")))
                } catch (e: Exception) {
                    logger.warn("Video artifact download failed for user '{}': {}", userId, e.message)
                    errorResult(toolCallId, name, "Video download failed: ${e.message}")
                }
            }
            is AsyncJobOutcome.Failed ->
                errorResult(toolCallId, name, "video task $taskId failed: ${outcome.reason}")
            is AsyncJobOutcome.Pending -> ToolResult(
                content = listOf(
                    ToolResultContent(
                        toolCallId, name,
                        MediaResult(MediaProviderSettings.SERVICE_KIND_VIDEO, MediaResult.STATUS_PENDING, taskId = outcome.taskId).toJson(),
                        mimeType = "application/json"
                    )
                )
            )
        }
    }
}

@Component
class VideoGenToolBuilder : AbstractMediaToolBuilder(MediaProviderSettings.SERVICE_KIND_VIDEO) {
    override val metadata = ToolMetadata(
        name = "generate_video",
        description = """Generate a video from a text prompt.
- Provide a 'prompt' describing the desired video
- Optionally name a 'model' when several video models are configured
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
        resolver: MediaProviderResolver,
        storage: ObjectStorage,
        userId: String
    ): ToolDefinition = VideoGenTool(metadata, resolver, storage, userId)
}
