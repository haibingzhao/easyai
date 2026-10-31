package com.easy.easyai.web.controller

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.speech.SpeechRenderEvent
import com.easy.easyai.speech.SpeechRole
import com.easy.easyai.speech.SpeechScript
import com.easy.easyai.speech.SpeechSegment
import com.easy.easyai.speech.SpeechWarning
import com.easy.easyai.speech.TimedWord
import com.easy.easyai.web.security.currentGroupOwners
import com.easy.easyai.web.service.PlannedScript
import com.easy.easyai.web.service.SpeechTarget
import com.easy.easyai.web.service.TtsAvailability
import com.easy.easyai.web.service.TtsService
import kotlinx.coroutines.reactor.mono
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/**
 * Chat read-aloud endpoints.
 *
 * - GET  /api/tts/availability - whether a usable `tts` row exists (no synthesis, no cost)
 * - POST /api/tts/speak        - one message's text → a chunked `audio/mpeg` stream
 * - POST /api/tts/script       - plan the text, return the script; synthesizes nothing
 * - POST /api/tts/render       - plan (or reload) and render it → a chunked NDJSON event stream
 *
 * The caller fetches `speak` with `authFetch` and plays the response stream (with a blob fallback),
 * rather than pointing an `<audio src>` at it: a URL-borne token would leak into history and logs.
 * `render` is fetched the same way; its body is one JSON event per line, because audio has to be
 * delivered together with the plan metadata that says which segment it is and how long to pause.
 *
 * Feature availability is decided by the caller's effective Task Models `tts` choice, read across
 * the same self → group owner set the settings page writes to; `speak` answers 409 when it is
 * unconfigured, 413 over the character limit and 429 when a request is already running.
 */
@RestController
@RequestMapping("/api/tts")
class TtsController(
    private val ttsService: TtsService
) {

    @PostMapping(
        "/speak",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = ["audio/mpeg"]
    )
    fun speak(@RequestBody request: SpeakRequest): Mono<ResponseEntity<Flux<DataBuffer>>> {
        // Claimed inside this Mono, before the ResponseEntity exists, for the same reason as
        // `render`: a 400/409/413/429 from the claim must stay a status code instead of arriving as
        // a `200 OK` whose audio body is already dead.
        val prepared = AtomicReference<TtsService.PreparedSpeech?>(null)
        return mono {
            val claimed = ttsService.openSpeak(currentGroupOwners(), request.text, request.mode, request.target())
            prepared.set(claimed)
            val response = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                // No Content-Length: the response stays open while provider frames arrive.
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .cacheControl(CacheControl.noStore())
            // The audio is still useful, but the client must not present a plain fallback as a
            // successful refinement. A short reason code keeps the HTTP header ASCII-only.
            claimed.fallbackReason?.let { response.header(SPEECH_FALLBACK_HEADER, it) }
            response.body(ttsService.speakStream(claimed).map<DataBuffer> { chunk ->
                DefaultDataBufferFactory.sharedInstance.wrap(chunk.bytes)
            })
        }.doOnCancel { prepared.getAndSet(null)?.let(ttsService::release) }
    }

    @PostMapping(
        "/render",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [NDJSON]
    )
    fun render(@RequestBody request: RenderRequest): Mono<ResponseEntity<Flux<DataBuffer>>> {
        // Claimed inside this Mono, before the ResponseEntity exists: a 409/413/404 here is still a
        // status code, whereas the same failure inside the body flux would arrive only after a `200`
        // status line and NDJSON headers had already been sent.
        val claimed = AtomicReference<TtsService.RenderSession?>(null)
        return mono {
            val handle = ttsService.openRender(
                owners = currentGroupOwners(),
                text = request.text,
                scriptId = request.scriptId,
                target = request.target()
            )
            claimed.set(handle)
            val events = ttsService.renderStream(
                handle = handle,
                editedSegments = request.segments?.map { it.toSegment() },
                incremental = request.incremental
            )
            ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(NDJSON))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .cacheControl(CacheControl.noStore())
                // One event per line: a chunk boundary may fall anywhere, so the client reassembles by
                // newline rather than assuming one frame per event.
                .body(events.map<DataBuffer> { event ->
                    DefaultDataBufferFactory.sharedInstance.wrap(toLine(event))
                })
        }.doOnCancel { claimed.getAndSet(null)?.let(ttsService::release) }
    }

    @PostMapping(
        "/script",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun script(@RequestBody request: SpeakRequest): Mono<SpeechScriptDto> = mono {
        val planned = ttsService.planScript(currentGroupOwners(), request.text, request.target())
        SpeechScriptDto.from(planned, planned.reused)
    }

    @GetMapping("/availability")
    fun availability(): Mono<TtsAvailabilityDto> = mono {
        TtsAvailabilityDto.from(ttsService.availability(currentGroupOwners()))
    }

    private fun toLine(event: SpeechRenderEvent): ByteArray {
        val dto: Any = when (event) {
            is SpeechRenderEvent.Meta -> MetaDto(
                scriptId = event.scriptId,
                revision = event.revision,
                segmentCount = event.segmentCount,
                planned = event.planned,
                warnings = event.warnings.map { WarningDto.from(it) }
            )

            is SpeechRenderEvent.SegmentAudio -> SegmentAudioDto(
                segmentId = event.segmentId,
                index = event.index,
                spokenText = event.spokenText,
                voice = event.voice,
                pauseAfterMs = event.pauseAfterMs,
                mimeType = event.mimeType,
                data = Base64.getEncoder().encodeToString(event.bytes),
                words = event.words.map { WordDto(it.text, it.beginMs, it.endMs) }
            )

            // A standalone warning is its own event line, so it needs the same `type` discriminator
            // every other line carries; the nested warnings inside `meta`/`done` are plain payloads.
            is SpeechRenderEvent.Warning -> WarningEventDto.from(event.warning)
            is SpeechRenderEvent.Completed ->
                CompletedDto(segments = event.segments, warnings = event.warnings.map { WarningDto.from(it) })
            is SpeechRenderEvent.Failed ->
                FailedDto(code = event.code, message = event.message, playedSegments = event.playedSegments)
        }
        return (jsonOf(dto) + "\n").toByteArray(Charsets.UTF_8)
    }

    private fun jsonOf(value: Any): String = SharedObjectMapper.instance.writeValueAsString(value)

    companion object {
        /** One JSON object per line; the client splits on `\n` and ignores blank lines. */
        const val NDJSON = "application/x-ndjson"
        const val SPEECH_FALLBACK_HEADER = "X-Speech-Fallback"
    }
}

