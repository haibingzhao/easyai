package com.easy.easyai.autoconfigure.dashscope

import com.easy.easyai.api.model.ModelCapabilities
import com.easy.easyai.api.model.ModelOptions
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.ReasoningMode
import com.easy.easyai.api.model.ReasoningProfile
import com.easy.easyai.api.model.SamplingSupport
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [DashScopeChatModelFactory]'s protocol dispatch and client construction.
 */
internal class DashScopeChatModelFactoryTest {

    private val factory = DashScopeChatModelFactory()

    private fun config(baseUrl: String? = DashScopeChatModelFactory.DEFAULT_BASE_URL) = ModelProviderConfig(
        id = "cfg",
        name = "Bailian",
        protocol = Protocol.DASHSCOPE,
        isCustom = false,
        baseUrl = baseUrl,
        apiKey = "sk-test",
        modelId = "qwen3-vl-plus",
        capabilities = ModelCapabilities(vision = true)
    )

    @Nested
    inner class `protocol dispatch` {

        @Test
        fun `supports only the dashscope protocol`() {
            assertTrue(factory.supports(Protocol.DASHSCOPE))
            assertFalse(factory.supports(Protocol.OPENAI))
            assertFalse(factory.supports(Protocol.ANTHROPIC))
        }
    }

    @Nested
    inner class `client creation` {

        @Test
        fun `requires an api key`() {
            val failure = assertFailsWith<IllegalStateException> {
                factory.create(config().copy(apiKey = null), ObservationRegistry.NOOP)
            }

            assertEquals("API key is required for DashScope provider", failure.message)
        }

        @Test
        fun `builds a model carrying the configured model id`() {
            val model = factory.create(config(baseUrl = "https://dashscope.aliyuncs.com/api/v1/"), ObservationRegistry.NOOP)

            assertEquals("qwen3-vl-plus", model.options.model)
        }
    }

    @Nested
    inner class `reasoning profile` {

        private fun row(
            mode: ReasoningMode,
            thinking: Boolean,
            effort: String? = null,
            levels: List<String> = listOf("low", "high", "max")
        ) = config().copy(
            capabilities = ModelCapabilities(reasoning = ReasoningProfile(mode, levels, thinking, effort)),
            options = ModelOptions(thinking = thinking, effort = effort, maxTokens = 4096)
        )

        @Test
        fun `thinking on with effort sends the level instead of a budget`() {
            val options = factory.build(row(ReasoningMode.TOGGLEABLE, thinking = true, effort = "high"), emptyList())
                as DashScopeChatOptions

            assertEquals(true, options.enableThinking)
            assertEquals("high", options.reasoningEffort)
            assertNull(options.thinkingBudget, "reasoning_effort and thinking_budget must not ride together")
            assertEquals(4096, options.maxTokens)
        }

        @Test
        fun `thinking on without effort keeps the fixed budget`() {
            val options = factory.build(row(ReasoningMode.TOGGLEABLE, thinking = true), emptyList())
                as DashScopeChatOptions

            assertEquals(true, options.enableThinking)
            assertNull(options.reasoningEffort)
            assertNotNull(options.thinkingBudget)
        }

        @Test
        fun `thinking off disables it explicitly and drops effort`() {
            val options = factory.build(row(ReasoningMode.TOGGLEABLE, thinking = false, effort = "max"), emptyList())
                as DashScopeChatOptions

            assertEquals(false, options.enableThinking)
            assertNull(options.reasoningEffort)
        }

        @Test
        fun `none sends no thinking controls at all`() {
            val options = factory.build(row(ReasoningMode.NONE, thinking = true, effort = "max"), emptyList())
                as DashScopeChatOptions

            assertNull(options.enableThinking)
            assertNull(options.thinkingBudget)
            assertNull(options.reasoningEffort)
        }

        @Test
        fun `always on reasons regardless of the stored switch`() {
            val options = factory.build(row(ReasoningMode.ALWAYS_ON, thinking = false, effort = "max"), emptyList())
                as DashScopeChatOptions

            assertEquals(true, options.enableThinking)
            assertEquals("max", options.reasoningEffort)
        }
    }

    @Nested
    inner class `protocol matrix` {

        @ParameterizedTest(name = "{0} thinking={1} effort={2}")
        @MethodSource("com.easy.easyai.autoconfigure.dashscope.DashScopeChatModelFactoryTest#reasoningMatrix")
        fun `thinking controls and effort follow the mode`(
            mode: ReasoningMode?,
            thinking: Boolean,
            effort: String?,
            expectedEnableThinking: Boolean?,
            expectedEffort: String?,
            expectedBudget: Boolean
        ) {
            val caps = mode?.let {
                ModelCapabilities(
                    reasoning = ReasoningProfile(it, listOf("low", "high", "max"), defaultThinking = true)
                )
            }
            val options = factory.build(
                config().copy(
                    capabilities = caps,
                    options = ModelOptions(thinking = thinking, effort = effort, maxTokens = 1000)
                ),
                emptyList()
            ) as DashScopeChatOptions

            assertEquals(expectedEnableThinking, options.enableThinking)
            assertEquals(expectedEffort, options.reasoningEffort)
            assertEquals(expectedBudget, options.thinkingBudget != null)
        }
    }

    @Nested
    inner class `sampling capability gate` {

        private fun row(sampling: SamplingSupport) = config()
            .copy(
                capabilities = ModelCapabilities(vision = true, sampling = sampling),
                options = ModelOptions(temperature = 0.7, maxTokens = 2_000)
            )

        @Test
        fun `a settable row keeps its temperature on the defaults the cached client carries`() {
            val options = factory.create(row(SamplingSupport.SETTABLE), ObservationRegistry.NOOP).options
                as DashScopeChatOptions

            assertEquals(0.7, options.temperature)
        }

        @Test
        fun `a NOT_SETTABLE row never reaches the defaults either`() {
            // create() bakes the row into defaultOptions here, and a portable per-call ChatOptions
            // can only override them, never clear them — so the capability has to be applied at
            // construction or a caller that pinned nothing still sends 0.7 and eats the 400.
            val model = factory.create(row(SamplingSupport.NOT_SETTABLE), ObservationRegistry.NOOP).options
                as DashScopeChatOptions

            assertNull(model.temperature)
            assertEquals(2_000, model.maxTokens)
        }

        @Test
        fun `per-turn options honour the same gate`() {
            val options = factory.build(row(SamplingSupport.NOT_SETTABLE), emptyList()) as DashScopeChatOptions

            assertNull(options.temperature)
        }
    }

    companion object {
        /** protocol × mode × thinking × effort matrix: effort and budget never ride together. */
        @JvmStatic
        fun reasoningMatrix(): List<Arguments> = listOf(
            Arguments.of(ReasoningMode.TOGGLEABLE, true, "high", true, "high", false),
            Arguments.of(ReasoningMode.TOGGLEABLE, true, null, true, null, true),
            Arguments.of(ReasoningMode.TOGGLEABLE, false, "max", false, null, false),
            Arguments.of(ReasoningMode.ALWAYS_ON, false, "max", true, "max", false),
            Arguments.of(ReasoningMode.ALWAYS_ON, false, null, true, null, true),
            Arguments.of(ReasoningMode.NONE, true, "max", null, null, false),
            Arguments.of(ReasoningMode.UNKNOWN, true, "high", true, null, true),
            Arguments.of(ReasoningMode.UNKNOWN, false, "high", false, "high", false),
            Arguments.of(null, true, "high", true, null, true),
            Arguments.of(null, false, "high", false, "high", false),
            Arguments.of(null, false, null, false, null, false),
        )
    }
}
