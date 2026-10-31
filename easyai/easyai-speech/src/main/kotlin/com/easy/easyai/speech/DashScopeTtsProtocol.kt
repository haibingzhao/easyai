package com.easy.easyai.speech

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.speech.DashScopeTtsProtocol.LOOKBACK_CHARS
import com.easy.easyai.speech.DashScopeTtsProtocol.MAX_TOTAL_CHARS
import com.easy.easyai.speech.DashScopeTtsProtocol.finalizeAudio
import com.easy.easyai.speech.DashScopeTtsProtocol.parseEvent
import tools.jackson.databind.JsonNode
import java.io.ByteArrayOutputStream

/**
 * Wire codec for the DashScope-native TTS duplex protocol.
 *
 * Qwen-Audio-TTS (`qwen-audio-3.0-tts-*` / `qwen-audio-3.1-tts-*`) and CosyVoice v3 publish no
 * OpenAI-compatible `/audio/speech` route. Their full-capability channel is this WebSocket protocol:
 * `run-task` opens a synthesis task, text arrives through one or more `continue-task` frames, audio
 * comes back as **binary frames** (not base64 in the JSON events), and `finish-task` closes the task.
 *
 * Everything positional lives here as pure functions so the shapes can be asserted without a socket.
 * The transport in [DashScopeTtsClient] only moves bytes.
 */
internal object DashScopeTtsProtocol {

    /** Single `continue-task` cap; the sum across frames is capped by [MAX_TOTAL_CHARS]. */
    const val MAX_CHARS_PER_FRAME = 20_000

    /** Server-side ceiling for one task; beyond this the service rejects the remainder. */
    const val MAX_TOTAL_CHARS = 200_000

    /**
     * Ceiling on the audio one task may aggregate.
     *
     * A frame cap alone does not bound a whole task: 200000 characters of speech is minutes of
     * audio, and every byte is held in memory until the reply is complete. Exceeding this is a
     * failure, never a truncated result.
     */
    const val MAX_AUDIO_BYTES = 32 * 1024 * 1024

    /**
     * Build the `run-task` frame.
     *
     * `payload.input` is deliberately an empty object: the protocol only accepts text through
     * `continue-task`, and a non-empty `input` here is rejected by the service.
     */
    fun runTask(taskId: String, model: String, parameters: Map<String, Any>): String {
        val body = linkedMapOf(
            "header" to linkedMapOf(
                "action" to "run-task",
                "task_id" to taskId,
                "streaming" to "duplex"
            ),
            "payload" to linkedMapOf(
                "task_group" to "audio",
                "task" to "tts",
                "function" to "SpeechSynthesizer",
                "model" to model,
                "input" to emptyMap<String, Any>(),
                "parameters" to LinkedHashMap(parameters).apply {
                    // Fixed by the protocol, SSML included: `enable_ssml` is the switch that turns
                    // markup parsing on (the vendor's own SSML example sends PlainText + enable_ssml).
                    // An `SSML` value here is rejected, so a caller cannot override it.
                    put("text_type", "PlainText")
                }
            )
        )
        return SharedObjectMapper.instance.writeValueAsString(body)
    }

    /** Build a `continue-task` frame carrying one slice of the text. */
    fun continueTask(taskId: String, text: String): String {
        val body = linkedMapOf(
            "header" to linkedMapOf(
                "action" to "continue-task",
                "task_id" to taskId,
                "streaming" to "duplex"
            ),
            "payload" to linkedMapOf("input" to linkedMapOf("text" to text))
        )
        return SharedObjectMapper.instance.writeValueAsString(body)
    }

    /**
     * Build the `finish-task` frame.
     *
     * With [cancel] the server stops immediately, emits `task-finished` and produces no further
     * audio — this is the "click again to stop" path, and the connection stays reusable.
     */
    fun finishTask(taskId: String, cancel: Boolean = false): String {
        val input: Map<String, Any> =
            if (cancel) mapOf("directive" to "cancel") else emptyMap()
        val body = linkedMapOf(
            "header" to linkedMapOf(
                "action" to "finish-task",
                "task_id" to taskId,
                "streaming" to "duplex"
            ),
            "payload" to linkedMapOf("input" to input)
        )
        return SharedObjectMapper.instance.writeValueAsString(body)
    }

