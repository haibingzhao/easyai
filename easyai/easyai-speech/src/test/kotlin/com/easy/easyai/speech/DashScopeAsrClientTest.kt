package com.easy.easyai.speech

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.media.MediaProviderSettings
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests [DashScopeAsrClient]'s wiring: what it sends, which endpoint it derives and how a transport
 * failure surfaces. The socket is replaced by [RecordingTransport]; frame shapes are covered by
 * [DashScopeAsrProtocolTest].
 */
class DashScopeAsrClientTest {

    private fun settings(
        model: String = "qwen-audio-3.1-asr-flash-streaming",
        baseUrl: String = "https://ws-123.cn-beijing.maas.aliyuncs.com/api/v1",
        apiKey: String = "sk-test",
        provider: String = MediaProviderSettings.PROVIDER_DASHSCOPE
    ) = MediaProviderSettings(
        id = "row-1",
        displayName = "Qwen ASR",
        enabled = true,
        serviceKind = MediaProviderSettings.SERVICE_KIND_ASR,
        providerType = provider,
        baseUrl = baseUrl,
        apiKey = apiKey,
        defaultModel = model,
        timeoutSeconds = 600
    )

    private class RecordingTransport(
        private val events: List<SpeechRecognitionEvent> = emptyList(),
        private val failure: Throwable? = null
    ) : AsrTransport {
        var uri: String? = null
        var apiKey: String? = null
        var exchange: AsrExchange? = null
        val audioFrames = mutableListOf<Int>()

        override fun duplex(
            uri: String,
            apiKey: String,
            exchange: AsrExchange,
            audio: Flux<ByteArray>
        ): Flux<SpeechRecognitionEvent> {
            this.uri = uri
            this.apiKey = apiKey
            this.exchange = exchange
            if (failure != null) return Flux.error(failure)
            return audio
                .doOnNext { audioFrames += it.size }
                .thenMany(Flux.fromIterable(events))
        }
    }

    @Nested
    inner class Wiring {

        @Test
        fun `derives the duplex endpoint and forwards the audio it is given`() {
            val transport = RecordingTransport(
                events = listOf(SpeechRecognitionEvent.Sentence(1, "你好", final = false))
            )
            val client = DashScopeAsrClient(settings(), transport)

            val events = client
                .recognize(emptyMap(), Flux.just(ByteArray(3200), ByteArray(1600)))
                .collectList()
                .block(Duration.ofSeconds(5))

            assertEquals("wss://ws-123.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference", transport.uri)
            assertEquals("sk-test", transport.apiKey)
            assertEquals(listOf(3200, 1600), transport.audioFrames)
            assertEquals(1, events?.size)
        }

        @Test
        fun `the run-task frame carries the configured model and the validated parameters`() {
            val transport = RecordingTransport()
            val client = DashScopeAsrClient(settings("qwen-audio-3.0-asr-flash-streaming"), transport)

            client.recognize(mapOf("max_sentence_silence" to 400), Flux.empty<ByteArray>())
                .collectList()
                .block(Duration.ofSeconds(5))

            val frame = SharedObjectMapper.instance.readTree(transport.exchange!!.runTask)
            assertEquals("qwen-audio-3.0-asr-flash-streaming", frame.path("payload").path("model").asString())
            assertEquals(400, frame.path("payload").path("parameters").path("max_sentence_silence").asInt())
            assertEquals(
                transport.exchange!!.taskId,
                frame.path("header").path("task_id").asString()
            )
            assertTrue(frame.path("payload").path("parameters").path("vad_model").isMissingNode)
        }

        @Test
        fun `a transport failure keeps its code so the caller can map it`() {
            val transport = RecordingTransport(failure = IllegalStateException("socket closed"))
            val client = DashScopeAsrClient(settings(), transport)

            val error = assertFailsWith<SpeechRecognitionException> {
                client.recognize(emptyMap(), Flux.empty<ByteArray>())
                    .collectList()
                    .block(Duration.ofSeconds(5))
            }
            assertEquals(SpeechRecognitionException.UNREACHABLE, error.code)
        }

        @Test
        fun `an upstream task failure keeps its own code`() {
            val transport = RecordingTransport(
                failure = SpeechRecognitionException(SpeechRecognitionException.TASK_FAILED, "bad vad_model")
            )
            val client = DashScopeAsrClient(settings(), transport)

            val error = assertFailsWith<SpeechRecognitionException> {
                client.recognize(emptyMap(), Flux.empty<ByteArray>())
                    .collectList()
                    .block(Duration.ofSeconds(5))
            }
            assertEquals(SpeechRecognitionException.TASK_FAILED, error.code)
        }

        @Test
        fun `refuses to start without credentials`() {
            val client = DashScopeAsrClient(settings(apiKey = " "))
            assertFailsWith<IllegalArgumentException> { client.recognize(emptyMap(), Flux.empty()) }
        }
    }

    @Nested
    inner class Supports {

        private val recognizer = SpeechRecognizer.create()

        @Test
        fun `accepts a dashscope row`() {
            assertTrue(recognizer.supports(settings()))
        }

        @Test
        fun `accepts a qwen-audio asr row sharing a bailian compatible-mode group`() {
            assertTrue(
                recognizer.supports(
                    settings(
                        model = "qwen-audio-3.1-asr-flash-message",
                        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        provider = MediaProviderSettings.PROVIDER_OPENAI
                    )
                )
            )
        }

        @Test
        fun `rejects an openai transcription row`() {
            val supports = recognizer.supports(
                settings(
                    model = "whisper-1",
                    baseUrl = "https://api.openai.com/v1",
                    provider = MediaProviderSettings.PROVIDER_OPENAI
                )
            )
            assertEquals(false, supports)
        }
    }
}
