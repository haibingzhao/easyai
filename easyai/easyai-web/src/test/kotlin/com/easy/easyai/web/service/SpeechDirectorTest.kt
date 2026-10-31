package com.easy.easyai.web.service

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.llm.AssistantMessage
import com.easy.easyai.api.llm.ChatModel
import com.easy.easyai.api.llm.ChatOptions
import com.easy.easyai.api.llm.ChatResponse
import com.easy.easyai.api.llm.Generation
import com.easy.easyai.api.llm.Prompt
import com.easy.easyai.api.model.ModelCatalogEntry
import com.easy.easyai.api.model.ModelOptions
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.ModelType
import com.easy.easyai.api.model.SpeechCapabilities
import com.easy.easyai.api.model.VoiceSpec
import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.model.aux.AuxModelResolver
import com.easy.easyai.core.model.aux.AuxModelTask
import com.easy.easyai.core.model.aux.ResolvedAuxModel
import com.easy.easyai.speech.SpeechRole
import com.easy.easyai.speech.SpeechScript
import com.easy.easyai.speech.SpeechSegment
import com.easy.easyai.speech.SpeechStyle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

class SpeechDirectorTest {

    private val ttsConfig = ModelProviderConfig(
        id = "tts-row",
        name = "TTS",
        protocol = Protocol.DASHSCOPE,
        isCustom = true,
        baseUrl = "https://ws-1.cn-beijing.maas.aliyuncs.com/api/v1",
        apiKey = "sk-test",
        modelId = "qwen-audio-3.1-tts-flash",
        modelType = ModelType.SPEECH
    )
    private val chatConfig = ModelProviderConfig(
        id = "chat-row",
        name = "Director",
        protocol = Protocol.OPENAI,
        isCustom = true,
        baseUrl = "https://chat.example/v1",
        apiKey = "sk-test",
        modelId = "qwen-flash",
        modelType = ModelType.CHAT,
        options = ModelOptions(thinking = true)
    )
    private val catalogEntry = ModelCatalogEntry(
        modelId = ttsConfig.modelId,
        vendor = "bailian",
        displayName = "TTS",
        speechCapabilities = SpeechCapabilities(
            supportsInstruction = true,
            emotionTags = listOf("[serious]"),
            defaultVoice = "voice-a",
            voices = listOf(VoiceSpec(voice = "voice-a", name = "A", instruction = true))
        ),
        source = "test"
    )

    private fun output(source: String, spoken: String, voice: String = "voice-a"): String = SharedObjectMapper.instance.writeValueAsString(
        SpeechDirectionOutput(
            mood = "克制",
            style = SpeechStyle.TEACHER,
            segments = listOf(
                SpeechDirectionSegment(
                    id = "seg-0",
                    index = 0,
                    sourceText = source,
                    spokenText = spoken,
                    voice = voice,
                    emotion = "serious",
                    styleNote = "温和讲述",
                    rate = 0.95,
                    role = SpeechRole.NARRATION,
                    pauseAfterMs = 0
                )
            )
        )
    )

    private fun response(body: String): ChatResponse {
        // The parameter must not be called `text`: a local name shadows the receiver's property and
        // `every { text }` would then answer with the String instead of recording a call.
        val assistant = mockk<AssistantMessage> { every { this@mockk.text } returns body }
        val generation = mockk<Generation> { every { output } returns assistant }
        return mockk { every { result } returns generation }
    }

    private fun resolver(chatModel: ChatModel, config: ModelProviderConfig = chatConfig): AuxModelResolver {
        val resolver = mockk<AuxModelResolver>()
        coEvery { resolver.resolve(listOf("alice"), AuxModelTask.SPEECH_DIRECTION) } returns
            ResolvedAuxModel(chatModel, config)
        return resolver
    }

    private fun catalog(): ModelCatalogService {
        val catalog = mockk<ModelCatalogService>()
        coEvery { catalog.lookup(ttsConfig.modelId) } returns catalogEntry
        return catalog
    }

