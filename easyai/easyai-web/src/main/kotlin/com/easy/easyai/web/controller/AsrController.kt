package com.easy.easyai.web.controller

import com.easy.easyai.web.security.getCurrentUserId
import com.easy.easyai.web.service.AsrService
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.multipart.FilePart
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

/**
 * Speech-input endpoints for the chat composer (frontend dictation mode).
 *
 * - POST /api/asr/transcribe - one WAV segment (multipart `file`) → its transcription text
 * - POST /api/asr/refine     - a dictation round's text + editor context → the context-aware rewrite
 *
 * Feature availability is decided by the user's Task Models choices (asr / dictation_refine);
 * both endpoints answer 409 when the backing task is unconfigured.
 */
@RestController
@RequestMapping("/api/asr")
class AsrController(
    private val asrService: AsrService
) {

    @PostMapping("/transcribe", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun transcribe(
        @RequestPart("file") filePart: FilePart,
        @RequestParam(name = "language", required = false) language: String?
    ): Mono<AsrTextDto> = mono {
        val userId = getCurrentUserId()
        val bytes = try {
            DataBufferUtils.join(filePart.content(), MAX_SEGMENT_BYTES).awaitSingle()
        } catch (e: DataBufferLimitException) {
            throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Audio segment exceeds the size limit", e)
        }
        val audio = try {
            ByteArray(bytes.readableByteCount()).also { bytes.read(it) }
        } finally {
            DataBufferUtils.release(bytes)
        }
        AsrTextDto(asrService.transcribe(userId, audio, language))
    }

    @PostMapping("/refine", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun refine(@RequestBody request: AsrRefineRequest): Mono<AsrTextDto> = mono {
        val userId = getCurrentUserId()
        AsrTextDto(
            asrService.refine(
                userId = userId,
                text = request.text,
                contextBefore = request.contextBefore,
                contextAfter = request.contextAfter
            )
        )
    }

    private companion object {
        const val MAX_SEGMENT_BYTES = 2 * 1024 * 1024
    }
}

/** Transcription / refine result; the text is final for the dictation slot it replaces. */
data class AsrTextDto(
    val text: String
)

/** One dictation round to rewrite; the context fields are reference only, never echoed back. */
data class AsrRefineRequest(
    val text: String = "",
    val contextBefore: String = "",
    val contextAfter: String = ""
)
