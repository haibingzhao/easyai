package com.easy.easyai.web.controller

import com.easy.easyai.auth.group.GroupClaims
import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.speech.SpeechAudio
import com.easy.easyai.speech.SpeechRenderEvent
import com.easy.easyai.speech.SpeechScript
import com.easy.easyai.speech.SpeechSegment
import com.easy.easyai.speech.SpeechStyle
import com.easy.easyai.speech.SpeechWarning
import com.easy.easyai.speech.TimedWord
import com.easy.easyai.web.service.PlannedScript
import com.easy.easyai.web.service.SpeechTarget
import com.easy.easyai.web.service.TtsAvailability
import com.easy.easyai.web.service.TtsService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import reactor.util.context.Context
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The read-aloud endpoints must resolve Task Models over the same owner set the settings page writes
 * to (self → group bucket) and must hand the browser bytes it can play: the MIME type comes from the
 * clip, and the response must never be cacheable.
 */
class TtsControllerTest {

    private val ttsService = mockk<TtsService>()

    private fun asGroupMember(): Context =
        ReactiveSecurityContextHolder.withAuthentication(
            UsernamePasswordAuthenticationToken("alice", "", emptyList<GrantedAuthority>()).apply {
                details = GroupClaims(owners = listOf("g_fam-1"), groupId = "fam-1", groupUserId = "g_fam-1")
            }
        )

    @Test
    fun `speak streams every chunk inline and uncacheable over the group owner set`() {
        val owners = slot<Collection<String>>()
        val prepared = mockk<TtsService.PreparedSpeech>()
        every { prepared.fallbackReason } returns null
        coEvery { ttsService.openSpeak(capture(owners), any(), any(), any()) } returns prepared
        every { ttsService.speakStream(prepared) } returns Flux.just(
            SpeechAudio(byteArrayOf(1, 2), "audio/mpeg"),
            SpeechAudio(byteArrayOf(3), "audio/mpeg")
        )

        val response = TtsController(ttsService)
            .speak(SpeakRequest(text = "你好"))
            .contextWrite(asGroupMember())
            .block()!!

        assertEquals(listOf("alice", "g_fam-1"), owners.captured.toList())
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(MediaType.parseMediaType("audio/mpeg"), response.headers.contentType)
        assertEquals(-1L, response.headers.contentLength)
        assertEquals("inline", response.headers.getFirst(HttpHeaders.CONTENT_DISPOSITION))
        assertEquals("no-store", response.headers.getFirst(HttpHeaders.CACHE_CONTROL))
        val bytes = response.body!!.collectList().block()!!.flatMap { buffer ->
            val chunk = ByteArray(buffer.readableByteCount())
            buffer.read(chunk)
            DataBufferUtils.release(buffer)
            chunk.toList()
        }
        assertEquals(listOf<Byte>(1, 2, 3), bytes)
    }

    @Test
    fun `speak reports a refine fallback without changing the audio response`() {
        val prepared = mockk<TtsService.PreparedSpeech>()
        every { prepared.fallbackReason } returns TtsService.FALLBACK_LENGTH_LIMIT
        coEvery { ttsService.openSpeak(any(), any(), any(), any()) } returns prepared
        every { ttsService.speakStream(prepared) } returns Flux.just(SpeechAudio(byteArrayOf(1), "audio/mpeg"))

        val response = TtsController(ttsService)
            .speak(SpeakRequest(text = "长文本", mode = "refine"))
            .contextWrite(asGroupMember())
            .block()!!

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(
            TtsService.FALLBACK_LENGTH_LIMIT,
            response.headers.getFirst(TtsController.SPEECH_FALLBACK_HEADER)
        )
        assertEquals(MediaType.parseMediaType("audio/mpeg"), response.headers.contentType)
    }

