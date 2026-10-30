package com.easy.easyai.speech

import com.easy.easyai.core.media.MediaProviderSettings
import kotlinx.coroutines.reactor.mono
import reactor.core.publisher.Flux

/**
 * One synthesized clip: the bytes plus the media type they must be served as.
 *
 * Callers decide what to do with it — the generation tool stores it and returns a URL, the chat
 * read-aloud endpoint hands it straight to the browser. Nothing here knows about either.
 */
data class SpeechAudio(
    val bytes: ByteArray,
    val mimeType: String
) {
    // Array equality is identity, so the generated members would treat equal clips as different;
    // compare contents instead (same contract as ImageContent / ObjectContent).
    override fun equals(other: Any?): Boolean =
        this === other || (other is SpeechAudio && bytes.contentEquals(other.bytes) && mimeType == other.mimeType)

    override fun hashCode(): Int = bytes.contentHashCode() * 31 + mimeType.hashCode()
}

/**
 * Public entry point for speech synthesis, shared by `easyai-tools` (`generate_speech`) and
 * `easyai-web` (chat read-aloud).
 *
 * Both callers need the same provider dispatch, voice precedence and format fallback. Letting them
 * reach the transports directly would either duplicate that logic or force one module to depend on
 * another module's internals, so the façade is the only published surface: it speaks
 * [MediaProviderSettings] and returns bytes, and the DashScope protocol stays internal to this
 * module.
 *
 * Only the DashScope-native protocol is implemented. A generic OpenAI-compatible `/audio/speech`
 * provider reports [supports] = false, so callers can answer "not available" up front instead of
 * failing after a paid round-trip. Qwen-Audio-TTS rows are the model-aware exception: they may
 * share an OPENAI compatible-mode group with chat models, but still require the native socket.
 */
interface SpeechSynthesizer {

    /** True when this façade can speak [providerType] (`MediaProviderSettings.PROVIDER_*`). */
    fun supports(providerType: String): Boolean

    /** Model-aware gate; rows whose protocol alone is sufficient keep the default implementation. */
    fun supports(settings: MediaProviderSettings): Boolean = supports(settings.providerType)

    /**
     * Synthesize [text] with the row's own defaults, overridden by an explicit [voice] / [format].
     *
     * [plainTextOnly] forces plain-text input even when the row enables SSML: chat read-aloud speaks
     * Markdown-derived text, which must never be parsed as markup.
     */
    suspend fun synthesize(
        settings: MediaProviderSettings,
        text: String,
        voice: String? = null,
        format: String? = null,
        plainTextOnly: Boolean = false
    ): SpeechAudio

    /**
     * Stream audio chunks as the provider produces them. The default keeps existing engines
     * compatible by emitting their one-shot result; native transports override it for low latency.
     * Cancelling the returned Flux must cancel the provider connection.
     */
    fun synthesizeStream(
        settings: MediaProviderSettings,
        text: String,
        voice: String? = null,
        format: String? = null,
        plainTextOnly: Boolean = false
    ): Flux<SpeechAudio> = mono {
        synthesize(settings, text, voice, format, plainTextOnly)
    }.flux()

    companion object {
        /** The production façade: DashScope-native duplex protocol. */
        @JvmStatic
        fun create(): SpeechSynthesizer = DashScopeSpeechSynthesizer
    }
}

/** [SpeechSynthesizer] over [DashScopeTtsClient]; the only provider implemented today. */
internal object DashScopeSpeechSynthesizer : SpeechSynthesizer {

    override fun supports(providerType: String): Boolean =
        providerType.equals(MediaProviderSettings.PROVIDER_DASHSCOPE, ignoreCase = true)

    override fun supports(settings: MediaProviderSettings): Boolean =
        supports(settings.providerType) || isBailianQwenTts(settings)

    override suspend fun synthesize(
        settings: MediaProviderSettings,
        text: String,
        voice: String?,
        format: String?,
        plainTextOnly: Boolean
    ): SpeechAudio {
        require(supports(settings)) {
            "speech provider '${settings.providerType}' is not supported by this synthesizer"
        }
        val (bytes, mime) = DashScopeTtsClient(settings).synthesize(text, voice, format, plainTextOnly)
        return SpeechAudio(bytes, mime)
    }

    override fun synthesizeStream(
        settings: MediaProviderSettings,
        text: String,
        voice: String?,
        format: String?,
        plainTextOnly: Boolean
    ): Flux<SpeechAudio> {
        require(supports(settings)) {
            "speech provider '${settings.providerType}' is not supported by this synthesizer"
        }
        return DashScopeTtsClient(settings).stream(text, voice, format, plainTextOnly)
    }

    /**
     * Qwen-Audio-TTS has no compatible-mode `/audio/speech` route. Its catalog row can nevertheless
     * live in an OPENAI group because that group's chat models need compatible-mode; recognize the
     * Bailian host and let [DashScopeTtsProtocol.endpoint] derive the native socket from the same
     * base URL instead of forcing users to duplicate the group and credentials.
     */
    private fun isBailianQwenTts(settings: MediaProviderSettings): Boolean {
        val model = settings.defaultModel.lowercase()
        val baseUrl = settings.baseUrl.lowercase()
        return model.startsWith("qwen-audio-") && model.contains("-tts-") &&
            (baseUrl.contains(".maas.aliyuncs.com") || baseUrl.contains("dashscope.aliyuncs.com"))
    }
}
