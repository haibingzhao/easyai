package com.easy.easyai.autoconfigure.anthropic

import com.anthropic.models.messages.OutputConfig
import com.easy.easyai.api.model.ModelCapabilities
import com.easy.easyai.api.model.ModelOptions
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.StructuredOutputSupport
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.model.tool.StructuredOutputChatOptions
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the structuredOutput capability gate in [AnthropicChatModelFactory.build]:
 * the schema is only pushed into output_config when the model declares support, and
 * must coexist with the effort field on the same OutputConfig.
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

    @Nested
    inner class `structured output gate` {

        @Test
        fun `undeclared capabilities keep schema enforcement`() {
            val options = factory.build(config(null), emptyList(), schema) as StructuredOutputChatOptions
            assertNotNull(options.outputSchema)
        }

        @Test
        fun `JSON_SCHEMA declares support - schema enforced`() {
            val caps = ModelCapabilities(structuredOutput = StructuredOutputSupport.JSON_SCHEMA)
            val options = factory.build(config(caps), emptyList(), schema) as StructuredOutputChatOptions
            assertNotNull(options.outputSchema)
        }

        @Test
        fun `JSON_OBJECT not expressible on Anthropic protocol - skipped`() {
            val caps = ModelCapabilities(structuredOutput = StructuredOutputSupport.JSON_OBJECT)
            val options = factory.build(config(caps), emptyList(), schema) as StructuredOutputChatOptions
            assertNull(options.outputSchema)
        }

        @Test
        fun `NONE skips schema enforcement`() {
            val caps = ModelCapabilities(structuredOutput = StructuredOutputSupport.NONE)
            val options = factory.build(config(caps), emptyList(), schema) as StructuredOutputChatOptions
            assertNull(options.outputSchema)
        }

        @Test
        fun `null schema sets no output format`() {
            val options = factory.build(config(null), emptyList(), null) as StructuredOutputChatOptions
            assertNull(options.outputSchema)
        }
    }

    @Nested
    inner class `output config merge with effort` {

        @Test
        fun `schema and effort coexist on the same OutputConfig`() {
            val options = ModelOptions(temperature = 0.7, maxTokens = 1000, thinking = false, effort = "high")
            val built = factory.build(
                config(null, options), emptyList(), schema
            ) as AnthropicChatOptions
            assertNotNull(built.outputSchema)
            assertEquals(OutputConfig.Effort.HIGH, built.outputConfig?.effort()?.orElse(null))
        }
    }

    @Nested
    inner class `thinking toggle` {

        @Test
        fun `thinking=false sends explicit disabled config instead of omitting it`() {
            // Some Anthropic-protocol models (e.g. qwen3.x-max) reason by DEFAULT, so the
            // thinking field must be sent as {type: disabled}; omitting it leaves reasoning on.
            val options = ModelOptions(temperature = 0.7, maxTokens = 1000, thinking = false)
            val built = factory.build(config(null, options), emptyList(), null) as AnthropicChatOptions
            val thinking = built.thinking
            assertNotNull(thinking, "thinking config must be sent explicitly, not omitted")
            assertTrue(thinking.isDisabled(), "thinking=false must map to a disabled thinking config")
        }

        @Test
        fun `thinking=true sends enabled config`() {
            val options = ModelOptions(temperature = 0.7, maxTokens = 20_000, thinking = true)
            val built = factory.build(config(null, options), emptyList(), null) as AnthropicChatOptions
            val thinking = built.thinking
            assertNotNull(thinking)
            assertTrue(thinking.isEnabled(), "thinking=true must map to an enabled thinking config")
        }
    }
}
