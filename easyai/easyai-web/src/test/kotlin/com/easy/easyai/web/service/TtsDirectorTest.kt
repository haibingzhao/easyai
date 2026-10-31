package com.easy.easyai.web.service

import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.ModelType
import com.easy.easyai.api.model.SpeechCapabilities
import com.easy.easyai.api.model.VoiceSpec
import com.easy.easyai.core.media.MediaProviderSettings
import com.easy.easyai.core.model.aux.AuxModelResolver
import com.easy.easyai.core.model.aux.AuxModelTask
import com.easy.easyai.speech.ResolvedSpeechCapabilities
import com.easy.easyai.speech.SpeechAudio
import com.easy.easyai.speech.SpeechCapabilityResolver
import com.easy.easyai.speech.SpeechOverrides
import com.easy.easyai.speech.SpeechRenderEvent
import com.easy.easyai.speech.SpeechScript
import com.easy.easyai.speech.SpeechSegment
import com.easy.easyai.speech.SpeechStyle
import com.easy.easyai.speech.SpeechSegmentScheduler
import com.easy.easyai.speech.SpeechSynthesizer
import com.easy.easyai.speech.SpeechTone
import com.easy.easyai.speech.SpokenTextNormalizer
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The director rendering pipeline: ordering, pauses, the one retry, partial failure, edit/revision. */
class TtsDirectorTest {

    private val capabilities = SpeechCapabilityResolver().resolve(
        SpeechCapabilities(
            defaultVoice = "voice-a",
            voices = listOf(VoiceSpec(voice = "voice-a", name = "A"))
        )
    )

    /** Two selectable voices, so a retry can be seen switching away from the planned one. */
    private val twoVoices = SpeechCapabilityResolver().resolve(
        SpeechCapabilities(
            defaultVoice = "voice-a",
            voices = listOf(VoiceSpec(voice = "voice-a", name = "A"), VoiceSpec(voice = "voice-b", name = "B"))
        )
    )

    private fun config() = ModelProviderConfig(
        id = "tts-row",
        name = "TTS",
        protocol = Protocol.DASHSCOPE,
        isCustom = true,
        baseUrl = "https://ws-1.cn-beijing.maas.aliyuncs.com/api/v1",
        apiKey = "sk-test",
        modelId = "qwen-audio-3.1-tts-flash",
        modelType = ModelType.SPEECH
    )

    private fun resolver(): AuxModelResolver {
        val resolver = mockk<AuxModelResolver>()
        coEvery { resolver.resolveConfig(any<Collection<String>>(), AuxModelTask.TTS) } returns config()
        return resolver
    }

    /** A planner that hands over a fixed plan; the pipeline under test is the renderer. */
    private class FakePlanner(
        private val segments: List<SpeechSegment>,
        private val capabilities: ResolvedSpeechCapabilities?,
        private val failMessage: String? = null
    ) : SpeechPlanner {

        var produceCalls = 0
            private set

        override suspend fun capabilitiesFor(ttsConfig: ModelProviderConfig) = capabilities

        override suspend fun quickRefine(
            owners: Collection<String>,
            text: String,
            ttsConfig: ModelProviderConfig,
            maxChars: Int,
            tone: SpeechTone
        ): RefineOutcome = RefineOutcome.Unavailable(SpeechPlanner.REFINE_UNAVAILABLE)

        override suspend fun produce(
            owners: Collection<String>,
            text: String,
            ttsConfig: ModelProviderConfig,
            maxChars: Int,
            incremental: Boolean,
            firstSegmentBudgetMs: Long?,
            tone: SpeechTone,
            onSegment: suspend (SpeechSegment) -> Unit
        ): SpeechPlanner.PlanSummary {
            produceCalls++
            if (failMessage != null) throw SpeechPlanException(failMessage, SpeechPlanner.CODE_NOT_CONFIGURED)
            segments.forEach { onSegment(it) }
            return SpeechPlanner.PlanSummary("plan-1", 1, "克制", SpeechStyle.TEACHER, segments.size)
        }
    }