/**
 * Read-aloud request; voice, format and provider controls remain server-owned.
 *
 * The optional target fields say where the text came from. They are used by `POST /script`, by a
 * refined `POST /speak` (for its library row), and by `render` when no `scriptId` is sent: without
 * them a plan is filed as manual text, which is never reused for a message and never shared between
 * replies.
 */
data class SpeakRequest(
    val text: String = "",
    /** `direct` preserves the legacy behavior; `refine` plans one validated single-task script. */
    val mode: String = "direct",
    val targetType: String? = null,
    val targetId: String? = null,
    val sessionId: String? = null
) {
    fun target(): SpeechTarget = SpeechTarget(
        type = targetType?.takeIf { it.isNotBlank() } ?: SpeechTarget.manual().type,
        id = targetId?.takeIf { it.isNotBlank() },
        sessionId = sessionId?.takeIf { it.isNotBlank() }
    )
}

/**
 * A directed read-aloud request.
 *
 * Either [text] (plan it now) or [scriptId] (render the stored plan) identifies the work. [segments]
 * only makes sense with [scriptId]: it replaces the stored plan's segments, and the server
 * re-validates the result against the source it stored, then bumps the revision.
 */
data class RenderRequest(
    val text: String = "",
    val scriptId: String? = null,
    val segments: List<SpeechSegmentDto>? = null,
    val targetType: String? = null,
    val targetId: String? = null,
    val sessionId: String? = null,
    /**
     * `true` (default) streams a fresh plan; `false` builds and validates the whole plan first.
     * Only used without [scriptId] — a stored plan is always validated whole before it renders.
     */
    val incremental: Boolean = true
) {
    fun target(): SpeechTarget = SpeechTarget(
        type = targetType?.takeIf { it.isNotBlank() } ?: SpeechTarget.manual().type,
        id = targetId?.takeIf { it.isNotBlank() },
        sessionId = sessionId?.takeIf { it.isNotBlank() }
    )
}

