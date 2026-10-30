package com.easy.easyai.web.controller

import com.easy.easyai.speech.SpeechRecognitionEvent
import com.easy.easyai.speech.SpeechRecognitionUsage
import com.easy.easyai.web.security.AsrHandshakeAuthenticationFilter
import com.easy.easyai.web.service.AsrRealtimeService
import com.easy.easyai.web.service.AsrRealtimeStart
import com.easy.easyai.web.service.AsrTicket
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.reactivestreams.Publisher
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.HandshakeInfo
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the sequencing rules of one round, which is where every bug in this class has lived:
 * the ticket handoff, and the fact that `done` must still arrive after the uplink was closed.
 */
class AsrRealtimeWebSocketHandlerTest {

    private val modelId = "qwen-audio-3.1-asr-flash-streaming"

    private val ticket = AsrTicket(
        value = "t-1",
        userId = "alice",
        appName = "easyai",
        owners = listOf("alice"),
        origin = "https://app.example.com",
        recordingId = "rec-1",
        configId = "row-1",
        modelId = modelId,
        expiresAt = Long.MAX_VALUE
    )

    private val events = Sinks.many().unicast().onBackpressureBuffer<SpeechRecognitionEvent>()
    private val inbound = Sinks.many().unicast().onBackpressureBuffer<WebSocketMessage>()
    private val frames = CopyOnWriteArrayList<String>()
    private val audioClosed = AtomicBoolean(false)

    private fun service(): AsrRealtimeService {
        val service = mockk<AsrRealtimeService>(relaxed = true)
        val start = mockk<AsrRealtimeStart>(relaxed = true)
        every { start.ticket } returns ticket
        every { start.modelId } returns modelId
        every { start.capability } returns "streaming"
        every { service.claimTicket("t-1") } returns ticket
        every { service.acquire("alice") } returns true
        every { service.release(any()) } just Runs
        every { service.reasonFor(any()) } returns "UNAVAILABLE"
        coEvery { service.start(any()) } returns start
        every { service.recognize(any(), any()) } answers {
            // Stand-in for the network uplink: the real service closes it once `finish` is honoured.
            secondArg<Flux<ByteArray>>()
                .doOnComplete { audioClosed.set(true) }
                .onErrorResume { Flux.empty() }
                .subscribe()
            events.asFlux()
        }
        return service
    }

    private fun session(withTicket: Boolean = true): WebSocketSession {
        val path = AsrHandshakeAuthenticationFilter.PATH
        val uri = URI("ws://localhost$path" + if (withTicket) "?ticket=t-1" else "")
        val handshake = mockk<HandshakeInfo>()
        every { handshake.uri } returns uri
        val session = mockk<WebSocketSession>(relaxed = true)
        every { session.handshakeInfo } returns handshake
        every { session.receive() } returns inbound.asFlux()
        every { session.close(any()) } returns Mono.empty()
        every { session.textMessage(any()) } answers {
            textMessage(firstArg())
        }
        every { session.send(any()) } answers {
            Flux.from(firstArg<Publisher<WebSocketMessage>>())
                .doOnNext { frames += it.payloadAsText }
                .then()
        }
        return session
    }

    private fun textMessage(body: String): WebSocketMessage {
        val buffer = mockk<DataBuffer>(relaxed = true)
        every { buffer.readableByteCount() } returns body.toByteArray().size
        val message = mockk<WebSocketMessage>()
        every { message.type } returns WebSocketMessage.Type.TEXT
        every { message.payloadAsText } returns body
        every { message.payload } returns buffer
        return message
    }

    private fun awaitFrame(timeoutMs: Long = 5_000, match: (String) -> Boolean): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            frames.firstOrNull(match)?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("expected frame never arrived; saw $frames")
    }

    private fun awaitFlag(flag: AtomicBoolean, what: String, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (flag.get()) return
            Thread.sleep(10)
        }
        throw AssertionError("$what never happened")
    }

    @Nested
    inner class Handshake {

        @Test
        fun `a socket without a ticket is closed instead of starting a round`() {
            val service = service()
            val session = session(withTicket = false)
            AsrRealtimeWebSocketHandler(service).handle(session).block()

            verify(exactly = 1) { session.close(CloseStatus.POLICY_VIOLATION) }
            verify(exactly = 0) { service.acquire(any()) }
        }
    }

    @Nested
    inner class Finalizing {

        @Test
        fun `the corrected sentence that arrives after finish still reaches the browser`() {
            val session = session()
            val done = AsrRealtimeWebSocketHandler(service()).handle(session).subscribe()
            try {
                events.tryEmitNext(SpeechRecognitionEvent.Started(modelId))
                awaitFrame { it.contains("\"type\":\"ready\"") }

                inbound.tryEmitNext(textMessage("""{"type":"finish"}"""))
                awaitFlag(audioClosed, "the upstream uplink was closed")

                // The service revises the sentence only after `finish-task`, so the round must keep
                // forwarding until `done` — dropping these frames loses the final transcript.
                events.tryEmitNext(SpeechRecognitionEvent.Sentence(1, "hello world", final = true))
                events.tryEmitNext(
                    SpeechRecognitionEvent.Finished(SpeechRecognitionUsage(totalTokens = 12))
                )

                val doneFrame = awaitFrame { it.contains("\"type\":\"done\"") }
                assertTrue(
                    frames.any { it.contains("hello world") },
                    "the post-finish sentence was dropped: $frames"
                )
                assertTrue(doneFrame.contains("\"totalTokens\":12"), doneFrame)
                assertTrue(!doneFrame.contains("\"notice\""), "an unlimited round carried a notice: $doneFrame")
            } finally {
                done.dispose()
            }
        }

        @Test
        fun `cancel terminates the round once and nothing is emitted afterwards`() {
            val session = session()
            val done = AsrRealtimeWebSocketHandler(service()).handle(session).subscribe()
            try {
                events.tryEmitNext(SpeechRecognitionEvent.Started(modelId))
                awaitFrame { it.contains("\"type\":\"ready\"") }

                inbound.tryEmitNext(textMessage("""{"type":"cancel"}"""))
                awaitFrame { it.contains("\"type\":\"cancelled\"") }

                events.tryEmitNext(SpeechRecognitionEvent.Sentence(1, "too late", final = true))
                events.tryEmitNext(
                    SpeechRecognitionEvent.Finished(SpeechRecognitionUsage(totalTokens = 1))
                )
                Thread.sleep(200)

                assertEquals(1, frames.count { it.contains("\"type\":\"cancelled\"") })
                assertEquals(0, frames.count { it.contains("\"type\":\"done\"") })
                assertEquals(0, frames.count { it.contains("too late") })
            } finally {
                done.dispose()
            }
        }
    }
}
