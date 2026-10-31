package com.easy.easyai.speech

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.media.MediaProviderSettings
import com.easy.easyai.speech.WebSocketTransport.pump
import com.easy.easyai.speech.WebSocketTransport.run
import io.netty.channel.ChannelOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import reactor.netty.http.client.WebsocketClientSpec
import tools.jackson.databind.JsonNode
import java.io.ByteArrayOutputStream
import java.net.URI
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.seconds

/**
 * Speech synthesis over the DashScope-native duplex WebSocket protocol.
 *
 * Qwen-Audio-TTS (`qwen-audio-3.0-tts-*` / `qwen-audio-3.1-tts-*`) and CosyVoice v3 publish no
 * OpenAI-compatible `/audio/speech` route, and their HTTP synthesis route is a strict subset of this
 * one. Driving the socket directly keeps a single protocol implementation for both uses:
 *
 *  - one-shot: send the whole text, drain every binary frame, hand the bytes to the caller
 *    ([synthesize], what `generate_speech` and the chat read-aloud endpoint need);
 *  - streaming: the same frames can be surfaced as they arrive, and `finish-task` with
 *    `directive=cancel` is the "stop" path — the connection stays reusable either way.
 *
 * Every model has its own voice set: a voice from another model is rejected by the service
 * (e.g. `Engine error [411]`), never silently substituted.
 *
 * Per-entry defaults (voice, format, sample rate, instruction, …) come from the row's `mediaOptions`
 * JSON, so one configured row serves every call; a `voice` passed by the tool call wins over it.
 */
