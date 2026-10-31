package com.easy.easyai.speech

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.media.MediaProviderSettings
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests [DashScopeTtsClient] parameter resolution and transport wiring.
 *
 * The socket itself is replaced by [RecordingTransport], so these assertions cover exactly what the
 * client decides — voice precedence, format fallback, frame count, endpoint — without a network.
 * Frame encoding is covered separately by [DashScopeTtsProtocolTest].
 */
class DashScopeTtsClientTest {

    private fun settings(
        baseUrl: String = "https://ws-123.cn-beijing.maas.aliyuncs.com/api/v1",
        model: String = "qwen-audio-3.1-tts-flash",
        options: String = "",
        apiKey: String = "sk-test"
    ) = MediaProviderSettings(
        id = "row-1",
        displayName = "Qwen TTS",
        enabled = true,
        serviceKind = MediaProviderSettings.SERVICE_KIND_SPEECH,
        providerType = MediaProviderSettings.PROVIDER_DASHSCOPE,
        baseUrl = baseUrl,
        apiKey = apiKey,
        defaultModel = model,
        options = options,
        timeoutSeconds = 90
    )

    /** Captures the exchange instead of opening a socket. */
    private class RecordingTransport(private val audio: ByteArray = byteArrayOf(1, 2, 3)) : TtsTransport {
        var uri: String? = null
        var apiKey: String? = null
        var exchange: TtsExchange? = null
        var timeoutSeconds: Long? = null
        var failWith: Throwable? = null

        override suspend fun run(uri: String, apiKey: String, exchange: TtsExchange, timeoutSeconds: Long): ByteArray {
            this.uri = uri
            this.apiKey = apiKey
            this.exchange = exchange
            this.timeoutSeconds = timeoutSeconds
            failWith?.let { throw it }
            return audio
        }

        /** The `payload.parameters` object of the captured run-task frame, flattened to Kotlin values. */
        fun parameterValues(): Map<String, Any?> {
            val node = SharedObjectMapper.instance.readTree(exchange!!.runTask)
            val params = node.path("payload").path("parameters")
            return buildMap {
                params.propertyNames().forEach { name ->
                    val value = params.path(name)
                    put(
                        name,
                        when {
                            value.isBoolean -> value.asBoolean()
                            value.isIntegralNumber -> value.asInt()
                            value.isFloatingPointNumber -> value.asDouble()
                            value.isArray -> value.get(0).asString(null)
                            else -> value.asString(null)
                        }
                    )
                }
            }
        }

        /** Number of entries of an array parameter, or -1 when it is not an array. */
        fun arraySize(name: String): Int {
            val node = SharedObjectMapper.instance.readTree(exchange!!.runTask)
            val value = node.path("payload").path("parameters").path(name)
            return if (value.isArray) value.size() else -1
        }
    }

    @Nested
    inner class Wiring {

        @Test
        fun `connects to the duplex endpoint with the row credentials`() = runTest {
            val transport = RecordingTransport()
            DashScopeTtsClient(settings(), transport).synthesize("你好", null, null)

            assertEquals("wss://ws-123.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference", transport.uri)
            assertEquals("sk-test", transport.apiKey)
            assertEquals(90, transport.timeoutSeconds)
        }

        @Test
        fun `a blank base URL is refused before connecting`() = runTest {
            val transport = RecordingTransport()
            val error = assertFailsWith<IllegalStateException> {
                DashScopeTtsClient(settings(baseUrl = ""), transport).synthesize("你好", null, null)
            }
            assertTrue(error.message!!.contains("base URL"))
            assertTrue(transport.exchange == null)
        }

        @Test
        fun `a blank API key is refused before connecting`() = runTest {
            val transport = RecordingTransport()
            val error = assertFailsWith<IllegalArgumentException> {
                DashScopeTtsClient(settings(apiKey = ""), transport).synthesize("你好", null, null)
            }
            assertTrue(error.message!!.contains("API key"))
            assertTrue(transport.exchange == null)
        }

        @Test
        fun `provider failures surface with their original wording`() = runTest {
            val transport = RecordingTransport().apply {
                failWith = IllegalStateException("InvalidParameter: [tts:]Engine return error code: 418")
            }
            val error = assertFailsWith<IllegalStateException> {
                DashScopeTtsClient(settings(), transport).synthesize("你好", null, null)
            }
            assertTrue(error.message!!.contains("Engine return error code: 418"))
        }

        @Test
        fun `empty audio from the provider is an error`() = runTest {
            assertFailsWith<IllegalArgumentException> {
                DashScopeTtsClient(settings(), RecordingTransport(ByteArray(0))).synthesize("你好", null, null)
            }
        }

        @Test
        fun `blank text is refused before connecting`() = runTest {
            assertFailsWith<IllegalArgumentException> {
                DashScopeTtsClient(settings(), RecordingTransport()).synthesize("   ", null, null)
            }
        }

        @Test
        fun `blank text is refused even when an emotion tag is prepended`() = runTest {
            // The tag would otherwise make the payload non-blank and send "[serious]" alone to be spoken.
            assertFailsWith<IllegalArgumentException> {
                DashScopeTtsClient(settings(), RecordingTransport()).synthesize(
                    "   ",
                    SpeechOverrides(emotionTag = "[serious]"),
                    null
                )
            }
        }
    }

