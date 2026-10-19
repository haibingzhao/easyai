package com.easy.easyai.autoconfigure.dashscope

import com.easy.easyai.api.model.ModelCapabilities
import com.easy.easyai.api.model.ModelOptions
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.SamplingSupport
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
}