    /** Records every text it is asked to speak; [failFor] injects a provider failure per text. */
    private class FakeSynth(
        private val failFor: (String) -> Boolean = { false },
        private val clipFor: (String) -> ByteArray = { byteArrayOf(1, 2, 3) },
        private val errorFor: ((String) -> Throwable)? = null
    ) : SpeechSynthesizer {

        // Renders run concurrently on `Dispatchers.IO`, so the fake must not use a plain list.
        val texts = CopyOnWriteArrayList<String>()
        /** What each call was asked for, so a retry can be checked for the voice it actually used. */
        val overrides = CopyOnWriteArrayList<SpeechOverrides>()

        override fun supports(providerType: String): Boolean = true

        override suspend fun synthesize(
            settings: MediaProviderSettings,
            text: String,
            voice: String?,
            format: String?,
            plainTextOnly: Boolean
        ): SpeechAudio = synthesize(settings, text, SpeechOverrides(voice = voice), format)

        override suspend fun synthesize(
            settings: MediaProviderSettings,
            text: String,
            request: SpeechOverrides,
            format: String?
        ): SpeechAudio {
            texts += text
            overrides += request
            errorFor?.invoke(text)?.let { throw it }
            if (failFor(text)) throw IllegalStateException("provider rejected '$text'")
            return SpeechAudio(clipFor(text), "audio/mpeg")
        }
    }

    private fun segment(index: Int, source: String, spoken: String = source, pause: Int = 0) = SpeechSegment(
        id = "seg_$index",
        index = index,
        sourceText = source,
        spokenText = spoken,
        voice = "voice-a",
        pauseAfterMs = pause
    )

    /** A minimal stored plan, for the revision comparison-and-set. */
    private fun planFor(scriptId: String, revision: Int, text: String) = SpeechScript(
        scriptId = scriptId,
        revision = revision,
        sourceDigest = SpokenTextNormalizer.sourceDigest(text),
        mood = "平稳",
        style = SpeechStyle.NARRATOR,
        segments = listOf(segment(0, text))
    )

    /** One service per test: the script store lives inside it, so a re-render must reuse it. */
    private fun service(planner: SpeechPlanner, synth: SpeechSynthesizer) = TtsService(resolver(), synth, planner)

    /**
     * The controller's two steps in the order it takes them: claim, then stream.
     *
     * The claim stays outside the stream on purpose — it is what turns a 409/413/404 into a status
     * code, so folding it into the stream here would test a path the controller never takes.
     */
    private fun render(
        service: TtsService,
        text: String,
        scriptId: String? = null,
        edited: List<SpeechSegment>? = null,
        incremental: Boolean = true,
        owners: List<String> = listOf("alice")
    ): List<SpeechRenderEvent> = runBlocking {
        service.renderStream(service.openRender(owners, text, scriptId), edited, incremental).asFlow().toList()
    }

