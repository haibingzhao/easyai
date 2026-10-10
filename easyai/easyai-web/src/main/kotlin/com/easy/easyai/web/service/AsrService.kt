package com.easy.easyai.web.service

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.model.ModelOptions
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.core.media.MediaProviderSettings
import com.easy.easyai.core.model.aux.AuxModelResolver
import com.easy.easyai.core.model.aux.AuxModelTask
import com.easy.easyai.core.model.aux.ResolvedAuxModel
import com.easy.easyai.tools.media.OpenAiCompatibleClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import com.easy.easyai.api.llm.Message
import com.easy.easyai.api.llm.SystemMessage
import com.easy.easyai.api.llm.UserMessage
import com.easy.easyai.api.llm.Prompt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import kotlin.time.Duration.Companion.seconds

/**
 * Backend for chat-input dictation: raw audio-segment transcription plus the post-dictation
 * context-aware rewrite.
 *
 * Both capabilities are gated on the user's Task Models settings — an unconfigured `asr` task
 * means voice input is off, an unconfigured `dictation_refine` task means the rewrite step is
 * skipped. The frontend discovers this through GET /api/aux-models; these endpoints answer 409
 * as the authoritative backstop.
 */
@Service
class AsrService(
    @param:Autowired(required = false)
    private val auxModelResolver: AuxModelResolver? = null,
    private val modelFactories: List<ChatModelFactory> = emptyList()
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Transcribes one audio segment (16 kHz mono WAV from the browser) via the configured ASR row. */
    suspend fun transcribe(userId: String, bytes: ByteArray, language: String?): String {
        if (bytes.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty audio segment")
        }
        if (bytes.size > MAX_SEGMENT_BYTES) {
            throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Audio segment exceeds 2 MB")
        }
        val config = auxModelResolver?.resolveConfig(userId, AuxModelTask.ASR)
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, MSG_NO_ASR)
        return try {
            OpenAiCompatibleClient(config.asAsrSettings())
                .transcribe(bytes, "segment.wav", language?.trim()?.takeIf { it.isNotBlank() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("ASR transcription failed for user '{}': {}", userId, e.message)
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Transcription failed: ${e.message ?: "provider error"}")
        }
    }

    /**
     * Rewrites one dictation round's text against its surrounding editor context.
     * Only [text] may appear in the result; the context is reference material for the model.
     */
    suspend fun refine(userId: String, text: String, contextBefore: String, contextAfter: String): String {
        val target = text.trim()
        if (target.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to refine")
        }
        if (target.length > MAX_REFINE_CHARS) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Dictation text exceeds $MAX_REFINE_CHARS characters")
        }
        val resolved = auxModelResolver?.resolve(userId, AuxModelTask.DICTATION_REFINE)
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, MSG_NO_REFINE)
        val messages: List<Message> = listOf(
            SystemMessage(REFINE_SYSTEM_PROMPT),
            UserMessage(buildRefineUserPrompt(target, contextBefore, contextAfter))
        )
        val output = try {
            withTimeout(REFINE_TIMEOUT_SECONDS.seconds) {
                withContext(Dispatchers.IO) { resolved.chatModel.call(refinePrompt(resolved, messages)) }
            }
        } catch (e: TimeoutCancellationException) {
            throw ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Refine timed out after ${REFINE_TIMEOUT_SECONDS}s")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Dictation refine failed for user '{}': {}", userId, e.message)
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Refine failed: ${e.message ?: "model error"}")
        }
        val refined = stripCodeFence(output.result?.output?.text.orEmpty()).trim()
        if (refined.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Refine returned no text")
        }
        return refined
    }

    /**
     * Refine is a single forward correction pass — extended thinking adds latency and leaks
     * monologue into the output — so a thinking-enabled row gets Prompt-level options rebuilt
     * with thinking off. Those options (the row's factory `build()`) are what the protocol
     * factories actually send; the cached ChatModel itself carries only the model name.
     */
    private fun refinePrompt(resolved: ResolvedAuxModel, messages: List<Message>): Prompt {
        val config = resolved.modelConfig
        val factory = modelFactories.firstOrNull { it.supports(config.protocol) }
            ?: return Prompt(messages)
        val options = factory.build(
            config.copy(options = (config.options ?: ModelOptions()).copy(thinking = false)),
            emptyList()
        )
        return Prompt(messages, options)
    }

    private fun buildRefineUserPrompt(text: String, contextBefore: String, contextAfter: String): String =
        buildString {
            val before = contextBefore.trim().takeLast(CONTEXT_WINDOW_CHARS)
            val after = contextAfter.trim().take(CONTEXT_WINDOW_CHARS)
            if (before.isNotEmpty()) {
                appendLine("【前文，仅供理解语境，不要输出】")
                appendLine(before)
                appendLine()
            }
            appendLine("【待校对文本】")
            appendLine(text)
            if (after.isNotEmpty()) {
                appendLine()
                appendLine("【后文，仅供理解语境，不要输出】")
                append(after)
            }
        }

    /** The ASR task points at a model_provider_config row; tools speak MediaProviderSettings. */
    private fun ModelProviderConfig.asAsrSettings(): MediaProviderSettings = MediaProviderSettings(
        id = id,
        displayName = name,
        enabled = true,
        serviceKind = MediaProviderSettings.SERVICE_KIND_ASR,
        providerType = protocol.name.lowercase(),
        baseUrl = baseUrl.orEmpty(),
        apiKey = apiKey.orEmpty(),
        defaultModel = modelId,
        options = mediaOptions.orEmpty(),
        timeoutSeconds = timeoutSeconds,
        isDefault = true
    )

    private fun stripCodeFence(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val firstLineEnd = trimmed.indexOf('\n')
        if (firstLineEnd == -1) return trimmed
        return trimmed.substring(firstLineEnd + 1).removeSuffix("```").trim()
    }

    companion object {
        private const val MAX_SEGMENT_BYTES = 2 * 1024 * 1024
        private const val MAX_REFINE_CHARS = 8_000
        private const val CONTEXT_WINDOW_CHARS = 2_000
        private const val REFINE_TIMEOUT_SECONDS = 30L
        private const val MSG_NO_ASR =
            "No speech-recognition model is configured (Settings -> Task Models -> ASR Model)"
        private const val MSG_NO_REFINE =
            "No dictation-refine model is configured (Settings -> Task Models -> Dictation Refine Model)"
        private const val REFINE_SYSTEM_PROMPT =
            "你是听写校对员。用户会给出待校对文本及其前后文。只做最小修正：同音字、专业术语与热词、断句与标点。" +
                "不得改写句式、替换同义词、增删实词，不得润色或泛化；无需修正时原样返回待校对文本。" +
                "只输出修正后的待校对文本本身：不要输出前后文、不要解释、不要输出任何思考过程。"
    }
}
