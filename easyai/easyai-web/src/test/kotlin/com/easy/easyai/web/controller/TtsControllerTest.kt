package com.easy.easyai.web.controller

import com.easy.easyai.auth.group.GroupClaims
import com.easy.easyai.speech.SpeechAudio
import com.easy.easyai.web.service.TtsAvailability
import com.easy.easyai.web.service.TtsService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import reactor.util.context.Context
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
        every { ttsService.speakStream(capture(owners), any()) } returns Flux.just(
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
    fun `a refusal from the service reaches the caller unchanged`() {
        every { ttsService.speakStream(any(), any()) } throws
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
}
