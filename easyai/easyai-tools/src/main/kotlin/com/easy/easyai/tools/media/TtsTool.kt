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

/** Parameters for [TtsTool]. */
data class TtsParams(
    /** The text to speak. */
    val text: String,
    /** Voice identifier (provider-specific), e.g. "alloy". Optional. */
    val voice: String? = null,
    /** Output audio format: mp3 (default), wav, opus, aac, flac. */
    val format: String? = null,
    /** Optional generation-model name; matches a configured entry's model id or display name. */
    val model: String? = null
)

/**
 * Synthesizes speech from text through an OpenAI-compatible provider, persisting the audio to object
 * storage and returning only a reference. Fast (seconds), so it completes within one call.
 *
 * The concrete provider is resolved per call from the optional [TtsParams.model] against the user's
 * enabled SPEECH entries.
 */
class TtsTool(
    metadata: ToolMetadata,
    private val resolver: MediaProviderResolver,
    private val storage: ObjectStorage,
    private val userId: String
) : BaseToolDefinition(metadata) {

    private val logger = LoggerFactory.getLogger(javaClass)

    override val executionMode = ToolExecutionMode.PARALLEL
    override fun parameterType() = TtsParams::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val text = (args["text"] as? String)?.takeIf { it.isNotBlank() }
            ?: return errorResult(toolCallId, name, "'text' is required")
        val voice = args["voice"] as? String
        val format = args["format"] as? String
        val model = (args["model"] as? String)?.takeIf { it.isNotBlank() }

        val settings = resolver.resolveEntry(userId, MediaProviderSettings.SERVICE_KIND_SPEECH, model)
            ?: return errorResult(toolCallId, name, "no enabled speech model is configured${model?.let { " for '$it'" } ?: ""}")

        onUpdate(ToolUpdate.Progress("Synthesizing speech…"))
        return try {
            val (bytes, mime) = OpenAiCompatibleClient(settings).synthesize(text, voice, format)
            if (bytes.isEmpty()) return errorResult(toolCallId, name, "provider returned empty audio")
            val item = MediaArtifacts.store(storage, userId, bytes, mime)
            val result = MediaResult(MediaProviderSettings.SERVICE_KIND_SPEECH, MediaResult.STATUS_COMPLETED, listOf(item))
            ToolResult(content = listOf(ToolResultContent(toolCallId, name, result.toJson(), mimeType = "application/json")))
        } catch (e: Exception) {
            logger.warn("Speech synthesis failed for user '{}': {}", userId, e.message)
            errorResult(toolCallId, name, "Speech synthesis failed: ${e.message}")
        }
    }
}

@Component
class TtsToolBuilder : AbstractMediaToolBuilder(MediaProviderSettings.SERVICE_KIND_SPEECH) {
    override val metadata = ToolMetadata(
        name = "generate_speech",
        description = """Convert text to spoken audio (text-to-speech).
- Provide the 'text' to speak
- Optionally choose a 'voice', an output 'format' (mp3, wav, opus, aac, flac), and a 'model' when several speech models are configured
- Returns a reference to stored audio (a URL the interface plays inline)
- Costs quota; use only when the user asks for generated audio""",
        permissionCategory = "media",
        uiRenderer = "generate_speech",
        isDefaultTool = false,
        patternKeys = listOf("text")
    )

    override fun createTool(
        resolver: MediaProviderResolver,
        storage: ObjectStorage,
        userId: String
    ): ToolDefinition = TtsTool(metadata, resolver, storage, userId)
}