    /** One server event, flattened to the fields the client acts on. */
    data class Event(
        /** `task-started` / `result-generated` / `task-finished` / `task-failed`, or null if unrecognised. */
        val type: String?,
        /** Present on `task-failed` only, e.g. `InvalidParameter`. */
        val errorCode: String? = null,
        /** Present on `task-failed` only, e.g. `[tts:]Engine return error code: 418`. */
        val errorMessage: String? = null,
        /** For `result-generated`: `sentence-begin` / `sentence-synthesis` / `sentence-end`. */
        val sentenceType: String? = null,
        /**
         * Word timings carried by a `result-generated` frame, present only when the task asked for
         * `word_timestamp_enabled`. Empty is the normal case, not an error.
         */
        val words: List<TimedWord> = emptyList()
    ) {
        val isFailure: Boolean get() = type == "task-failed"
        val isFinished: Boolean get() = type == "task-finished"
        val isTerminal: Boolean get() = type == "task-finished" || type == "task-failed"

        /** The message surfaced to the tool caller; keeps the provider's own wording. */
        fun failureMessage(): String =
            listOfNotNull(errorCode?.takeIf { it.isNotBlank() }, errorMessage?.takeIf { it.isNotBlank() })
                .joinToString(": ")
                .ifBlank { "speech task failed without an error message" }
    }

    /** Events the client acts on; anything else is a protocol error, not a frame to skip. */
    private val KNOWN_EVENTS = setOf("task-started", "result-generated", "task-finished", "task-failed")

    /** Parse a text frame; never throws — an unknown or malformed frame yields `Event(null)`. */
    fun parseEvent(payload: String): Event {
        val node = runCatching { SharedObjectMapper.instance.readTree(payload) }.getOrNull() ?: return Event(null)
        val header = node.path("header")
        val output = node.path("payload").path("output")
        return Event(
            type = header.path("event").asString(null),
            errorCode = header.path("error_code").asString(null),
            errorMessage = header.path("error_message").asString(null),
            sentenceType = output.path("type").asString(null),
            words = parseWords(output)
        )
    }

    /**
     * Read `output.sentence.words[]`, the only documented place for timings, with `output.words[]`
     * as a fallback for a flattened variant.
     *
     * A word is only accepted when it carries a usable millisecond range: an entry with character
     * indices instead of times cannot be turned into a seek position without knowing the clip's
     * duration, so it is dropped rather than guessed at. Dropping is safe — the feature degrades to
     * "no highlighting", which the client already handles.
     */
    private fun parseWords(output: JsonNode): List<TimedWord> {
        val array = listOf(output.path("sentence").path("words"), output.path("words"))
            .firstOrNull { it.isArray } ?: return emptyList()
        return array.mapNotNull { entry ->
            val text = entry.path("text").asString(null)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val begin = intOrNull(entry, "begin_time") ?: return@mapNotNull null
            val end = intOrNull(entry, "end_time") ?: begin
            if (begin !in 0..end) return@mapNotNull null
            TimedWord(text, begin, end)
        }
    }

    private fun intOrNull(node: JsonNode, field: String): Int? {
        val value = node.path(field)
        return when {
            value.isNumber -> value.intValue()
            // Some deployments quote numbers; an unusable value simply yields no timestamp.
            value.isString -> value.asString(null)?.trim()?.toIntOrNull()
            else -> null
        }
    }

    /** True for a frame carrying an event [parseEvent] recognised. */
    fun isKnownEvent(event: Event): Boolean = event.type in KNOWN_EVENTS

