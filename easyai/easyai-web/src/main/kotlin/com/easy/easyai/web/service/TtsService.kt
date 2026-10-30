package com.easy.easyai.web.service

import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelType
import com.easy.easyai.core.media.MediaProviderSettings
import com.easy.easyai.core.model.aux.AuxModelResolver
import com.easy.easyai.core.model.aux.AuxModelTask
import com.easy.easyai.speech.SpeechAudio
import com.easy.easyai.speech.SpeechSynthesizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import reactor.core.publisher.SignalType
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/** Whether chat read-aloud is usable right now, and why not when it is not. */
data class TtsAvailability(
    val configured: Boolean,
    val modelId: String? = null,
    /** One of [TtsService.REASON_*]; null when [configured]. */
    val reason: String? = null
)

/**
 * Chat read-aloud: synthesizes one assistant message's text into a single audio clip.
 *
 * Gated on the caller's effective Task Models `tts` choice, resolved across the same self → group
 * owner set the settings page writes to — an unconfigured task, or a row whose credentials are
 * missing, means the feature is off, and the frontend discovers that through [availability] rather
 * than by rendering a button that fails.
 *
 * Deliberate restrictions, all of them product decisions rather than protocol limits:
 *  - **MP3 only.** Raw PCM/OPUS are not reliable `HTMLAudioElement` inputs, and WAV framing is only
 *    verified for the generation tool, so the read-aloud path pins the format instead of echoing the
 *    row's choice back to the browser.
 *  - **Plain text only.** Message content is Markdown-derived text; a row with `enable_ssml` on must
 *    not turn `**bold**` into markup the service tries to parse.
 *  - **20 000 characters.** The protocol allows 200 000, but read-aloud is billed per character and
 *    is intended for chat replies; over this the request is refused, never truncated.
 *  - **One synthesis per owner at a time.** The button is not the guard; the server is.
 */