internal class DashScopeTtsClient(
    private val settings: MediaProviderSettings,
    /** Socket seam; production opens a real connection through [WebSocketTransport]. */
    private val transport: TtsTransport = WebSocketTransport
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Synthesize [text]; returns raw audio bytes and their media type.
     *
     * [plainTextOnly] pins `enable_ssml` to false regardless of the row: the chat read-aloud path
     * speaks Markdown-derived plain text, which must never be parsed as SSML markup.
     */
    suspend fun synthesize(
        text: String,
        voice: String?,
        format: String?,
        plainTextOnly: Boolean = false
    ): Pair<ByteArray, String> = synthesize(
        text = text,
        overrides = SpeechOverrides(
            voice = voice,
            inputMode = if (plainTextOnly) SpeechInputMode.PLAIN_TEXT else SpeechInputMode.PROVIDER_GENERATED_MARKUP
        ),
        format = format
    )

    suspend fun synthesize(
        text: String,
        overrides: SpeechOverrides,
        format: String?
    ): Pair<ByteArray, String> {
        val audio = synthesizeResult(text, overrides, format)
        return audio.bytes to audio.mimeType
    }

    /** Same round-trip, keeping the word timings the provider reported for the clip. */
    suspend fun synthesizeResult(
        text: String,
        overrides: SpeechOverrides,
        format: String?
    ): SpeechAudio {
        val prepared = prepare(text, overrides, format)
        val result = try {
            transport.runWithWords(
                DashScopeTtsProtocol.endpoint(settings.baseUrl),
                settings.apiKey,
                prepared.exchange,
                settings.timeoutSeconds
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("DashScope speech synthesis failed: ${e.message}", e)
        }
        require(result.bytes.isNotEmpty()) { "provider returned empty audio" }
        return SpeechAudio(result.bytes, mimeOf(prepared.format), result.words)
    }

    /** Emit native audio frames immediately; WAV keeps the one-shot path because its header needs final sizes. */
    fun stream(
        text: String,
        voice: String?,
        format: String?,
        plainTextOnly: Boolean = false
    ): Flux<SpeechAudio> = stream(
        text = text,
        overrides = SpeechOverrides(
            voice = voice,
            inputMode = if (plainTextOnly) SpeechInputMode.PLAIN_TEXT else SpeechInputMode.PROVIDER_GENERATED_MARKUP
        ),
        format = format
    )

    fun stream(
        text: String,
        overrides: SpeechOverrides,
        format: String?
    ): Flux<SpeechAudio> = Flux.defer {
        val prepared = prepare(text, overrides, format)
        val uri = DashScopeTtsProtocol.endpoint(settings.baseUrl)
        val chunks = if (prepared.format == "wav") {
            mono { transport.run(uri, settings.apiKey, prepared.exchange, settings.timeoutSeconds) }.flux()
        } else {
            transport.stream(uri, settings.apiKey, prepared.exchange, settings.timeoutSeconds)
        }
        chunks
            .map { bytes -> SpeechAudio(bytes, mimeOf(prepared.format)) }
            .onErrorMap { error ->
                IllegalStateException("DashScope speech synthesis failed: ${error.message}", error)
            }
    }

    private fun prepare(
        text: String,
        overrides: SpeechOverrides,
        format: String?
    ): PreparedSpeech {
        val model = settings.defaultModel.trim()
        require(model.isNotBlank()) { "DashScope speech needs a model id (e.g. qwen-audio-3.1-tts-flash)" }
        require(settings.apiKey.isNotBlank()) {
            "DashScope speech needs an API key; set it on the model's group or row"
        }

        val options = parseOptions(settings.options)
        val resolvedVoice = overrides.voice?.takeIf { it.isNotBlank() }
            ?: options?.string("voice")
            ?: DEFAULT_VOICES[model]
            ?: throw IllegalStateException(
                "no voice for '$model': set \"voice\" in this model's Generation Options, " +
                    "or pass a system voice from the Qwen-Audio-TTS voice list"
            )
        val resolvedFormat = supportedFormat(format?.takeIf { it.isNotBlank() } ?: options?.string("format"))
        val ssml = overrides.inputMode == SpeechInputMode.PROVIDER_GENERATED_MARKUP &&
            options?.boolean("enable_ssml") == true
        require(text.isNotBlank()) { "nothing to synthesize: the text is blank" }
        // A segment emotion is a control tag prepended to the whole segment; the text may already
        // open with one the planner wrote itself, in which case prefixing would override it.
        val taggedText = overrides.emotionTag?.takeIf { it.isNotBlank() }?.takeIf { opensWithPlainText(text) }
            ?.let { tag ->
                require(EMOTION_TAG_PATTERN.matches(tag)) { "invalid emotion tag" }
                "$tag$text"
            } ?: text

        require(taggedText.length <= DashScopeTtsProtocol.MAX_TOTAL_CHARS) {
            "text is ${taggedText.length} characters, over the ${DashScopeTtsProtocol.MAX_TOTAL_CHARS}-character limit of one speech task"
        }
        // SSML mode accepts exactly one continue-task, so an over-limit SSML document cannot be split.
        if (ssml && taggedText.length > DashScopeTtsProtocol.MAX_CHARS_PER_FRAME) {
            throw IllegalStateException(
                "SSML is enabled and this text is ${taggedText.length} characters; SSML allows a single frame of at most " +
                    "${DashScopeTtsProtocol.MAX_CHARS_PER_FRAME} characters. Shorten it or disable enable_ssml."
            )
        }

        val taskId = UUID.randomUUID().toString()
        val parameters = buildParameters(resolvedVoice, resolvedFormat, overrides, options, ssml)
        val chunks = DashScopeTtsProtocol.chunkText(taggedText, singleFrame = ssml)
        val exchange = TtsExchange(
            runTask = DashScopeTtsProtocol.runTask(taskId, model, parameters),
            continueTasks = chunks.map { DashScopeTtsProtocol.continueTask(taskId, it) },
            finishTask = DashScopeTtsProtocol.finishTask(taskId),
            format = resolvedFormat
        )
        logger.debug(
            "DashScope TTS (ws): model={} voice={} format={} frames={} chars={}",
            model, resolvedVoice, resolvedFormat, chunks.size, text.length
        )
        return PreparedSpeech(resolvedFormat, exchange)
    }

    /**
     * Assemble `payload.parameters`, omitting anything the row did not set so the service defaults
     * apply. Values are clamped to their documented ranges instead of being sent raw — the service
     * answers a rejected range with an opaque `Engine error`, which is harder to act on than a
     * locally-normalised value.
     */
    private fun buildParameters(
        voice: String,
        format: String,
        overrides: SpeechOverrides,
        options: JsonNode?,
        ssml: Boolean
    ): Map<String, Any> = linkedMapOf<String, Any>().apply {
        put("voice", voice)
        put("format", format)
        put("sample_rate", options?.int("sample_rate")?.takeIf { it in DashScopeTtsProtocol.SAMPLE_RATES } ?: DEFAULT_SAMPLE_RATE)
        options?.int("volume")?.takeIf { it in 0..100 }?.let { put("volume", it) }
        (overrides.rate ?: options?.double("rate"))?.takeIf { it in 0.5..2.0 }?.let { put("rate", it) }
        (overrides.pitch ?: options?.double("pitch"))?.takeIf { it in 0.5..2.0 }?.let { put("pitch", it) }
        // Bit rate only exists for the compressed formats; sending it with pcm/wav would be ignored at best.
        if (format == "mp3" || format == "opus") {
            options?.int("bit_rate")?.takeIf { it in 6..510 }?.let { put("bit_rate", it) }
        }
        options?.boolean("enable_ssml")?.let { put("enable_ssml", ssml) }
        (overrides.instruction ?: options?.string("instruction"))?.takeIf { it.isNotBlank() }?.let {
            put("instruction", it)
        }
        // The service reads only the first element; the row and every other consumer write an array.
        options?.strings("language_hints")?.takeIf { it.isNotEmpty() }?.let { put("language_hints", it) }
        options?.boolean("word_timestamp_enabled")?.let { put("word_timestamp_enabled", it) }
        options?.int("seed")?.takeIf { it in 0..65535 }?.let { put("seed", it) }
        options?.path("hot_fix")?.takeIf { !it.isMissingNode && !it.isNull }?.let { put("hot_fix", it) }
        options?.boolean("enable_aigc_tag")?.let { put("enable_aigc_tag", it) }
        options?.string("aigc_propagator")?.takeIf { it.isNotBlank() }?.let { put("aigc_propagator", it) }
        options?.string("aigc_propagate_id")?.takeIf { it.isNotBlank() }?.let { put("aigc_propagate_id", it) }
    }

    /** The protocol accepts mp3 / pcm / wav / opus only; anything else (aac, flac) becomes mp3. */
    private fun supportedFormat(requested: String?): String {
        val format = requested?.trim()?.lowercase()
        if (format.isNullOrBlank()) return DEFAULT_FORMAT
        if (format in DashScopeTtsProtocol.SUPPORTED_FORMATS) return format
        logger.debug("DashScope TTS has no '{}' output; falling back to {}", format, DEFAULT_FORMAT)
        return DEFAULT_FORMAT
    }

    private fun mimeOf(format: String): String = when (format) {
        "wav" -> "audio/wav"
        "opus" -> "audio/opus"
        "pcm" -> "audio/L16"
        else -> "audio/mpeg"
    }

    private fun readJson(body: String): JsonNode = SharedObjectMapper.instance.readTree(body)

    /**
     * Parse the row's `mediaOptions`. Unparseable JSON fails the call with the row's own wording:
     * silently falling back to the model defaults would speak with the wrong voice/format, which is
     * harder to notice than an error naming the bad row.
     */
    private fun parseOptions(raw: String): JsonNode? {
        if (raw.isBlank()) return null
        return try {
            readJson(raw)
        } catch (e: Exception) {
            throw IllegalStateException("this model's Generation Options is not valid JSON: ${e.message}", e)
        }
    }

    private fun JsonNode.string(field: String): String? =
        path(field).asString(null)?.takeIf { it.isNotBlank() }

    /**
     * A string-or-array field. `language_hints` is documented as an array, but rows written against
     * the older single-string shape are still honoured rather than silently dropped.
     */
    private fun JsonNode.strings(field: String): List<String>? {
        val node = path(field)
        if (node.isArray) {
            return node.mapNotNull { it.asString(null)?.takeIf { value -> value.isNotBlank() } }
        }
        return string(field)?.let { listOf(it) }
    }

    private fun JsonNode.int(field: String): Int? =
        path(field).asString(null)?.trim()?.toIntOrNull()
            ?: path(field).takeIf { it.isNumber }?.asInt()

    private fun JsonNode.double(field: String): Double? =
        path(field).asString(null)?.trim()?.toDoubleOrNull()
            ?: path(field).takeIf { it.isNumber }?.asDouble()

    private fun JsonNode.boolean(field: String): Boolean? =
        path(field).asString(null)?.trim()?.lowercase()?.let {
            when (it) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> null
            }
        } ?: path(field).takeIf { it.isBoolean }?.asBoolean()

    companion object {
        private const val DEFAULT_FORMAT = "mp3"

        /**
         * A control tag as the vendor publishes it, including the multi-word ones:
         * `[serious]`, `[very slowly]`, `[deep and loud shouting]`.
         */
        private val EMOTION_TAG_PATTERN = Regex("\\[[A-Za-z][A-Za-z0-9 _-]{0,39}]")

        /**
         * False when [text] already opens with an inline tag, which a prepended emotion would
         * override. Deliberately coarse: treating a leading `[1]` as a tag costs one dropped
         * prefix, while missing a real leading tag would silently discard the planner's choice.
         */
        private fun opensWithPlainText(text: String): Boolean = !text.trimStart().startsWith("[")

        /**
         * Sent explicitly rather than leaning on the server default (22050), so the same row produces
         * the same audio whether it is synthesised over this channel or another one.
         */
        private const val DEFAULT_SAMPLE_RATE = 24000

        /**
         * Documented system voices per model family, used only when neither the call nor the row
         * names one — a wrong voice is a hard provider error, so these stay conservative: each is
         * listed for its own model in the Qwen-Audio-TTS voice list.
         */
        private val DEFAULT_VOICES = mapOf(
            // Kept identical to `META[model].default` in scripts/gen-tts-catalog.py, which seeds the
            // catalog's `defaultVoice`; two sources of truth for the same fact would let the row's
            // seeded voice and this fallback disagree.
            "qwen-audio-3.1-tts-plus" to "longanlingxin_v3.1",
            "qwen-audio-3.1-tts-flash" to "longanhuan_v3.1",
            "qwen-audio-3.0-tts-plus" to "longanlingxin",
            "qwen-audio-3.0-tts-flash" to "longanfengyue",
            "cosyvoice-v3-flash" to "longanyang",
            "cosyvoice-v3-plus" to "longanyang",
            "cosyvoice-v3.5-flash" to "longanyang",
            "cosyvoice-v3.5-plus" to "longanyang"
        )
    }
}

