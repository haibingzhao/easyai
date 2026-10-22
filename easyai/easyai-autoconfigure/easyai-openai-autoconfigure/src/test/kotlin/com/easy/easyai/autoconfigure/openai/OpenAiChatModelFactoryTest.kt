package com.easy.easyai.autoconfigure.openai

import com.easy.easyai.api.llm.ToolCallback
import com.easy.easyai.api.model.ModelCapabilities
import com.easy.easyai.api.model.ModelOptions
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.ReasoningMode
import com.easy.easyai.api.model.ReasoningProfile
import com.easy.easyai.api.model.StructuredOutputSupport
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
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
    inner class `reasoning profile` {

        private fun row(
            mode: ReasoningMode,
            thinking: Boolean,
            effort: String? = null,
            levels: List<String> = listOf("low", "medium", "high", "xhigh", "max")
        ) = config(ModelCapabilities(reasoning = ReasoningProfile(mode, levels, thinking, effort)))
            .copy(options = ModelOptions(thinking = thinking, effort = effort, maxTokens = 4096))

        @Test
        fun `toggleable thinking on sends the switch and the raw effort`() {
            val built = factory.build(row(ReasoningMode.TOGGLEABLE, thinking = true, effort = "xhigh"), emptyList(), null)
                as OpenAiChatOptions

            assertEquals(true, built.enableThinking)
            assertEquals("xhigh", built.reasoningEffort, "declared levels must not be collapsed to high")
            assertEquals(4096, built.maxCompletionTokens)
        }

        @Test
        fun `toggleable thinking off disables thinking and drops effort`() {
            val built = factory.build(row(ReasoningMode.TOGGLEABLE, thinking = false, effort = "max"), emptyList(), null)
                as OpenAiChatOptions

            assertEquals(false, built.enableThinking)
            assertNull(built.reasoningEffort, "effort is meaningless while thinking is off")
            assertEquals(4096, built.maxTokens)
        }

        @Test
        fun `none sends neither thinking nor effort`() {
            val built = factory.build(row(ReasoningMode.NONE, thinking = true, effort = "max"), emptyList(), null)
                as OpenAiChatOptions

            assertNull(built.enableThinking)
            assertNull(built.reasoningEffort)
        }

        @Test
        fun `always on sends effort without an enable flag`() {
            val built = factory.build(row(ReasoningMode.ALWAYS_ON, thinking = false, effort = "max"), emptyList(), null)
                as OpenAiChatOptions

            assertNull(built.enableThinking, "an always-on model must not receive enable_thinking=false")
            assertEquals("max", built.reasoningEffort)
        }

        @Test
        fun `undeclared rows keep the legacy collapse`() {
            val legacy = config(null).copy(options = ModelOptions(thinking = true, effort = "xhigh", maxTokens = 1000))
            val built = factory.build(legacy, emptyList(), null) as OpenAiChatOptions

            assertNull(built.enableThinking)
            assertEquals("high", built.reasoningEffort)
        }
    }

    @Nested
    inner class `protocol matrix` {

        @ParameterizedTest(name = "{0} thinking={1} effort={2}")
        @MethodSource("com.easy.easyai.autoconfigure.openai.OpenAiChatModelFactoryTest#reasoningMatrix")
        fun `thinking switch and effort follow the mode`(
            mode: ReasoningMode?,
            thinking: Boolean,
            effort: String?,
            expectedEnableThinking: Boolean?,
            expectedEffort: String?
        ) {
            val caps = mode?.let {
                ModelCapabilities(
                    reasoning = ReasoningProfile(
                        it,
                        listOf("low", "medium", "high", "xhigh", "max"),
                        defaultThinking = true
                    )
                )
            }
            val built = factory.build(
                config(caps).copy(options = ModelOptions(thinking = thinking, effort = effort, maxTokens = 1000)),
                emptyList(),
                null
            ) as OpenAiChatOptions

            assertEquals(expectedEnableThinking, built.enableThinking)
            assertEquals(expectedEffort, built.reasoningEffort)
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

    companion object {
        /** protocol × mode × thinking × effort matrix. */
        @JvmStatic
        fun reasoningMatrix(): List<Arguments> = listOf(
            Arguments.of(ReasoningMode.TOGGLEABLE, true, "xhigh", true, "xhigh"),
            Arguments.of(ReasoningMode.TOGGLEABLE, false, "max", false, null),
            Arguments.of(ReasoningMode.TOGGLEABLE, true, null, true, null),
            Arguments.of(ReasoningMode.ALWAYS_ON, true, "max", null, "max"),
            Arguments.of(ReasoningMode.ALWAYS_ON, false, "max", null, "max"),
            Arguments.of(ReasoningMode.ALWAYS_ON, true, null, null, null),
            Arguments.of(ReasoningMode.NONE, true, "max", null, null),
            Arguments.of(ReasoningMode.UNKNOWN, true, "xhigh", null, "high"),
            Arguments.of(ReasoningMode.UNKNOWN, false, "low", null, "low"),
            Arguments.of(null, true, null, null, "high"),
            Arguments.of(null, false, "max", null, "high"),
            Arguments.of(null, false, null, null, null),
        )
    }
}