@Service
class TtsService(
    @param:Autowired(required = false)
    private val auxModelResolver: AuxModelResolver? = null,
    // Optional, not defaulted-only: Kotlin default arguments are invisible to Spring's constructor
    // resolution, so a plain `synthesizer: SpeechSynthesizer = SpeechSynthesizer.create()` asks the
    // container for a bean that does not exist and the context fails to start.
    @param:Autowired(required = false)
    private val synthesizer: SpeechSynthesizer? = null
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** The façade actually used: an injected bean when one exists, the DashScope one otherwise. */
    private val engine: SpeechSynthesizer = synthesizer ?: SpeechSynthesizer.create()

    /** Owners with a synthesis in flight; the second concurrent request for one owner gets a 429. */
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Read-aloud state for the caller; resolves the config but never synthesizes (no cost).
     *
     * Credentials are part of the answer: a row that cannot authenticate would answer 409 on the
     * first click, so reporting it here keeps the button from appearing in the first place.
     */
    suspend fun availability(owners: Collection<String>): TtsAvailability {
        val evaluation = evaluate(owners)
        val config = evaluation.config
        return TtsAvailability(
            configured = config != null && config.hasCredentials(),
            modelId = config?.modelId ?: evaluation.offModelId,
            reason = when {
                config == null -> evaluation.reason
                !config.hasCredentials() -> REASON_INCOMPLETE_CONFIG
                else -> null
            }
        )
    }

    /**
     * Synthesize [text] with the configured `tts` row.
     *
     * [owners] is the self → group owner set; its **first element is the caller**, which is what the
     * single-flight slot is keyed on (`currentGroupOwners()` inserts the user id before the group
     * owners). Keying on any other element would let one member's synthesis reject another's.
     */
    suspend fun speak(owners: Collection<String>, text: String): SpeechAudio {
        val request = prepare(owners, text)
        try {
            val audio = try {
                withTimeout(request.settings.timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS).seconds) {
                    engine.synthesize(
                        settings = request.settings,
                        text = request.speech,
                        voice = null,
                        format = CHAT_FORMAT,
                        plainTextOnly = true
                    )
                }
            } catch (e: TimeoutCancellationException) {
                logger.warn("Read-aloud timed out for config '{}' ({} chars)", request.config.id, request.speech.length)
                throw ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Speech synthesis timed out")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(
                    "Read-aloud failed for config '{}' ({} chars): {}",
                    request.config.id,
                    request.speech.length,
                    e.message
                )
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Speech synthesis failed")
            }

            logger.info(
                "Read-aloud: config={} model={} chars={} bytes={} mime={}",
                request.config.id,
                request.config.modelId,
                request.speech.length,
                audio.bytes.size,
                audio.mimeType
            )
            return audio
        } finally {
            inFlight.remove(request.owner)
        }
    }

    /**
     * Low-latency read-aloud: headers are returned before synthesis finishes and every provider
     * frame is forwarded. The single-flight slot lives until the HTTP subscriber completes,
     * fails or cancels; cancellation also propagates into the provider's WebSocket.
     */
    fun speakStream(owners: Collection<String>, text: String): Flux<SpeechAudio> =
        mono { prepare(owners, text) }.flatMapMany { request ->
            val startedAt = System.nanoTime()
            var bytes = 0L
            var frames = 0L
            Flux.defer {
                engine.synthesizeStream(
                    settings = request.settings,
                    text = request.speech,
                    voice = null,
                    format = CHAT_FORMAT,
                    plainTextOnly = true
                )
            }
                .timeout(Duration.ofSeconds(request.settings.timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS)))
                .doOnNext { chunk ->
                    frames++
                    bytes += chunk.bytes.size
                }
                .doOnComplete {
                    logger.info(
                        "Read-aloud stream: config={} model={} chars={} bytes={} frames={} in {} ms",
                        request.config.id,
                        request.config.modelId,
                        request.speech.length,
                        bytes,
                        frames,
                        (System.nanoTime() - startedAt) / 1_000_000
                    )
                }
                .doOnError { error ->
                    logger.warn(
                        "Read-aloud stream failed for config '{}' ({} chars): {}",
                        request.config.id,
                        request.speech.length,
                        error.message
                    )
                }
                .onErrorMap { error ->
                    when (error) {
                        is ResponseStatusException -> error
                        is java.util.concurrent.TimeoutException, is TimeoutCancellationException ->
                            ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Speech synthesis timed out")
                        else -> ResponseStatusException(HttpStatus.BAD_GATEWAY, "Speech synthesis failed")
                    }
                }
                .doFinally { signal ->
                    if (signal == SignalType.CANCEL) {
                        logger.info(
                            "Read-aloud stream cancelled: config={} chars={} bytes={} frames={}",
                            request.config.id,
                            request.speech.length,
                            bytes,
                            frames
                        )
                    }
                    inFlight.remove(request.owner)
                }
        }

    /** Validate and claim the caller's slot before any paid provider work starts. */
    private suspend fun prepare(owners: Collection<String>, text: String): PreparedSpeech {
        val speech = text.trim()
        if (speech.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to read aloud")
        }
        // Same unit on both sides: JS `String.length` and Kotlin `String.length` are UTF-16 units.
        if (speech.length > MAX_CHARS) {
            throw ResponseStatusException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "Text exceeds the $MAX_CHARS-character read-aloud limit"
            )
        }

        val evaluation = evaluate(owners)
        val config = evaluation.config
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, messageFor(evaluation.reason))
        val owner = owners.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "No owner in scope")
        val settings = config.asSpeechSettings()
        if (settings.baseUrl.isBlank()) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "The TTS model has no Base URL (Settings -> Models -> Speech)"
            )
        }
        if (settings.apiKey.isBlank()) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "The TTS model has no API key (set it on the model's group)"
            )
        }
        if (!inFlight.add(owner)) {
            throw ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "A read-aloud request is already running; wait for it to finish"
            )
        }
        return PreparedSpeech(config, settings, speech, owner)
    }

    /**
     * Resolve the `tts` row and check that it is actually usable.
     *
     * The checks repeat what `PUT /api/aux-models` enforces because a choice can predate that
     * validation, or point at a row that was disabled, retyped or re-pointed afterwards.
     */
    private suspend fun evaluate(owners: Collection<String>): Evaluation {
        val config = auxModelResolver?.resolveConfig(owners, AuxModelTask.TTS)
            ?: return Evaluation(config = null, reason = REASON_NOT_CONFIGURED)
        return when {
            config.modelType != ModelType.SPEECH -> Evaluation(null, REASON_WRONG_MODEL_TYPE, config.modelId)
            !config.enabled -> Evaluation(null, REASON_DISABLED, config.modelId)
            !engine.supports(config.asSpeechSettings()) -> Evaluation(null, REASON_UNSUPPORTED_PROVIDER, config.modelId)
            else -> Evaluation(config, null)
        }
    }

    private fun messageFor(reason: String?): String = when (reason) {
        REASON_WRONG_MODEL_TYPE ->
            "The TTS Model is not a speech row; pick a SPEECH model in Settings -> Task Models -> TTS Model"
        REASON_DISABLED ->
            "The configured TTS model is disabled; enable it in Settings -> Models -> Speech"
        REASON_UNSUPPORTED_PROVIDER ->
            "The configured TTS model uses a provider read-aloud does not support yet; " +
                "use a DashScope (Qwen-Audio-TTS) speech model"
        REASON_INCOMPLETE_CONFIG ->
            "The TTS model is incomplete: it needs a model ID and a Base URL on the row, " +
                "and an API key on its group"
        else -> "No text-to-speech model is configured (Settings -> Task Models -> TTS Model)"
    }

    /** A row can be the right type and still be unusable without credentials. */
    private fun ModelProviderConfig.hasCredentials(): Boolean =
        !baseUrl.isNullOrBlank() && !apiKey.isNullOrBlank() && modelId.isNotBlank()

    /** The `tts` task points at a model_provider_config row; the speech module speaks MediaProviderSettings. */
    private fun ModelProviderConfig.asSpeechSettings(): MediaProviderSettings = MediaProviderSettings(
        id = id,
        displayName = name,
        enabled = true,
        serviceKind = MediaProviderSettings.SERVICE_KIND_SPEECH,
        providerType = protocol.name.lowercase(),
        baseUrl = baseUrl.orEmpty(),
        apiKey = apiKey.orEmpty(),
        defaultModel = modelId,
        options = mediaOptions.orEmpty(),
        timeoutSeconds = timeoutSeconds,
        isDefault = true
    )

    private data class PreparedSpeech(
        val config: ModelProviderConfig,
        val settings: MediaProviderSettings,
        val speech: String,
        val owner: String
    )

    /** A config plus, when it is unusable, why — [offModelId] still lets the UI name the broken row. */
    private data class Evaluation(
        val config: ModelProviderConfig?,
        val reason: String?,
        val offModelId: String? = null
    )

    companion object {
        /** Product limit for one read-aloud request; the protocol's 200 000 stays a lower-level guard. */
        const val MAX_CHARS = 20_000

        /** Read-aloud is always MP3 — see the class comment. */
        private const val CHAT_FORMAT = "mp3"

        /** Same ceiling the DashScope transport applies; the service one must not be looser. */
        private const val MAX_TIMEOUT_SECONDS = 300L

        const val REASON_NOT_CONFIGURED = "NOT_CONFIGURED"
        const val REASON_WRONG_MODEL_TYPE = "WRONG_MODEL_TYPE"
        const val REASON_DISABLED = "DISABLED"
        const val REASON_UNSUPPORTED_PROVIDER = "UNSUPPORTED_PROVIDER"
        const val REASON_INCOMPLETE_CONFIG = "INCOMPLETE_CONFIG"
    }
}
