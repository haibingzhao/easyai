package com.easy.easyai.autoconfigure.openai

import com.easy.easyai.api.llm.ToolCallback
import com.easy.easyai.api.model.ModelCapabilities
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.StructuredOutputSupport
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for the structuredOutput capability gate in [OpenAiChatModelFactory.build]:
 * JSON_SCHEMA maps to response_format json_schema (with the schema), JSON_OBJECT maps to plain
 * json_object, NONE skips API-level enforcement entirely.
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
        fun `undeclared capabilities get no response format`() {
            // StructuredOutputPolicy: nothing is sent on a row nobody declared, because a gateway that
            // rejects response_format answers 400 and the whole schema-bearing run dies on it.
            val built = factory.build(config(null), emptyList(), schema) as OpenAiChatOptions
            assertNull(built.responseFormatKind)
            assertNull(built.outputSchema)
        }

        @Test
        fun `JSON_SCHEMA enforces schema via response_format`() {
            val caps = ModelCapabilities(structuredOutput = StructuredOutputSupport.JSON_SCHEMA)
            val built = factory.build(config(caps), emptyList(), schema) as OpenAiChatOptions
            assertEquals(OpenAiResponseFormatKind.JSON_SCHEMA, built.responseFormatKind)
            assertNotNull(built.outputSchema)
        }

        @Test
        fun `JSON_OBJECT maps to schema-less json_object response format`() {
            val caps = ModelCapabilities(structuredOutput = StructuredOutputSupport.JSON_OBJECT)
            val built = factory.build(config(caps), emptyList(), schema) as OpenAiChatOptions
            assertEquals(OpenAiResponseFormatKind.JSON_OBJECT, built.responseFormatKind)
            assertNull(built.outputSchema)
        }

        @Test
        fun `NONE skips response format`() {
            val caps = ModelCapabilities(structuredOutput = StructuredOutputSupport.NONE)
            val built = factory.build(config(caps), emptyList(), schema) as OpenAiChatOptions
            assertNull(built.responseFormatKind)
            assertNull(built.outputSchema)
        }

        @Test
        fun `null schema leaves response format unset`() {
            val built = factory.build(config(null), emptyList(), null) as OpenAiChatOptions
            assertNull(built.responseFormatKind)
        }

        @Test
        fun `tool schemas decline the response format, as the native dashscope builder does`() {
            // A Bailian-family gateway 400s when both ride on one request, so tools win and the schema
            // travels in the prompt. A 400 on the format now reaches the user as a capability to fix,
            // so sending one the model cannot answer to is not something to fall back on later.
            val caps = ModelCapabilities(structuredOutput = StructuredOutputSupport.JSON_SCHEMA)
            val built = factory.build(config(caps), listOf(ToolCallback("echo", "echo back", "{}")), schema)
                as OpenAiChatOptions

            assertNull(built.responseFormatKind)
            assertNull(built.outputSchema)
            assertEquals(1, built.toolCallbacks.size, "the tools themselves still go out")
        }

        @Test
        fun `plain json object is declined by tools too`() {
            val caps = ModelCapabilities(structuredOutput = StructuredOutputSupport.JSON_OBJECT)
            val built = factory.build(config(caps), listOf(ToolCallback("echo", "echo back", "{}")), schema)
                as OpenAiChatOptions

            assertNull(built.responseFormatKind)
        }
    }

    @Nested
    inner class `client assembly` {

        @Test
        fun `create builds the client from hand-assembled ClientOptions`() {
            // The SDK's own builder installs its HTTP client unconditionally, so the error-body logger
            // is injected by assembling ClientOptions here. ClientOptions.Builder.build() fails loudly on
            // a missing required field, which makes constructing the model the assertion.
            val withKey = config(null).copy(apiKey = "sk-test", timeoutSeconds = 42L)

            val built = factory.create(withKey, ObservationRegistry.NOOP).options as OpenAiChatOptions

            assertEquals("test-model", built.model)
            assertEquals(42L, built.timeoutSeconds)
        }
    }
}