    /** The script a successful refine produced; a different outcome fails loudly instead of NPE-ing. */
    private fun RefineOutcome.planned(): SpeechScript =
        (this as? RefineOutcome.Planned)?.script ?: fail("expected a plan, got $this")

    /** The refusal of a failed refine; a plan here means the test is asserting the wrong thing. */
    private fun RefineOutcome.unavailable(): RefineOutcome.Unavailable =
        this as? RefineOutcome.Unavailable ?: fail("expected a refusal, got $this")

    @Test
    fun `unconfigured director reports why without a model call`() = runBlocking {
        val resolver = mockk<AuxModelResolver>()
        coEvery { resolver.resolve(listOf("alice"), AuxModelTask.SPEECH_DIRECTION) } returns null
        assertEquals(
            SpeechPlanner.REFINE_NOT_CONFIGURED,
            SpeechDirector(resolver).quickRefine(listOf("alice"), "你好", ttsConfig, 20_000).unavailable().reason
        )
    }

    @Test
    fun `valid single segment JSON becomes a script with deterministic structured options`() = runBlocking {
        val source = "上限20。"
        val promptSlot = slot<Prompt>()
        val chatModel = mockk<ChatModel>()
        coEvery { chatModel.callAsync(capture(promptSlot)) } returns response(output(source, "上限二十。"))
        val factory = mockk<ChatModelFactory>()
        every { factory.supports(Protocol.OPENAI) } returns true
        val configSlot = slot<ModelProviderConfig>()
        val schemaSlot = slot<String>()
        val options = mockk<ChatOptions>()
        every { factory.build(capture(configSlot), emptyList(), capture(schemaSlot)) } returns options

        val script = SpeechDirector(resolver(chatModel), listOf(factory), catalog())
            .quickRefine(listOf("alice"), source, ttsConfig, 20_000)
            .planned()

        assertEquals("上限二十。", script.segments.single().spokenText)
        assertEquals(false, configSlot.captured.options?.thinking)
        assertTrue(schemaSlot.captured.contains("segments"))
        assertTrue(promptSlot.captured.options === options)
    }

    @Test
    fun `a voice copied with its display label is stored as the bare id`() = runBlocking {
        // The refined script goes straight into synthesis, so the label form would be sent to the
        // provider verbatim. Validation alone cannot fix that: it only answers "is this allowed?".
        val source = "上限20。"
        val model = mockk<ChatModel>()
        coEvery { model.callAsync(any<Prompt>()) } returns
            response(output(source, "上限二十。", voice = "voice-a｜A"))

        val script = SpeechDirector(resolver(model), catalog = catalog())
            .quickRefine(listOf("alice"), source, ttsConfig, 20_000)
            .planned()

        assertEquals("voice-a", script.segments.single().voice)
    }

    @Test
    fun `a text past the refine limit is read directly without calling the model`() = runBlocking {
        // Refine echoes the whole source back, so for a post this long the call can only end in the
        // same fallback — after a wait. Skipping it keeps the first audio at TTS latency.
        val model = mockk<ChatModel>()
        coEvery { model.callAsync(any<Prompt>()) } returns response(output("很长。", "很长。"))
        val text = "很".repeat(SpeechDirector.REFINE_MAX_CHARS + 1)

        val outcome = SpeechDirector(resolver(model), catalog = catalog())
            .quickRefine(listOf("alice"), text, ttsConfig, 20_000)

        assertEquals(SpeechPlanner.REFINE_LENGTH_LIMIT, outcome.unavailable().reason)
        coVerify(exactly = 0) { model.callAsync(any<Prompt>()) }
    }

    @Test
    fun `the refine budget grows with the text and stays inside the skip limit`() {
        assertEquals(4, SpeechDirector.refineBudgetSeconds(10))
        // 600 chars is exactly 5 s of writing at 120 chars/s — the last size the 4 s floor cannot serve.
        assertEquals(5, SpeechDirector.refineBudgetSeconds(600))
        // 679 chars is 5.7 s at this rate: floor division would hand back 5 s of a 5.7 s job.
        assertEquals(6, SpeechDirector.refineBudgetSeconds(679))
        assertEquals(7, SpeechDirector.refineBudgetSeconds(750))
        assertEquals(10, SpeechDirector.refineBudgetSeconds(SpeechDirector.REFINE_MAX_CHARS))
    }