    @Test
    fun `speak forwards the selected mode while old requests default to direct`() {
        val mode = slot<String>()
        val target = slot<SpeechTarget>()
        val prepared = mockk<TtsService.PreparedSpeech>()
        every { prepared.fallbackReason } returns null
        coEvery { ttsService.openSpeak(any(), any(), capture(mode), capture(target)) } returns prepared
        every { ttsService.speakStream(prepared) } returns Flux.just(
            SpeechAudio(byteArrayOf(1), "audio/mpeg")
        )
        TtsController(ttsService).speak(
            SpeakRequest(
                text = "你好",
                mode = "refine",
                targetType = "session_message",
                targetId = "msg-1",
                sessionId = "sess-1"
            )
        ).contextWrite(asGroupMember()).block()!!
        assertEquals("refine", mode.captured)
        assertEquals(SpeechTarget("session_message", "msg-1", "sess-1"), target.captured)

        TtsController(ttsService).speak(SpeakRequest(text = "你好"))
            .contextWrite(asGroupMember()).block()!!
        assertEquals("direct", mode.captured)
    }

    @Test
    fun `a refusal from the service reaches the caller unchanged`() {
        // The claim happens inside the response Mono; if it moved into the body flux, this would be a
        // `200 OK` with a dead audio body instead of a status the client can explain.
        coEvery { ttsService.openSpeak(any(), any(), any(), any()) } throws
            ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "too long")

