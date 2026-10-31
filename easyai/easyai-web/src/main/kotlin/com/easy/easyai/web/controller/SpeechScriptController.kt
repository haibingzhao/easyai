package com.easy.easyai.web.controller

import com.easy.easyai.speech.SpeechDirectionParams
import com.easy.easyai.speech.SpeechScriptQuery
import com.easy.easyai.speech.SpeechScriptRecord
import com.easy.easyai.speech.SpeechScriptSort
import com.easy.easyai.speech.SpeechScriptStatus
import com.easy.easyai.speech.SpeechScriptSummary
import com.easy.easyai.speech.SpeechScriptTarget
import com.easy.easyai.speech.SpeechTtsParams
import com.easy.easyai.web.security.currentGroupOwners
import com.easy.easyai.web.service.SpeechScriptArchiveService
import com.easy.easyai.web.service.SpeechTarget
import com.easy.easyai.web.service.TtsService
import kotlinx.coroutines.reactor.mono
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

/**
 * The read-aloud script library.
 *
 * - `GET    /api/speech-scripts`          — list (summary rows only, never the segments)
 * - `GET    /api/speech-scripts/{id}`     — detail: source, segments, and the full parameter snapshot
 * - `POST   /api/speech-scripts`          — pin the plan the caller is holding (`scriptId`)
 * - `PATCH  /api/speech-scripts/{id}`     — rename / favourite
 * - `DELETE /api/speech-scripts/{id}`     — soft delete
 *
 * Rendering a stored script is *not* a second endpoint: `POST /api/tts/render {scriptId}` already
 * does it, and it now falls back to this archive when the in-memory plan has expired. One render
 * path means one place where segments are validated, paused and counted.
 *
 * Ownership is the caller's own bucket (`currentGroupOwners().first()`), the same bucket read-aloud
 * writes to. Scripts are personal: a shared session does not share its scripts, because the text
 * belongs to whoever pressed play.
 */
@RestController
@RequestMapping("/api/speech-scripts")
class SpeechScriptController(
    private val archive: SpeechScriptArchiveService,
    private val ttsService: TtsService
) {

    @GetMapping
    fun list(
        @RequestParam(required = false) type: String? = null,
        @RequestParam(required = false) targetId: String? = null,
        @RequestParam(required = false) sessionId: String? = null,
        @RequestParam(required = false) status: String? = null,
        @RequestParam(required = false) mode: String? = null,
        @RequestParam(required = false) favorite: Boolean? = null,
        @RequestParam(required = false) q: String? = null,
        @RequestParam(required = false) sort: String? = null,
        @RequestParam(required = false, defaultValue = "50") limit: Int = 50,
        @RequestParam(required = false, defaultValue = "0") offset: Int = 0
    ): Mono<List<ScriptSummaryDto>> = mono {
        requireArchive()
        val query = SpeechScriptQuery(
            targetType = type?.takeIf { it.isNotBlank() }?.also { requireKnownTarget(it) },
            targetId = targetId?.takeIf { it.isNotBlank() },
            sessionId = sessionId?.takeIf { it.isNotBlank() },
            status = status?.takeIf { it.isNotBlank() }?.let { parseStatus(it) },
            mode = mode?.takeIf { it.isNotBlank() },
            favoriteOnly = favorite == true,
            search = q?.takeIf { it.isNotBlank() },
            limit = limit.coerceIn(1, MAX_LIMIT),
            offset = offset.coerceAtLeast(0),
            sort = sort?.takeIf { it.isNotBlank() }?.let { parseSort(it) } ?: SpeechScriptSort.UPDATED
        )
        archive.list(owner(), query).map { ScriptSummaryDto.from(it) }
    }

    @GetMapping("/{id}")
    fun detail(@PathVariable id: String): Mono<ArchivedScriptDto> = mono {
        requireArchive()
        val record = archive.load(owner(), id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "This read-aloud script does not exist")
        ArchivedScriptDto.from(record)
    }

    /**
     * Pin the plan the caller already has.
     *
     * `scriptId` refers to the in-memory plan the panel is showing (or to an archived row). The
     * server reads the text from its own copy, so a client cannot pin a script against text it
     * never sent.
     */
    @PostMapping
    fun pin(@RequestBody request: PinScriptRequest): Mono<ArchivedScriptDto> = mono {
        requireArchive()
        val scriptId = request.scriptId?.takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "scriptId is required")
        val target = request.target()
        val record = ttsService.pinScript(currentGroupOwners(), scriptId, target, request.title)
            ?: throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "This script can no longer be saved; reopen the read-aloud script panel"
            )
        ArchivedScriptDto.from(record)
    }

    @PatchMapping("/{id}")
    fun patch(@PathVariable id: String, @RequestBody request: PatchScriptRequest): Mono<ArchivedScriptDto> =
        mono {
            requireArchive()
            if (request.title == null && request.favorite == null) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to update")
            }
            val updated = archive.patch(
                owner = owner(),
                id = id,
                title = request.title,
                favorite = request.favorite
            ) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "This read-aloud script does not exist")
            ArchivedScriptDto.from(updated)
        }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): Mono<Map<String, Boolean>> = mono {
        requireArchive()
        val removed = archive.archive(owner(), id)
        if (!removed) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "This read-aloud script does not exist")
        }
        mapOf("deleted" to true)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private suspend fun owner(): String = currentGroupOwners().firstOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "No owner in scope")

    private fun requireArchive() {
        if (!archive.available) {
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "The read-aloud script library needs the database (easyai.r2dbc.enabled)"
            )
        }
    }

    private fun requireKnownTarget(type: String) {
        if (SpeechScriptTarget.fromKey(type) == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown read-aloud target: $type")
        }
    }

    private fun parseStatus(raw: String): SpeechScriptStatus = SpeechScriptStatus.fromKey(raw)
        ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown read-aloud script status: $raw")

    private fun parseSort(raw: String): SpeechScriptSort =
        SpeechScriptSort.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown sort order: $raw")

    companion object {
        /** The list is a library page, not an export: 200 rows is already more than one screen. */
        private const val MAX_LIMIT = 200
    }
}

