package com.easy.easyai.api.model

import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelOverridePolicyTest {

    private fun profile(
        mode: ReasoningMode,
        levels: List<String> = listOf("low", "medium", "high", "xhigh", "max")
    ) = ReasoningProfile(
        mode = mode,
        effortLevels = levels,
        defaultThinking = mode == ReasoningMode.ALWAYS_ON,
        defaultEffort = levels.lastOrNull(),
        source = ReasoningProfile.SOURCE_MANUAL
    )

    private fun config(
        protocol: Protocol = Protocol.OPENAI,
        thinking: Boolean = true,
        reasoning: ReasoningProfile? = profile(ReasoningMode.TOGGLEABLE),
        maxContextTokens: Int = 204_800,
        effort: String? = null
    ) = ModelProviderConfig(
        id = "cfg",
        name = "test",
        protocol = protocol,
        isCustom = false,
        modelId = "test-model",
        options = ModelOptions(
            thinking = thinking,
            effort = effort,
            maxContextTokens = maxContextTokens,
            contextToken = maxContextTokens
        ),
        capabilities = ModelCapabilities(reasoning = reasoning)
    )

    @Nested
    inner class `apply` {

        @Test
        fun `overrides only the given fields`() {
            val applied = ModelOverridePolicy.apply(config(), ModelOverrides(effort = "max"))
            assertEquals("max", applied.options?.effort)
            assertEquals(204_800, applied.options?.contextToken, "context window follows the config when not overridden")
            assertEquals(true, applied.options?.thinking, "thinking is never overridden")
        }

        @Test
        fun `empty overrides leave the config untouched`() {
            val base = config().copy(options = ModelOptions(effort = "low", contextToken = 100_000))
            val applied = ModelOverridePolicy.apply(base, ModelOverrides.EMPTY)
            assertEquals(base, applied)
        }

        @Test
        fun `clamps a context window above the model maximum`() {
            val base = config(maxContextTokens = 131_072)
            val applied = ModelOverridePolicy.apply(base, ModelOverrides(contextToken = 262_144))
            assertEquals(131_072, applied.options?.contextToken)
        }
    }

    @Nested
    inner class `apply recorded` {

        @Test
        fun `drops an effort the current profile no longer offers`() {
            val base = config(reasoning = profile(ReasoningMode.TOGGLEABLE, listOf("low", "high")), effort = "low")
            val applied = ModelOverridePolicy.applyRecorded(base, ModelOverrides(effort = "max"))
            assertEquals("low", applied.options?.effort, "the stored config value takes over")
        }

        @Test
        fun `drops an effort whose protocol gate has closed`() {
            val base = config(thinking = false)
            val applied = ModelOverridePolicy.applyRecorded(base, ModelOverrides(effort = "high"))
            assertNull(applied.options?.effort)
        }

        @Test
        fun `keeps an effort the current profile still offers`() {
            val applied = ModelOverridePolicy.applyRecorded(config(), ModelOverrides(effort = "high"))
            assertEquals("high", applied.options?.effort)
        }

        @Test
        fun `drops a stored effort the current profile no longer offers`() {
            val base = config(
                reasoning = profile(ReasoningMode.TOGGLEABLE, listOf("high", "max")),
                effort = "low"
            )
            val applied = ModelOverridePolicy.applyRecorded(base, ModelOverrides(contextToken = 100_000))
            assertNull(applied.options?.effort)
        }

        @Test
        fun `undeclared rows keep the recorded effort`() {
            val base = config(reasoning = null, thinking = false)
            val applied = ModelOverridePolicy.applyRecorded(base, ModelOverrides(effort = "max"))
            assertEquals("max", applied.options?.effort)
        }

        @Test
        fun `clamps the recorded context window`() {
            val base = config(maxContextTokens = 131_072)
            val applied = ModelOverridePolicy.applyRecorded(base, ModelOverrides(contextToken = 262_144))
            assertEquals(131_072, applied.options?.contextToken)
        }

        @Test
        fun `ignores a non-positive recorded context window`() {
            val base = config(maxContextTokens = 131_072)
            assertEquals(
                131_072,
                ModelOverridePolicy.applyRecorded(base, ModelOverrides(contextToken = 0)).options?.contextToken
            )
            assertEquals(
                131_072,
                ModelOverridePolicy.applyRecorded(base, ModelOverrides(contextToken = -1)).options?.contextToken
            )
        }
    }

    @Nested
    inner class `effort validation` {

        @Test
        fun `rejects a level the model does not offer`() {
            val cfg = config(reasoning = profile(ReasoningMode.TOGGLEABLE, listOf("high", "max")))
            val failure = assertFailsWith<IllegalArgumentException> {
                ModelOverridePolicy.validate(cfg, ModelOverrides(effort = "low"))
            }
            assertTrue(failure.message!!.contains("accepts effort"))
        }

        @Test
        fun `rejects effort on a model without reasoning`() {
            val cfg = config(reasoning = profile(ReasoningMode.NONE))
            assertFailsWith<IllegalArgumentException> {
                ModelOverridePolicy.validate(cfg, ModelOverrides(effort = "high"))
            }
        }

        @Test
        fun `openai rows only allow effort while thinking is on`() {
            val cfg = config(thinking = false)
            assertFailsWith<IllegalArgumentException> {
                ModelOverridePolicy.validate(cfg, ModelOverrides(effort = "high"))
            }
            // Thinking on is the normal state for these rows.
            ModelOverridePolicy.validate(cfg.copy(options = ModelOptions(thinking = true)), ModelOverrides(effort = "high"))
        }

        @Test
        fun `anthropic rows only allow effort while thinking is off`() {
            val on = config(protocol = Protocol.ANTHROPIC, thinking = true)
            assertFailsWith<IllegalArgumentException> {
                ModelOverridePolicy.validate(on, ModelOverrides(effort = "high"))
            }
            ModelOverridePolicy.validate(
                on.copy(options = ModelOptions(thinking = false)),
                ModelOverrides(effort = "high")
            )
        }

        @Test
        fun `always-on rows accept effort regardless of the stored switch`() {
            val cfg = config(reasoning = profile(ReasoningMode.ALWAYS_ON), thinking = false)
            ModelOverridePolicy.validate(cfg, ModelOverrides(effort = "max"))
        }

        @Test
        fun `always-on rows still reject a level the profile does not offer`() {
            val cfg = config(
                reasoning = profile(ReasoningMode.ALWAYS_ON, listOf("low", "high", "max")),
                thinking = true
            )
            val failure = assertFailsWith<IllegalArgumentException> {
                ModelOverridePolicy.validate(cfg, ModelOverrides(effort = "medium"))
            }
            assertTrue(failure.message!!.contains("accepts effort"))
        }

        @Test
        fun `a model without an effort knob rejects every effort`() {
            val cfg = config(reasoning = profile(ReasoningMode.TOGGLEABLE, emptyList()), thinking = true)
            val failure = assertFailsWith<IllegalArgumentException> {
                ModelOverridePolicy.validate(cfg, ModelOverrides(effort = "low"))
            }
            assertTrue(failure.message!!.contains("no effort levels"), failure.message!!)
        }

        @Test
        fun `undeclared rows impose no effort gate`() {
            ModelOverridePolicy.validate(config(reasoning = null, thinking = false), ModelOverrides(effort = "max"))
        }
    }

    @Nested
    inner class `context validation` {

        @Test
        fun `a window above the maximum is clamped rather than rejected`() {
            ModelOverridePolicy.validate(config(maxContextTokens = 131_072), ModelOverrides(contextToken = 262_144))
        }

        @Test
        fun `accepts a window within the maximum`() {
            ModelOverridePolicy.validate(config(maxContextTokens = 614_400), ModelOverrides(contextToken = 409_600))
        }

        @Test
        fun `rejects a non-positive window`() {
            assertFailsWith<IllegalArgumentException> {
                ModelOverridePolicy.validate(config(), ModelOverrides(contextToken = 0))
            }
        }

        @Test
        fun `no overrides is valid`() {
            ModelOverridePolicy.validate(config(), ModelOverrides.EMPTY)
            assertNull(ModelOverridePolicy.apply(config(), null).options?.effort)
        }
    }
}
