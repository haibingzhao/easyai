package com.easy.easyai.speech

import com.easy.easyai.api.model.SpeechCapabilities
import com.easy.easyai.api.model.VoiceSpec
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeechScriptValidatorTest {

    private val capabilities = SpeechCapabilityResolver().resolve(
        SpeechCapabilities(
            supportsInstruction = true,
            emotionTags = listOf("[serious]", "[excited]"),
            paralinguisticTags = listOf("[clears throat]", "[laughing]"),
            defaultVoice = "voice-a",
            voices = listOf(
                VoiceSpec(voice = "voice-a", name = "A", instruction = true),
                VoiceSpec(voice = "voice-b", name = "B", instruction = false)
            )
        )
    )

    private fun script(
        source: String,
        spoken: String,
        voice: String = "voice-a",
        emotion: String? = null,
        styleNote: String? = "平稳陈述"
    ): SpeechScript {
        val segment = SpeechSegment(
            id = "seg-0",
            index = 0,
            sourceText = source,
            spokenText = spoken,
            voice = voice,
            emotion = emotion,
            styleNote = styleNote
        )
        return SpeechScript(
            scriptId = "script-1",
            sourceDigest = SpokenTextNormalizer.sourceDigest(source),
            mood = "克制",
            style = SpeechStyle.TEACHER,
            segments = listOf(segment)
        )
    }

    @Nested
    inner class NumericFidelity {
        @Test
        fun `accepts equivalent Chinese spoken numbers`() {
            val source = "上限是20，并发35，成功率3.5%，版本2026-Q3。"
            val spoken = "上限是二十，并发三十五，成功率百分之三点五，版本二零二六年第三季度。"
            assertEquals(emptyList(), SpeechScriptValidator.validateRefine(script(source, spoken), source, capabilities, 20_000))
        }

        @Test
        fun `rejects even one missing number`() {
            val source = "上限20，实际35。"
            val spoken = "上限二十，实际并发很高。"
            assertTrue(SpeechScriptValidator.validateRefine(script(source, spoken), source, capabilities, 20_000)
                .contains("numeric fidelity mismatch"))
        }

        @Test
        fun `normalizes decimals and thousands`() {
            assertEquals(listOf("1.2", "20000"), SpokenTextNormalizer.numericTokens("1.2GB 和 两万"))
            assertEquals(listOf("0.035"), SpokenTextNormalizer.numericTokens("百分之三点五"))
        }

        @Test
        fun `accepts a spoken datetime instead of rejecting the rewrite`() {
            // The single-segment refine path has its own fidelity check; a hyphenated date used to fail
            // it as `2026, -10, -31` and cost the whole answer its direction.
            val source = "下一次播报是2026-10-31 07:18（Asia/Shanghai）。"
            val spoken = "下一次播报是二零二六年十月三十一日七点十八分，上海时区。"
            assertEquals(emptyList(), SpeechScriptValidator.validateRefine(script(source, spoken), source, capabilities, 20_000))
        }

        @Test
        fun `rejects a spoken datetime that moved the minute`() {
            val source = "下一次播报是2026-10-31 07:18（Asia/Shanghai）。"
            val spoken = "下一次播报是二零二六年十月三十一日七点二十八分，上海时区。"
            assertTrue(SpeechScriptValidator.validateRefine(script(source, spoken), source, capabilities, 20_000)
                .contains("numeric fidelity mismatch"))
        }
    }

    @Nested
    inner class StructureAndCapabilities {
        @Test
        fun `rejects more than one segment rather than truncating`() {
            val source = "原文"
            val original = script(source, source)
            val multiple = original.copy(segments = original.segments + original.segments.single().copy(id = "seg-1", index = 1))
            assertTrue(SpeechScriptValidator.validateRefine(multiple, source, capabilities, 20_000)
                .contains("refine requires exactly one segment"))
        }

        @Test
        fun `accepts a source echoed with re-wrapped whitespace`() {
            // The director rewrites line breaks of the text it copied; that changes bytes but not
            // what the user wrote, so it must not cost the reply its directed read.
            val source = "第一行内容。\n第二行内容。"
            val reflowed = "第一行内容。 第二行内容。"
            val original = script(source, source)
            val echoed = original.copy(
                segments = listOf(original.segments.single().copy(sourceText = reflowed))
            )

            assertEquals(emptyList(), SpeechScriptValidator.validateRefine(echoed, source, capabilities, 20_000))
        }

        @Test
        fun `rejects a voice outside the catalog`() {
            val source = "原文"
            val errors = SpeechScriptValidator.validateRefine(
                script(source, source, voice = "invented"), source, capabilities, 20_000
            )
            assertTrue("voice is not allowed" in errors)
        }

        @Test
        fun `rejects instruction for a voice that does not support it`() {
            val source = "原文"
            val errors = SpeechScriptValidator.validateRefine(
                script(source, source, voice = "voice-b"), source, capabilities, 20_000
            )
            assertTrue("voice does not support instruction" in errors)
        }

        @Test
        fun `rejects XML markup, undeclared tags and unknown emotion arguments`() {
            val source = "原文"
            val errors = SpeechScriptValidator.validateRefine(
                script(source, "[whispers]<break/>原文", emotion = "happy"), source, capabilities, 20_000
            )
            assertTrue(errors.any { it.startsWith("unknown markup in spoken text") }, "errors were $errors")
            assertTrue(errors.any { it.contains("[whispers]") }, "errors were $errors")
            assertTrue("emotion is not allowed" in errors)
        }

        @Test
        fun `accepts declared control tags and multi-word paralinguistic tags inline`() {
            val source = "原文"
            val spoken = "[excited]原文，[clears throat]我们继续[laughing]。"
            val errors = SpeechScriptValidator.validateRefine(
                script(source, spoken), source, capabilities, 20_000
            )
            assertFalse(errors.any { it.startsWith("unknown markup") }, "errors were $errors")
        }

        @Test
        fun `still rejects a tag outside this model's vocabulary`() {
            val source = "原文"
            val errors = SpeechScriptValidator.validateRefine(
                script(source, "原文[shouting]"), source, capabilities, 20_000
            )
            assertTrue(errors.any { it.contains("[shouting]") }, "errors were $errors")
        }

        @Test
        fun `keeps URL hosts and code identifiers`() {
            val source = "打开 https://example.com/a 并调用 getUserName。"
            val errors = SpeechScriptValidator.validateRefine(
                script(source, "打开链接并调用获取用户名方法。"), source, capabilities, 20_000
            )
            assertTrue("term fidelity mismatch" in errors)
            assertFalse(SpeechScriptValidator.validateRefine(script(source, source), source, capabilities, 20_000)
                .contains("term fidelity mismatch"))
        }
    }

    /** Multi-segment plans: the P2 gate, where a defect is repaired per segment instead of per plan. */
    @Nested
    inner class FullPlan {

        private fun plan(source: String, vararg segments: SpeechSegment): SpeechScript = SpeechScript(
            scriptId = "script-full",
            sourceDigest = SpokenTextNormalizer.sourceDigest(source),
            mood = "平稳",
            style = SpeechStyle.TEACHER,
            segments = segments.toList()
        )

        private fun segment(
            index: Int,
            sourceText: String,
            spokenText: String = sourceText,
            voice: String = "voice-a",
            styleNote: String? = null,
            emotion: String? = null,
            pauseAfterMs: Int = 0
        ) = SpeechSegment(
            id = "seg-$index",
            index = index,
            sourceText = sourceText,
            spokenText = spokenText,
            voice = voice,
            styleNote = styleNote,
            emotion = emotion,
            pauseAfterMs = pauseAfterMs
        )

        @Test
        fun `accepts a contiguous plan and keeps the source slices authoritative`() {
            val source = "别急，这个问题不大。上限是20，实际到了35。"
            val result = SpeechScriptValidator.validateFull(
                plan(
                    source,
                    segment(0, "别急，这个问题不大。"),
                    segment(1, "上限是20，实际到了35。")
                ),
                source,
                capabilities,
                20_000
            )
            assertTrue(result.usable, "unexpected fault: ${result.fault}")
            assertEquals(2, result.script!!.segments.size)
            assertEquals("别急，这个问题不大。", result.script.segments[0].sourceText)
            assertEquals(emptyList(), result.warnings)
        }

        @Test
        fun `tolerates a reflowed copy but keeps the original slice`() {
            val source = "第一句说完。\n\n第二句开始。"
            val result = SpeechScriptValidator.validateFull(
                plan(source, segment(0, "第一句说完。 第二句开始。")),
                source,
                capabilities,
                20_000
            )
            assertTrue(result.usable, "unexpected fault: ${result.fault}")
            // The declared text was reflowed; the slice handed to the synthesizer is the real source.
            assertEquals("第一句说完。\n\n第二句开始。", result.script!!.segments.single().sourceText)
        }

        @Test
        fun `refuses a boundary that cuts a number in half`() {
            // Each half passes every per-segment check on its own — "上限是2" and "0个。" both keep
            // their own number — while the concatenation says "two" then "zero" instead of "twenty".
            val source = "上限是20个。"
            val result = SpeechScriptValidator.validateFull(
                plan(source, segment(0, "上限是2"), segment(1, "0个。")),
                source,
                capabilities,
                20_000
            )
            assertNull(result.script)
            assertEquals(SpeechScriptValidator.CODE_COVERAGE, result.fault?.code)
        }

        @Test
        fun `a boundary at whitespace is not mistaken for a split token`() {
            // The guard must not fire on the normal case it most resembles: an English word followed
            // by a space and another word. Only adjacency inside one token is a defect.
            val source = "the user id is 5."
            val result = SpeechScriptValidator.validateFull(
                plan(source, segment(0, "the user"), segment(1, "id is 5.")),
                source,
                capabilities,
                20_000
            )
            assertTrue(result.usable, "unexpected fault: ${result.fault}")
        }

        @Test
        fun `refuses a plan that omits a sentence`() {
            val source = "第一句。第二句。第三句。"
            val result = SpeechScriptValidator.validateFull(
                plan(source, segment(0, "第一句。"), segment(1, "第三句。")),
                source,
                capabilities,
                20_000
            )
            assertFalse(result.usable)
            assertEquals(SpeechScriptValidator.CODE_COVERAGE, result.fault?.code)
        }

        @Test
        fun `refuses a plan that stops before the end`() {
            val source = "第一句。第二句。"
            val result = SpeechScriptValidator.validateFull(
                plan(source, segment(0, "第一句。")),
                source,
                capabilities,
                20_000
            )
            assertEquals(SpeechScriptValidator.CODE_COVERAGE, result.fault?.code)
        }

        @Test
        fun `refuses segments that are not continuous from zero`() {
            val source = "第一句。第二句。"
            val result = SpeechScriptValidator.validateFull(
                plan(source, segment(0, "第一句。"), segment(2, "第二句。")),
                source,
                capabilities,
                20_000
            )
            assertEquals(SpeechScriptValidator.CODE_SCHEMA, result.fault?.code)
        }

        @Test
        fun `refuses more voices than a script may use`() {
            val source = "第一句。第二句。第三句。"
            val catalog = SpeechCapabilityResolver().resolve(
                SpeechCapabilities(
                    defaultVoice = "v1",
                    voices = listOf(
                        VoiceSpec(voice = "v1", name = "V1"),
                        VoiceSpec(voice = "v2", name = "V2"),
                        VoiceSpec(voice = "v3", name = "V3")
                    )
                )
            )
            val result = SpeechScriptValidator.validateFull(
                plan(
                    source,
                    segment(0, "第一句。", voice = "v1"),
                    segment(1, "第二句。", voice = "v2"),
                    segment(2, "第三句。", voice = "v3")
                ),
                source,
                catalog,
                20_000
            )
            assertEquals(SpeechScriptValidator.CODE_TOO_MANY_VOICES, result.fault?.code)
        }

        @Test
        fun `refuses a plan built for different text`() {
            val stale = SpeechScript(
                scriptId = "stale",
                sourceDigest = SpokenTextNormalizer.sourceDigest("别的文本"),
                mood = "平稳",
                style = SpeechStyle.TEACHER,
                segments = listOf(segment(0, "第一句。"))
            )
            val result = SpeechScriptValidator.validateFull(stale, "第一句。", capabilities, 20_000)
            assertEquals(SpeechScriptValidator.CODE_STALE_SOURCE, result.fault?.code)
        }

        @Test
        fun `drops a voice the model does not offer instead of rejecting the plan`() {
            val source = "第一句。"
            val result = SpeechScriptValidator.validateFull(
                plan(source, segment(0, "第一句。", voice = "invented")),
                source,
                capabilities,
                20_000
            )
            assertTrue(result.usable)
            assertEquals("voice-a", result.script!!.segments.single().voice)
            assertTrue(result.warnings.any { it.code == SpeechScriptValidator.CODE_VOICE })
        }

        @Test
        fun `drops a style note for a voice that cannot take one`() {
            val source = "第一句。"
            val result = SpeechScriptValidator.validateFull(
                plan(source, segment(0, "第一句。", voice = "voice-b", styleNote = "压低声音")),
                source,
                capabilities,
                20_000
            )
            assertTrue(result.usable)
            assertEquals(null, result.script!!.segments.single().styleNote)
            assertTrue(result.warnings.any { it.code == SpeechScriptValidator.CODE_INSTRUCTION })
        }

        @Test
        fun `drops an unsupported emotion and clamps a pause`() {
            val source = "第一句。"
            val result = SpeechScriptValidator.validateFull(
                plan(source, segment(0, "第一句。", emotion = "furious", pauseAfterMs = 9_000)),
                source,
                capabilities,
                20_000
            )
            val fixed = result.script!!.segments.single()
            assertEquals(null, fixed.emotion)
            assertEquals(SpeechScriptValidator.MAX_PAUSE_MS, fixed.pauseAfterMs)
            assertTrue(result.warnings.any { it.code == SpeechScriptValidator.CODE_EMOTION })
            assertTrue(result.warnings.any { it.code == SpeechScriptValidator.CODE_PAUSE })
        }

        @Test
        fun `falls a segment back to its own sentence when a number changed`() {
            val source = "上限是20，实际到了35。"
            val result = SpeechScriptValidator.validateFull(
                plan(source, segment(0, source, spokenText = "上限大概是二十。")),
                source,
                capabilities,
                20_000
            )
            assertTrue(result.usable)
            val fixed = result.script!!.segments.single()
            // The rewrite lost a number, so the segment speaks the original sentence instead of
            // a number nobody said.
            assertEquals("上限是20，实际到了35。", fixed.spokenText)
            assertTrue(result.warnings.any { it.code == SpeechScriptValidator.CODE_SEGMENT_FALLBACK })
        }

        @Test
        fun `refuses a plan whose spoken text exceeds the product limit`() {
            val source = "第一句。"
            val result = SpeechScriptValidator.validateFull(
                plan(source, segment(0, "第一句。")),
                source,
                capabilities,
                2
            )
            assertEquals(SpeechScriptValidator.CODE_TOO_LONG, result.fault?.code)
        }

        @Test
        fun `refuses more segments than one script allows`() {
            val source = (0 until 41).joinToString("") { "第${it}句。" }
            val segments = (0 until 41).map { segment(it, "第${it}句。") }.toTypedArray()
            val result = SpeechScriptValidator.validateFull(plan(source, *segments), source, capabilities, 200_000)
            assertEquals(SpeechScriptValidator.CODE_SCHEMA, result.fault?.code)
        }
    }
}