    @Nested
    inner class Parameters {

        @Test
        fun `row options supply voice format sample rate and instruction`() = runTest {
            val transport = RecordingTransport()
            val client = DashScopeTtsClient(
                settings(
                    options = """{"voice":"longanlingxin_v3.1","format":"wav","sample_rate":16000,
                        "instruction":"请讲上海话。","volume":60,"rate":1.2,"pitch":0.9}"""
                ),
                transport
            )

            val (_, mime) = client.synthesize("你好", null, null)
            val values = transport.parameterValues()

            assertEquals("longanlingxin_v3.1", values["voice"])
            assertEquals("wav", values["format"])
            assertEquals(16000, values["sample_rate"])
            assertEquals("请讲上海话。", values["instruction"])
            assertEquals(60, values["volume"])
            assertEquals(1.2, values["rate"])
            assertEquals(0.9, values["pitch"])
            assertEquals("PlainText", values["text_type"])
            assertEquals("audio/wav", mime)
        }

        @Test
        fun `SSML mode enables SSML without changing text_type`() = runTest {
            val transport = RecordingTransport()
            DashScopeTtsClient(settings(options = """{"enable_ssml":true}"""), transport)
                .synthesize("<speak>你好</speak>", null, null)

            val values = transport.parameterValues()
            // `enable_ssml` is the only SSML switch: the vendor's SSML example sends PlainText too.
            assertEquals("PlainText", values["text_type"])
            assertEquals(true, values["enable_ssml"])
        }

        @Test
        fun `a plain-text caller never inherits the row's SSML switch`() = runTest {
            val transport = RecordingTransport()
            DashScopeTtsClient(settings(options = """{"enable_ssml":true}"""), transport)
                .synthesize("**你好**", null, null, plainTextOnly = true)

            val values = transport.parameterValues()
            assertEquals("PlainText", values["text_type"])
            assertEquals(false, values["enable_ssml"])
        }

        @Test
        fun `an explicit call voice beats the row and the model default`() = runTest {            val transport = RecordingTransport()
            DashScopeTtsClient(settings(options = """{"voice":"longanhuan_v3.1"}"""), transport)
                .synthesize("你好", "xunanchuan_v3.1", null)

            assertEquals("xunanchuan_v3.1", transport.parameterValues()["voice"])
        }

        @Test
        fun `request overrides control prosody without enabling arbitrary markup`() = runTest {
            val transport = RecordingTransport()
            val client = DashScopeTtsClient(
                settings(options = """{"voice":"row-voice","instruction":"row instruction","rate":1.2,"enable_ssml":true}"""),
                transport
            )
            client.synthesize(
                "你好",
                SpeechOverrides(
                    voice = "request-voice",
                    instruction = "温和讲述",
                    emotionTag = "[serious]",
                    rate = 0.95,
                    inputMode = SpeechInputMode.PLAIN_TEXT
                ),
                "mp3"
            )
            val values = transport.parameterValues()
            assertEquals("request-voice", values["voice"])
            assertEquals("温和讲述", values["instruction"])
            assertEquals(0.95, values["rate"])
            assertEquals(false, values["enable_ssml"])
            assertTrue(transport.exchange!!.continueTasks.single().contains("[serious]你好"))
        }

        @Test
        fun `a segment emotion is not prefixed over a tag the text already opens with`() = runTest {
            // The planner wrote `[excited]` itself; prepending `[serious]` would silently override it.
            val transport = RecordingTransport()
            DashScopeTtsClient(settings(), transport).synthesize(
                "[excited]你好",
                SpeechOverrides(emotionTag = "[serious]", inputMode = SpeechInputMode.PLAIN_TEXT),
                "mp3"
            )

            val frame = transport.exchange!!.continueTasks.single()
            assertTrue(frame.contains("[excited]你好"), "frame was $frame")
            assertFalse(frame.contains("[serious]"), "frame was $frame")
        }

        @Test
        fun `a multi-word control tag is accepted as an emotion`() = runTest {
            val transport = RecordingTransport()
            DashScopeTtsClient(settings(), transport).synthesize(
                "我们开始吧",
                SpeechOverrides(emotionTag = "[very slowly]", inputMode = SpeechInputMode.PLAIN_TEXT),
                "mp3"
            )

            assertTrue(transport.exchange!!.continueTasks.single().contains("[very slowly]我们开始吧"))
        }

        @Test
        fun `a model without a configured voice falls back to its documented system voice`() = runTest {
            val transport = RecordingTransport()
            DashScopeTtsClient(settings(model = "qwen-audio-3.0-tts-plus"), transport).synthesize("你好", null, null)

            assertEquals("longanlingxin", transport.parameterValues()["voice"])
        }

        @Test
        fun `an unknown model with no voice is rejected before connecting`() = runTest {
            val error = assertFailsWith<IllegalStateException> {
                DashScopeTtsClient(settings(model = "some-vendor-tts"), RecordingTransport())
                    .synthesize("你好", null, null)
            }
            assertTrue(error.message!!.contains("Generation Options"))
        }

        @Test
        fun `unsupported formats degrade to mp3`() = runTest {
            val transport = RecordingTransport()
            val (_, mime) = DashScopeTtsClient(settings(), transport).synthesize("你好", null, "flac")

            assertEquals("mp3", transport.parameterValues()["format"])
            assertEquals("audio/mpeg", mime)
        }

        @Test
        fun `pcm and opus map to their own media types`() = runTest {
            val pcm = RecordingTransport()
            assertEquals("audio/L16", DashScopeTtsClient(settings(), pcm).synthesize("你好", null, "pcm").second)
            val opus = RecordingTransport()
            assertEquals("audio/opus", DashScopeTtsClient(settings(), opus).synthesize("你好", null, "opus").second)
        }

        @Test
        fun `sample rate outside the documented set falls back to the default`() = runTest {
            val transport = RecordingTransport()
            DashScopeTtsClient(settings(options = """{"sample_rate":12345}"""), transport).synthesize("你好", null, null)

            assertEquals(24000, transport.parameterValues()["sample_rate"])
        }

        @Test
        fun `bit rate is only sent for compressed formats`() = runTest {
            val mp3 = RecordingTransport()
            DashScopeTtsClient(settings(options = """{"bit_rate":64}"""), mp3).synthesize("你好", null, "mp3")
            assertEquals(64, mp3.parameterValues()["bit_rate"])

            val wav = RecordingTransport()
            DashScopeTtsClient(settings(options = """{"bit_rate":64}"""), wav).synthesize("你好", null, "wav")
            assertTrue(wav.parameterValues()["bit_rate"] == null)
        }

        @Test
        fun `language hints travel as the documented array`() = runTest {
            val transport = RecordingTransport()
            DashScopeTtsClient(settings(options = """{"language_hints":["vi"]}"""), transport)
                .synthesize("你好", null, null)

            assertEquals(1, transport.arraySize("language_hints"))
            assertEquals("vi", transport.parameterValues()["language_hints"])
        }

        @Test
        fun `a language hint written as a bare string still passes through`() = runTest {
            val transport = RecordingTransport()
            DashScopeTtsClient(settings(options = """{"language_hints":"vi"}"""), transport).synthesize("你好", null, null)

            assertEquals(1, transport.arraySize("language_hints"))
            assertEquals("vi", transport.parameterValues()["language_hints"])
        }

        @Test
        fun `a blank language hint array is dropped entirely`() = runTest {
            val transport = RecordingTransport()
            DashScopeTtsClient(settings(options = """{"language_hints":[]}"""), transport).synthesize("你好", null, null)

            assertTrue(transport.parameterValues()["language_hints"] == null)
        }

        @Test
        fun `unset optional parameters stay out of the frame`() = runTest {
            val transport = RecordingTransport()
            DashScopeTtsClient(settings(), transport).synthesize("你好", null, null)
            val values = transport.parameterValues()

            assertTrue(values["instruction"] == null)
            assertTrue(values["volume"] == null)
            assertTrue(values["enable_ssml"] == null)
            assertTrue(values["hot_fix"] == null)
            assertEquals(24000, values["sample_rate"])
            assertEquals("mp3", values["format"])
        }

        @Test
        fun `malformed options fail the call instead of silently falling back`() = runTest {
            val transport = RecordingTransport()
            val error = assertFailsWith<IllegalStateException> {
                DashScopeTtsClient(settings(options = """{"voice": """), transport).synthesize("你好", null, null)
            }

            assertTrue(error.message!!.contains("Generation Options"))
            assertTrue(transport.exchange == null)
        }

        @Test
        fun `plain-text-only overrides a row that enables SSML`() = runTest {
            val transport = RecordingTransport()
            DashScopeTtsClient(settings(options = """{"enable_ssml":true}"""), transport)
                .synthesize("**bold** text", null, "mp3", plainTextOnly = true)

            assertEquals(false, transport.parameterValues()["enable_ssml"])
        }
    }

