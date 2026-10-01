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

/** Parameters for [MusicGenTool]. */
data class MusicGenParams(
    /** Text description of the music to generate. Required unless [taskId] is given. */
    val prompt: String? = null,
    /** A task id from a previous `pending` result, to poll an in-flight job without re-submitting. */
    val taskId: String? = null,
    /** Optional generation-model name; matches a configured entry's model id or display name. */
    val model: String? = null
)

/**
 * Generates music from a text prompt through an async provider (submit → poll → fetch), persisting
 * the finished audio and returning a reference.
 *
 * Music jobs are slow enough to deserve the same *bounded polling* shape as video: wait up to a cap,
 * then hand `{status:"pending", taskId}` back so the model re-invokes this tool with that id.
 */
class MusicGenTool(
    metadata: ToolMetadata,
    private val resolver: MediaProviderResolver,
    private val storage: ObjectStorage,
    private val userId: String
) : BaseToolDefinition(metadata) {

    private val logger = LoggerFactory.getLogger(javaClass)

    override val executionMode = ToolExecutionMode.PARALLEL
    override fun parameterType() = MusicGenParams::class.java

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

        val settings = resolver.resolveEntry(userId, MediaProviderSettings.SERVICE_KIND_MUSIC, model)
            ?: return errorResult(toolCallId, name, "no enabled music model is configured${model?.let { " for '$it'" } ?: ""}")
        val client = AsyncGenerationClient(
            settings,
            pathSegment = "music",
            artifactUrlPaths = listOf("audio_url", "url", "output.audio_url", "data.audio_url", "results[0].url", "output.audio.url")
        )

        val taskId = explicitTaskId ?: run {
            if (prompt == null) return errorResult(toolCallId, name, "either 'prompt' or 'taskId' is required")
            try {
                client.submit("music-generation", prompt)
            } catch (e: Exception) {
                logger.warn("Music submit failed for user '{}': {}", userId, e.message)
                return errorResult(toolCallId, name, "Music generation failed: ${e.message}")
            } ?: return errorResult(toolCallId, name, "provider did not return a task id")
        }

        val outcome = try {
            client.awaitTask(taskId, { "Composing music (task $it)…" }) { onUpdate(ToolUpdate.Progress(it)) }
        } catch (e: Exception) {
            logger.warn("Music generation failed for user '{}': {}", userId, e.message)
            return errorResult(toolCallId, name, "Music generation failed: ${e.message}")
        }

        return when (outcome) {
            is AsyncJobOutcome.Succeeded -> {
                try {
                    val bytes = client.download(outcome.url)
                    val item = MediaArtifacts.store(storage, userId, bytes, "audio/mpeg")
                    val result = MediaResult(MediaProviderSettings.SERVICE_KIND_MUSIC, MediaResult.STATUS_COMPLETED, listOf(item))
                    ToolResult(content = listOf(ToolResultContent(toolCallId, name, result.toJson(), mimeType = "application/json")))
                } catch (e: Exception) {
                    logger.warn("Music artifact download failed for user '{}': {}", userId, e.message)
                    errorResult(toolCallId, name, "Music download failed: ${e.message}")
                }
            }
            is AsyncJobOutcome.Failed ->
                errorResult(toolCallId, name, "music task $taskId failed: ${outcome.reason}")
            is AsyncJobOutcome.Pending -> ToolResult(
                content = listOf(
                    ToolResultContent(
                        toolCallId, name,
                        MediaResult(MediaProviderSettings.SERVICE_KIND_MUSIC, MediaResult.STATUS_PENDING, taskId = outcome.taskId).toJson(),
                        mimeType = "application/json"
                    )
                )
            )
        }
    }
}

@Component
class MusicGenToolBuilder : AbstractMediaToolBuilder(MediaProviderSettings.SERVICE_KIND_MUSIC) {
    override val metadata = ToolMetadata(
        name = "generate_music",
        description = """Generate music from a text prompt.
- Provide a 'prompt' describing the desired music (genre, mood, instruments)
- Optionally name a 'model' when several music models are configured
- Music rendering is slow: if the job is still running the result comes back {"status":"pending","taskId":...}
- To check a pending job, call this tool again with just the 'taskId' from the previous result
- Returns a reference to stored audio (a URL the interface plays inline) once complete
- Costs quota; use only when the user explicitly asks for generated music""",
        permissionCategory = "media",
        uiRenderer = "generate_music",
        isDefaultTool = false,
        patternKeys = listOf("prompt")
    )

    override fun createTool(
        resolver: MediaProviderResolver,
        storage: ObjectStorage,
        userId: String
    ): ToolDefinition = MusicGenTool(metadata, resolver, storage, userId)
}
