package com.easy.easyai.web.controller

import com.easy.easyai.auth.group.GroupClaims
import com.easy.easyai.web.service.AsrService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.codec.multipart.FilePart
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import reactor.core.publisher.Flux
import reactor.util.context.Context
import kotlin.test.assertEquals

/**
 * The ASR endpoints must resolve Task Models over the same owner set the settings page writes to
 * (self → family bucket). Resolving with the bare principal made a family-configured ASR row
 * invisible and turned voice input into a 409 even though the mic was enabled.
 */
class AsrControllerTest {

    private val asrService = mockk<AsrService>()

    private fun asGroupMember(): Context =
        ReactiveSecurityContextHolder.withAuthentication(
            UsernamePasswordAuthenticationToken("alice", "", emptyList<GrantedAuthority>()).apply {
                details = GroupClaims(owners = listOf("g_fam-1"), groupId = "fam-1", groupUserId = "g_fam-1")
            }
        )

    @Test
    fun `refine passes the family bucket to the resolver`() {
        val owners = slot<Collection<String>>()
        coEvery { asrService.refine(capture(owners), any(), any(), any()) } returns "你好"

        val result = AsrController(asrService)
            .refine(AsrRefineRequest(text = "你好", contextBefore = "前", contextAfter = "后"))
            .contextWrite(asGroupMember())
            .block()!!

        assertEquals("你好", result.text)
        assertEquals(listOf("alice", "g_fam-1"), owners.captured.toList())
    }

    @Test
    fun `transcribe passes the family bucket to the resolver`() {
        val owners = slot<Collection<String>>()
        coEvery { asrService.transcribe(capture(owners), any(), any()) } returns "你好"
        val file = mockk<FilePart>()
        every { file.content() } returns
            Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(byteArrayOf(1, 2, 3)))

        val result = AsrController(asrService)
            .transcribe(file, null)
            .contextWrite(asGroupMember())
            .block()!!

        assertEquals("你好", result.text)
        assertEquals(listOf("alice", "g_fam-1"), owners.captured.toList())
    }
}
