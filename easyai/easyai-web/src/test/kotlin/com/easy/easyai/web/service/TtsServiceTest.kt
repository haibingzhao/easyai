package com.easy.easyai.web.service

import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.ModelType
import com.easy.easyai.core.media.MediaProviderSettings
import com.easy.easyai.core.model.aux.AuxModelResolver
import com.easy.easyai.core.model.aux.AuxModelTask
import com.easy.easyai.speech.SpeechAudio
import com.easy.easyai.speech.SpeechInputMode
import com.easy.easyai.speech.SpeechMode
import com.easy.easyai.speech.SpeechOverrides
import com.easy.easyai.speech.SpeechRole
import com.easy.easyai.speech.SpeechScript
import com.easy.easyai.speech.SpeechSegment
import com.easy.easyai.speech.SpeechStyle
import com.easy.easyai.speech.SpeechSynthesizer
import com.easy.easyai.speech.SpokenTextNormalizer
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Read-aloud gates: an unusable `tts` choice must be a clear 409 rather than a paid failure, the
 * product limits (length, one-at-a-time) must be enforced server-side, and the row's own voice and
 * format choices must not leak into what the browser receives.
 */
class TtsServiceTest {

    companion object {
        /** Generous bound for cross-thread hand-offs; the assertions still fail if the signal never comes. */
        private const val AWAIT_SECONDS = 15L
    }

    private fun config(
        modelType: ModelType = ModelType.SPEECH,
        enabled: Boolean = true,
        protocol: Protocol = Protocol.DASHSCOPE,
        modelId: String = "qwen-audio-3.1-tts-flash",
        baseUrl: String? = "https://ws-1.cn-beijing.maas.aliyuncs.com/api/v1",
        apiKey: String? = "sk-test",
        mediaOptions: String? = """{"voice":"longanhuan_v3.1","format":"wav"}"""
    ) = ModelProviderConfig(
        id = "row-tts",
        name = "Qwen TTS",
        protocol = protocol,
        isCustom = true,
        baseUrl = baseUrl,
        apiKey = apiKey,
        modelId = modelId,
        enabled = enabled,
        modelType = modelType,
        mediaOptions = mediaOptions,
        timeoutSeconds = 90
    )

    private fun resolverReturning(config: ModelProviderConfig?): AuxModelResolver {
        val resolver = mockk<AuxModelResolver>()
        coEvery { resolver.resolveConfig(any<Collection<String>>(), AuxModelTask.TTS) } returns config
        return resolver
    }

    private fun refineScript(source: String, spoken: String): SpeechScript = SpeechScript(
        scriptId = "script-1",
        sourceDigest = SpokenTextNormalizer.sourceDigest(source),
        mood = "克制",
        style = SpeechStyle.TEACHER,
        segments = listOf(
            SpeechSegment(
                id = "seg-0",
                index = 0,
                sourceText = source,
                spokenText = spoken,
                voice = "longanhuan_v3.1",
                emotion = "serious",
                styleNote = "温和但清晰地讲述",
                rate = 0.95,
                role = SpeechRole.NARRATION
            )
        )
    )