    @Nested
    inner class Rendering {
        @Test
        fun `renders the plan in order and reports the count it does not know yet`() {
            val planner = FakePlanner(
                listOf(segment(0, "第一句。", pause = 200), segment(1, "第二句。", pause = 0)),
                capabilities
            )
            val events = render(service(planner, FakeSynth()), "第一句。第二句。")

            val meta = events.filterIsInstance<SpeechRenderEvent.Meta>().single()
            assertTrue(meta.planned)
            assertEquals(TtsService.UNKNOWN_SEGMENT_COUNT, meta.segmentCount)
            val audio = events.filterIsInstance<SpeechRenderEvent.SegmentAudio>()
            assertEquals(listOf(0, 1), audio.map { it.index })
            assertEquals(listOf("第一句。", "第二句。"), audio.map { it.spokenText })
            // The pause between segments belongs to the player, so it has to travel with the clip.
            assertEquals(200, audio[0].pauseAfterMs)
            assertEquals(2, events.filterIsInstance<SpeechRenderEvent.Completed>().single().segments)
        }

        @Test
        fun `a whole validated plan reports its exact length`() {
            val planner = FakePlanner(listOf(segment(0, "第一句。"), segment(1, "第二句。")), capabilities)
            val events = render(service(planner, FakeSynth()), "第一句。第二句。", incremental = false)
            assertEquals(2, events.filterIsInstance<SpeechRenderEvent.Meta>().single().segmentCount)
        }

        @Test
        fun `a segment that fails is retried from its source sentence`() {
            val synth = FakeSynth(failFor = { it == "改写后的一句。" })
            val planner = FakePlanner(listOf(segment(0, "原句。", "改写后的一句。")), capabilities)
            val events = render(service(planner, synth), "原句。")

            assertEquals(listOf("改写后的一句。", "原句。"), synth.texts)
            assertEquals("原句。", events.filterIsInstance<SpeechRenderEvent.SegmentAudio>().single().spokenText)
            // The retry is a degradation, not a failure: the read completes.
            assertEquals(1, events.filterIsInstance<SpeechRenderEvent.Completed>().single().segments)
        }

        @Test
        fun `a segment that fails twice stops the plan and says how much played`() {
            val synth = FakeSynth(failFor = { it.contains("第二句") })
            val planner = FakePlanner(
                listOf(segment(0, "第一句。"), segment(1, "第二句。"), segment(2, "第三句。")),
                capabilities
            )
            val events = render(service(planner, synth), "第一句。第二句。第三句。")

            val failed = events.filterIsInstance<SpeechRenderEvent.Failed>().single()
            assertEquals(1, failed.playedSegments)
            assertTrue(events.none { it is SpeechRenderEvent.Completed })
            // Nothing is silently skipped: the third sentence is never spoken, and the caller knows.
            assertEquals(listOf("第一句。"), events.filterIsInstance<SpeechRenderEvent.SegmentAudio>().map { it.spokenText })
        }

        @Test
        fun `an unusable plan reads the original text and says it was unplanned`() {
            val planner = FakePlanner(emptyList(), capabilities, failMessage = "no director configured")
            val events = render(service(planner, FakeSynth()), "原文在这里。")

            val meta = events.filterIsInstance<SpeechRenderEvent.Meta>().single()
            assertFalse(meta.planned)
            assertEquals("DIRECTOR_FALLBACK", meta.warnings.single().code)
            assertEquals("原文在这里。", events.filterIsInstance<SpeechRenderEvent.SegmentAudio>().single().spokenText)
        }

        @Test
        fun `a retried segment drops the planned voice and instruction`() {
            val synth = FakeSynth(failFor = { it == "兴奋版。" })
            val planner = FakePlanner(
                listOf(
                    SpeechSegment(
                        id = "seg_0",
                        index = 0,
                        sourceText = "原句。",
                        spokenText = "兴奋版。",
                        voice = "voice-b",
                        styleNote = "兴奋"
                    )
                ),
                twoVoices
            )
            render(service(planner, synth), "原句。")

            assertEquals(listOf("兴奋版。", "原句。"), synth.texts)
            // The first attempt may have failed *because of* the voice or the note. Reusing them would
            // fail again and bill twice, so the retry speaks in the row's default voice, unadorned.
            assertEquals("voice-a", synth.overrides.last().voice)
            assertNull(synth.overrides.last().instruction)
        }

        @Test
        fun `an over-size clip stops the plan instead of being retried`() {
            // Retrying cannot shrink a provider response, so this must cost exactly one paid call per
            // segment and surface as a failure rather than as a silent skip.
            val synth = FakeSynth(
                clipFor = { text ->
                    if (text == "第二句。") ByteArray(SpeechSegmentScheduler.MAX_SEGMENT_BYTES + 1)
                    else byteArrayOf(1)
                }
            )
            val planner = FakePlanner(listOf(segment(0, "第一句。"), segment(1, "第二句。")), capabilities)
            val events = render(service(planner, synth), "第一句。第二句。")

            // Concurrency 2, so only the set is deterministic; each segment must be tried exactly once.
            assertEquals(setOf("第一句。", "第二句。"), synth.texts.toSet())
            assertEquals(2, synth.texts.size)
            val failed = events.filterIsInstance<SpeechRenderEvent.Failed>().single()
            assertEquals(SpeechRenderEvent.CODE_SEGMENT_FAILED, failed.code)
            assertEquals(1, failed.playedSegments)
        }

        @Test
        fun `a provider timeout is reported as a timeout, not swallowed as a cancellation`() {
            // A `TimeoutCancellationException` is a CancellationException, and every layer above treats
            // one as "the caller went away": no retry, no `Failed`, just a stream that stops.
            val synth = FakeSynth(errorFor = { TimeoutException("provider was slow") })
            val planner = FakePlanner(listOf(segment(0, "第一句。")), capabilities)
            val events = render(service(planner, synth), "第一句。")

            val failed = events.filterIsInstance<SpeechRenderEvent.Failed>().single()
            assertEquals(SpeechRenderEvent.CODE_TIMEOUT, failed.code)
            assertEquals(0, failed.playedSegments)
        }
    }

