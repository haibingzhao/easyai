package com.easy.easyai.web.service

import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.ModelType
import com.easy.easyai.api.model.SpeechCapabilities
import com.easy.easyai.api.model.VoiceSpec
import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.media.MediaProviderSettings
import com.easy.easyai.core.model.aux.AuxModelResolver
import com.easy.easyai.core.model.aux.AuxModelTask
import com.easy.easyai.speech.ResolvedSpeechCapabilities
import com.easy.easyai.speech.SpeechAudio
import com.easy.easyai.speech.SpeechCapabilityResolver
import com.easy.easyai.speech.SpeechRenderEvent
import com.easy.easyai.speech.SpeechScript
import com.easy.easyai.speech.SpeechScriptGenerationStatus
import com.easy.easyai.speech.SpeechScriptQuery
import com.easy.easyai.speech.SpeechScriptRecord
import com.easy.easyai.speech.SpeechScriptSort
import com.easy.easyai.speech.SpeechScriptStatus
import com.easy.easyai.speech.SpeechScriptStore
import com.easy.easyai.speech.SpeechScriptSummary
import com.easy.easyai.speech.SpeechSegment
import com.easy.easyai.speech.SpeechStyle
import com.easy.easyai.speech.SpeechSynthesizer
import com.easy.easyai.speech.SpeechTone
import com.easy.easyai.speech.SpokenTextNormalizer
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reuse contract of the script archive.
 *
 * What must hold: a second preview of the same text costs no planning call, a changed text is never
 * served from the old script, and the snapshot records the model without ever recording its key.
 */
class TtsScriptReuseTest {

    private companion object {
        /** The archive's own retention window, restated here so the test fails if it changes. */
        const val NINETY_DAYS_MS = 90L * 24 * 60 * 60 * 1000
    }

    private fun config() = ModelProviderConfig(
        id = "tts-row",
        name = "Qwen TTS",
        protocol = Protocol.DASHSCOPE,
        isCustom = true,
        baseUrl = "https://ws-1.cn-beijing.maas.aliyuncs.com/api/v1",
        apiKey = "sk-secret-value",
        modelId = "qwen-audio-3.1-tts-flash",
        modelType = ModelType.SPEECH,
        mediaOptions = """{"voice":"voice-a","format":"wav","word_timestamp_enabled":true}"""
    )