    @Nested
    inner class Framing {

        @Test
        fun `short text travels in a single continue-task`() = runTest {
            val transport = RecordingTransport()
            DashScopeTtsClient(settings(), transport).synthesize("你好", null, null)

            assertEquals(1, transport.exchange!!.continueTasks.size)
            assertEquals("你好", continueText(transport.exchange!!.continueTasks.first()))
        }

        @Test
        fun `long text is split across continue-tasks and reassembles to the input`() = runTest {
            val transport = RecordingTransport()
            val text = "这是一个很长的句子。".repeat(3_000) // 30k chars, over one frame
            DashScopeTtsClient(settings(), transport).synthesize(text, null, null)

            val frames = transport.exchange!!.continueTasks
            assertTrue(frames.size > 1)
            assertEquals(text, frames.joinToString("") { continueText(it) })
        }

        @Test
        fun `every frame of one task shares its task id`() = runTest {
            val transport = RecordingTransport()
            DashScopeTtsClient(settings(), transport).synthesize("a".repeat(25_000), null, null)

            val exchange = transport.exchange!!
            val runTaskId = taskIdOf(exchange.runTask)
            assertTrue(taskIdOf(exchange.finishTask) == runTaskId)
            exchange.continueTasks.forEach { assertEquals(runTaskId, taskIdOf(it)) }
        }

        @Test
        fun `SSML over one frame is refused instead of silently truncated`() = runTest {
            val error = assertFailsWith<IllegalStateException> {
                DashScopeTtsClient(
                    settings(options = """{"enable_ssml":true}"""),
                    RecordingTransport()
                ).synthesize("a".repeat(20_001), null, null)
            }
            assertTrue(error.message!!.contains("SSML"))
        }

        @Test
        fun `SSML within one frame passes enable_ssml through`() = runTest {
            val transport = RecordingTransport()
            DashScopeTtsClient(settings(options = """{"enable_ssml":true}"""), transport)
                .synthesize("<speak>你好</speak>", null, null)

            assertEquals(true, transport.parameterValues()["enable_ssml"])
            assertEquals(1, transport.exchange!!.continueTasks.size)
        }

        @Test
        fun `text beyond the task ceiling is refused before connecting`() = runTest {
            val error = assertFailsWith<IllegalArgumentException> {
                DashScopeTtsClient(settings(), RecordingTransport()).synthesize("a".repeat(200_001), null, null)
            }
            assertTrue(error.message!!.contains("limit"))
        }

        private fun continueText(frame: String): String {
            val node = SharedObjectMapper.instance.readTree(frame)
            return node.path("payload").path("input").path("text").asString(null)!!
        }

        private fun taskIdOf(frame: String): String? =
            SharedObjectMapper.instance.readTree(frame).path("header").path("task_id").asString(null)
    }
}