/** One client-editable segment. Every field is re-validated server-side. */
data class SpeechSegmentDto(
    val id: String = "",
    val index: Int = 0,
    val sourceText: String = "",
    val spokenText: String = "",
    val voice: String = "",
    val emotion: String? = null,
    val styleNote: String? = null,
    val rate: Double? = null,
    val role: SpeechRole = SpeechRole.NARRATION,
    val pauseAfterMs: Int = 0
) {
    fun toSegment(): SpeechSegment = SpeechSegment(
        id = id,
        index = index,
        // Only `spokenText` is user-editable in practice; `sourceText` is still submitted so the
        // server can refuse a plan whose segments no longer map onto the stored source.
        sourceText = sourceText,
        spokenText = spokenText,
        voice = voice,
        emotion = emotion,
        styleNote = styleNote,
        rate = rate,
        role = role,
        pauseAfterMs = pauseAfterMs
    )
}

/** The plan as the script panel needs it: both texts, per segment, plus what was repaired. */
data class SpeechScriptDto(
    val scriptId: String,
    val revision: Int,
    val style: String,
    val mood: String,
    val segments: List<SpeechSegmentDto>,
    val warnings: List<WarningDto>,
    val expiresInSeconds: Long,
    /** True when this plan came from the archive instead of a fresh planning call. */
    val reused: Boolean = false
) {
    companion object {
        @JvmStatic
        fun from(planned: PlannedScript, reused: Boolean = planned.reused): SpeechScriptDto {
            val script: SpeechScript = planned.script
            return SpeechScriptDto(
                scriptId = script.scriptId,
                revision = script.revision,
                style = script.style.name,
                mood = script.mood,
                segments = script.segments.map { segment ->
                    SpeechSegmentDto(
                        id = segment.id,
                        index = segment.index,
                        sourceText = segment.sourceText,
                        spokenText = segment.spokenText,
                        voice = segment.voice,
                        emotion = segment.emotion,
                        styleNote = segment.styleNote,
                        rate = segment.rate,
                        role = segment.role,
                        pauseAfterMs = segment.pauseAfterMs
                    )
                },
                warnings = planned.warnings.map { WarningDto.from(it) },
                expiresInSeconds = planned.expiresInSeconds,
                reused = reused
            )
        }
    }
}

/** One mid-stream warning event; [type] is what lets the client route it. */
data class WarningEventDto(
    val type: String = "warning",
    val code: String,
    val segmentId: String? = null,
    val message: String
) {
    companion object {
        @JvmStatic
        fun from(warning: SpeechWarning): WarningEventDto = WarningEventDto(
            code = warning.code,
            segmentId = warning.segmentId,
            message = warning.message
        )
    }
}

/** A repaired defect, kept machine-readable so the client can explain rather than guess. */
data class WarningDto(
    val code: String,
    val segmentId: String? = null,
    val message: String
) {
    companion object {
        @JvmStatic
        fun from(warning: SpeechWarning): WarningDto =
            WarningDto(warning.code, warning.segmentId, warning.message)
    }
}

data class WordDto(
    val text: String,
    val beginMs: Int,
    val endMs: Int
) {
    companion object {
        @JvmStatic
        fun from(word: TimedWord): WordDto = WordDto(word.text, word.beginMs, word.endMs)
    }
}

data class MetaDto(
    val type: String = "meta",
    val scriptId: String,
    val revision: Int,
    val segmentCount: Int,
    val planned: Boolean,
    val warnings: List<WarningDto> = emptyList()
)

data class SegmentAudioDto(
    val type: String = "audio",
    val segmentId: String,
    val index: Int,
    val spokenText: String,
    val voice: String,
    val pauseAfterMs: Int,
    val mimeType: String,
    /** Base64: NDJSON has to survive text framing, and a length prefix would need its own decoder. */
    val data: String,
    val words: List<WordDto> = emptyList()
)

data class CompletedDto(
    val type: String = "done",
    val segments: Int,
    val warnings: List<WarningDto> = emptyList()
)

data class FailedDto(
    val type: String = "failed",
    val code: String,
    val message: String,
    val playedSegments: Int
)

/** Whether the read-aloud button should render, and the reason when it should not. */
data class TtsAvailabilityDto(
    val configured: Boolean,
    val modelId: String? = null,
    val reason: String? = null
) {
    companion object {
        @JvmStatic
        fun from(availability: TtsAvailability): TtsAvailabilityDto = TtsAvailabilityDto(
            configured = availability.configured,
            modelId = availability.modelId,
            reason = availability.reason
        )
    }
}