        val error = assertFailsWith<ResponseStatusException> {
            TtsController(ttsService).speak(SpeakRequest("x".repeat(20_001))).contextWrite(asGroupMember()).block()
        }
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, error.statusCode)
    }

    @Test
    fun `availability reports the configuration without synthesizing`() {
        val owners = slot<Collection<String>>()
        coEvery { ttsService.availability(capture(owners)) } returns
            TtsAvailability(configured = false, reason = TtsService.REASON_NOT_CONFIGURED)

        val dto = TtsController(ttsService).availability().contextWrite(asGroupMember()).block()!!

        assertEquals(listOf("alice", "g_fam-1"), owners.captured.toList())
        assertFalse(dto.configured)
        assertEquals(TtsService.REASON_NOT_CONFIGURED, dto.reason)
    }

    @Test
    fun `availability names the model in force`() {
        coEvery { ttsService.availability(any()) } returns
            TtsAvailability(configured = true, modelId = "qwen-audio-3.1-tts-flash")

        val dto = TtsController(ttsService).availability().contextWrite(asGroupMember()).block()!!

        assertTrue(dto.configured)
        assertEquals("qwen-audio-3.1-tts-flash", dto.modelId)
        assertEquals(null, dto.reason)
    }

    // ─── Directed read-aloud ──────────────────────────────────────────────────

    /** Collect an NDJSON body and split it the way the browser does: one JSON object per line. */
    private fun lines(response: ResponseEntity<Flux<DataBuffer>>): List<String> {
        val bytes = response.body!!.collectList().block()!!.flatMap { buffer ->
            val chunk = ByteArray(buffer.readableByteCount())
            buffer.read(chunk)
            DataBufferUtils.release(buffer)
            chunk.toList()
        }.toByteArray()
        return String(bytes, Charsets.UTF_8).split('\n').filter { it.isNotBlank() }
    }

    @Test
    fun `render streams one NDJSON event per line with decodable audio`() {
        val clip = byteArrayOf(9, 8, 7)
        val handle = mockk<TtsService.RenderSession>()
        coEvery { ttsService.openRender(any(), any(), any()) } returns handle
        every { ttsService.renderStream(handle, any(), any()) } returns Flux.just(
            SpeechRenderEvent.Meta("script-1", revision = 1, segmentCount = -1, planned = true),
            SpeechRenderEvent.SegmentAudio(
                segmentId = "seg_0",
                index = 0,
                spokenText = "第一句。",
                voice = "voice-a",
                pauseAfterMs = 250,
                bytes = clip,
                mimeType = "audio/mpeg",
                words = listOf(TimedWord("第一句", 0, 400))
            ),
            SpeechRenderEvent.Warning(
                SpeechWarning("SEGMENT_FALLBACK", "seg_0", "read the source sentence instead")
            ),
            SpeechRenderEvent.Completed(segments = 1)
        )

        val response = TtsController(ttsService)
            .render(RenderRequest(text = "第一句。"))
            .contextWrite(asGroupMember())
            .block()!!

        assertEquals(MediaType.parseMediaType(TtsController.NDJSON), response.headers.contentType)
        assertEquals("no-store", response.headers.getFirst(HttpHeaders.CACHE_CONTROL))
        val events = lines(response).map { SharedObjectMapper.instance.readTree(it) }
        // Every line carries a `type`: an untyped warning line is silently dropped by the client.
        assertEquals(listOf("meta", "audio", "warning", "done"), events.map { it.path("type").asString() })
        assertEquals("script-1", events[0].path("scriptId").asString())
        assertEquals(true, events[0].path("planned").asBoolean())
        assertEquals(250, events[1].path("pauseAfterMs").asInt())
        assertEquals(clip.toList(), Base64.getDecoder().decode(events[1].path("data").asString()).toList())
        assertEquals(400, events[1].path("words").get(0).path("endMs").asInt())
        assertEquals("SEGMENT_FALLBACK", events[2].path("code").asString())
        assertEquals("seg_0", events[2].path("segmentId").asString())
        assertEquals(1, events[3].path("segments").asInt())
    }

    @Test
    fun `render forwards an edited script and the planning strategy`() {
        // The claim and the stream are two calls now, so each captures its own arguments: an edited
        // script still has to reach the renderer with the strategy the caller asked for.
        var claimedScriptId: String? = null
        val handle = mockk<TtsService.RenderSession>()
        coEvery { ttsService.openRender(any(), any(), any()) } answers {
            claimedScriptId = arg(2)
            handle
        }
        // MockK slots cannot be typed nullable (T : Any), so capture into non-null slots: the DTO
        // under test always carries both, and a null would fail the assertions below anyway.
        val segments = slot<List<SpeechSegment>>()
        val incremental = slot<Boolean>()
        every { ttsService.renderStream(handle, capture(segments), capture(incremental)) } returns Flux.empty()

        TtsController(ttsService).render(
            RenderRequest(
                text = "",
                scriptId = "script-1",
                segments = listOf(
                    SpeechSegmentDto(
                        id = "seg_0",
                        index = 0,
                        sourceText = "原文。",
                        spokenText = "改过的。",
                        voice = "voice-a",
                        pauseAfterMs = 120
                    )
                ),
                incremental = false
            )
        ).contextWrite(asGroupMember()).block()!!

        assertEquals("script-1", claimedScriptId)
        assertEquals("改过的。", segments.captured.single().spokenText)
        assertEquals(120, segments.captured.single().pauseAfterMs)
        assertEquals(false, incremental.captured)
    }

    @Test
    fun `render fails the request instead of committing a 200 when the claim is refused`() {
        // The claim must happen before the ResponseEntity exists. If it moves back inside the body
        // flux, a 409 becomes `200 OK` plus a broken stream, and the client can only say "it broke"
        // instead of naming the missing model.
        coEvery { ttsService.openRender(any(), any(), any()) } answers {
            throw ResponseStatusException(HttpStatus.CONFLICT, "no speech model")
        }

        val error = assertFailsWith<ResponseStatusException> {
            TtsController(ttsService)
                .render(RenderRequest(text = "你好"))
                .contextWrite(asGroupMember())
                .block()
        }
        assertEquals(HttpStatus.CONFLICT, error.statusCode)
    }

    @Test
    fun `script returns the plan without synthesizing`() {
        val script = SpeechScript(
            scriptId = "script-1",
            sourceDigest = "sha256:x",
            mood = "克制",
            style = SpeechStyle.TEACHER,
            segments = listOf(
                SpeechSegment(
                    id = "seg_0",
                    index = 0,
                    sourceText = "原文。",
                    spokenText = "原文。",
                    voice = "voice-a",
                    pauseAfterMs = 200
                )
            )
        )
        coEvery { ttsService.planScript(any(), any()) } returns
            PlannedScript(script, listOf(SpeechWarning("VOICE_FALLBACK", "seg_0", "used the default voice")), 600)

        val dto = TtsController(ttsService).script(SpeakRequest(text = "原文。"))
            .contextWrite(asGroupMember()).block()!!

        assertEquals("script-1", dto.scriptId)
        assertEquals("TEACHER", dto.style)
        assertEquals(1, dto.segments.size)
        assertEquals("原文。", dto.segments.single().sourceText)
        assertEquals("VOICE_FALLBACK", dto.warnings.single().code)
        assertEquals(600, dto.expiresInSeconds)
    }
}
