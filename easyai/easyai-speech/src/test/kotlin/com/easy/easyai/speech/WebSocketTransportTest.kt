package com.easy.easyai.speech

import io.netty.buffer.UnpooledByteBufAllocator
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.core.io.buffer.NettyDataBuffer
import org.springframework.core.io.buffer.NettyDataBufferFactory
import org.springframework.web.reactive.socket.WebSocketMessage
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The reply-stream rules of [WebSocketTransport], over a plain `Flux<WebSocketMessage>`.
 *
 * These are the rules a socket-level test cannot check cheaply: that `task-finished` *closes* the
 * stream instead of waiting for the server, that a truncated reply is never accepted, and that a
 * failure or an undocumented frame becomes an error rather than a silent skip.
 */
class WebSocketTransportTest {

    // ─── Fixtures ─────────────────────────────────────────────────────────────

    private val bufferFactory = DefaultDataBufferFactory.sharedInstance

    private fun text(json: String): WebSocketMessage =
        WebSocketMessage(WebSocketMessage.Type.TEXT, bufferFactory.wrap(json.toByteArray(Charsets.UTF_8)))

    private fun binary(bytes: ByteArray): WebSocketMessage =
        WebSocketMessage(WebSocketMessage.Type.BINARY, bufferFactory.wrap(bytes))

    private fun event(type: String, errorCode: String? = null, errorMessage: String? = null): WebSocketMessage {
        val code = errorCode?.let { """"error_code":"$it",""" } ?: ""
        val message = errorMessage?.let { """"error_message":"$it",""" } ?: ""
        return text("""{"header":{"event":"$type",$code$message"task_id":"t1"}}""")
    }

    private val started = { event("task-started") }
    private val finished = { event("task-finished") }

    private fun exchange(format: String = "mp3") = TtsExchange(
        runTask = "run",
        continueTasks = listOf("c1", "c2"),
        finishTask = "finish",
        format = format
    )

    private class Result(
        val audio: ByteArrayOutputStream,
        val state: WebSocketTransport.TaskState,
        val sent: List<List<String>>
    )

    /** Drive the pump to completion; a stream that never terminates fails the test by timeout. */
    private fun drive(
        messages: Flux<WebSocketMessage>,
        format: String = "mp3"
    ): Result {
        val audio = ByteArrayOutputStream()
        val state = WebSocketTransport.TaskState()
        val sent = mutableListOf<List<String>>()
        runBlocking {
            withTimeout(5.seconds) {
                WebSocketTransport.pump(
                    messages = messages,
                    sendFrames = { frames ->
                        sent += frames
                        Mono.empty()
                    },
                    exchange = exchange(format),
                    audio = audio,
                    state = state
                ).awaitSingleOrNull()
            }
        }
        return Result(audio, state, sent)
    }

    // ─── Termination ──────────────────────────────────────────────────────────

    @Test
    fun `task-finished closes the stream instead of waiting for the server to hang up`() {
        // A real session keeps sending keepalives for up to 60s after the task ends; `never()` models
        // that. Completion here proves `takeUntil` observes the terminal frame.
        val result = drive(Flux.concat(Flux.just(started(), finished()), Flux.never()))

        assertTrue(result.state.finished)
        assertEquals(listOf(listOf("c1", "c2", "finish")), result.sent)
    }

    @Test
    fun `text frames are sent only after task-started`() {
        val result = drive(Flux.just(started(), finished()))

        assertEquals(1, result.sent.size)
        assertEquals(listOf("c1", "c2", "finish"), result.sent.single())
        assertTrue(result.state.started)
    }

    @Test
    fun `a stream that ends before task-finished is not a success`() {
        val result = drive(Flux.just(started(), event("result-generated")))

        // `run` turns this into an exception rather than returning the partial buffer.
        assertFalse(result.state.finished)
    }

    // ─── Failures ─────────────────────────────────────────────────────────────

    @Test
    fun `task-failed surfaces the provider's own code and message`() {
        val error = assertFailsWith<IllegalStateException> {
            drive(Flux.just(started(), event("task-failed", "InvalidParameter", "voice not found")))
        }

        assertEquals("InvalidParameter: voice not found", error.message)
    }

    @Test
    fun `an undocumented event is an error, not a frame to skip`() {
        val error = assertFailsWith<IllegalStateException> {
            drive(Flux.just(started(), event("task-paused")))
        }

        assertTrue(error.message!!.contains("unrecognised speech event 'task-paused'"), error.message)
    }

    @Test
    fun `an unparseable frame is an error`() {
        val error = assertFailsWith<IllegalStateException> {
            drive(Flux.just(text("not json at all")))
        }

        assertTrue(error.message!!.contains("unparseable"), error.message)
    }

    // ─── Audio assembly ───────────────────────────────────────────────────────

    @Test
    fun `binary frames are appended in arrival order`() {
        val result = drive(
            Flux.just(started(), binary(byteArrayOf(1, 2)), binary(byteArrayOf(3)), finished())
        )

        assertEquals(listOf<Byte>(1, 2, 3), result.audio.toByteArray().toList())
        assertEquals(2, result.state.binaryFrames)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `live audio is published before the task finishes`() = runTest {
        val messages = Sinks.many().unicast().onBackpressureBuffer<WebSocketMessage>()
        val state = WebSocketTransport.TaskState()
        val chunks = mutableListOf<ByteArray>()
        val pump = WebSocketTransport.pump(
            messages = messages.asFlux(),
            sendFrames = { Mono.empty() },
            exchange = exchange(),
            audio = null,
            state = state,
            onAudio = { chunks += it }
        )
        val job = launch { pump.awaitSingleOrNull() }
        runCurrent()

        messages.tryEmitNext(started())
        messages.tryEmitNext(binary(byteArrayOf(1, 2, 3)))
        runCurrent()

        assertFalse(state.finished)
        assertEquals(listOf<Byte>(1, 2, 3), chunks.single().toList())
        assertEquals(3, state.audioBytes)
        job.cancelAndJoin()
    }

    @Test
    fun `audio past the task cap is a failure rather than a truncated clip`() {
        val oversized = ByteArray(DashScopeTtsProtocol.MAX_AUDIO_BYTES + 1)

        val error = assertFailsWith<IllegalStateException> {
            drive(Flux.just(started(), binary(oversized), finished()))
        }

        assertTrue(error.message!!.contains("exceeded"), error.message)
    }

    @Test
    fun `inbound buffers remain releasable by reactor after handling`() {
        val nettyFactory = NettyDataBufferFactory(UnpooledByteBufAllocator.DEFAULT)
        val messages = listOf(
            event("task-started"),
            binary(byteArrayOf(1, 2, 3)),
            event("task-finished")
        ).map { message ->
            WebSocketMessage(
                message.type,
                nettyFactory.wrap(message.payloadAsText.takeIf { message.type == WebSocketMessage.Type.TEXT }
                    ?.toByteArray(Charsets.UTF_8)
                    ?: byteArrayOf(1, 2, 3))
            )
        }

        drive(Flux.fromIterable(messages))

        // Reactor Netty releases each native frame after onNext. If the pump already released the
        // wrapped content, this assertion is zero and Reactor's own release throws refCnt: 0.
        messages.forEach { message ->
            val buffer = (message.payload as NettyDataBuffer).nativeBuffer
            assertEquals(1, buffer.refCnt())
            assertTrue(DataBufferUtils.release(message.payload))
            assertEquals(0, buffer.refCnt())
        }
    }
}
