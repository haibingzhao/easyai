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
import java.net.URI

/** Parameters for [TranscribeAudioTool]. */
data class TranscribeAudioParams(
    /** The audio to transcribe: an object-storage key (e.g. from a prior generation tool) or a public http(s) URL. */
    val audio: String,
    /** Optional spoken-language hint (BCP-47, e.g. "zh"), improves accuracy when the provider supports it. */
    val language: String? = null,
    /** Optional transcription-model name; matches a configured entry's model id or display name. */
    val model: String? = null
)

/**
 * Transcribes spoken audio to text through an OpenAI-compatible endpoint
 * (`POST {base}/audio/transcriptions`, Whisper-shaped).
 *
 * Input audio is referenced, never inlined: an object-storage key (artifacts produced by the other
 * media tools) or a public URL fetched with the shared guards. The result is plain text, so unlike
 * the generation tools it returns directly in the tool output — no [MediaResult] envelope.
 */
class TranscribeAudioTool(
    metadata: ToolMetadata,
    private val resolver: MediaProviderResolver,
    private val storage: ObjectStorage,
    private val userId: String
) : BaseToolDefinition(metadata) {

    private val logger = LoggerFactory.getLogger(javaClass)

    override val executionMode = ToolExecutionMode.PARALLEL
    override fun parameterType() = TranscribeAudioParams::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val audio = (args["audio"] as? String)?.takeIf { it.isNotBlank() }
            ?: return errorResult(toolCallId, name, "'audio' is required (a storage key or a public URL)")
        val language = args["language"] as? String
        val model = (args["model"] as? String)?.takeIf { it.isNotBlank() }

        val settings = resolver.resolveEntry(agentContext.effectiveOwners, MediaProviderSettings.SERVICE_KIND_ASR, model)
            ?: return errorResult(toolCallId, name, "no enabled transcription model is configured${model?.let { " for '$it'" } ?: ""}")

        onUpdate(ToolUpdate.Progress("Transcribing audio…"))
        return try {
            val (bytes, filename) = loadAudio(audio)
            val text = OpenAiCompatibleClient(settings).transcribe(bytes, filename, language)
            ToolResult(content = listOf(ToolResultContent(toolCallId, name, text, mimeType = "text/plain")))
        } catch (e: Exception) {
            logger.warn("Audio transcription failed for user '{}': {}", userId, e.message)
            errorResult(toolCallId, name, "Transcription failed: ${e.message}")
        }
    }

    /** Reads the referenced audio: storage objects directly, URLs through the shared guarded fetch. */
    private suspend fun loadAudio(reference: String): Pair<ByteArray, String> {
        val isUrl = reference.startsWith("http://") || reference.startsWith("https://")
        val bytes: ByteArray
        val contentType: String?
        if (isUrl) {
            val fetched = MediaFetch.download(reference)
            bytes = fetched.first
            contentType = fetched.second
        } else {
            val content = storage.get(reference)
                ?: throw IllegalStateException("audio object '$reference' not found in storage")
            bytes = content.bytes
            contentType = null
        }
        if (bytes.isEmpty()) throw IllegalStateException("referenced audio is empty")
        return bytes to filenameFor(reference, contentType)
    }

    private fun filenameFor(reference: String, contentType: String?): String {
        val path = if (contentType != null) reference else URI(reference).path ?: reference
        val ext = contentType?.let { MediaArtifacts.extensionFor(it) }?.takeIf { it != "bin" }
            ?: path.substringAfterLast('.', "mp3").takeIf { it.length in 1..5 && it.all { c -> c.isLetterOrDigit() } }
            ?: "mp3"
        return "audio.$ext"
    }
}

@Component
class TranscribeAudioToolBuilder : AbstractMediaToolBuilder(MediaProviderSettings.SERVICE_KIND_ASR) {
    override val metadata = ToolMetadata(
        name = "transcribe_audio",
        description = """Transcribe spoken audio to text (speech recognition).
- Provide 'audio': an object-storage key (e.g. a media/{user}/... path) or a public http(s) URL
- Optionally hint the spoken 'language' (e.g. "zh", "en") and name a 'model' when several are configured
- Returns the transcribed text
- Costs quota; use when the user asks to transcribe or understand an audio recording""",
        permissionCategory = "media",
        uiRenderer = "transcribe_audio",
        isDefaultTool = false,
        patternKeys = listOf("audio")
    )

    override fun createTool(
        resolver: MediaProviderResolver,
        storage: ObjectStorage,
        userId: String
    ): ToolDefinition = TranscribeAudioTool(metadata, resolver, storage, userId)
}
