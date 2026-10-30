package com.easy.easyai.web.controller

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.speech.SpeechRecognitionEvent
import com.easy.easyai.speech.SpeechRecognitionException
import com.easy.easyai.web.security.AsrHandshakeAuthenticationFilter
import com.easy.easyai.web.service.AsrRealtimeService
import com.easy.easyai.web.service.AsrRealtimeStart
import com.easy.easyai.web.service.AsrTicket
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import org.springframework.web.server.ResponseStatusException
import reactor.core.Disposable
import reactor.core.publisher.Mono
import reactor.util.concurrent.Queues
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Schedulers
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * The browser half of one recognition round.
 *
 * Responsibilities, all of them about *sequencing* rather than recognition itself:
 *  - the upstream task is opened only after the row was re-validated ([AsrRealtimeService.start]);
 *  - `ready` is sent when the service confirms `task-started`, so the console never invites speech
 *    into a socket that is not listening yet;
 *  - client audio is forwarded through one bounded queue, and `finish` is only honoured once its
 *    frame counter matches what actually arrived — a dropped binary frame must fail the round
 *    rather than silently shorten the transcript;
 *  - every exit path (done, error, cancel, client disconnect, timeout) releases the upstream socket,
 *    the queue and the caller's session slot.
 *
 * Downstream frames are the project's own contract (§6.3 of the design), not the vendor's: the
 * vendor's shapes never reach the browser.
 */
