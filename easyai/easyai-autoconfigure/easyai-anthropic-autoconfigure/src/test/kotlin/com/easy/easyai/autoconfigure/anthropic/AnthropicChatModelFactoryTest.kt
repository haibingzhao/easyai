package com.easy.easyai.autoconfigure.anthropic

import com.easy.easyai.api.model.ModelCapabilities
import com.easy.easyai.api.model.ModelOptions
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.ReasoningMode
import com.easy.easyai.api.model.ReasoningProfile
import com.easy.easyai.api.model.SamplingSupport
import com.easy.easyai.api.model.StructuredOutputSupport
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for [AnthropicChatModelFactory.build]: the structuredOutput capability gate only pushes the
 * schema when the model declares support, and thinking/effort are mutually exclusive on the
 * Anthropic-compatible wire (thinking budget wins when thinking is on). The mapper turns
 * `thinkingBudget == null` into an explicit `{type: disabled}` config, so the options only carry
 * the budget/effort decision.
 */
class AnthropicChatModelFactoryTest {

    private val factory = AnthropicChatModelFactory()
    private val schema = """{"type":"object","properties":{"answer":{"type":"string"}}}"""

    private fun config(
        capabilities: ModelCapabilities?,
        options: ModelOptions? = null
    ) = ModelProviderConfig(
        id = "cfg-1",
        name = "test",
        protocol = Protocol.ANTHROPIC,
        isCustom = false,
        modelId = "test-model",
        capabilities = capabilities,
        options = options
    )

    private fun built(capabilities: ModelCapabilities? = null, options: ModelOptions? = null, outputSchema: String? = null): AnthropicChatOptions =
        factory.build(config(capabilities, options), emptyList(), outputSchema) as AnthropicChatOptions

    @Nested
    inner class `structured output gate` {

        @Test
        fun `undeclared capabilities get no api-level enforcement`() {
            // StructuredOutputPolicy: an undeclared row is a row nobody tested, so nothing is asserted
            // of it — the schema travels in the prompt instead.
            assertNull(built(outputSchema = schema).outputSchema)
        }

        @Test
        fun `JSON_SCHEMA declares support - schema enforced`() {
            val caps = ModelCapabilities(structuredOutput = StructuredOutputSupport.JSON_SCHEMA)
            assertNotNull(built(caps, outputSchema = schema).outputSchema)
        }

        @Test
        fun `JSON_OBJECT not expressible on Anthropic protocol - skipped`() {
            val caps = ModelCapabilities(structuredOutput = StructuredOutputSupport.JSON_OBJECT)
            assertNull(built(caps, outputSchema = schema).outputSchema)
        }

        @Test
        fun `NONE skips schema enforcement`() {
            val caps = ModelCapabilities(structuredOutput = StructuredOutputSupport.NONE)
            assertNull(built(caps, outputSchema = schema).outputSchema)
        }

        @Test
        fun `null schema sets no output schema`() {
            assertNull(built(outputSchema = null).outputSchema)
        }
    }

    @Nested
    inner class `effort and thinking` {

        @Test
        fun `thinking=false emits effort and no budget`() {
            val options = ModelOptions(temperature = 0.7, maxTokens = 1000, thinking = false, effort = "high")
            val caps = ModelCapabilities(structuredOutput = StructuredOutputSupport.JSON_SCHEMA)
            val b = built(caps, options = options, outputSchema = schema)
            assertNull(b.thinkingBudget)
            assertEquals("high", b.effort)
            assertNotNull(b.outputSchema)
        }

        @Test
        fun `thinking=true sends a budget and suppresses effort`() {
            // Bailian token-plan rejects reasoning_effort alongside thinking_budget; thinking wins.
            val options = ModelOptions(temperature = 0.7, maxTokens = 20_000, thinking = true, effort = "high")
            val b = built(options = options)
            assertNotNull(b.thinkingBudget, "thinking=true must set a thinking budget")
            assertNull(b.effort, "effort must not be sent when thinking is enabled")
        }

        @Test
        fun `thinking=false without effort leaves both unset`() {
            val options = ModelOptions(temperature = 0.7, maxTokens = 1000, thinking = false)
            val b = built(options = options)
            assertNull(b.thinkingBudget)
            assertNull(b.effort)
        }
    }