/** Where the extra text came from; omitted fields mean "manual text". */
data class PinScriptRequest(
    val scriptId: String? = null,
    val title: String? = null,
    val targetType: String? = null,
    val targetId: String? = null,
    val sessionId: String? = null
) {
    fun target(): SpeechTarget = SpeechTarget(
        type = targetType?.takeIf { it.isNotBlank() } ?: SpeechScriptTarget.MANUAL_TEXT.key,
        id = targetId?.takeIf { it.isNotBlank() },
        sessionId = sessionId?.takeIf { it.isNotBlank() }
    )
}

/** Rename or favourite; the script body is not editable here on purpose (edits go through render). */
data class PatchScriptRequest(
    val title: String? = null,
    val favorite: Boolean? = null
)

/**
 * A list row.
 *
 * [targetId] may be null (manual text), and [mode] is `refine` or `director` — the UI labels it, the
 * wire keeps the key.
 */
data class ScriptSummaryDto(
    val id: String,
    val title: String,
    val targetType: String,
    val targetId: String?,
    val sessionId: String?,
    val mode: String,
    val status: String,
    val revision: Int,
    val segmentCount: Int,
    val sourceChars: Int,
    val spokenChars: Int,
    val style: String,
    val mood: String?,
    val favorite: Boolean,
    val playCount: Int,
    val lastPlayedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long
) {
    companion object {
        @JvmStatic
        fun from(summary: SpeechScriptSummary): ScriptSummaryDto = ScriptSummaryDto(
            id = summary.id,
            title = summary.title,
            targetType = summary.targetType,
            targetId = summary.targetId,
            sessionId = summary.sessionId,
            mode = summary.mode,
            status = summary.status.name,
            revision = summary.revision,
            segmentCount = summary.segmentCount,
            sourceChars = summary.sourceChars,
            spokenChars = summary.spokenChars,
            style = summary.style,
            mood = summary.mood,
            favorite = summary.isFavorite,
            playCount = summary.playCount,
            lastPlayedAt = summary.lastPlayedAt,
            createdAt = summary.createdAt,
            updatedAt = summary.updatedAt
        )
    }
}

/**
 * A stored script in full, including the parameter snapshot.
 *
 * The snapshot is what makes an old script still playable after the model row it named has been
 * renamed, retyped or deleted: the audio is re-synthesized with the voice recorded here, not with
 * whatever the current configuration says. A client that wants to warn "this will play with the old
 * voice" can compare [ttsParams] against the current model row; the server does not pre-judge that,
 * because a stored script that still plays is the feature, not a defect.
 */
data class ArchivedScriptDto(
    val id: String,
    val title: String,
    val targetType: String,
    val targetId: String?,
    val sessionId: String?,
    val mode: String,
    val status: String,
    val revision: Int,
    val style: String,
    val mood: String,
    val sourceText: String,
    val sourceChars: Int,
    val spokenChars: Int,
    val segmentCount: Int,
    val segments: List<SpeechSegmentDto>,
    val warnings: List<WarningDto>,
    val ttsParams: SpeechTtsParams,
    val directionParams: SpeechDirectionParams?,
    val favorite: Boolean,
    val playCount: Int,
    val lastPlayedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long
) {
    companion object {
        @JvmStatic
        fun from(record: SpeechScriptRecord): ArchivedScriptDto = ArchivedScriptDto(
            id = record.id,
            title = record.title,
            targetType = record.targetType,
            targetId = record.targetId,
            sessionId = record.sessionId,
            mode = record.mode,
            status = record.status.name,
            revision = record.revision,
            style = record.script.style.name,
            mood = record.script.mood,
            sourceText = record.sourceText,
            sourceChars = record.sourceChars,
            spokenChars = record.spokenChars,
            segmentCount = record.segmentCount,
            segments = record.script.segments.map { segment ->
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
            warnings = record.script.warnings.map { WarningDto.from(it) },
            ttsParams = record.ttsParams,
            directionParams = record.directionParams,
            favorite = record.isFavorite,
            playCount = record.playCount,
            lastPlayedAt = record.lastPlayedAt,
            createdAt = record.createdAt,
            updatedAt = record.updatedAt
        )
    }
}
