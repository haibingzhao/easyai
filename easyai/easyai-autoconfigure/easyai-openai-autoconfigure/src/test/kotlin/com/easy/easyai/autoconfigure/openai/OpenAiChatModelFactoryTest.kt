package com.easy.easyai.autoconfigure.openai

import com.easy.easyai.api.model.ModelCapabilities
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.StructuredOutputSupport
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.model.tool.StructuredOutputChatOptions
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for the structuredOutput capability gate in [OpenAiChatModelFactory.build]:
 * JSON_SCHEMA maps to response_format json_schema (Spring AI outputSchema), JSON_OBJECT
 * maps to plain json_object, NONE skips API-level enforcement entirely.
 */
class OpenAiChatModelFactoryTest {

    private val factory = OpenAiChatModelFactory()
    private val schema = """{"type":"object","properties":{"answer":{"type":"string"}}}"""

    private fun config(capabilities: ModelCapabilities?) = ModelProviderConfig(
        id = "cfg-1",
        name = "test",
        protocol = Protocol.OPENAI,
        isCustom = false,
        modelId = "test-model",
        capabilities = capabilities
    )

    @Nested
    inner class `structured output gate` {

        @Test
        fun `undeclared capabilities keep schema enforcement`() {
            val built = factory.build(config(null), emptyList(), schema) as OpenAiChatOptions
            assertNotNull(built.getResponseFormat())
            assertEquals(
                OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA,
                built.getResponseFormat()!!.getType()
            )
        }

        @Test
        fun `JSON_SCHEMA enforces schema via response_format`() {
            val caps = ModelCapabilities(structuredOutput = StructuredOutputSupport.JSON_SCHEMA)
            val built = factory.build(config(caps), emptyList(), schema) as StructuredOutputChatOptions
            assertNotNull(built.outputSchema)
        }

        @Test
        fun `JSON_OBJECT maps to schema-less json_object response format`() {
            val caps = ModelCapabilities(structuredOutput = StructuredOutputSupport.JSON_OBJECT)
            val built = factory.build(config(caps), emptyList(), schema) as OpenAiChatOptions
            assertEquals(
                OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT,
                built.getResponseFormat()!!.getType()
            )
            assertNull((built as StructuredOutputChatOptions).outputSchema)
        }

        @Test
        fun `NONE skips response format`() {
            val caps = ModelCapabilities(structuredOutput = StructuredOutputSupport.NONE)
            val built = factory.build(config(caps), emptyList(), schema) as OpenAiChatOptions
            assertNull(built.getResponseFormat())
        }

        @Test
        fun `null schema leaves response format unset`() {
            val built = factory.build(config(null), emptyList(), null) as OpenAiChatOptions
            assertNull(built.getResponseFormat())
        }
    }
}