    @Test
    fun `a timeout reports the budget it was given, not just that it failed`() = runTest {
        // The listener's notice has to say *why*: "timed out" and "no direction model is configured"
        // are fixed in different places. Virtual time, so waiting out the budget costs nothing.
        val slow = mockk<ChatModel>()
        coEvery { slow.callAsync(any<Prompt>()) } coAnswers {
            delay(30_000)
            response(output("你好", "你好。"))
        }

        val outcome = SpeechDirector(resolver(slow), catalog = catalog())
            .quickRefine(listOf("alice"), "你好", ttsConfig, 20_000)
            .unavailable()

        assertEquals(SpeechPlanner.REFINE_TIMEOUT, outcome.reason)
        // Two characters are below the floor, so the attempt was allowed the 4-second minimum.
        assertEquals(4, outcome.budgetSeconds)
        assertEquals("refine-timeout:4", outcome.code())
    }

    @Test
    fun `malformed JSON and numeric loss both fall back`() = runBlocking {
        val source = "上限20。"
        val malformed = mockk<ChatModel>()
        coEvery { malformed.callAsync(any<Prompt>()) } returns response("not json")
        assertEquals(
            SpeechPlanner.REFINE_UNAVAILABLE,
            SpeechDirector(resolver(malformed), catalog = catalog())
                .quickRefine(listOf("alice"), source, ttsConfig, 20_000)
                .unavailable().reason
        )

        val loss = mockk<ChatModel>()
        coEvery { loss.callAsync(any<Prompt>()) } returns response(output(source, "上限很高。"))
        assertEquals(
            SpeechPlanner.REFINE_REJECTED,
            SpeechDirector(resolver(loss), catalog = catalog())
                .quickRefine(listOf("alice"), source, ttsConfig, 20_000)
                .unavailable().reason
        )
    }

    @Test
    // `runBlocking<Unit>` on purpose: the last expression below is `assertFailsWith`, which returns
    // the exception, and a JUnit method that returns a value is silently skipped instead of run.
    fun `provider failures fall back but real cancellation propagates`() = runBlocking<Unit> {
        val failure = mockk<ChatModel>()
        coEvery { failure.callAsync(any<Prompt>()) } throws IllegalStateException("boom")
        assertEquals(
            SpeechPlanner.REFINE_UNAVAILABLE,
            SpeechDirector(resolver(failure), catalog = catalog())
                .quickRefine(listOf("alice"), "你好", ttsConfig, 20_000)
                .unavailable().reason
        )

        val cancelled = mockk<ChatModel>()
        coEvery { cancelled.callAsync(any<Prompt>()) } throws CancellationException("client stopped")
        assertFailsWith<CancellationException> {
            SpeechDirector(resolver(cancelled), catalog = catalog())
                .quickRefine(listOf("alice"), "你好", ttsConfig, 20_000)
        }
    }

    // ─── Incremental (P3) planning ─────────────────────────────────────────────

    /** One NDJSON line, exactly the shape [systemPromptIncremental] asks the model for. */
    private fun line(index: Int, source: String, spoken: String = source): String =
        """{"type":"segment","id":"seg_$index","index":$index,"sourceText":"$source",""" +
            """"spokenText":"$spoken","voice":"voice-a","role":"NARRATION","pauseAfterMs":0}"""

    private fun directorFor(model: ChatModel) = SpeechDirector(resolver(model), catalog = catalog())

    private suspend fun SpeechDirector.produceIncremental(
        text: String,
        firstSegmentBudgetMs: Long = 2_000,
        onSegment: suspend (SpeechSegment) -> Unit = {}
    ) = produce(
        owners = listOf("alice"),
        text = text,
        ttsConfig = ttsConfig,
        maxChars = 20_000,
        incremental = true,
        firstSegmentBudgetMs = firstSegmentBudgetMs,
        onSegment = onSegment
    )