private data class PreparedSpeech(
    val format: String,
    val exchange: TtsExchange
)

/**
 * The three pre-encoded frames of one synthesis task plus the format needed to concatenate the
 * reply. Encoding lives in [DashScopeTtsProtocol]; a transport only has to move these strings out
 * and binary audio back.
 */
internal data class TtsExchange(
    val runTask: String,
    val continueTasks: List<String>,
    /** Non-cancel finish: the text is complete, so the server should flush and close the task. */
    val finishTask: String,
    val format: String
)

/** One duplex round-trip's result: the assembled clip plus any word timings the provider sent. */
internal data class TtsResult(
    val bytes: ByteArray,
    val words: List<TimedWord> = emptyList()
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is TtsResult && bytes.contentEquals(other.bytes) && words == other.words)

    override fun hashCode(): Int = bytes.contentHashCode() * 31 + words.hashCode()
}

/** One duplex round-trip, isolated so the protocol shapes can be tested without a socket. */
internal fun interface TtsTransport {
    suspend fun run(uri: String, apiKey: String, exchange: TtsExchange, timeoutSeconds: Long): ByteArray

    /**
     * Metadata-aware variant. The default reports no timings, so a one-line fake transport in a test
     * stays a one-liner; the real socket overrides it and collects what the provider sent.
     */
    suspend fun runWithWords(
        uri: String,
        apiKey: String,
        exchange: TtsExchange,
        timeoutSeconds: Long
    ): TtsResult = TtsResult(run(uri, apiKey, exchange, timeoutSeconds))

    /** One-shot fallback for test/fake transports; the real socket overrides this with live frames. */
    fun stream(uri: String, apiKey: String, exchange: TtsExchange, timeoutSeconds: Long): Flux<ByteArray> =
        mono { run(uri, apiKey, exchange, timeoutSeconds) }.flux()
}

