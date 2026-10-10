package com.easy.easyai.autoconfigure.dashscope

import com.alibaba.dashscope.common.ResponseFormat
import com.alibaba.dashscope.tools.ToolFunction
import com.easy.easyai.api.model.ModelCapabilities
import com.easy.easyai.api.model.ModelOptions
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.StructuredOutputSupport
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import com.easy.easyai.api.llm.ChatOptions
import com.easy.easyai.api.llm.ToolCallback
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [DashScopeRequestBuilder] and [DashScopeChatModelFactory.optionsFor], which together
 * decide the exact JSON Bailian receives: thinking control, the streaming flag, tool definitions,
 * and whether structured output is sent at all.
 */
internal class DashScopeRequestBuilderTest {

    private val tool = ToolCallback(
        name = "get_weather",
        description = "Lookup weather",
        inputSchema = """{"type":"object","properties":{"city":{"type":"string"}}}"""
    )

    private fun options(block: DashScopeChatOptions.Builder.() -> Unit = {}) =
        DashScopeChatOptions.builder()
            .model("qwen3-max")
            .apiKey("sk-test")
            .resultFormat(DashScopeChatModelFactory.RESULT_FORMAT_MESSAGE)
            .apply(block)
            .build()

    private val userSpec = DashScopeMessageSpec(role = "user", text = "天气")

    @Nested
    inner class `text param` {

        @Test
        fun `sends incremental output only while streaming`() {
            val blocking = DashScopeRequestBuilder.textParam(listOf(userSpec), emptyList(), options(), streaming = false)
            val streaming = DashScopeRequestBuilder.textParam(listOf(userSpec), emptyList(), options(), streaming = true)

            assertNull(blocking.incrementalOutput)
            assertTrue(streaming.incrementalOutput!!)
        }

        @Test
        fun `always sends result_format message because tool calling requires it`() {
            val param = DashScopeRequestBuilder.textParam(listOf(userSpec), listOf(tool), options(), streaming = false)

            assertEquals("message", param.resultFormat)
        }

        @Test
        fun `registers tool definitions without executing them`() {
            val param = DashScopeRequestBuilder.textParam(listOf(userSpec), listOf(tool), options(), streaming = false)
            val function = (param.tools.single() as ToolFunction).function

            assertEquals("get_weather", function.name)
            assertEquals("Lookup weather", function.description)
            assertEquals("object", function.parameters.getAsJsonPrimitive("type").asString)
        }

        @Test
        fun `sends a replayed assistant tool call`() {
            val specs = listOf(
                userSpec,
                DashScopeMessageSpec(
                    role = "assistant",
                    text = "",
                    toolCalls = listOf(DashScopeToolCallSpec("call_1", "get_weather", """{"city":"Hangzhou"}"""))
                )
            )

            val call = DashScopeRequestBuilder.textParam(specs, listOf(tool), options(), streaming = false)
                .httpBody.getAsJsonObject("input")
                .getAsJsonArray("messages")[1].asJsonObject
                .getAsJsonArray("tool_calls")[0].asJsonObject

            assertEquals("call_1", call.get("id").asString)
            assertEquals("get_weather", call.getAsJsonObject("function").get("name").asString)
            assertEquals("""{"city":"Hangzhou"}""", call.getAsJsonObject("function").get("arguments").asString)
        }

        @Test
        fun `passes reasoning effort through the parameters escape hatch`() {
            val param = DashScopeRequestBuilder.textParam(
                listOf(userSpec),
                emptyList(),
                options { enableThinking(false).reasoningEffort("high") },
                streaming = false
            )

            assertEquals("high", param.parameters["reasoning_effort"])
        }
    }