    private class FakePlanner(
        private val capabilities: ResolvedSpeechCapabilities,
        private val splitIntoSentences: Boolean = false,
        private val refinedText: String? = null,
        private val afterFirstSegment: suspend () -> Unit = {},
        /** What a refine reports when [refinedText] is null; tests pick the cause they assert on. */
        private val refineFault: RefineOutcome.Unavailable =
            RefineOutcome.Unavailable(SpeechPlanner.REFINE_UNAVAILABLE)
    ) : SpeechPlanner {

        var produceCalls = 0
            private set

        var quickRefineCalls = 0
            private set

        /** The tone of the most recent planning call, so the tests can prove it was threaded through. */
        var lastTone: SpeechTone? = null
            private set

        override suspend fun capabilitiesFor(ttsConfig: ModelProviderConfig) = capabilities

        override suspend fun quickRefine(
            owners: Collection<String>,
            text: String,
            ttsConfig: ModelProviderConfig,
            maxChars: Int,
            tone: SpeechTone
        ): RefineOutcome {
            quickRefineCalls++
            lastTone = tone
            // Match SpeechDirector's production guard: long input is a deliberate plain fallback.
            if (text.length > SpeechDirector.REFINE_MAX_CHARS) {
                return RefineOutcome.Unavailable(SpeechPlanner.REFINE_LENGTH_LIMIT)
            }
            val script = refinedText?.let { spoken ->
                SpeechScript(
                    scriptId = "planner-id",
                    sourceDigest = SpokenTextNormalizer.sourceDigest(text),
                    mood = "克制",
                    style = SpeechStyle.TEACHER,
                    segments = listOf(
                        SpeechSegment(
                            id = "seg_0",
                            index = 0,
                            sourceText = text,
                            spokenText = spoken,
                            voice = "voice-a"
                        )
                    )
                )
            } ?: return refineFault
            return RefineOutcome.Planned(script)
        }

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
            lastTone = tone
            val segments = if (splitIntoSentences) {
                text.split('。').filter { it.isNotBlank() }.mapIndexed { index, sentence ->
                    val source = "$sentence。"
                    SpeechSegment(
                        id = "seg_$index",
                        index = index,
                        sourceText = source,
                        spokenText = source,
                        voice = "voice-a"
                    )
                }
            } else {
                listOf(
                    SpeechSegment(
                        id = "seg_0",
                        index = 0,
                        sourceText = text,
                        spokenText = text,
                        voice = "voice-a"
                    )
                )
            }
            segments.forEachIndexed { index, segment ->
                onSegment(segment)
                if (index == 0 && produceCalls == 1) afterFirstSegment()
            }
            return SpeechPlanner.PlanSummary("plan-$produceCalls", 1, "克制", SpeechStyle.TEACHER, segments.size)
        }
    }

    private class SilentSynth : SpeechSynthesizer {
        override fun supports(providerType: String): Boolean = true

        override suspend fun synthesize(
            settings: MediaProviderSettings,
            text: String,
            voice: String?,
            format: String?,
            plainTextOnly: Boolean
        ): SpeechAudio = SpeechAudio(byteArrayOf(0), "audio/mpeg", emptyList())
    }

    /** Enough of the store to observe the archive's decisions; no database involved. */
    private class MemoryStore : SpeechScriptStore {
        val rows = LinkedHashMap<String, SpeechScriptRecord>()

        override suspend fun saveAuto(record: SpeechScriptRecord): SpeechScriptRecord {
            beforeAutoSave()
            val existing = rows.values.firstOrNull {
                it.owner == record.owner && it.targetType == record.targetType &&
                    it.targetId == record.targetId && it.mode == record.mode &&
                    it.status == SpeechScriptStatus.AUTO
            }
            // Same contract as the SQL update: the body is replaced, the row's identity and the
            // metadata outside `writeBody` (favourite, play counter) are kept.
            val stored = if (existing == null) {
                record
            } else {
                record.copy(
                    id = existing.id,
                    isFavorite = existing.isFavorite,
                    playCount = existing.playCount,
                    lastPlayedAt = existing.lastPlayedAt,
                    createdAt = existing.createdAt
                )
            }
            rows[stored.id] = stored
            afterAutoWrite(stored)
            return stored
        }

        override suspend fun saveAbandoned(record: SpeechScriptRecord): Boolean {
            val existing = rows[record.id]
            val applied = existing != null &&
                existing.owner == record.owner &&
                existing.status == SpeechScriptStatus.AUTO &&
                existing.generationStatus == SpeechScriptGenerationStatus.IN_PROGRESS
            if (applied && existing != null) {
                rows[record.id] = record.copy(
                    isFavorite = existing.isFavorite,
                    playCount = existing.playCount,
                    lastPlayedAt = existing.lastPlayedAt,
                    createdAt = existing.createdAt
                )
            }
            afterAutoWrite(record)
            return applied
        }

        override suspend fun savePinned(record: SpeechScriptRecord): SpeechScriptRecord {
            // The SQL store inserts: reusing an existing id is a primary-key violation.
            check(!rows.containsKey(record.id)) { "duplicate speech script id ${record.id}" }
            rows[record.id] = record
            return record
        }

        override suspend fun findAutoId(
            owner: String,
            targetType: String,
            targetId: String?,
            mode: String
        ): String? =
            rows.values
                .filter {
                    it.owner == owner && it.targetType == targetType && it.targetId == targetId &&
                        it.mode == mode && it.status == SpeechScriptStatus.AUTO
                }
                .maxByOrNull { it.updatedAt }
                ?.id

        override suspend fun findReusable(
            owner: String,
            targetType: String,
            targetId: String?,
            mode: String,
            digest: String
        ): SpeechScriptRecord? = rows.values
            .filter {
                it.owner == owner && it.targetType == targetType && it.targetId == targetId &&
                    it.mode == mode && it.sourceDigest == digest &&
                    it.status != SpeechScriptStatus.ARCHIVED &&
                    it.generationStatus == SpeechScriptGenerationStatus.COMPLETED
            }
            .maxByOrNull { it.updatedAt }

        override suspend fun get(owner: String, id: String): SpeechScriptRecord? =
            rows[id]?.takeIf { it.owner == owner }

        override suspend fun list(owner: String, query: SpeechScriptQuery): List<SpeechScriptSummary> =
            rows.values
                .filter { it.owner == owner && it.status != SpeechScriptStatus.ARCHIVED }
                .sortedByDescending { record -> record.updatedAt }
                .take(query.limit)
                .map { record ->
                    SpeechScriptSummary(
                        id = record.id,
                        title = record.title,
                        targetType = record.targetType,
                        targetId = record.targetId,
                        sessionId = record.sessionId,
                        mode = record.mode,
                        status = record.status,
                        revision = record.revision,
                        segmentCount = record.segmentCount,
                        sourceChars = record.sourceChars,
                        spokenChars = record.spokenChars,
                        style = record.script.style.name,
                        mood = record.script.mood,
                        isFavorite = record.isFavorite,
                        playCount = record.playCount,
                        lastPlayedAt = record.lastPlayedAt,
                        createdAt = record.createdAt,
                        updatedAt = record.updatedAt
                    )
                }

        override suspend fun update(record: SpeechScriptRecord, expectedRevision: Int): Boolean {
            val current = rows[record.id] ?: return false
            if (current.revision != expectedRevision) return false
            rows[record.id] = record
            return true
        }

        override suspend fun archive(owner: String, id: String): Boolean {
            val current = rows[id]?.takeIf { it.owner == owner } ?: return false
            rows[id] = current.copy(status = SpeechScriptStatus.ARCHIVED)
            return true
        }

        override suspend fun touchPlayed(owner: String, id: String, at: Long) {
            val current = rows[id]?.takeIf { it.owner == owner } ?: return
            rows[id] = current.copy(playCount = current.playCount + 1, lastPlayedAt = at)
        }

        override suspend fun purgeAutoBefore(owner: String, before: Long): Int {
            purgeCalls++
            lastPurgeBefore = before
            var archived = 0
            for (entry in rows.entries) {
                val record = entry.value
                if (record.owner != owner || record.status != SpeechScriptStatus.AUTO) continue
                if (record.isFavorite || record.updatedAt >= before) continue
                entry.setValue(record.copy(status = SpeechScriptStatus.ARCHIVED))
                archived++
            }
            return archived
        }

        var purgeCalls = 0
            private set
        var lastPurgeBefore: Long? = null
            private set
        var beforeAutoSave: suspend () -> Unit = {}
        /** Fires after an auto-save, and after every abandon attempt, applied or not. */
        var afterAutoWrite: suspend (SpeechScriptRecord) -> Unit = {}

        override suspend fun archiveForTarget(targetType: String, targetId: String): Int =
            archiveMatching { it.targetType == targetType && it.targetId == targetId }

        override suspend fun archiveForSession(sessionId: String): Int =
            archiveMatching { it.sessionId == sessionId }

        /** Same rule as the SQL: already-archived rows are not counted twice. */
        private fun archiveMatching(predicate: (SpeechScriptRecord) -> Boolean): Int {
            var archived = 0
            for (entry in rows.entries) {
                val record = entry.value
                if (record.status == SpeechScriptStatus.ARCHIVED || !predicate(record)) continue
                entry.setValue(record.copy(status = SpeechScriptStatus.ARCHIVED))
                archived++
            }
            return archived
        }
    }

    private class Harness(
        splitIntoSentences: Boolean = false,
        refinedText: String? = null,
        afterFirstSegment: suspend () -> Unit = {},
        refineFault: RefineOutcome.Unavailable = RefineOutcome.Unavailable(SpeechPlanner.REFINE_UNAVAILABLE)
    ) {
        val store = MemoryStore()
        val planner: FakePlanner
        val service: TtsService
        val archive: SpeechScriptArchiveService
        private val resolver = mockk<AuxModelResolver>()

        init {
            val capabilities = SpeechCapabilityResolver().resolve(
                SpeechCapabilities(
                    defaultVoice = "voice-a",
                    voices = listOf(VoiceSpec(voice = "voice-a", name = "A"))
                )
            )
            planner = FakePlanner(capabilities, splitIntoSentences, refinedText, afterFirstSegment, refineFault)
            coEvery { resolver.resolveConfig(any<Collection<String>>(), AuxModelTask.TTS) } returns ModelProviderConfig(
                id = "tts-row",
                name = "Qwen TTS",
                protocol = Protocol.DASHSCOPE,
                isCustom = true,
                baseUrl = "https://ws-1.cn-beijing.maas.aliyuncs.com/api/v1",
                apiKey = "sk-secret-value",
                modelId = "qwen-audio-3.1-tts-flash",
                modelType = ModelType.SPEECH,
                mediaOptions = """{"voice":"voice-a","format":"wav","word_timestamp_enabled":true}"""
            )
            coEvery { resolver.resolve(any<Collection<String>>(), AuxModelTask.SPEECH_DIRECTION) } returns null
            archive = SpeechScriptArchiveService(store = store, auxModelResolver = resolver)
            service = TtsService(
                auxModelResolver = resolver,
                synthesizer = SilentSynth(),
                speechDirector = planner,
                planStore = SpeechPlanStore(),
                archive = archive
            )
        }

        /** A restarted process: the same archive, but nothing left in the in-memory plan store. */
        fun serviceWithFreshPlans(): TtsService = TtsService(
            auxModelResolver = resolver,
            synthesizer = SilentSynth(),
            speechDirector = planner,
            planStore = SpeechPlanStore(),
            archive = archive
        )
    }

    private fun target() = SpeechTarget(
        type = "session_message",
        id = "msg-1",
        sessionId = "sess-1"
    )

    @Nested
    inner class `Reuse` {

        @Test
        fun `a second preview of the same reply costs no planning call`() = runTest {
            val harness = Harness()
            val text = "今天杭州有雨，出门记得带伞。"

            val first = harness.service.planScript(listOf("alice"), text, target())
            val second = harness.service.planScript(listOf("alice"), text, target())

            assertFalse(first.reused)
            assertTrue(second.reused)
            assertEquals(1, harness.planner.produceCalls)
            assertEquals(first.script.scriptId, second.script.scriptId)
            assertEquals(1, harness.store.rows.size)
        }

        @Test
        fun `a changed reply is never served from the old script`() = runTest {
            val harness = Harness()

            harness.service.planScript(listOf("alice"), "第一版回复。", target())
            val second = harness.service.planScript(listOf("alice"), "第二版回复，内容不同。", target())

            assertFalse(second.reused)
            assertEquals(2, harness.planner.produceCalls)
        }

        @Test
        fun `legacy incomplete cancelled and failed rows are planned again`() = runTest {
            val statuses = listOf<SpeechScriptGenerationStatus?>(
                null,
                SpeechScriptGenerationStatus.IN_PROGRESS,
                SpeechScriptGenerationStatus.CANCELLED,
                SpeechScriptGenerationStatus.FAILED
            )

            for (status in statuses) {
                val harness = Harness()
                val text = "只有完整剧本才能复用。"
                harness.service.planScript(listOf("alice"), text, target())
                val row = harness.store.rows.values.single()
                harness.store.rows[row.id] = row.copy(generationStatus = status)

                val second = harness.serviceWithFreshPlans().planScript(listOf("alice"), text, target())

                assertFalse(second.reused, "generation status $status must not be reused")
                assertEquals(2, harness.planner.produceCalls, "generation status $status must plan again")
            }
        }

        @Test
        fun `manual text never shares a script with a message`() = runTest {
            val harness = Harness()
            val text = "同一段文字。"

            harness.service.planScript(listOf("alice"), text, target())
            val manual = harness.service.planScript(listOf("alice"), text, SpeechTarget.manual())

            assertFalse(manual.reused)
            assertEquals(2, harness.planner.produceCalls)
        }

        @Test
        fun `a refined read and a directed plan of one reply keep two rows`() = runTest {
            val harness = Harness(refinedText = "润色后的朗读文本。")
            val text = "今天杭州有雨，出门记得带伞。"

            harness.service.speak(listOf("alice"), text, "refine", target())
            val planned = harness.service.planScript(listOf("alice"), text, target())

            // The mode is part of the archive's identity: the refined read must survive as its own
            // row instead of being relabelled Directed by the plan that follows it.
            assertEquals(2, harness.store.rows.size)
            assertFalse(planned.reused, "a directed plan must not be answered with the refined read")
            assertEquals(1, harness.planner.quickRefineCalls)
            assertEquals(1, harness.planner.produceCalls)
            assertEquals("director", harness.store.rows.getValue(planned.script.scriptId).mode)
            assertEquals(setOf("refine", "director"), harness.store.rows.values.map { it.mode }.toSet())
        }

        @Test
        fun `a directed plan does not satisfy a later refined read`() = runTest {
            val harness = Harness(refinedText = "润色后的朗读文本。")
            val text = "今天杭州有雨，出门记得带伞。"

            harness.service.planScript(listOf("alice"), text, target())
            harness.service.speak(listOf("alice"), text, "refine", target())

            // A one-segment director plan is a legal plan, so only the mode filter keeps it from
            // being played back as "the refined version" the user just asked for.
            assertEquals(2, harness.store.rows.size)
            assertEquals(1, harness.planner.quickRefineCalls)
            assertEquals(1, harness.planner.produceCalls)
        }
    }

    @Nested
    inner class `One clip archiving` {

        @Test
        fun `a refined read saves its single segment under the message target`() = runTest {
            val harness = Harness(refinedText = "润色后的朗读文本。")

            harness.service.speak(listOf("alice"), "原始回复文本。", "refine", target())

            val record = assertNotNull(harness.store.rows.values.singleOrNull())
            assertEquals("refine", record.mode)
            assertEquals("session_message", record.targetType)
            assertEquals("msg-1", record.targetId)
            assertEquals("sess-1", record.sessionId)
            assertEquals("原始回复文本。", record.sourceText)
            assertEquals("润色后的朗读文本。", record.script.segments.single().spokenText)
            assertEquals(1, record.playCount)
        }

        @Test
        fun `a second refined read reuses the stored script without refining again`() = runTest {
            val harness = Harness(refinedText = "润色后的朗读文本。")

            harness.service.speak(listOf("alice"), "原始回复文本。", "refine", target())
            val restarted = harness.serviceWithFreshPlans()
            restarted.speak(listOf("alice"), "原始回复文本。", "refine", target())

            assertEquals(1, harness.planner.quickRefineCalls)
            assertEquals(1, harness.store.rows.size)
            assertEquals(2, harness.store.rows.values.single().playCount)
            assertEquals("润色后的朗读文本。", harness.store.rows.values.single().script.segments.single().spokenText)
        }

        @Test
        fun `a direct read is not archived`() = runTest {
            val harness = Harness(refinedText = "直读不会使用这段润色文本。")

            harness.service.speak(listOf("alice"), "原始回复文本。", "direct", target())

            assertTrue(harness.store.rows.isEmpty())
        }

        @Test
        fun `a refine fallback archives the original text and reports why`() = runTest {
            val harness = Harness(refinedText = null)

            val prepared = harness.service.openSpeak(listOf("alice"), "原始回复文本。", "refine", target())
            harness.service.speakStream(prepared).asFlow().toList()

            assertEquals(TtsService.FALLBACK_UNAVAILABLE, prepared.fallbackReason)
            val record = assertNotNull(harness.store.rows.values.singleOrNull())
            assertEquals("refine", record.mode)
            assertEquals("原始回复文本。", record.sourceText)
            assertEquals("原始回复文本。", record.script.segments.single().spokenText)
            assertEquals(TtsService.FALLBACK_UNAVAILABLE, record.script.warnings.single().code)
        }

        @Test
        fun `a refine timeout reports the budget it was given, on the play and on the replay`() = runTest {
            // "Unavailable" is the same sentence for a timeout, a missing model and a rejected
            // rewrite, and each is fixed somewhere else. The reason — with the seconds it was
            // allowed — reaches the client, and the archived fallback remembers it for the next play.
            val harness = Harness(
                refinedText = null,
                refineFault = RefineOutcome.Unavailable(SpeechPlanner.REFINE_TIMEOUT, 5)
            )

            val prepared = harness.service.openSpeak(listOf("alice"), "原始回复文本。", "refine", target())
            harness.service.speakStream(prepared).asFlow().toList()

            assertEquals("refine-timeout:5", prepared.fallbackReason)
            assertEquals("refine-timeout:5", harness.store.rows.values.single().script.warnings.single().code)

            val replayed = harness.serviceWithFreshPlans()
                .openSpeak(listOf("alice"), "原始回复文本。", "refine", target())

            assertEquals("refine-timeout:5", replayed.fallbackReason)
            assertEquals(1, harness.planner.quickRefineCalls)
        }

        @Test
        fun `an over-limit refine archives and reports the plain read instead of disappearing`() = runTest {
            val harness = Harness(refinedText = "这段润色不应被使用")
            val text = "长".repeat(SpeechDirector.REFINE_MAX_CHARS + 1)

            val prepared = harness.service.openSpeak(listOf("alice"), text, "refine", target())
            harness.service.speakStream(prepared).asFlow().toList()

            assertEquals(TtsService.FALLBACK_LENGTH_LIMIT, prepared.fallbackReason)
            val record = assertNotNull(harness.store.rows.values.singleOrNull())
            assertEquals("refine", record.mode)
            assertEquals(text, record.sourceText)
            assertEquals(text, record.script.segments.single().spokenText)
            assertEquals(TtsService.FALLBACK_LENGTH_LIMIT, record.script.warnings.single().code)
        }
    }

    @Nested
    inner class `Snapshot` {

        @Test
        fun `the snapshot records the model but never its key`() = runTest {
            val harness = Harness()
            harness.service.planScript(listOf("alice"), "只有一段话。", target())

            val record = assertNotNull(harness.store.rows.values.firstOrNull())
            val json = SharedObjectMapper.instance.writeValueAsString(record.ttsParams)

            assertFalse(json.contains("sk-secret-value"), "an API key must never reach a script row")
            assertFalse(json.contains("/api/v1"), "the full base URL is not needed; the host is")
            assertTrue(
                json.contains("ws-1.cn-beijing.maas.aliyuncs.com"),
                "the host is what explains a provider change"
            )
            assertEquals("mp3", record.ttsParams.synthesis.format)
            assertEquals("PLAIN_TEXT", record.ttsParams.synthesis.inputMode)
            assertEquals("voice-a", record.ttsParams.rowDefaults?.voice)
            assertEquals(true, record.ttsParams.rowDefaults?.wordTimestampEnabled)
            assertEquals(1, record.ttsParams.perSegment.size)
            assertEquals(record.sourceText.length, record.ttsParams.cost.characters)
        }

        @Test
        fun `the stored row carries the target it was filed under`() = runTest {
            val harness = Harness()
            harness.service.planScript(listOf("alice"), "目标关联。", target())

            val record = assertNotNull(harness.store.rows.values.firstOrNull())
            assertEquals("session_message", record.targetType)
            assertEquals("msg-1", record.targetId)
            assertEquals("sess-1", record.sessionId)
            assertEquals(SpeechScriptStatus.AUTO, record.status)
            assertEquals(record.sourceText, "目标关联。")
            assertEquals(SpokenTextNormalizer.sourceDigest("目标关联。"), record.sourceDigest)
        }

        @Test
        fun `titles are derived from the text, not from a model call`() {
            val archive = SpeechScriptArchiveService(store = null)

            assertEquals("今天杭州有雨，出门记得带伞", archive.titleOf("今天杭州有雨，出门记得带伞。"))
            assertTrue(archive.titleOf("**加粗** 开头的一段").startsWith("加粗"))
            assertEquals("Read-aloud script", archive.titleOf("   "))
            assertTrue(archive.titleOf("很长".repeat(60)).length <= 60)
        }
    }

    @Nested
    inner class `Lifecycle` {

        @Test
        fun `a save expires what is older than ninety days`() = runTest {
            val harness = Harness()

            harness.service.planScript(listOf("alice"), "第一次朗读。", target())

            assertEquals(1, harness.store.purgeCalls)
            val cutoff = assertNotNull(harness.store.lastPurgeBefore)
            val age = System.currentTimeMillis() - cutoff
            // The exact millisecond is nobody's business; "ninety days back" is the contract.
            assertTrue(
                kotlin.math.abs(age - NINETY_DAYS_MS) < 60_000,
                "expected a cutoff ninety days back, got $age ms"
            )
        }

        @Test
        fun `a second save in the same hour does not sweep again`() = runTest {
            val harness = Harness()

            harness.service.planScript(listOf("alice"), "一稿。", target())
            harness.service.planScript(listOf("alice"), "二稿。", SpeechTarget("session_message", "msg-2", "sess-1"))

            // One sweep per hour per process: a sweep per saved script would tax every read-aloud.
            assertEquals(1, harness.store.purgeCalls)
        }

        @Test
        fun `an automatic save commits when its caller is cancelled`() = runTest {
            val harness = Harness()
            val saveStarted = CompletableDeferred<Unit>()
            val releaseSave = CompletableDeferred<Unit>()
            harness.store.beforeAutoSave = {
                saveStarted.complete(Unit)
                releaseSave.await()
                // R2DBC observes the caller's job in the same way: a cancelled streaming request
                // must not roll back the small bookkeeping transaction at the end of playback.
                currentCoroutineContext().ensureActive()
            }

            val job = launch {
                harness.archive.saveAuto(captureOf("alice"), "新稿。", scriptOf("fresh"), incremental = false)
            }
            saveStarted.await()
            job.cancel()
            releaseSave.complete(Unit)
            job.join()

            assertEquals(1, harness.store.rows.size)
        }

        @Test
        fun `a sweep archives the expired automatic script and keeps the rest`() = runTest {
            val harness = Harness()
            harness.service.planScript(listOf("alice"), "旧稿。", target())
            val stale = assertNotNull(harness.store.rows.values.firstOrNull())
            harness.store.rows[stale.id] =
                stale.copy(updatedAt = System.currentTimeMillis() - NINETY_DAYS_MS - 1_000)

            // A fresh archive over the same store: its throttle is independent, so this save sweeps.
            val second = SpeechScriptArchiveService(store = harness.store, auxModelResolver = null)
            second.saveAuto(captureOf("alice"), "新稿。", scriptOf("fresh"), incremental = false)

            assertEquals(SpeechScriptStatus.ARCHIVED, harness.store.rows[stale.id]?.status)
        }

        private fun captureOf(owner: String) = SpeechCapture(
            owner = owner,
            owners = listOf(owner),
            target = SpeechTarget("manual_text"),
            mode = "director",
            modelConfig = config(),
            settings = MediaProviderSettings(),
            format = "mp3",
            mimeType = "audio/mpeg",
            sampleRate = 24_000,
            maxChars = 20_000
        )

        private fun scriptOf(id: String) = SpeechScript(
            scriptId = id,
            sourceDigest = SpokenTextNormalizer.sourceDigest("新稿。"),
            mood = "平静",
            style = SpeechStyle.TEACHER,
            segments = listOf(
                SpeechSegment(id = "seg_0", index = 0, sourceText = "新稿。", spokenText = "新稿。", voice = "voice-a")
            )
        )
    }

    @Nested
    inner class `Rendering` {

        @Test
        fun `a fresh render is archived as soon as its plan is announced`() = runTest {
            val harness = Harness(splitIntoSentences = true)
            val text = "第一句。第二句。第三句。"

            // Stop after Meta, before the first audio clip is consumed. Opening the library while
            // a read is still running must already show the plan instead of an empty page.
            val events = harness.service.renderStream(
                harness.service.openRender(listOf("alice"), text, null, target()),
                incremental = true
            ).asFlow().take(1).toList()

            assertEquals(1, events.filterIsInstance<SpeechRenderEvent.Meta>().size)
            val record = assertNotNull(harness.store.rows.values.singleOrNull())
            assertEquals(text, record.sourceText)
            assertTrue(record.script.segments.isNotEmpty())
        }

        @Test
        fun `cancelling an incremental plan forces the next read to direct again`() = runTest {
            val harness = Harness(splitIntoSentences = true, afterFirstSegment = { awaitCancellation() })
            val cancelled = CompletableDeferred<Unit>()
            harness.store.afterAutoWrite = { record ->
                if (record.generationStatus == SpeechScriptGenerationStatus.CANCELLED) cancelled.complete(Unit)
            }
            val text = "第一句。第二句。第三句。"

            harness.service.renderStream(
                harness.service.openRender(listOf("alice"), text, null, target()),
                incremental = true
            ).asFlow().take(1).toList()
            cancelled.await()
            assertEquals(
                SpeechScriptGenerationStatus.CANCELLED,
                harness.store.rows.values.single().generationStatus
            )

            val restarted = harness.serviceWithFreshPlans()
            restarted.renderStream(
                restarted.openRender(listOf("alice"), text, null, target()),
                incremental = false
            ).asFlow().toList()

            assertEquals(2, harness.planner.produceCalls)
            assertEquals(
                SpeechScriptGenerationStatus.COMPLETED,
                harness.store.rows.values.single().generationStatus
            )
        }

        @Test
        fun `stopping a read never overwrites a plan completed by another request`() = runTest {
            val harness = Harness(splitIntoSentences = false, afterFirstSegment = { awaitCancellation() })
            val abandoned = CompletableDeferred<Unit>()
            val text = "第一句。第二句。第三句。"
            // The script panel plans the same reply without taking the playback slot, so by the time
            // the reader stops this render the row already belongs to that complete plan.
            harness.store.afterAutoWrite = { record ->
                when (record.generationStatus) {
                    SpeechScriptGenerationStatus.IN_PROGRESS ->
                        harness.store.rows[record.id] = record.copy(
                            generationStatus = SpeechScriptGenerationStatus.COMPLETED,
                            script = record.script.copy(
                                segments = listOf(
                                    SpeechSegment(
                                        id = "seg_panel",
                                        index = 0,
                                        sourceText = text,
                                        spokenText = text,
                                        voice = "voice-a"
                                    )
                                )
                            )
                        )
                    SpeechScriptGenerationStatus.CANCELLED -> abandoned.complete(Unit)
                    else -> Unit
                }
            }

            harness.service.renderStream(
                harness.service.openRender(listOf("alice"), text, null, target()),
                incremental = true
            ).asFlow().take(1).toList()
            abandoned.await()

            val row = harness.store.rows.values.single()
            assertEquals(SpeechScriptGenerationStatus.COMPLETED, row.generationStatus)
            assertEquals("seg_panel", row.script.segments.single().id)
            val second = harness.serviceWithFreshPlans().planScript(listOf("alice"), text, target())
            assertTrue(second.reused)
            assertEquals(1, harness.planner.produceCalls)
        }

        @Test
        fun `a director failure after the first segment is marked failed and retried`() = runTest {
            val harness = Harness(
                splitIntoSentences = true,
                afterFirstSegment = { throw IllegalStateException("director failed") }
            )
            val text = "第一句。第二句。第三句。"

            harness.service.renderStream(
                harness.service.openRender(listOf("alice"), text, null, target()),
                incremental = true
            ).asFlow().toList()
            assertEquals(
                SpeechScriptGenerationStatus.FAILED,
                harness.store.rows.values.single().generationStatus
            )

            val restarted = harness.serviceWithFreshPlans()
            restarted.renderStream(
                restarted.openRender(listOf("alice"), text, null, target()),
                incremental = false
            ).asFlow().toList()

            assertEquals(2, harness.planner.produceCalls)
            assertEquals(
                SpeechScriptGenerationStatus.COMPLETED,
                harness.store.rows.values.single().generationStatus
            )
        }

        @Test
        fun `rendering a cancelled archive id regenerates from its stored source`() = runTest {
            val harness = Harness()
            val text = "取消后按存档编号重新导演。"
            val planned = harness.service.planScript(listOf("alice"), text, target())
            val cancelled = harness.store.rows.values.single()
            harness.store.rows[cancelled.id] = cancelled.copy(
                generationStatus = SpeechScriptGenerationStatus.CANCELLED
            )

            // Keep the old COMPLETED plan in the hot cache on purpose: the durable CANCELLED state
            // must win over that stale entry, not only over an empty cache after a restart.
            harness.service.renderStream(
                harness.service.openRender(listOf("alice"), "", planned.script.scriptId, SpeechTarget.manual()),
                incremental = false
            ).asFlow().toList()

            assertEquals(2, harness.planner.produceCalls)
            val regenerated = harness.store.rows.values.single()
            assertEquals(text, regenerated.sourceText)
            assertEquals("session_message", regenerated.targetType)
            assertEquals(SpeechScriptGenerationStatus.COMPLETED, regenerated.generationStatus)
        }

        @Test
        fun `stopping playback after planning keeps the complete script reusable`() = runTest {
            val harness = Harness(splitIntoSentences = true)
            val text = "第一句。第二句。第三句。"

            // Whole-plan mode completes the director before Meta and the first clip. Cancelling
            // after that clip is a playback stop, not a cancellation of script generation.
            harness.service.renderStream(
                harness.service.openRender(listOf("alice"), text, null, target()),
                incremental = false
            ).asFlow().take(2).toList()
            val stopped = harness.store.rows.values.single()
            assertEquals(SpeechScriptGenerationStatus.COMPLETED, stopped.generationStatus)
            assertEquals(3, stopped.script.segments.size)

            val restarted = harness.serviceWithFreshPlans()
            val second = restarted.renderStream(
                restarted.openRender(listOf("alice"), text, null, target()),
                incremental = false
            ).asFlow().toList()

            assertEquals(1, harness.planner.produceCalls)
            assertEquals(3, second.filterIsInstance<SpeechRenderEvent.Meta>().single().segmentCount)
            assertEquals(3, harness.store.rows.values.single().script.segments.size)
        }

        @Test
        fun `a second render reuses the archived script without directing again`() = runTest {
            val harness = Harness()
            val text = "同一段朗读文本。"

            // The speaker button does not preview first. Even with an empty in-memory plan store,
            // the second click must load the archived script rather than paying for a new direction.
            val first = harness.service.renderStream(
                harness.service.openRender(listOf("alice"), text, null),
                incremental = false
            ).asFlow().toList()
            val restarted = harness.serviceWithFreshPlans()
            val second = restarted.renderStream(
                restarted.openRender(listOf("alice"), text, null),
                incremental = false
            ).asFlow().toList()

            val firstId = first.filterIsInstance<SpeechRenderEvent.Meta>().single().scriptId
            val secondId = second.filterIsInstance<SpeechRenderEvent.Meta>().single().scriptId
            assertEquals(firstId, secondId)
            assertEquals(1, harness.planner.produceCalls)
            assertEquals(2, harness.store.rows.values.single().playCount)
        }
    }

    @Nested
    inner class `Pinning` {

        @Test
        fun `pinning an auto-saved script promotes that row instead of adding a second one`() = runTest {
            val harness = Harness()
            val planned = harness.service.planScript(listOf("alice"), "今天杭州有雨，出门记得带伞。", target())

            val pinned = assertNotNull(
                harness.service.pinScript(listOf("alice"), planned.script.scriptId, target(), title = "keep me")
            )

            assertEquals(SpeechScriptStatus.PINNED, pinned.status)
            assertEquals(planned.script.scriptId, pinned.id)
            assertEquals(1, harness.store.rows.size)
        }

        @Test
        fun `pinning twice keeps one row`() = runTest {
            val harness = Harness()
            val planned = harness.service.planScript(listOf("alice"), "重复保留同一份剧本。", target())

            harness.service.pinScript(listOf("alice"), planned.script.scriptId, target())
            harness.service.pinScript(listOf("alice"), planned.script.scriptId, target())

            assertEquals(1, harness.store.rows.size)
            assertEquals(SpeechScriptStatus.PINNED, harness.store.rows.values.single().status)
        }

        @Test
        fun `pinning a deleted script starts a new row instead of colliding with the archived one`() = runTest {
            val harness = Harness()
            val planned = harness.service.planScript(listOf("alice"), "今天杭州有雨，出门记得带伞。", target())
            // Keep the row as the script's id owner, but soft-delete it: the id is now taken forever.
            harness.store.archive("alice", planned.script.scriptId)

            val pinned = harness.serviceWithFreshPlans()
                .pinScript(listOf("alice"), planned.script.scriptId, target(), title = "keep me")

            assertNotNull(pinned)
            assertEquals(SpeechScriptStatus.PINNED, pinned.status)
            assertEquals("keep me", pinned.title)
            assertNotEquals(planned.script.scriptId, pinned.id)
        }
    }

    @Nested
    inner class `Degradation` {

        @Test
        fun `without a store everything still plans`() = runTest {
            val capabilities = SpeechCapabilityResolver().resolve(
                SpeechCapabilities(defaultVoice = "voice-a", voices = listOf(VoiceSpec(voice = "voice-a", name = "A")))
            )
            val planner = FakePlanner(capabilities)
            val resolver = mockk<AuxModelResolver>()
            coEvery { resolver.resolveConfig(any<Collection<String>>(), AuxModelTask.TTS) } returns config()
            val archive = SpeechScriptArchiveService(store = null, auxModelResolver = resolver)
            val service = TtsService(
                auxModelResolver = resolver,
                synthesizer = SilentSynth(),
                speechDirector = planner,
                planStore = SpeechPlanStore(),
                archive = archive
            )

            val planned = service.planScript(listOf("alice"), "没有数据库也能朗读。", target())

            assertFalse(planned.reused)
            assertFalse(archive.available)
            assertNull(archive.load("alice", planned.script.scriptId))
        }
    }
}
