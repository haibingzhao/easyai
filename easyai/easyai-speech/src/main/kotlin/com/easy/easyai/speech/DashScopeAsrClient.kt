package com.easy.easyai.speech

import com.easy.easyai.core.media.MediaProviderSettings
import io.netty.channel.ChannelOption
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.netty.http.client.HttpClient
import reactor.netty.http.client.WebsocketClientSpec
import reactor.util.concurrent.Queues
import java.net.URI
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeoutException

/**
 * Recognition over the DashScope-native duplex WebSocket protocol.
 *
 * One call is one recording: `run-task`, then the caller's PCM frames as they arrive, then
 * `finish-task`. Unlike [DashScopeTtsClient] the audio is not known up front, so the outbound
 * pipeline is subscribed to the caller's stream and only starts forwarding after the service has
 * answered `task-started` — audio sent earlier is dropped by the service, and a caller that has
 * already produced frames must be held back rather than losing them.
 *
 * Backpressure is preserved end to end: the outbound subscription is what requests more audio, so a
 * slow socket stops the browser's queue instead of this class buffering it.
 */
internal class DashScopeAsrClient(
    private val settings: MediaProviderSettings,
    /** Socket seam; production opens a real connection through [WebSocketAsrTransport]. */
    private val transport: AsrTransport = WebSocketAsrTransport
) {

    fun recognize(parameters: Map<String, Any?>, pcmAudio: Flux<ByteArray>): Flux<SpeechRecognitionEvent> {
        val model = settings.defaultModel.trim()
        require(model.isNotBlank()) { "DashScope recognition needs a model id (e.g. qwen-audio-3.1-asr-flash-streaming)" }
        require(settings.apiKey.isNotBlank()) { "DashScope recognition needs an API key; set it on the model's group or row" }
        val taskId = UUID.randomUUID().toString()
        val exchange = AsrExchange(
            taskId = taskId,
            model = model,
            runTask = DashScopeAsrProtocol.runTask(taskId, model, parameters),
            finishTask = DashScopeAsrProtocol.finishTask(taskId)
        )
        return transport
            .duplex(DashScopeSpeechEndpoint.endpoint(settings.baseUrl), settings.apiKey, exchange, pcmAudio)
            .onErrorMap { error ->
                when (error) {
                    is SpeechRecognitionException -> error
                    else -> SpeechRecognitionException(
                        SpeechRecognitionException.UNREACHABLE,
                        error.message ?: "recognition transport failed",
                        error
                    )
                }
            }
    }
}

/** The pre-encoded control frames plus the task id every inbound frame is checked against. */
internal data class AsrExchange(
    val taskId: String,
    val model: String,
    val runTask: String,
    val finishTask: String
)

/** One duplex session, isolated so the protocol shapes can be tested without a socket. */
internal fun interface AsrTransport {
    /**
     * Open one task, forward [audio] after `task-started`, close after the last frame.
     *
     * The returned Flux must start no upstream work before it is subscribed, and cancelling it must
     * close the socket.
     */
    fun duplex(
        uri: String,
        apiKey: String,
        exchange: AsrExchange,
        audio: Flux<ByteArray>
    ): Flux<SpeechRecognitionEvent>
}

/**
 * The real socket.
 *
 * Three things are deliberate here:
 *  - the outbound stream is a concatenation (`run-task`, gated audio, `finish-task`), which is what
 *    makes "finish never overtakes the last audio frame" a property of the pipeline rather than a
 *    timing assumption;
 *  - the inbound stream only stops early on a terminal frame, so trailing sentences after
 *    `finish-task` are still delivered;
 *  - the event sink is bounded and refuses to grow. A consumer that stops reading is a broken UI,
 *    and dropping transcript text silently would be worse than failing the round.
 */
internal object WebSocketAsrTransport : AsrTransport {

    private val logger = LoggerFactory.getLogger(javaClass)

    private const val MAX_FRAME_PAYLOAD = 256 * 1024
    private const val CONNECT_TIMEOUT_MS = 15_000
    private val EVENT_QUEUE_CAPACITY = Queues.SMALL_BUFFER_SIZE

    /** No upstream frame for this long means the task is dead, even if the socket is open. */
    private const val IDLE_TIMEOUT_SECONDS = 30L

    private val client = ReactorNettyWebSocketClient(
        HttpClient.create().option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
    ) { WebsocketClientSpec.builder().maxFramePayloadLength(MAX_FRAME_PAYLOAD) }