    @Nested
    inner class `thinking control` {

        @Test
        fun `sends an explicit disable so hybrid models stop reasoning`() {
            val param = DashScopeRequestBuilder.textParam(
                listOf(userSpec),
                emptyList(),
                options { enableThinking(false) },
                streaming = false
            )

            assertFalse(param.enableThinking!!)
            assertNull(param.thinkingBudget)
        }

        @Test
        fun `sends budget alongside enable`() {
            val param = DashScopeRequestBuilder.textParam(
                listOf(userSpec),
                emptyList(),
                options { enableThinking(true).thinkingBudget(4096).maxTokens(8192) },
                streaming = false
            )

            assertTrue(param.enableThinking!!)
            assertEquals(4096, param.thinkingBudget)
            assertEquals(8192, param.maxTokens)
        }

        @Test
        fun `factory keeps effort and budget mutually exclusive`() {
            val thinking = dashScopeOptions(configWithModelOptions(thinking = true, effort = "high"))
            val plain = dashScopeOptions(configWithModelOptions(thinking = false, effort = "high"))

            assertEquals(true, thinking.enableThinking)
            assertEquals(DashScopeChatModelFactory.DEFAULT_THINKING_BUDGET_TOKENS + 1, thinking.maxTokens)
            assertNull(thinking.reasoningEffort)
            assertEquals(false, plain.enableThinking)
            assertEquals("high", plain.reasoningEffort)
        }
    }

    @Nested
    inner class `structured output` {

        @Test
        fun `declares json schema when support is undeclared`() {
            val options = dashScopeOptions(config(structuredOutput = null), schema)

            assertEquals(schema, options.outputSchema)
            assertEquals(DashScopeResponseFormatKind.JSON_SCHEMA, options.responseFormatKind)
        }

        @Test
        fun `keeps json object support on the native protocol`() {
            val options = dashScopeOptions(
                config(structuredOutput = StructuredOutputSupport.JSON_OBJECT),
                schema
            )

            assertEquals(DashScopeResponseFormatKind.JSON_OBJECT, options.responseFormatKind)
        }

        @Test
        fun `drops api enforcement when the model declares none`() {
            val options = dashScopeOptions(config(structuredOutput = StructuredOutputSupport.NONE), schema)

            assertNull(options.outputSchema)
        }

        @Test
        fun `prefers tools over response format because bailian rejects both`() {
            val param = DashScopeRequestBuilder.textParam(
                listOf(userSpec),
                listOf(tool),
                options { outputSchema(schema).responseFormatKind(DashScopeResponseFormatKind.JSON_SCHEMA) },
                streaming = false
            )

            assertTrue(param.tools.isNotEmpty())
            assertNull(param.responseFormat)
        }

        @Test
        fun `sends the schema itself when no tools are registered`() {
            val param = DashScopeRequestBuilder.textParam(
                listOf(userSpec),
                emptyList(),
                options { outputSchema(schema).responseFormatKind(DashScopeResponseFormatKind.JSON_SCHEMA) },
                streaming = false
            )

            assertEquals(ResponseFormat.JSON_SCHEMA, param.responseFormat.type)
            assertTrue(param.responseFormat.jsonSchema.strict)
            assertEquals("object", param.responseFormat.jsonSchema.schema.getAsJsonPrimitive("type").asString)
        }
    }

    private fun dashScopeOptions(
        config: ModelProviderConfig,
        outputSchema: String? = null
    ): DashScopeChatOptions =
        DashScopeChatModelFactory().build(config, emptyList(), outputSchema) as DashScopeChatOptions

    private fun config(structuredOutput: StructuredOutputSupport?) = ModelProviderConfig(
        id = "cfg",
        name = "Bailian",
        protocol = Protocol.DASHSCOPE,
        isCustom = false,
        baseUrl = DashScopeChatModelFactory.DEFAULT_BASE_URL,
        apiKey = "sk-test",
        modelId = "qwen3-max",
        capabilities = structuredOutput?.let { ModelCapabilities(structuredOutput = it) }
    )

    private fun configWithModelOptions(thinking: Boolean, effort: String?) = config(structuredOutput = null).copy(
        options = ModelOptions(thinking = thinking, effort = effort, maxTokens = 512)
    )

    private companion object {
        const val schema = """{"type":"object","properties":{"answer":{"type":"string"}}}"""
    }
}