    @Nested
    inner class ScriptsAndRevisions {
        private fun firstScriptId(events: List<SpeechRenderEvent>): String =
            events.filterIsInstance<SpeechRenderEvent.Meta>().single().scriptId

        @Test
        fun `an edited script is re-validated and its revision is bumped`() {
            val text = "第一句。第二句。"
            val planner = FakePlanner(listOf(segment(0, "第一句。"), segment(1, "第二句。")), capabilities)
            val svc = service(planner, FakeSynth())
            val scriptId = firstScriptId(render(svc, text))

            val edited = listOf(
                segment(0, "第一句。", "第一句，改过了。"),
                segment(1, "第二句。")
            )
            val second = render(svc, "", scriptId = scriptId, edited = edited)

            val meta = second.filterIsInstance<SpeechRenderEvent.Meta>().single()
            assertEquals(2, meta.revision)
            assertEquals(2, meta.segmentCount)
            assertEquals(
                listOf("第一句，改过了。", "第二句。"),
                second.filterIsInstance<SpeechRenderEvent.SegmentAudio>().map { it.spokenText }
            )
            // The plan itself is reused: editing a script must not pay for a second LLM call.
            assertEquals(1, planner.produceCalls)
        }

        @Test
        fun `an edit that no longer covers the source is refused instead of spoken`() {
            val text = "第一句。第二句。"
            val planner = FakePlanner(listOf(segment(0, "第一句。"), segment(1, "第二句。")), capabilities)
            val svc = service(planner, FakeSynth())
            val scriptId = firstScriptId(render(svc, text))

            val broken = listOf(segment(0, "第一句。", "只剩这一句。"))
            val events = render(svc, "", scriptId = scriptId, edited = broken)

            val failed = events.filterIsInstance<SpeechRenderEvent.Failed>().single()
            assertEquals(SpeechRenderEvent.CODE_EDIT_REJECTED, failed.code)
            assertEquals(0, failed.playedSegments)
            assertTrue(events.none { it is SpeechRenderEvent.SegmentAudio })
        }

        @Test
        fun `an edit that removes every segment is refused instead of re-rendering the plan`() {
            // "No segments" is not "no edit": re-rendering the stored plan would speak exactly what
            // the user believed they had deleted.
            val text = "第一句。"
            val planner = FakePlanner(listOf(segment(0, "第一句。")), capabilities)
            val svc = service(planner, FakeSynth())
            val scriptId = firstScriptId(render(svc, text))

            val events = render(svc, "", scriptId = scriptId, edited = emptyList())

            val failed = events.filterIsInstance<SpeechRenderEvent.Failed>().single()
            assertEquals(SpeechRenderEvent.CODE_EDIT_REJECTED, failed.code)
            assertEquals(0, failed.playedSegments)
            assertTrue(events.none { it is SpeechRenderEvent.SegmentAudio })
        }

        @Test
        fun `an edit based on a stale plan is refused instead of overwriting the newer one`() {
            // The comparison-and-set itself is staged directly here, because the pipeline's reaction
            // is what matters: the store refuses, and the caller is told rather than shown a plan it
            // did not send.
            val store = SpeechPlanStore()
            val text = "第一句。"
            store.put("alice", planFor("s-1", revision = 1, text), text)

            // The first edit, based on revision 1, wins and bumps the plan to 2.
            assertTrue(store.putIfRevision("alice", planFor("s-1", revision = 2, text), 1, text))
            // A second writer that still believes revision 1 is current must not overwrite it.
            assertFalse(store.putIfRevision("alice", planFor("s-1", revision = 3, text), 1, text))
            assertEquals(2, store.get("alice", "s-1")!!.script.revision)
        }

        @Test
        fun `another account cannot render a script it did not plan`() {
            val planner = FakePlanner(listOf(segment(0, "第一句。")), capabilities)
            val svc = service(planner, FakeSynth())
            val scriptId = firstScriptId(render(svc, "第一句。", owners = listOf("alice")))

            val error = assertFailsWith<ResponseStatusException> {
                render(svc, "", scriptId = scriptId, owners = listOf("bob"))
            }
            assertEquals(404, error.statusCode.value())
        }

        @Test
        fun `an expired or unknown script id is a 404 rather than a re-plan`() {
            val error = assertFailsWith<ResponseStatusException> {
                render(service(FakePlanner(listOf(segment(0, "第一句。")), capabilities), FakeSynth()), "", scriptId = "missing")
            }
            assertEquals(404, error.statusCode.value())
        }
    }