    private class RecordingSynthesizer(
        private val audio: ByteArray = byteArrayOf(1, 2, 3),
        private val onCall: (suspend () -> Unit)? = null
    ) : SpeechSynthesizer {
        data class Call(
            val settings: MediaProviderSettings,
            val text: String,
            val voice: String?,
            val format: String?,
            val plainTextOnly: Boolean,
            val overrides: SpeechOverrides
        )

        val calls = mutableListOf<Call>()
        var failWith: Throwable? = null
        var hang: CompletableDeferred<Unit>? = null
        var streamFactory: (() -> Flux<SpeechAudio>)? = null

        /** When set, the fake stalls until this budget elapses, producing a real timeout exception. */
        var timeoutAfterMs: Long? = null

        override fun supports(providerType: String): Boolean =
            providerType.equals(MediaProviderSettings.PROVIDER_DASHSCOPE, ignoreCase = true)

        override suspend fun synthesize(
            settings: MediaProviderSettings,
            text: String,
            voice: String?,
            format: String?,
            plainTextOnly: Boolean
        ): SpeechAudio = synthesize(
            settings = settings,
            text = text,
            overrides = SpeechOverrides(
                voice = voice,
                inputMode = if (plainTextOnly) SpeechInputMode.PLAIN_TEXT else SpeechInputMode.PROVIDER_GENERATED_MARKUP
            ),
            format = format
        )

        override suspend fun synthesize(
            settings: MediaProviderSettings,
            text: String,
            overrides: SpeechOverrides,
            format: String?
        ): SpeechAudio {
            calls += Call(
                settings,
                text,
                overrides.voice,
                format,
                overrides.inputMode == SpeechInputMode.PLAIN_TEXT,
                overrides
            )
            hang?.await()
            timeoutAfterMs?.let { budget -> withTimeout(budget) { delay(budget + 1_000) } }
            onCall?.invoke()
            failWith?.let { throw it }
            return SpeechAudio(audio, "audio/mpeg")
        }

        // The service streams through the overrides overload, so the fake has to intercept that one:
        // overriding only the five-argument shape leaves the default implementation in place and the
        // test would pass a one-shot clip off as a stream.
        override fun synthesizeStream(
            settings: MediaProviderSettings,
            text: String,
            overrides: SpeechOverrides,
            format: String?
        ): Flux<SpeechAudio> = streamFactory?.invoke()
            ?: super.synthesizeStream(settings, text, overrides, format)
    }

    @Nested
    inner class Gates {

        @Test
        fun `an unconfigured task is a conflict that names the settings page`() = runTest {
            val error = assertFailsWith<ResponseStatusException> {
                TtsService(resolverReturning(null), RecordingSynthesizer()).speak(listOf("alice"), "你好")
            }
            assertEquals(HttpStatus.CONFLICT, error.statusCode)
            assertTrue(error.reason!!.contains("Task Models -> TTS Model"))
        }

        @Test
        fun `a row of the wrong model type is refused at run time too`() = runTest {
            val error = assertFailsWith<ResponseStatusException> {
                TtsService(resolverReturning(config(modelType = ModelType.CHAT)), RecordingSynthesizer())
                    .speak(listOf("alice"), "你好")
            }
            assertEquals(HttpStatus.CONFLICT, error.statusCode)
            assertTrue(error.reason!!.contains("SPEECH"))
        }

        @Test
        fun `a row disabled after it was chosen is refused`() = runTest {
            val error = assertFailsWith<ResponseStatusException> {
                TtsService(resolverReturning(config(enabled = false)), RecordingSynthesizer())
                    .speak(listOf("alice"), "你好")
            }
            assertEquals(HttpStatus.CONFLICT, error.statusCode)
            assertTrue(error.reason!!.contains("disabled"))
        }

        @Test
        fun `an openai-compatible speech row is reported as unsupported, not attempted`() = runTest {
            val synthesizer = RecordingSynthesizer()
            val service = TtsService(resolverReturning(config(protocol = Protocol.OPENAI)), synthesizer)

            val error = assertFailsWith<ResponseStatusException> { service.speak(listOf("alice"), "你好") }
            assertEquals(HttpStatus.CONFLICT, error.statusCode)
            assertTrue(error.reason!!.contains("does not support"))
            assertTrue(synthesizer.calls.isEmpty())
        }

        @Test
        fun `a blank base URL or API key is a configuration conflict`() = runTest {
            val noUrl = assertFailsWith<ResponseStatusException> {
                TtsService(resolverReturning(config(baseUrl = "")), RecordingSynthesizer())
                    .speak(listOf("alice"), "你好")
            }
            assertEquals(HttpStatus.CONFLICT, noUrl.statusCode)
            assertTrue(noUrl.reason!!.contains("Base URL"))

            val noKey = assertFailsWith<ResponseStatusException> {
                TtsService(resolverReturning(config(apiKey = "  ")), RecordingSynthesizer())
                    .speak(listOf("alice"), "你好")
            }
            assertEquals(HttpStatus.CONFLICT, noKey.statusCode)
            assertTrue(noKey.reason!!.contains("API key"))
        }

        @Test
        fun `blank text is a bad request`() = runTest {
            val error = assertFailsWith<ResponseStatusException> {
                TtsService(resolverReturning(config()), RecordingSynthesizer()).speak(listOf("alice"), "   \n ")
            }
            assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
        }

        @Test
        fun `an unknown read-aloud mode is a bad request`() = runTest {
            val synthesizer = RecordingSynthesizer()
            val error = assertFailsWith<ResponseStatusException> {
                TtsService(resolverReturning(config()), synthesizer).speak(listOf("alice"), "你好", "directed")
            }
            assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
            assertTrue(synthesizer.calls.isEmpty())
        }

        @Test
        fun `text over the product limit is refused instead of truncated`() = runTest {
            val synthesizer = RecordingSynthesizer()
            val error = assertFailsWith<ResponseStatusException> {
                TtsService(resolverReturning(config()), synthesizer)
                    .speak(listOf("alice"), "a".repeat(TtsService.MAX_CHARS + 1))
            }
            assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, error.statusCode)
            assertTrue(synthesizer.calls.isEmpty())
        }

