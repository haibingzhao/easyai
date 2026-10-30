package com.easy.easyai.web.controller

import com.easy.easyai.web.security.currentGroupOwners
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

/**
 * Chat read-aloud endpoints.
 *
 * - GET  /api/tts/availability - whether a usable `tts` row exists (no synthesis, no cost)
 * - POST /api/tts/speak        - one message's text → a chunked audio stream
 *
 * The caller fetches `speak` with `authFetch` and plays the response stream (with a blob fallback),
 * rather than pointing an `<audio src>` at it: a URL-borne token would leak into history and logs.
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
    fun speak(@RequestBody request: SpeakRequest): Mono<ResponseEntity<Flux<DataBuffer>>> = mono {
        val chunks = ttsService.speakStream(currentGroupOwners(), request.text)
        ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("audio/mpeg"))
            // No Content-Length: the response stays open while provider frames arrive.
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
            .cacheControl(CacheControl.noStore())
            .body(chunks.map { chunk -> DefaultDataBufferFactory.sharedInstance.wrap(chunk.bytes) })
    }

    @GetMapping("/availability")
    fun availability(): Mono<TtsAvailabilityDto> = mono {
        TtsAvailabilityDto.from(ttsService.availability(currentGroupOwners()))
    }
}

/** Read-aloud request; the body deliberately carries no voice/format overrides in P0. */
data class SpeakRequest(
    val text: String = ""
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