    @Test
    fun `a streamed plan is validated per line and delivered as it arrives`() = runBlocking {
        val model = mockk<ChatModel>()
        every { model.stream(any<Prompt>()) } returns Flux.just(
            // A chunk boundary may fall anywhere, including between the meta line and the first
            // segment, so the reader buffers rather than assuming one line per chunk.
            response("""{"type":"meta","mood":"克制","style":"TEACHER"}""" + "\n" + line(0, "第一句。") + "\n"),
            response(line(1, "第二句。") + "\n")
        )
        val delivered = mutableListOf<String>()
        val summary = directorFor(model).produceIncremental("第一句。第二句。") { delivered += it.spokenText }

        assertEquals(listOf("第一句。", "第二句。"), delivered)
        assertEquals(2, summary.segments)
        assertEquals("克制", summary.mood)
        assertEquals(SpeechStyle.TEACHER, summary.style)
    }

    @Test
    fun `several objects on one line are all kept`() = runBlocking {
        // A model that ignores "one object per line" would otherwise have every object after the first
        // dropped, and the coverage gate would then reject the whole plan.
        val model = mockk<ChatModel>()
        every { model.stream(any<Prompt>()) } returns Flux.just(
            response(line(0, "第一句。") + line(1, "第二句。") + "\n")
        )
        val delivered = mutableListOf<String>()
        directorFor(model).produceIncremental("第一句。第二句。") { delivered += it.spokenText }

        assertEquals(listOf("第一句。", "第二句。"), delivered)
    }

    @Test
    fun `a plan that stops before the end of the source is refused, not silently truncated`() = runBlocking {
        val model = mockk<ChatModel>()
        every { model.stream(any<Prompt>()) } returns Flux.just(response(line(0, "第一句。") + "\n"))

        val error = assertFailsWith<SpeechPlanException> {
            directorFor(model).produceIncremental("第一句。第二句。")
        }
        assertEquals("COVERAGE", error.code)
    }

    @Test
    fun `a model that never answers is cut off by the first segment budget`() = runBlocking {
        // The budget exists so the caller can fall back to a plain read inside the first-audio window
        // instead of showing a spinner for as long as the model feels like taking.
        val model = mockk<ChatModel>()
        every { model.stream(any<Prompt>()) } returns Flux.never()

        val startedAt = System.nanoTime()
        val error = assertFailsWith<SpeechPlanException> {
            directorFor(model).produceIncremental("第一句。", firstSegmentBudgetMs = 100)
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertEquals(SpeechPlanner.CODE_TIMEOUT, error.code)
        assertTrue(elapsedMs < 1_000, "the budget did not cut the wait short (${elapsedMs}ms)")
    }

    @Test
    fun `a slow consumer does not block reading the model stream`() = runBlocking {
        // Back-pressure from the renderer is not the model being slow: reading and handling run in
        // separate coroutines, so the next chunk must arrive while `onSegment` is still parked. A
        // single-coroutine implementation would request it only after the handler returns, and this
        // test would time out instead of passing.
        val secondChunkEmitted = CompletableDeferred<Unit>()
        val model = mockk<ChatModel>()
        every { model.stream(any<Prompt>()) } returns Flux.concat(
            Flux.just(response(line(0, "第一句。") + "\n")),
            Mono.fromCallable {
                secondChunkEmitted.complete(Unit)
                response(line(1, "第二句。") + "\n")
            }.flux()
        )
        val releaseFirst = CompletableDeferred<Unit>()
        val delivered = mutableListOf<String>()
        val plan = async {
            directorFor(model).produceIncremental("第一句。第二句。") {
                delivered += it.spokenText
                if (delivered.size == 1) releaseFirst.await()
            }
        }

        withTimeout(5_000) { secondChunkEmitted.await() }
        releaseFirst.complete(Unit)
        plan.await()

        assertEquals(listOf("第一句。", "第二句。"), delivered)
    }
}