        @Test
        fun `text exactly at the product limit is accepted`() = runTest {
            val synthesizer = RecordingSynthesizer()
            TtsService(resolverReturning(config()), synthesizer)
                .speak(listOf("alice"), "a".repeat(TtsService.MAX_CHARS))

            assertEquals(TtsService.MAX_CHARS, synthesizer.calls.single().text.length)
        }

        @Test
        fun `only one synthesis runs per owner`() = runTest {
            val synthesizer = RecordingSynthesizer().apply { hang = CompletableDeferred() }
            val service = TtsService(resolverReturning(config()), synthesizer)

            val first = async { service.speak(listOf("alice", "g_fam"), "你好") }
            runCurrent()

            // The guard waits out a replacement first, so this refusal is what is left after the
            // grace period — a second read-aloud, not a superseded one.
            val error = assertFailsWith<ResponseStatusException> { service.speak(listOf("alice", "g_fam"), "你好") }
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.statusCode)

            synthesizer.hang!!.complete(Unit)
            assertEquals("audio/mpeg", first.await().mimeType)
        }

        @Test
        fun `a replacement read-aloud waits for the request it is replacing`() = runTest {
            // Clicking another reply aborts the old request and fires the new one at once, but the
            // abort reaches the server a round-trip later. Refusing on arrival turned that normal
            // sequence into a 429 for a request that was never concurrent with anything.
            val synthesizer = RecordingSynthesizer().apply { hang = CompletableDeferred() }
            val service = TtsService(resolverReturning(config()), synthesizer)

            val first = async { service.speak(listOf("alice"), "你好") }
            runCurrent()
            val second = async { service.speak(listOf("alice"), "下一段") }
            runCurrent()
            synthesizer.hang!!.complete(Unit)

            assertEquals("audio/mpeg", first.await().mimeType)
            assertEquals("audio/mpeg", second.await().mimeType)
            assertEquals(listOf("你好", "下一段"), synthesizer.calls.map { it.text })
        }

        @Test
        fun `a plan preview does not take the read-aloud slot`() = runTest {
            // A script preview is a full LLM plan that can run for a minute and logs nothing while it
            // does. Taking the synthesis slot for it refused the speaker button on the very reply the
            // panel had been opened for — a 429 with no synthesis anywhere in the server log.
            val synthesizer = RecordingSynthesizer().apply { hang = CompletableDeferred() }
            val service = TtsService(resolverReturning(config()), synthesizer)

            val first = async { service.speak(listOf("alice"), "你好") }
            runCurrent()

            // No director on purpose: the point is that this fails on configuration (409), which can
            // only happen if it never asked for the slot the running read-aloud is holding.
            val error = assertFailsWith<ResponseStatusException> { service.planScript(listOf("alice"), "你好") }
            assertEquals(HttpStatus.CONFLICT, error.statusCode)

            synthesizer.hang!!.complete(Unit)
            assertEquals("audio/mpeg", first.await().mimeType)
        }

        @Test
        fun `the in-flight slot is released after a failure`() = runTest {
            val synthesizer = RecordingSynthesizer().apply { failWith = IllegalStateException("boom") }
            val service = TtsService(resolverReturning(config()), synthesizer)

            assertFailsWith<ResponseStatusException> { service.speak(listOf("alice"), "你好") }
            synthesizer.failWith = null
            assertEquals("audio/mpeg", service.speak(listOf("alice"), "你好").mimeType)
        }
    }

    @Nested
    inner class Synthesis {

        @Test
        fun `the row's voice and endpoint reach the synthesizer`() = runTest {
            val synthesizer = RecordingSynthesizer()
            TtsService(resolverReturning(config()), synthesizer).speak(listOf("alice"), "  你好  ")

            val call = synthesizer.calls.single()
            assertEquals("你好", call.text)
            assertEquals("row-tts", call.settings.id)
            assertEquals("qwen-audio-3.1-tts-flash", call.settings.defaultModel)
            assertEquals("sk-test", call.settings.apiKey)
            assertEquals(MediaProviderSettings.SERVICE_KIND_SPEECH, call.settings.serviceKind)
            assertEquals("dashscope", call.settings.providerType)
            // The row keeps wav for the generation tool; read-aloud always asks for mp3.
            assertEquals("mp3", call.format)
            assertNull(call.voice)
            assertTrue(call.plainTextOnly)
        }

        @Test
        fun `refine uses the validated text and request overrides`() = runTest {
            val director = mockk<SpeechDirector>()
            coEvery { director.quickRefine(listOf("alice"), "你好", any(), TtsService.MAX_CHARS) } returns
                RefineOutcome.Planned(refineScript("你好", "你好呀"))
            val synthesizer = RecordingSynthesizer()
            TtsService(resolverReturning(config()), synthesizer, director)
                .speak(listOf("alice"), "你好", SpeechMode.REFINE.key)

            val call = synthesizer.calls.single()
            assertEquals("你好呀", call.text)
            assertEquals("longanhuan_v3.1", call.overrides.voice)
            assertEquals("温和但清晰地讲述", call.overrides.instruction)
            assertEquals("[serious]", call.overrides.emotionTag)
            assertEquals(0.95, call.overrides.rate)
            assertEquals(SpeechInputMode.PLAIN_TEXT, call.overrides.inputMode)
            assertEquals("mp3", call.format)
        }

        @Test
        fun `missing or invalid direction falls back to the original text`() = runTest {
            val director = mockk<SpeechDirector>()
            coEvery { director.quickRefine(any(), any(), any(), any()) } returns
                RefineOutcome.Unavailable(SpeechPlanner.REFINE_UNAVAILABLE)
            val synthesizer = RecordingSynthesizer()
            TtsService(resolverReturning(config()), synthesizer, director)
                .speak(listOf("alice"), "你好", SpeechMode.REFINE.key)

            val call = synthesizer.calls.single()
            assertEquals("你好", call.text)
            assertNull(call.overrides.voice)
            assertEquals(SpeechInputMode.PLAIN_TEXT, call.overrides.inputMode)
        }

        @Test
        fun `a planning exception releases single flight and does not block the next request`() = runTest {
            val director = mockk<SpeechDirector>()
            coEvery { director.quickRefine(any(), any(), any(), any()) } throws IllegalStateException("planner down")
            val synthesizer = RecordingSynthesizer()
            val service = TtsService(resolverReturning(config()), synthesizer, director)

            assertEquals("audio/mpeg", service.speak(listOf("alice"), "你好", SpeechMode.REFINE.key).mimeType)
            assertEquals("audio/mpeg", service.speak(listOf("alice"), "下一段").mimeType)
            assertEquals(listOf("你好", "下一段"), synthesizer.calls.map { it.text })
        }

        @Test
        fun `an oversized planner result falls back instead of bypassing the limit`() = runTest {
            val director = mockk<SpeechDirector>()
            coEvery { director.quickRefine(any(), any(), any(), any()) } answers {
                val source = secondArg<String>()
                RefineOutcome.Planned(refineScript(source, "a".repeat(TtsService.MAX_CHARS + 1)))
            }
            val synthesizer = RecordingSynthesizer()
            TtsService(resolverReturning(config()), synthesizer, director)
                .speak(listOf("alice"), "你好", SpeechMode.REFINE.key)
            assertEquals("你好", synthesizer.calls.single().text)
        }

        @Test
        fun `provider failures surface as a gateway error without leaking the provider text`() = runTest {
            val synthesizer = RecordingSynthesizer().apply {
                failWith = IllegalStateException("Engine return error code: 418")
            }
            val error = assertFailsWith<ResponseStatusException> {
                TtsService(resolverReturning(config()), synthesizer).speak(listOf("alice"), "你好")
            }
            assertEquals(HttpStatus.BAD_GATEWAY, error.statusCode)
            assertEquals("Speech synthesis failed", error.reason)
        }

        @Test
        fun `a provider timeout is a gateway timeout`() = runTest {
            val synthesizer = RecordingSynthesizer().apply { timeoutAfterMs = 5 }
            val error = assertFailsWith<ResponseStatusException> {
                TtsService(resolverReturning(config()), synthesizer).speak(listOf("alice"), "你好")
            }
            assertEquals(HttpStatus.GATEWAY_TIMEOUT, error.statusCode)
        }

        @Test
        fun `a real cancellation is propagated rather than turned into a 500`() = runTest {
            val synthesizer = RecordingSynthesizer().apply { failWith = CancellationException("client hung up") }
            assertFailsWith<CancellationException> {
                TtsService(resolverReturning(config()), synthesizer).speak(listOf("alice"), "你好")
            }
        }

        @Test
        fun `streaming forwards each audio chunk and releases the owner on completion`() = runTest {
            val synthesizer = RecordingSynthesizer().apply {
                streamFactory = {
                    Flux.just(
                        SpeechAudio(byteArrayOf(1, 2), "audio/mpeg"),
                        SpeechAudio(byteArrayOf(3), "audio/mpeg")
                    )
                }
            }
            val service = TtsService(resolverReturning(config()), synthesizer)

            StepVerifier.create(service.speakStream(service.openSpeak(listOf("alice"), "你好")))
                .expectNextCount(2)
                .verifyComplete()
            assertEquals("audio/mpeg", service.speak(listOf("alice"), "下一段").mimeType)
        }

        @Test
        fun `cancelling the HTTP stream cancels upstream and releases the owner`() = runTest {
            val upstreamCancelled = CountDownLatch(1)
            val upstreamSubscribed = CountDownLatch(1)
            val synthesizer = RecordingSynthesizer().apply {
                streamFactory = {
                    Flux.never<SpeechAudio>()
                        .doOnSubscribe { upstreamSubscribed.countDown() }
                        .doOnCancel { upstreamCancelled.countDown() }
                }
            }
            val service = TtsService(resolverReturning(config()), synthesizer)

            // Cancellation propagates through the coroutine bridge and the timeout operator before it
            // reaches the engine subscription, so every wait here is a bounded poll rather than an
            // instantaneous assertion; a slow shared CI runner must not turn this into a flake.
            StepVerifier.create(service.speakStream(service.openSpeak(listOf("alice"), "你好")))
                .expectSubscription()
                .then { assertTrue(upstreamSubscribed.await(AWAIT_SECONDS, TimeUnit.SECONDS), "upstream never subscribed") }
                .thenCancel()
                .verify()

            assertTrue(upstreamCancelled.await(AWAIT_SECONDS, TimeUnit.SECONDS), "upstream was not cancelled")
            assertEquals("audio/mpeg", service.speak(listOf("alice"), "下一段").mimeType)
        }
    }

    @Nested
    inner class Availability {

        @Test
        fun `a usable row reports the model id and no reason`() = runTest {
            val availability = TtsService(resolverReturning(config()), RecordingSynthesizer())
                .availability(listOf("alice"))

            assertTrue(availability.configured)
            assertEquals("qwen-audio-3.1-tts-flash", availability.modelId)
            assertNull(availability.reason)
        }

        @Test
        fun `qwen tts in a bailian openai-compatible group is available`() = runTest {
            val row = config(
                protocol = Protocol.OPENAI,
                baseUrl = "https://llm-xy.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"
            )
            val availability = TtsService(resolverReturning(row), SpeechSynthesizer.create())
                .availability(listOf("alice"))

            assertTrue(availability.configured)
            assertEquals("qwen-audio-3.1-tts-flash", availability.modelId)
            assertNull(availability.reason)
        }

        @Test
        fun `every off state carries a machine-readable reason`() = runTest {
            suspend fun reasonOf(config: ModelProviderConfig?): String? =
                TtsService(resolverReturning(config), RecordingSynthesizer())
                    .availability(listOf("alice")).reason

            assertEquals(TtsService.REASON_NOT_CONFIGURED, reasonOf(null))
            assertEquals(TtsService.REASON_WRONG_MODEL_TYPE, reasonOf(config(modelType = ModelType.IMAGE)))
            assertEquals(TtsService.REASON_DISABLED, reasonOf(config(enabled = false)))
            assertEquals(TtsService.REASON_UNSUPPORTED_PROVIDER, reasonOf(config(protocol = Protocol.KLING)))
        }

        @Test
        fun `a row without credentials is reported as off instead of failing on click`() = runTest {
            // `speak` answers 409 for these, so the button must not appear at all: probing is the
            // only chance to tell the user before they pay for a round-trip.
            suspend fun availabilityOf(config: ModelProviderConfig) =
                TtsService(resolverReturning(config), RecordingSynthesizer()).availability(listOf("alice"))

            val noKey = availabilityOf(config(apiKey = null))
            assertEquals(false, noKey.configured)
            assertEquals(TtsService.REASON_INCOMPLETE_CONFIG, noKey.reason)
            assertEquals("qwen-audio-3.1-tts-flash", noKey.modelId)

            val noUrl = availabilityOf(config(baseUrl = "  "))
            assertEquals(false, noUrl.configured)
            assertEquals(TtsService.REASON_INCOMPLETE_CONFIG, noUrl.reason)
        }

        @Test
        fun `availability never synthesizes`() = runTest {
            val synthesizer = RecordingSynthesizer()
            TtsService(resolverReturning(config()), synthesizer).availability(listOf("alice"))
            assertTrue(synthesizer.calls.isEmpty())
        }

        @Test
        fun `the service works with no bean for the synthesizer`() = runTest {
            // Kotlin default arguments are invisible to Spring's constructor resolution, so the
            // parameter is nullable + @Autowired(required = false) and the façade is built here.
            val availability = TtsService(null).availability(listOf("alice"))
            assertEquals(TtsService.REASON_NOT_CONFIGURED, availability.reason)
        }
    }

    @Nested
    inner class LimitConstants {

        @Test
        fun `the product limit is the documented 20000 characters`() {
            assertEquals(20_000, TtsService.MAX_CHARS)
        }
    }
}