    @Nested
    inner class `reasoning profile` {

        private fun row(mode: ReasoningMode, thinking: Boolean, effort: String? = null) =
            ModelCapabilities(
                reasoning = ReasoningProfile(
                    mode = mode,
                    effortLevels = listOf("low", "high", "max"),
                    defaultThinking = mode == ReasoningMode.ALWAYS_ON,
                    defaultEffort = "max"
                )
            ) to ModelOptions(thinking = thinking, effort = effort, maxTokens = 1000)

        @Test
        fun `always on with effort sends only the effort`() {
            val (caps, options) = row(ReasoningMode.ALWAYS_ON, thinking = true, effort = "max")
            val b = built(caps, options = options)

            assertEquals("max", b.effort)
            assertNull(b.thinkingBudget, "an explicitly chosen effort replaces the fixed budget")
        }

        @Test
        fun `always on without effort keeps the fixed budget`() {
            val (caps, options) = row(ReasoningMode.ALWAYS_ON, thinking = true)
            val b = built(caps, options = options)

            assertNotNull(b.thinkingBudget)
            assertNull(b.effort)
        }

        @Test
        fun `toggleable thinking off sends effort without a budget`() {
            val (caps, options) = row(ReasoningMode.TOGGLEABLE, thinking = false, effort = "high")
            val b = built(caps, options = options)

            assertEquals("high", b.effort)
            assertNull(b.thinkingBudget)
        }

        @Test
        fun `toggleable thinking on keeps the mutual exclusion`() {
            val (caps, options) = row(ReasoningMode.TOGGLEABLE, thinking = true, effort = "high")
            val b = built(caps, options = options)

            assertNotNull(b.thinkingBudget)
            assertNull(b.effort)
        }

        @Test
        fun `none sends neither`() {
            val (caps, options) = row(ReasoningMode.NONE, thinking = true, effort = "max")
            val b = built(caps, options = options)

            assertNull(b.thinkingBudget)
            assertNull(b.effort)
        }
    }

    @Nested
    inner class `protocol matrix` {

        @ParameterizedTest(name = "{0} thinking={1} effort={2}")
        @MethodSource("com.easy.easyai.autoconfigure.anthropic.AnthropicChatModelFactoryTest#reasoningMatrix")
        fun `budget and effort follow the mode and switch`(
            mode: ReasoningMode?,
            thinking: Boolean,
            effort: String?,
            expectedEffort: String?,
            expectedBudget: Boolean
        ) {
            val caps = mode?.let {
                ModelCapabilities(
                    reasoning = ReasoningProfile(it, listOf("low", "high", "max"), defaultThinking = true)
                )
            }
            val built = built(caps, ModelOptions(thinking = thinking, effort = effort, maxTokens = 1000))

            assertEquals(expectedEffort, built.effort)
            assertEquals(expectedBudget, built.thinkingBudget != null)
        }
    }

    @Nested
    inner class `sampling capability gate` {

        private val row = ModelOptions(temperature = 0.7, maxTokens = 1000, thinking = false)

        @Test
        fun `a settable row sends its temperature`() {
            assertEquals(0.7, built(options = row).temperature)
        }

        @Test
        fun `NOT_SETTABLE drops the temperature and keeps the rest of the row`() {
            val caps = ModelCapabilities(sampling = SamplingSupport.NOT_SETTABLE)
            val b = built(caps, options = row)

            assertNull(b.temperature)
            assertEquals(1000, b.maxTokens)
        }
    }

    companion object {
        /** protocol × mode × thinking × effort matrix: the budget and effort must never ride together. */
        @JvmStatic
        fun reasoningMatrix(): List<Arguments> = listOf(
            Arguments.of(ReasoningMode.TOGGLEABLE, true, "high", null, true),
            Arguments.of(ReasoningMode.TOGGLEABLE, false, "high", "high", false),
            Arguments.of(ReasoningMode.TOGGLEABLE, true, null, null, true),
            Arguments.of(ReasoningMode.ALWAYS_ON, true, "high", "high", false),
            Arguments.of(ReasoningMode.ALWAYS_ON, false, "high", "high", false),
            Arguments.of(ReasoningMode.ALWAYS_ON, true, null, null, true),
            Arguments.of(ReasoningMode.NONE, true, "high", null, false),
            Arguments.of(ReasoningMode.UNKNOWN, true, "high", null, true),
            Arguments.of(ReasoningMode.UNKNOWN, false, "high", "high", false),
            Arguments.of(null, true, "high", null, true),
            Arguments.of(null, false, "high", "high", false),
        )
    }
}
