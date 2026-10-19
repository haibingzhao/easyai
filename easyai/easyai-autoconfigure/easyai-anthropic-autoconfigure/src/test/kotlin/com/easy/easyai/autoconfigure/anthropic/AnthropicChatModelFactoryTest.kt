package com.easy.easyai.autoconfigure.anthropic

import com.easy.easyai.api.model.ModelCapabilities
import com.easy.easyai.api.model.ModelOptions
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.SamplingSupport
import com.easy.easyai.api.model.StructuredOutputSupport
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
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
}
