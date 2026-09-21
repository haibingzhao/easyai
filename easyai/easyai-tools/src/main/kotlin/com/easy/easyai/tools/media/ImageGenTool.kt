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
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** Parameters for [ImageGenTool]. */
data class ImageGenParams(
    /** Text description of the image to generate. */
    val prompt: String,
    /** Number of images to generate in one call (1-10). Default 1. */
    val n: Int? = null,
    /** Optional size hint, e.g. "1024x1024". Provider-specific. */
    val size: String? = null
)

/**
 * Generates images from a text prompt through an OpenAI-compatible provider, persisting each result
 * to object storage and returning only references.
 *
 * The tool is offered solely when the builder found an enabled image credential *and* usable storage;
 * produced bytes never enter the LLM context or the DB message body — [MediaResult] carries a stable
 * `/api/media/file` URL the frontend renders.
 */
class ImageGenTool(
    metadata: ToolMetadata,
    private val settings: MediaProviderSettings,
    private val storage: ObjectStorage,
    private val userId: String
) : BaseToolDefinition(metadata) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val client = OpenAiCompatibleClient(settings)

    override val executionMode = ToolExecutionMode.PARALLEL
    override fun parameterType() = ImageGenParams::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val prompt = (args["prompt"] as? String)?.takeIf { it.isNotBlank() }
            ?: return errorResult(toolCallId, name, "'prompt' is required")
        val n = (args["n"] as? Number)?.toInt() ?: 1
        val size = args["size"] as? String

        onUpdate(ToolUpdate.Progress("Generating $n image(s)…"))
        return try {
            val generated = client.generateImages(prompt, n, size)
            if (generated.isEmpty()) return errorResult(toolCallId, name, "provider returned no images")
            val items = generated.map { (bytes, mime) -> MediaArtifacts.store(storage, userId, bytes, mime) }
            val result = MediaResult(MediaProviderSettings.SERVICE_KIND_IMAGE, MediaResult.STATUS_COMPLETED, items)
            ToolResult(content = listOf(ToolResultContent(toolCallId, name, result.toJson(), mimeType = "application/json")))
        } catch (e: Exception) {
            logger.warn("Image generation failed for user '{}': {}", userId, e.message)
            errorResult(toolCallId, name, "Image generation failed: ${e.message}")
        }
    }
}

@Component
class ImageGenToolBuilder : AbstractMediaToolBuilder(MediaProviderSettings.SERVICE_KIND_IMAGE) {
    override val metadata = ToolMetadata(
        name = "generate_image",
        description = """Generate image(s) from a text prompt.
- Provide a detailed 'prompt' describing the desired image
- Optionally set 'n' (1-10) for how many variations to create, and a 'size' like "1024x1024"
- Returns references to stored images (a URL the interface renders inline); the current year is available in the prompt if timing matters
- Costs quota; use only when the user asks for generated imagery""",
        permissionCategory = "media",
        uiRenderer = "generate_image",
        isDefaultTool = false,
        patternKeys = listOf("prompt")
    )

    override fun createTool(
        settings: MediaProviderSettings,
        storage: ObjectStorage,
        userId: String
    ): ToolDefinition = ImageGenTool(metadata, settings, storage, userId)
}
