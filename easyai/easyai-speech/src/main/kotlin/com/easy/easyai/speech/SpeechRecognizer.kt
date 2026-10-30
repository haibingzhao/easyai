package com.easy.easyai.speech

import com.easy.easyai.core.media.MediaProviderSettings
import reactor.core.publisher.Flux

/**
 * Public entry point for speech recognition, used by `easyai-web` for chat input and the realtime
 * bridge.
 *
 * The façade is the only published surface: it speaks [MediaProviderSettings] plus the already
 * validated `run-task` parameters and returns normalized events, so no caller has to know the
 * vendor's frame shapes and the protocol classes stay internal to this module.
 *
 * Only the DashScope-native duplex protocol is implemented. A generic OpenAI-compatible
 * `/audio/transcriptions` row reports [supports] = false: that route is one-shot over HTTP and is
 * served elsewhere, and pretending otherwise would route audio into a socket that never answers it.
 */
interface SpeechRecognizer {

    /** True when this façade can recognize with [settings] (protocol and, for Bailian rows, model). */
    fun supports(settings: MediaProviderSettings): Boolean

    /**
     * Start one recognition task.
     *
     * [pcmAudio] must be 16 kHz mono little-endian PCM without a container header; completing it
     * sends `finish-task` and the returned Flux keeps emitting the service's trailing sentences
     * until it completes. Cancelling the returned Flux closes the upstream socket, which is the only
     * cancellation this protocol defines.
     */
    fun recognize(
        settings: MediaProviderSettings,
        parameters: Map<String, Any?>,
        pcmAudio: Flux<ByteArray>
    ): Flux<SpeechRecognitionEvent>

    companion object {
        /** The production façade: DashScope-native duplex protocol. */
        @JvmStatic
        fun create(): SpeechRecognizer = DashScopeSpeechRecognizer
    }
}

/** [SpeechRecognizer] over [DashScopeAsrClient]; the only provider implemented today. */
internal object DashScopeSpeechRecognizer : SpeechRecognizer {

    override fun supports(settings: MediaProviderSettings): Boolean =
        settings.providerType.equals(MediaProviderSettings.PROVIDER_DASHSCOPE, ignoreCase = true) ||
            isBailianQwenAsr(settings)

    override fun recognize(
        settings: MediaProviderSettings,
        parameters: Map<String, Any?>,
        pcmAudio: Flux<ByteArray>
    ): Flux<SpeechRecognitionEvent> {
        require(supports(settings)) {
            "speech provider '${settings.providerType}' is not supported by this recognizer"
        }
        return DashScopeAsrClient(settings).recognize(parameters, pcmAudio)
    }

    /**
     * Recognition has no compatible-mode route either. A catalog row may live in an OPENAI group
     * because that group's chat models need compatible-mode, so the Bailian host plus an
     * `qwen-audio` ASR model is accepted and the native socket is derived from the same base URL.
     */
    private fun isBailianQwenAsr(settings: MediaProviderSettings): Boolean {
        val model = settings.defaultModel.lowercase()
        return model.startsWith("qwen-audio-") && model.contains("-asr-") &&
            DashScopeSpeechEndpoint.isTrusted(settings.baseUrl)
    }
}