class AsrRealtimeWebSocketHandler(
    private val service: AsrRealtimeService
) : WebSocketHandler {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(session: WebSocketSession): Mono<Void> {
        val ticket = claimTicket(session)
        if (ticket == null) {
            logger.info("Recognition socket opened without a ticket")
            return session.close(CloseStatus.POLICY_VIOLATION)
        }
        if (!service.acquire(ticket.userId)) {
            return session.close(CloseStatus.SERVICE_OVERLOAD)
        }
        return mono { service.start(ticket) }
            .flatMap { start -> runRound(session, start) }
            .onErrorResume(ResponseStatusException::class.java) { error ->
                val reason = service.reasonFor(error)
                logger.info("Recognition socket refused for {}: {}", ticket.userId, reason)
                session.send(Mono.just(session.textMessage(errorFrame(ticket, 1, reason))))
                    .then(session.close(CloseStatus.POLICY_VIOLATION))
            }
            .doFinally { service.release(ticket.userId) }
    }

    /**
     * The upgrade carries the ticket value the filter already validated; the handler claims the
     * parked copy. `HandshakeInfo.attributes` is not an option: it only exposes web-session
     * attributes, which are empty unless a `sessionAttributePredicate` is configured.
     */
    private fun claimTicket(session: WebSocketSession): AsrTicket? {
        val query = session.handshakeInfo.uri.rawQuery ?: return null
        val prefix = "${AsrHandshakeAuthenticationFilter.TICKET_PARAM}="
        val raw = query.split('&').firstOrNull { it.startsWith(prefix) }?.substring(prefix.length) ?: return null
        val value = try {
            URLDecoder.decode(raw, StandardCharsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            logger.info("Recognition socket presented an undecodable ticket: {}", e.message)
            return null
        }
        return service.claimTicket(value)
    }

    private fun runRound(session: WebSocketSession, start: AsrRealtimeStart): Mono<Void> {
        val round = Round(start)
        val recognition: Disposable = service.recognize(start, round.audio.asFlux())
            .subscribe(
                { event -> onRecognitionEvent(round, event) },
                { error -> onRecognitionError(round, error) }
            )
        // A round has a product limit; hitting it closes the audio so the service flushes and the
        // transcript is delivered instead of being cut off by a hard close.
        val limitTimer = Mono.delay(Duration.ofSeconds(MAX_ROUND_SECONDS), Schedulers.parallel())
            .subscribe { round.finishAudio(ROUND_LIMIT) }
        val receive = session.receive()
            .concatMap { message -> onClientMessage(round, message) }
            .takeUntilOther(round.terminal.asMono())
            .then()
        val send = session.send(round.outbound.asFlux().map { session.textMessage(it) })
        return send.and(receive).doFinally {
            limitTimer.dispose()
            recognition.dispose()
            round.audio.tryEmitComplete()
            round.outbound.tryEmitComplete()
            logger.info(
                "Recognition round {} finished: {} audio frames, {} sample bytes, {} sentences, reason={}",
                start.ticket.recordingId, round.audioFrames, round.totalBytes, round.sentenceCount, round.endReason
            )
        }
    }

    private fun onClientMessage(round: Round, message: WebSocketMessage): Mono<Void> {
        if (round.terminated || round.endReason != null) return Mono.empty()
        if (message.type == WebSocketMessage.Type.BINARY) {
            // Check the declared size before allocating: a hostile frame must not be able to ask the
            // server for a heap-sized buffer it will then refuse.
            val size = message.payload.readableByteCount()
            if (size > MAX_AUDIO_FRAME_BYTES) {
                return fail(
                    round,
                    CODE_PROTOCOL_ERROR,
                    "Audio frame exceeds $MAX_AUDIO_FRAME_BYTES bytes",
                    partial = round.sawText
                )
            }
            val bytes = ByteArray(size)
            message.payload.read(bytes)
            return acceptAudio(round, bytes)
        }
        if (message.type != WebSocketMessage.Type.TEXT) return Mono.empty()
        if (message.payload.readableByteCount() > MAX_CONTROL_FRAME_BYTES) {
            return fail(round, CODE_PROTOCOL_ERROR, "Control frame is too large", partial = false)
        }
        val text = message.payloadAsText
        val frame = try {
            SharedObjectMapper.instance.readValue(text, ClientControlFrame::class.java)
        } catch (e: Exception) {
            return fail(round, CODE_PROTOCOL_ERROR, "Unreadable control frame", partial = false)
        }
        return when (frame.type) {
            "ping" -> {
                round.emitPong()
                Mono.empty()
            }

            "finish" -> {
                val framesMatch = frame.lastAudioSeq == null || frame.lastAudioSeq == round.audioFrames.toLong()
                val samplesMatch = frame.totalSamples == null || frame.totalSamples == round.totalBytes / 2
                if (!framesMatch || !samplesMatch) {
                    fail(
                        round,
                        CODE_PROTOCOL_ERROR,
                        "Finish counters do not match the audio received " +
                            "(frames ${frame.lastAudioSeq}/${round.audioFrames}, samples ${frame.totalSamples}/${round.totalBytes / 2})",
                        partial = round.sawText
                    )
                } else {
                    round.finishAudio(CLIENT_FINISH)
                    Mono.empty()
                }
            }

            "cancel" -> {
                round.endReason = CANCEL
                round.emitCancelled()
                round.finishAudio(CANCEL)
                round.complete()
                Mono.empty()
            }

            else -> fail(round, CODE_PROTOCOL_ERROR, "Unknown control frame '${frame.type}'", partial = round.sawText)
        }
    }

    private fun acceptAudio(round: Round, bytes: ByteArray): Mono<Void> {
        if (round.endReason != null) return Mono.empty()
        if (bytes.size > MAX_AUDIO_FRAME_BYTES) {
            return fail(round, CODE_PROTOCOL_ERROR, "Audio frame exceeds ${MAX_AUDIO_FRAME_BYTES} bytes", partial = round.sawText)
        }
        if (!round.acceptingAudio) {
            return fail(round, CODE_PROTOCOL_ERROR, "Audio arrived before the service was ready", partial = false)
        }
        val result = round.audio.tryEmitNext(bytes)
        if (result.isFailure) {
            // Dropping frames is not an option: the transcript would silently lose speech, so an
            // over-full queue ends the round the same way a slow network would.
            return fail(round, CODE_BACKPRESSURE, "The recognition queue is full; the network is too slow", partial = round.sawText)
        }
        round.audioFrames++
        round.totalBytes += bytes.size
        return Mono.empty()
    }

    private fun onRecognitionEvent(round: Round, event: SpeechRecognitionEvent) {
        if (round.terminated) return
        when (event) {
            is SpeechRecognitionEvent.Started -> {
                round.acceptingAudio = true
                round.emitReady()
            }

            is SpeechRecognitionEvent.Sentence -> {
                round.acceptingAudio = true
                round.trackText(event)
                // Closing the uplink does not end the transcript: the final, corrected sentence is
                // always sent *after* `finish-task`, so only the limit itself is latched here. The
                // terminal frame is still `done`, driven by the service's `task-finished`.
                if (round.endReason == null && round.textUnits > MAX_TEXT_UNITS) {
                    round.endReason = TEXT_LIMIT
                    round.finishAudio(TEXT_LIMIT)
                }
                round.emitSentence(event)
            }

            is SpeechRecognitionEvent.Finished -> {
                // Read the reason *before* marking the round done, otherwise the notice it carries is
                // always lost.
                val reason = round.endReason
                round.endReason = DONE
                round.emitDone(event, reason)
                round.complete()
            }
        }
    }

    private fun onRecognitionError(round: Round, error: Throwable) {
        if (round.terminated) return
        round.endReason = FAILED
        val code = (error as? SpeechRecognitionException)?.code ?: CODE_UNAVAILABLE
        logger.info("Recognition failed for {}: {} {}", round.start.ticket.recordingId, code, error.message)
        round.emitError(code, error.message ?: "Recognition failed", partial = round.sawText)
        round.complete()
    }

    private fun fail(round: Round, code: String, message: String, partial: Boolean): Mono<Void> {
        if (round.terminated) return Mono.empty()
        round.endReason = code
        round.emitError(code, message, partial)
        round.complete()
        return Mono.empty()
    }

    /** Mutable per-connection state. A round is one socket, but three threads write to it. */
    private inner class Round(val start: AsrRealtimeStart) {
        val outbound = Sinks.many().unicast().onBackpressureBuffer<String>(Queues.get<String>(OUTBOUND_QUEUE).get())
        val audio = Sinks.many().unicast().onBackpressureBuffer<ByteArray>(Queues.get<ByteArray>(AUDIO_QUEUE).get())
        val terminal = Sinks.empty<Void>()

        @Volatile
        var acceptingAudio = false

        @Volatile
        var audioFrames = 0

        @Volatile
        var totalBytes = 0L

        @Volatile
        var sentenceCount = 0

        @Volatile
        var textUnits = 0

        @Volatile
        var sawText = false

        /** Why the uplink was closed; null while the round is still accepting audio. */
        @Volatile
        var endReason: String? = null

        /** Set once the terminal frame was written; nothing may be emitted after it. */
        @Volatile
        var terminated = false

        @Volatile
        private var audioClosed = false

        private var eventSeq = 0
        private val emitLock = Any()
        private val sentenceLengths = mutableMapOf<Int, Int>()

        fun trackText(event: SpeechRecognitionEvent.Sentence) {
            if (event.sentenceId <= 0) return
            sentenceLengths[event.sentenceId] = event.text.length
            textUnits = sentenceLengths.values.sum()
            if (event.text.isNotBlank()) sawText = true
        }

        /** Complete the audio stream exactly once: that is what sends `finish-task` upstream. */
        fun finishAudio(reason: String) {
            if (audioClosed) return
            audioClosed = true
            if (endReason == null) endReason = reason
            audio.tryEmitComplete()
        }

        /** Emit the one terminal frame, then shut the downstream down so `send` can complete. */
        fun complete() {
            if (terminated) return
            terminated = true
            outbound.tryEmitComplete()
            terminal.tryEmitEmpty()
        }

        fun emitReady() {
            emit(
                linkedMapOf(
                    "type" to "ready",
                    "modelId" to start.modelId,
                    "capability" to start.capability,
                    "audio" to linkedMapOf(
                        "format" to "pcm_s16le",
                        "sampleRate" to 16000,
                        "channels" to 1,
                        "frameMs" to 100
                    )
                )
            )
        }

        fun emitSentence(event: SpeechRecognitionEvent.Sentence) {
            sentenceCount++
            emit(
                linkedMapOf(
                    "type" to "sentence",
                    "sentenceId" to event.sentenceId,
                    "text" to event.text,
                    "isFinal" to event.final,
                    "beginTimeMs" to event.beginMs,
                    "endTimeMs" to event.endMs
                )
            )
        }

        fun emitDone(event: SpeechRecognitionEvent.Finished, reason: String?) {
            val usage = event.usage
            val payload = linkedMapOf<String, Any?>(
                "type" to "done",
                "usage" to linkedMapOf(
                    "inputTokens" to usage.inputTokens,
                    "outputTokens" to usage.outputTokens,
                    "totalTokens" to usage.totalTokens,
                    "durationSeconds" to usage.durationSeconds
                )
            )
            // Only present when the round was cut short by a product limit: a key that is always
            // there but usually null makes every consumer handle a case that never happens.
            when (reason) {
                ROUND_LIMIT -> payload["notice"] = ROUND_LIMIT
                TEXT_LIMIT -> payload["notice"] = TEXT_LIMIT
            }
            emit(payload)
        }

        fun emitError(code: String, message: String, partial: Boolean) {
            emit(
                linkedMapOf(
                    "type" to "error",
                    "code" to code,
                    "message" to message,
                    "partial" to partial
                )
            )
        }

        fun emitCancelled() {
            emit(linkedMapOf("type" to "cancelled"))
        }

        fun emitPong() {
            emit(linkedMapOf("type" to "pong"))
        }

        /**
         * One serialized writer. Socket reads, upstream events and the round timer all emit, so the
         * sequence number and the sink write have to be one critical section: a concurrent
         * `tryEmitNext` on a unicast sink fails with `FAIL_NON_SERIALIZED` and would drop a sentence.
         */
        private fun emit(payload: Map<String, Any?>) {
            val outcome = synchronized(emitLock) {
                val frame = linkedMapOf<String, Any?>(
                    "recordingId" to start.ticket.recordingId,
                    "eventSeq" to ++eventSeq
                )
                frame.putAll(payload)
                outbound.tryEmitNext(SharedObjectMapper.instance.writeValueAsString(frame))
            }
            if (outcome.isFailure) {
                logger.info("Could not write a frame to {}: {}", start.ticket.recordingId, outcome)
            }
        }
    }

    /** Client → server control frames; only `type` is required, the rest describe the audio sent. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ClientControlFrame(
        val type: String = "",
        val lastAudioSeq: Long? = null,
        val totalSamples: Long? = null
    )

    private fun errorFrame(ticket: AsrTicket, seq: Int, reason: String): String =
        SharedObjectMapper.instance.writeValueAsString(
            linkedMapOf(
                "type" to "error",
                "recordingId" to ticket.recordingId,
                "eventSeq" to seq,
                "code" to reason,
                "message" to "The recognition channel could not be opened",
                "partial" to false
            )
        )

    companion object {
        /** One frame is 100 ms of 16 kHz mono PCM16. */
        const val MAX_AUDIO_FRAME_BYTES = 3200
        private const val OUTBOUND_QUEUE = 64
        private const val AUDIO_QUEUE = 20
        private const val MAX_CONTROL_FRAME_BYTES = 8 * 1024
        private const val MAX_TEXT_UNITS = 20_000
        private const val MAX_ROUND_SECONDS = 600L

        const val CODE_PROTOCOL_ERROR = "PROTOCOL_ERROR"
        const val CODE_BACKPRESSURE = "BACKPRESSURE"
        const val CODE_UNAVAILABLE = "UNAVAILABLE"

        private const val CLIENT_FINISH = "CLIENT_FINISH"
        private const val ROUND_LIMIT = "ROUND_LIMIT"
        private const val TEXT_LIMIT = "TEXT_LIMIT"
        private const val CANCEL = "CANCEL"
        private const val DONE = "DONE"
        private const val FAILED = "FAILED"
    }
}