    @Nested
    inner class Gates {
        @Test
        fun `director mode is refused on the one-clip endpoint`() {
            val service = TtsService(resolver(), FakeSynth(), FakePlanner(emptyList(), capabilities))
            val error = assertFailsWith<ResponseStatusException> {
                runBlocking { service.speak(listOf("alice"), "你好", "director") }
            }
            assertEquals(400, error.statusCode.value())
        }

        @Test
        fun `an unconfigured tts row is a conflict before any planning`() {
            val resolver = mockk<AuxModelResolver>()
            coEvery { resolver.resolveConfig(any<Collection<String>>(), AuxModelTask.TTS) } returns null
            val planner = FakePlanner(emptyList(), capabilities)

            val error = assertFailsWith<ResponseStatusException> {
                runBlocking {
                    TtsService(resolver, FakeSynth(), planner).openRender(listOf("alice"), "你好", null)
                }
            }
            assertEquals(409, error.statusCode.value())
            assertEquals(0, planner.produceCalls)
        }

        @Test
        fun `over-long text is refused before it is planned`() {
            val planner = FakePlanner(emptyList(), capabilities)
            val error = assertFailsWith<ResponseStatusException> {
                runBlocking {
                    TtsService(resolver(), FakeSynth(), planner)
                        .openRender(listOf("alice"), "a".repeat(TtsService.MAX_CHARS + 1), null)
                }
            }
            assertEquals(413, error.statusCode.value())
            assertEquals(0, planner.produceCalls)
        }
    }

    @Nested
    inner class Planning {
        @Test
        fun `the preview plans without synthesizing and can be rendered by id`() {
            val synth = FakeSynth()
            val planner = FakePlanner(listOf(segment(0, "第一句。")), capabilities)
            val service = TtsService(resolver(), synth, planner)

            val planned = runBlocking { service.planScript(listOf("alice"), "第一句。") }
            assertNotNull(planned.script)
            assertTrue(synth.texts.isEmpty(), "previewing a script must not synthesize anything")

            val events = runBlocking {
                service
                    .renderStream(service.openRender(listOf("alice"), "", planned.script.scriptId))
                    .asFlow()
                    .toList()
            }
            assertEquals("第一句。", events.filterIsInstance<SpeechRenderEvent.SegmentAudio>().single().spokenText)
        }
    }
}