/**
 * The real socket.
 *
 * Frames are consumed strictly in order (`concatMap`), because the binary audio frames must be
 * appended in the order the server produced them. The single subscription is intentional: the
 * session's receive stream can only be consumed once, so `task-started` is handled inline by
 * sending the text frames from within the same pipeline.
 *
 * Lifecycle, which a one-shot task must not leave to the socket:
 *  - a `task-failed` event becomes an exception carrying the provider's own `error_code` /
 *    `error_message` — the socket closes successfully, so this is the only place it surfaces;
 *  - `task-finished` completes the stream immediately, which closes the connection instead of
 *    waiting out the server's 60-second idle window ([pump] + `takeUntil`);
 *  - a socket that closes before `task-finished`, a frame that is not a documented event, or an
 *    audio buffer past [DashScopeTtsProtocol.MAX_AUDIO_BYTES] is a failure — partial audio is never
 *    returned as success;
 *  - on timeout or cancellation the reactive chain is cancelled, which drops the connection.
 */
internal object WebSocketTransport : TtsTransport {

    private val logger = LoggerFactory.getLogger(javaClass)

    private const val MAX_FRAME_PAYLOAD = 8 * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val MAX_TIMEOUT_SECONDS = 300L

    /**
     * The frame cap is raised well above the 64 KiB default: one binary frame carries a slice of
     * audio, and a long sentence at a high sample rate can exceed the stock limit.
     */
    private val client = ReactorNettyWebSocketClient(
        HttpClient.create().option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
    ) { WebsocketClientSpec.builder().maxFramePayloadLength(MAX_FRAME_PAYLOAD) }

