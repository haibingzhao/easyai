package com.easy.easyai.web.service

import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.ModelType
import com.easy.easyai.core.host.HostIdentity
import com.easy.easyai.core.model.aux.AuxModelResolver
import com.easy.easyai.core.model.aux.AuxModelTask
import com.easy.easyai.speech.SpeechRecognitionEvent
import com.easy.easyai.speech.SpeechRecognitionUsage
import com.easy.easyai.speech.SpeechRecognizer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AsrRealtimeServiceTest {

    private val owners = listOf("alice", "group-1")

    private fun row(modelId: String, id: String = "row-1") = ModelProviderConfig(
        id = id,
        name = "ASR",
        protocol = Protocol.DASHSCOPE,
        isCustom = false,
        baseUrl = "https://ws-abc.cn-beijing.maas.aliyuncs.com/api/v1",
        apiKey = "sk-test",
        modelId = modelId,
        modelType = ModelType.ASR
    )

    private fun resolverFor(config: ModelProviderConfig?): AuxModelResolver {
        val resolver = mockk<AuxModelResolver>()
        coEvery { resolver.resolveConfig(owners, AuxModelTask.ASR) } returns config
        return resolver
    }

    private fun service(
        config: ModelProviderConfig?,
        recognizer: SpeechRecognizer? = null
    ) = AsrRealtimeService(
        auxModelResolver = resolverFor(config),
        capabilityResolver = AsrCapabilityResolver(catalog = null),
        tickets = AsrTicketStore(),
        host = HostIdentity("easyai"),
        recognizer = recognizer
    )

    @Nested
    inner class Ticketing {

        @Test
        fun `a realtime row yields a ticket bound to the row and the caller`() = runTest {
            val service = service(row("qwen-audio-3.1-asr-flash-streaming"))
            val ticket = service.issueTicket(owners, origin = "https://app.example.com")
            assertEquals("row-1", ticket.configId)
            assertEquals("alice", ticket.userId)
            assertEquals("easyai", ticket.appName)
            assertEquals("https://app.example.com", ticket.origin)
        }

        @Test
        fun `a row without a configured task is refused`() = runTest {
            val error = assertFailsWith<ResponseStatusException> { service(null).issueTicket(owners, null) }
            assertEquals(HttpStatus.CONFLICT, error.statusCode)
        }

        @Test
        fun `a segmented HTTP row does not get a realtime ticket`() = runTest {
            val service = service(row("whisper-1"))
            val error = assertFailsWith<ResponseStatusException> { service.issueTicket(owners, null) }
            assertEquals(HttpStatus.CONFLICT, error.statusCode)
            assertTrue(error.reason.orEmpty().contains("HTTP"), error.reason)
        }

        @Test
        fun `a file-only row does not get a realtime ticket`() = runTest {
            val service = service(row("qwen-audio-3.1-asr-flash-filetrans"))
            val error = assertFailsWith<ResponseStatusException> { service.issueTicket(owners, null) }
            assertEquals(HttpStatus.CONFLICT, error.statusCode)
            assertTrue(error.reason.orEmpty().contains("FILE_ONLY_MODEL"), error.reason)
        }
    }

    @Nested
    inner class SessionStart {

        @Test
        fun `start carries the validated parameters and model`() = runTest {
            val service = service(row("qwen-audio-3.1-asr-flash-streaming"))
            val ticket = service.issueTicket(owners, null)
            val start = service.start(ticket)
            assertEquals("qwen-audio-3.1-asr-flash-streaming", start.modelId)
            assertEquals("near_meeting_16k", start.parameters["vad_model"])
            assertEquals("qwen-audio-3.1-asr-flash-streaming", start.settings.defaultModel)
            assertEquals(AsrCapabilityResolver.CAPABILITY_REALTIME_STREAMING, start.capability)
        }

        @Test
        fun `a row that was re-pointed after the ticket was issued is refused`() = runTest {
            val service = service(row("qwen-audio-3.1-asr-flash-streaming"))
            val ticket = service.issueTicket(owners, null)
            val moved = service(row("qwen-audio-3.1-asr-flash-streaming", id = "row-2"))
            val error = assertFailsWith<ResponseStatusException> { moved.start(ticket) }
            assertEquals(HttpStatus.CONFLICT, error.statusCode)
        }

        @Test
        fun `a row that became unusable after the ticket was issued is refused`() = runTest {
            val service = service(row("qwen-audio-3.1-asr-flash-streaming"))
            val ticket = service.issueTicket(owners, null)
            val disabled = row("qwen-audio-3.1-asr-flash-streaming").copy(enabled = false)
            val error = assertFailsWith<ResponseStatusException> { service(disabled).start(ticket) }
            assertEquals(HttpStatus.CONFLICT, error.statusCode)
        }

        @Test
        fun `a provider the recognizer cannot drive is refused`() = runTest {
            val refusing = mockk<SpeechRecognizer>()
            every { refusing.supports(any()) } returns false
            val service = service(row("qwen-audio-3.1-asr-flash-streaming"), refusing)
            val ticket = service.issueTicket(owners, null)
            val error = assertFailsWith<ResponseStatusException> { service.start(ticket) }
            assertEquals(HttpStatus.CONFLICT, error.statusCode)
        }

        @Test
        fun `recognize delegates to the engine`() = runTest {
            val engine = mockk<SpeechRecognizer>()
            every { engine.supports(any()) } returns true
            every { engine.recognize(any(), any(), any()) } returns Flux.just(SpeechRecognitionEvent.Finished(SpeechRecognitionUsage()))
            val service = service(row("qwen-audio-3.1-asr-flash-streaming"), engine)
            val start = service.start(service.issueTicket(owners, null))
            val events = service.recognize(start, Flux.empty()).collectList().block()
            assertEquals(1, events?.size)
        }
    }

    @Nested
    inner class Slots {

        @Test
        fun `one caller holds one slot at a time`() {
            val service = service(row("qwen-audio-3.1-asr-flash-streaming"))
            assertTrue(service.acquire("alice"))
            assertTrue(!service.acquire("alice"))
            service.release("alice")
            assertTrue(service.acquire("alice"))
        }

        @Test
        fun `different callers do not block each other`() {
            val service = service(row("qwen-audio-3.1-asr-flash-streaming"))
            assertTrue(service.acquire("alice"))
            assertTrue(service.acquire("bob"))
            assertNotNull(service)
        }
    }
}