    override fun duplex(
        uri: String,
        apiKey: String,
        exchange: AsrExchange,
        audio: Flux<ByteArray>
    ): Flux<SpeechRecognitionEvent> = Flux.defer {
        val events = Sinks.many().unicast()
            .onBackpressureBuffer(Queues.get<SpeechRecognitionEvent>(EVENT_QUEUE_CAPACITY).get())
        val started = Sinks.one<Unit>()
        val terminal = Sinks.empty<Void>()
        val state = ReceiveState()

        val headers = HttpHeaders()
        headers.set(HttpHeaders.AUTHORIZATION, "bearer $apiKey")
        headers.set("X-DashScope-DataInspection", "enable")

        val connection = client.execute(URI.create(uri), headers) { session ->
            val outbound = Flux.concat(
                Mono.just(session.textMessage(exchange.runTask)),
                // Nothing leaves before the service is ready, and nothing leaves after a failure.
                started.asMono().thenMany(
                    audio
                        .takeUntilOther(terminal.asMono())
                        .map { bytes -> session.binaryMessage { factory -> factory.wrap(bytes) } }
                ),
                Mono.just(session.textMessage(exchange.finishTask))
            )
            val receive = session.receive()
                .timeout(Duration.ofSeconds(IDLE_TIMEOUT_SECONDS))
                .doOnNext { frame -> onFrame(frame, exchange, state, started, terminal, events) }
                .takeUntilOther(terminal.asMono())
                .then()
            session.send(outbound).and(receive)
        }
            .doOnError { error ->
                logger.warn("ASR socket failed for task {}: {}", exchange.taskId, error.message)
                events.tryEmitError(mapError(error, state))
            }
            .doOnSuccess {
                // A socket that closes without a terminal frame is a failed round, not a short one:
                // the transcript is incomplete and the caller must be told, not handed a done.
                if (!state.finished) {
                    events.tryEmitError(
                        SpeechRecognitionException(
                            SpeechRecognitionException.UNREACHABLE,
                            "ASR task ${exchange.taskId} closed before task-finished"
                        )
                    )
                } else {
                    events.tryEmitComplete()
                }
            }
            // The failure already left through `events`; without this the same error would also reach
            // the default handler and be logged a second time at ERROR level.
            .onErrorComplete()
            .subscribe()

        events.asFlux().doOnCancel { connection.dispose() }
    }

    private fun onFrame(
        frame: WebSocketMessage,
        exchange: AsrExchange,
        state: ReceiveState,
        started: Sinks.One<Unit>,
        terminal: Sinks.Empty<Void>,
        events: Sinks.Many<SpeechRecognitionEvent>
    ) {
        if (frame.type != WebSocketMessage.Type.TEXT) {
            // Upstream audio is not part of this protocol; ignoring it keeps the session alive while
            // a malformed expectation would otherwise kill a working recognition round.
            logger.debug("Ignoring non-text ASR frame of {} bytes", frame.payload.readableByteCount())
            return
        }
        val event = try {
            DashScopeAsrProtocol.parse(frame.payloadAsText)
        } catch (e: SpeechRecognitionException) {
            events.tryEmitError(e)
            terminal.tryEmitEmpty()
            return
        } ?: run {
            logger.debug("Ignoring unknown ASR event for task {}", exchange.taskId)
            return
        }
        if (event.taskId != null && event.taskId != exchange.taskId) {
            events.tryEmitError(
                SpeechRecognitionException(
                    SpeechRecognitionException.PROTOCOL_ERROR,
                    "frame for task ${event.taskId} arrived on task ${exchange.taskId}"
                )
            )
            terminal.tryEmitEmpty()
            return
        }
        event.usage?.let { state.usage = it }
        when (event.kind) {
            AsrEventKind.STARTED -> {
                if (state.started) return
                state.started = true
                started.tryEmitValue(Unit)
                events.tryEmitNext(SpeechRecognitionEvent.Started(exchange.model))
            }

            AsrEventKind.SENTENCE -> events.tryEmitNext(
                SpeechRecognitionEvent.Sentence(
                    sentenceId = event.sentenceId,
                    text = event.text,
                    final = event.isFinal,
                    beginMs = event.beginMs,
                    endMs = event.endMs
                )
            )

            AsrEventKind.HEARTBEAT -> Unit
            AsrEventKind.FINISHED -> {
                state.finished = true
                events.tryEmitNext(SpeechRecognitionEvent.Finished(state.usage ?: SpeechRecognitionUsage()))
                events.tryEmitComplete()
                terminal.tryEmitEmpty()
            }

            AsrEventKind.FAILED -> {
                events.tryEmitError(
                    SpeechRecognitionException(
                        SpeechRecognitionException.TASK_FAILED,
                        "ASR task failed: ${event.errorCode} ${event.errorMessage.orEmpty()}".trim()
                    )
                )
                terminal.tryEmitEmpty()
            }
        }
    }

    private fun mapError(error: Throwable, state: ReceiveState): SpeechRecognitionException = when (error) {
        is SpeechRecognitionException -> error
        is TimeoutException -> SpeechRecognitionException(
            SpeechRecognitionException.IDLE_TIMEOUT,
            "no ASR frame for ${IDLE_TIMEOUT_SECONDS}s (started=${state.started})"
        )

        else -> SpeechRecognitionException(
            SpeechRecognitionException.UNREACHABLE,
            error.message ?: "ASR socket failed (started=${state.started})"
        )
    }

    /** Per-session state; `takeUntilOther` reads it after every handled frame. */
    private class ReceiveState {
        var started = false
        var finished = false
        var usage: SpeechRecognitionUsage? = null
    }
}