    /**
     * Split [text] into `continue-task` slices respected by the protocol.
     *
     * Cuts prefer a sentence terminator in the last [LOOKBACK_CHARS] characters so a single frame
     * rarely ends mid-phrase; when no terminator is available the cut lands at the hard limit. A cut
     * is never placed between the halves of a surrogate pair, which would corrupt an emoji into two
     * replacement characters.
     *
     * With [singleFrame] (SSML mode allows exactly one `continue-task`) the whole text is returned
     * unsplit — the caller must reject over-limit input rather than silently truncate it.
     */
    fun chunkText(
        text: String,
        limit: Int = MAX_CHARS_PER_FRAME,
        singleFrame: Boolean = false
    ): List<String> {
        require(limit > 1) { "limit must exceed one char" }
        if (singleFrame || text.length <= limit) return listOf(text)

        val out = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + limit, text.length)
            if (end < text.length) {
                val from = maxOf(start, end - LOOKBACK_CHARS)
                val window = text.substring(from, end)
                val breakAt = window.indexOfLast { it in SENTENCE_ENDS }
                if (breakAt >= 0) end = from + breakAt + 1
                // A cut inside `[clears throat]` would speak "[clears" and then a bare "throat]",
                // and the second frame's tag would no longer parse at either end.
                val beforeTag = avoidSplittingTag(text, start, end)
                if (beforeTag > start) end = beforeTag
                // Never split a surrogate pair: end-1 being a high surrogate means the pair straddles the cut.
                if (end > start + 1 && Character.isHighSurrogate(text[end - 1])) end--
                if (end <= start) end = minOf(start + limit, text.length)
            }
            out += text.substring(start, end)
            start = end
        }
        return out
    }

    /**
     * The largest cut at or before [end] that does not land inside a `[...]` tag.
     *
     * Tags are short and never nested, so only the nearest `[` before [end] can matter: if a `]`
     * follows it on the far side of the cut, the cut is inside the tag and [end] moves back to that
     * bracket. A `[` with no `]` after it is literal text (an arrow, a subscript), not a tag.
     *
     * Returns [end] unchanged when the bracket opens before [start] — that tag was already split by
     * the previous frame and cannot be repaired here. The one case that overruns the limit is a tag
     * longer than [limit] starting exactly at [start]: a slightly oversized frame is recoverable,
     * a spoken "[clears" is not.
     */
    private fun avoidSplittingTag(text: String, start: Int, end: Int): Int {
        val open = text.lastIndexOf('[', end - 1)
        if (open < start) return end
        val close = text.indexOf(']', open + 1)
        if (close < 0 || close < end) return end
        return if (open > start) open else close + 1
    }

    /**
     * Derive the duplex endpoint from a configured HTTP base URL; see [DashScopeSpeechEndpoint],
     * which the recognition transport and the web availability check use as well.
     */
    fun endpoint(baseUrl: String): String = DashScopeSpeechEndpoint.endpoint(baseUrl)

    /**
     * Append one binary audio frame to [sink].
     *
     * `mp3` / `pcm` / `opus` frames concatenate byte-for-byte. `wav` is the exception: the service
     * *may* prefix every frame with its own RIFF header, which concatenated yields a file whose
     * middle is a header rather than samples. Repeated headers are therefore stripped from every
     * frame after the first — a no-op when only the first frame carries one. Stripping alone is not
     * enough, because the first frame's RIFF and `data` sizes describe that frame only; [finalizeAudio]
     * rewrites both once the whole task has been collected.
     */
    fun appendAudio(sink: ByteArrayOutputStream, frame: ByteArray, format: String) {
        if (format != "wav" || sink.size() == 0) {
            sink.write(frame)
            return
        }
        val dataOffset = wavDataOffset(frame)
        if (dataOffset == null) {
            sink.write(frame)
        } else {
            sink.write(frame, dataOffset, frame.size - dataOffset)
        }
    }

    /**
     * Close an assembled buffer: for `wav` rewrite the RIFF container size and the `data` chunk size
     * so players see the full duration instead of the first fragment's.
     *
     * Returns [bytes] untouched for every other format, and for a `wav` reply that carries no RIFF
     * header at all (raw samples, in which case there is no container to fix).
     */
    fun finalizeAudio(bytes: ByteArray, format: String): ByteArray {
        if (format != "wav" || bytes.size < 12) return bytes
        val dataOffset = wavDataOffset(bytes) ?: return bytes
        if (dataOffset < 12) return bytes
        val out = bytes.copyOf()
        // RIFF size counts everything after the 8-byte "RIFF"+size preamble.
        writeIntLe(out, 4, out.size - 8)
        // The data chunk's size field sits immediately before its payload.
        writeIntLe(out, dataOffset - 4, out.size - dataOffset)
        return out
    }

    /**
     * Byte offset of the sample data inside a RIFF/WAVE frame, or null when [frame] is not a WAV
     * container (already-raw samples, or a frame without a header).
     */
    internal fun wavDataOffset(frame: ByteArray): Int? {
        if (frame.size < 12) return null
        if (!(frame[0] == 'R'.code.toByte() && frame[1] == 'I'.code.toByte() &&
                frame[2] == 'F'.code.toByte() && frame[3] == 'F'.code.toByte())
        ) return null
        if (!(frame[8] == 'W'.code.toByte() && frame[9] == 'A'.code.toByte() &&
                frame[10] == 'V'.code.toByte() && frame[11] == 'E'.code.toByte())
        ) return null
        // Walk the chunk list: [4-byte id][4-byte size][payload…], padded to an even length.
        var pos = 12
        while (pos + 8 <= frame.size) {
            val id = String(frame, pos, 4, Charsets.US_ASCII)
            // Clamped rather than trusted: an absurd size would otherwise overflow `pos` and either
            // skip a real `data` chunk or spin. A frame may legitimately *declare* more sample data
            // than it carries (that is what `finalizeAudio` fixes later), so this only bounds the walk.
            val size = readIntLe(frame, pos + 4).coerceIn(0, frame.size)
            val payloadStart = pos + 8
            if (id == "data") return minOf(payloadStart, frame.size)
            pos = payloadStart + size + (size and 1)
        }
        return null
    }

    internal fun readIntLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun writeIntLe(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    /** Where a cut may land: CJK and Latin sentence enders, plus newlines. */
    private val SENTENCE_ENDS = charArrayOf(
        '。', '！', '？', '；', '…', '\n',
        '.', '!', '?', ';'
    )

    /** How far back to look for a sentence ender before falling back to a hard cut. */
    private const val LOOKBACK_CHARS = 400


    /** Audio formats the duplex protocol accepts; anything else is refused rather than guessed. */
    val SUPPORTED_FORMATS = setOf("mp3", "pcm", "wav", "opus")

    /** Sample rates the protocol documents, reused verbatim when a row omits one. */
    val SAMPLE_RATES = setOf(8000, 16000, 22050, 24000, 44100, 48000)
}