    /** Mutable per-task state; `takeUntil` reads [terminal] after every handled frame. */
    internal class TaskState(val startedAtNanos: Long = System.nanoTime()) {
        var started = false
        var finished = false
        var firstAudioNanos: Long? = null
        var binaryFrames = 0
        var audioBytes = 0
        val terminal: Boolean get() = finished
    }

    override suspend fun run(
        uri: String,
        apiKey: String,
        exchange: TtsExchange,
        timeoutSeconds: Long
    ): ByteArray = runWithWords(uri, apiKey, exchange, timeoutSeconds).bytes

    override suspend fun runWithWords(
        uri: String,
        apiKey: String,
        exchange: TtsExchange,
        timeoutSeconds: Long
    ): TtsResult {
        require(apiKey.isNotBlank()) { "DashScope speech needs an API key" }
        val headers = HttpHeaders()
        headers.set(HttpHeaders.AUTHORIZATION, "bearer $apiKey")
        headers.set("X-DashScope-DataInspection", "enable")

        val audio = ByteArrayOutputStream()
        val words = mutableListOf<TimedWord>()
        val state = TaskState()
        val limit = timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS).seconds
        // `withTimeoutOrNull` plus an explicit exception, not `withTimeout`: the latter throws a
        // `TimeoutCancellationException`, which *is* a CancellationException, so every caller above
        // treats a slow provider as "the user navigated away" — no retry, no error, no `Failed` event.
        // The distinction matters: a real cancellation still propagates from `withTimeoutOrNull`, while
        // only an expired budget comes back as null.
        val completed = withTimeoutOrNull(limit) {
            client.execute(URI.create(uri), headers) { session ->
                session.send(Mono.just(session.textMessage(exchange.runTask)))
                    .then(
                        pump(
                            messages = session.receive(),
                            sendFrames = { frames ->
                                session.send(Flux.fromIterable(frames.map { session.textMessage(it) }))
                            },
                            exchange = exchange,
                            audio = audio,
                            state = state,
                            onWords = { incoming -> words += incoming }
                        )
                    )
            }.awaitSingleOrNull()
            true
        }
        if (completed == null) {
            throw TimeoutException("speech synthesis produced no result within ${limit.inWholeSeconds}s")
        }

