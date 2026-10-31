package com.easy.easyai.speech

import com.easy.easyai.api.model.SpeechCapabilities
import com.easy.easyai.api.model.VoiceSpec
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Segment-level gate, exercised directly instead of through `validateFull`.
 *
 * The date and clock cases live here because they are the ones that decide whether a rewrite survives
 * or silently degrades to reading the raw ISO string — a per-segment decision that `validateFull` only
 * reports as a warning among a dozen other checks.
 */
class SpeechPlanAssemblerTest {

    private val capabilities = SpeechCapabilityResolver().resolve(
        SpeechCapabilities(
            supportsInstruction = true,
            emotionTags = listOf("[serious]", "[excited]"),
            paralinguisticTags = listOf("[clears throat]", "[laughing]"),
            defaultVoice = "voice-a",
            voices = listOf(VoiceSpec(voice = "voice-a", name = "A", instruction = true))
        )
    )

    private fun assemble(source: String, spoken: String): Pair<SpeechSegment, SpeechPlanAssembler> {
        val assembler = SpeechPlanAssembler(source, capabilities, 20_000)
        val segment = SpeechSegment(
            id = "seg-0",
            index = 0,
            sourceText = source,
            spokenText = spoken,
            voice = "voice-a"
        )
        val fixed = assertNotNull(assembler.accept(segment), "unexpected fault: ${assembler.fault}")
        return fixed to assembler
    }

    @Nested
    inner class CalendarsAndClocks {

        private val source = "下一次播报是2026-10-31 07:18（Asia/Shanghai）。"

        @Test
        fun `keeps a spoken datetime instead of falling back to the ISO string`() {
            val spoken = "下一次播报是二零二六年十月三十一日七点十八分，上海时区。"
            val (fixed, assembler) = assemble(source, spoken)
            assertEquals(spoken, fixed.spokenText)
            assertEquals(emptyList(), assembler.warnings)
        }

        @Test
        fun `keeps a spoken datetime that repeats the zone verbatim`() {
            val spoken = "下一次播报是2026年10月31日7点18分，Asia/Shanghai时区。"
            val (fixed, assembler) = assemble(source, spoken)
            assertEquals(spoken, fixed.spokenText)
            assertEquals(emptyList(), assembler.warnings)
        }

        @Test
        fun `keeps a spoken date that drops the year`() {
            // Saying the year out loud is what people skip; losing month or day is not the same thing.
            val spoken = "下一次播报是10月31日七点十八分，上海时区。"
            val (fixed, assembler) = assemble(source, spoken)
            assertEquals(spoken, fixed.spokenText)
            assertEquals(emptyList(), assembler.warnings)
        }

        @Test
        fun `a changed minute still falls the segment back to its source`() {
            val spoken = "下一次播报是二零二六年十月三十一日七点二十八分，上海时区。"
            val (fixed, assembler) = assemble(source, spoken)
            assertEquals(source, fixed.spokenText)
            assertTrue(assembler.warnings.any { it.code == SpeechScriptValidator.CODE_SEGMENT_FALLBACK })
        }

        @Test
        fun `a changed day still falls the segment back to its source`() {
            val spoken = "下一次播报是二零二六年十一月一日七点十八分，上海时区。"
            val (fixed, assembler) = assemble(source, spoken)
            assertEquals(source, fixed.spokenText)
        }

        @Test
        fun `a zone the catalog does not know must still be spoken`() {
            val berlin = "会议在 Europe/Berlin 时间开始。"
            val (fixed, assembler) = assemble(berlin, "会议在欧洲中部时间开始。")
            assertEquals(berlin, fixed.spokenText)
            assertTrue(assembler.warnings.any { it.code == SpeechScriptValidator.CODE_SEGMENT_FALLBACK })
        }

        @Test
        fun `an invented year is a changed fact`() {
            // The wildcard only works source -> spoken: the source never stated a year, so the
            // rewrite must not be allowed to supply one.
            val undated = "播报时间是10月31日 07:18。"
            val (fixed, _) = assemble(undated, "播报时间是2026年10月31日七点十八分。")
            assertEquals(undated, fixed.spokenText)
        }
    }

    /** Inline tags and the segment-level emotion that would otherwise override them. */
    @Nested
    inner class InlineTags {

        private val source = "今天的天气真不错。我们一起出去玩吧。"

        @Test
        fun `a declared tag survives the gate`() {
            val spoken = "今天的天气真不错。[laughing]我们一起出去玩吧。"
            val (fixed, assembler) = assemble(source, spoken)
            assertEquals(spoken, fixed.spokenText)
            assertEquals(emptyList(), assembler.warnings)
        }

        @Test
        fun `a tag the model does not declare falls the segment back`() {
            val spoken = "今天的天气真不错。[shouting]我们一起出去玩吧。"
            val (fixed, assembler) = assemble(source, spoken)
            assertEquals(source, fixed.spokenText)
            assertTrue(assembler.warnings.any { it.code == SpeechScriptValidator.CODE_SEGMENT_FALLBACK })
        }

        @Test
        fun `an opening control tag supersedes the segment emotion`() {
            val assembler = SpeechPlanAssembler(source, capabilities, 20_000)
            val fixed = assertNotNull(
                assembler.accept(
                    SpeechSegment("seg-0", 0, source, "[excited]$source", "voice-a", emotion = "serious")
                )
            )
            assertEquals("[excited]$source", fixed.spokenText)
            assertNull(fixed.emotion)
            assertTrue(assembler.warnings.any { it.code == SpeechScriptValidator.CODE_EMOTION_SUPERSEDED })
        }

        @Test
        fun `a fallback keeps the emotion the inline tag would have superseded`() {
            // The rewrite is rejected (unknown markup), so the source is spoken — without the
            // [excited] prefix. Dropping the field as well would lose the only style that is left.
            val assembler = SpeechPlanAssembler(source, capabilities, 20_000)
            val fixed = assertNotNull(
                assembler.accept(
                    SpeechSegment(
                        "seg-0",
                        0,
                        source,
                        "[excited]$source[shouting]",
                        "voice-a",
                        emotion = "serious"
                    )
                )
            )
            assertEquals(source, fixed.spokenText)
            assertEquals("serious", fixed.emotion)
        }

        @Test
        fun `a paralinguistic tag is not a segment emotion`() {
            // `[laughing]` prepended would mean "laugh, then say the whole segment", not "laugh here".
            val assembler = SpeechPlanAssembler(source, capabilities, 20_000)
            val fixed = assertNotNull(
                assembler.accept(SpeechSegment("seg-0", 0, source, source, "voice-a", emotion = "laughing"))
            )
            assertNull(fixed.emotion)
            assertTrue(assembler.warnings.any { it.code == SpeechScriptValidator.CODE_EMOTION })
        }
    }
}