        if (!state.finished) {
            throw IllegalStateException(
                "speech task ended before task-finished (started=$state.started, ${audio.size()} audio bytes); " +
                    "refusing to return a truncated result"
            )
        }
        val bytes = DashScopeTtsProtocol.finalizeAudio(audio.toByteArray(), exchange.format)
        logger.debug(
            "DashScope TTS done: {} bytes in {} frame(s), {} words, first audio at {} ms, total {} ms",
            bytes.size,
            state.binaryFrames,
            words.size,
            state.firstAudioNanos?.let { (it - state.startedAtNanos) / 1_000_000 } ?: -1,
            (System.nanoTime() - state.startedAtNanos) / 1_000_000
        )
        // Frames may repeat a word while its sentence is still being synthesized. Dropping *only* an
        // immediate exact repeat keeps that fix without erasing a word that legitimately recurs later:
        // `distinctBy(beginMs, text)` would also collapse two sentences whose timings restart at 0.
        val deduped = ArrayList<TimedWord>(words.size).apply {
            for (word in words) if (lastOrNull() != word) add(word)
        }
        return TtsResult(bytes, deduped)
    }

    /** Live variant of [run]: publish each binary frame and never retain the whole clip. */
    override fun stream(
        uri: String,
        apiKey: String,
        exchange: TtsExchange,
        timeoutSeconds: Long
    ): Flux<ByteArray> {
        require(apiKey.isNotBlank()) { "DashScope speech needs an API key" }
        require(exchange.format != "wav") { "streaming WAV requires a finalized header" }
        val headers = HttpHeaders()
        headers.set(HttpHeaders.AUTHORIZATION, "bearer $apiKey")
        headers.set("X-DashScope-DataInspection", "enable")

        return Flux.create({ sink ->
            val state = TaskState()
            val disposable = client.execute(URI.create(uri), headers) { session ->
                session.send(Mono.just(session.textMessage(exchange.runTask)))
                    .then(
                        pump(
                            messages = session.receive(),
                            sendFrames = { frames ->
                                session.send(Flux.fromIterable(frames.map { session.textMessage(it) }))
                            },
                            exchange = exchange,
                            audio = null,
                            state = state,
                            onAudio = { bytes -> if (!sink.isCancelled) sink.next(bytes) }
                        )
                    )
            }.subscribe(
                { },
                { error -> if (!sink.isCancelled) sink.error(error) },
                {
                    if (!sink.isCancelled) {
                        when {
                            !state.finished -> sink.error(
                                IllegalStateException("speech task ended before task-finished; refusing a truncated stream")
                            )
                            state.audioBytes == 0 -> sink.error(IllegalStateException("provider returned empty audio"))
                            else -> {
                                logger.debug(
                                    "DashScope TTS stream done: {} bytes in {} frame(s), first audio at {} ms, total {} ms",
                                    state.audioBytes,
                                    state.binaryFrames,
                                    state.firstAudioNanos?.let { (it - state.startedAtNanos) / 1_000_000 } ?: -1,
                                    (System.nanoTime() - state.startedAtNanos) / 1_000_000
                                )
                                sink.complete()
                            }
                        }
                    }
                }
            )
            // HTTP disconnect or server timeout disposes the socket, which is the provider cancel path.
            sink.onDispose { disposable.dispose() }
        }, FluxSink.OverflowStrategy.BUFFER).timeout(
            Duration.ofSeconds(timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS))
        )
    }

    /**
     * Drive one task's reply stream to completion.
     *
     * Split out from [run] so the ordering and termination rules can be tested over a plain
     * `Flux<WebSocketMessage>` with a fake send, without a socket.
     */
    internal fun pump(
        messages: Flux<WebSocketMessage>,
        sendFrames: (List<String>) -> Mono<Void>,
        exchange: TtsExchange,
        audio: ByteArrayOutputStream?,
        state: TaskState,
        onAudio: (ByteArray) -> Unit = {},
        onWords: (List<TimedWord>) -> Unit = {}
    ): Mono<Void> =
        messages
            .concatMap { message ->
                handle(message, exchange, audio, state, sendFrames, onAudio, onWords)
                    // Deferred on purpose: `thenReturn(state.terminal)` evaluates its argument when
                    // the inner Mono is *assembled*, i.e. before this frame is handled, so the value
                    // would always be the pre-frame one and `takeUntil` would never fire. That left
                    // the socket open until the server's idle timeout after every task.
                    .then(Mono.fromSupplier { state.terminal })
            }
            .takeUntil { terminal -> terminal }
            .then()

    private fun handle(
        message: WebSocketMessage,
        exchange: TtsExchange,
        audio: ByteArrayOutputStream?,
        state: TaskState,
        sendFrames: (List<String>) -> Mono<Void>,
        onAudio: (ByteArray) -> Unit,
        onWords: (List<TimedWord>) -> Unit
    ): Mono<Void> = when (message.type) {
        WebSocketMessage.Type.TEXT -> {
            // Reactor Netty releases each inbound frame after receive().onNext returns. Only copy
            // the payload here; releasing it ourselves makes Reactor's release a second decrement.
            val payload = message.payloadAsText
            val event = DashScopeTtsProtocol.parseEvent(payload)
            when {
                !DashScopeTtsProtocol.isKnownEvent(event) -> Mono.error(
                    IllegalStateException(
                        "unrecognised speech event '${event.type ?: "<unparseable>"}': ${payload.take(200)}"
                    )
                )
                event.isFailure -> Mono.error(IllegalStateException(event.failureMessage()))
                event.isFinished -> Mono.fromRunnable { state.finished = true }
                event.type == "task-started" -> {
                    state.started = true
                    // Only now may text be sent: the protocol rejects continue-task before task-started.
                    sendFrames(exchange.continueTasks + exchange.finishTask)
                }
                event.words.isNotEmpty() -> Mono.fromRunnable { onWords(event.words) }
                else -> Mono.empty()
            }
        }
        WebSocketMessage.Type.BINARY -> {
            if (state.firstAudioNanos == null) state.firstAudioNanos = System.nanoTime()
            state.binaryFrames++
            val bytes = readBytes(message)
            if (audio != null) DashScopeTtsProtocol.appendAudio(audio, bytes, exchange.format)
            state.audioBytes += bytes.size
            if (state.audioBytes > DashScopeTtsProtocol.MAX_AUDIO_BYTES) {
                Mono.error(
                    IllegalStateException(
                        "speech audio exceeded ${DashScopeTtsProtocol.MAX_AUDIO_BYTES} bytes; task cancelled"
                    )
                )
            } else {
                Mono.fromRunnable { onAudio(bytes) }
            }
        }
        else -> Mono.empty()
    }

    /** Copy a frame out of its pooled buffer; Reactor Netty owns and releases the inbound frame. */
    private fun readBytes(message: WebSocketMessage): ByteArray {
        val buffer = message.payload
        val bytes = ByteArray(buffer.readableByteCount())
        buffer.read(bytes)
        return bytes
    }
}
