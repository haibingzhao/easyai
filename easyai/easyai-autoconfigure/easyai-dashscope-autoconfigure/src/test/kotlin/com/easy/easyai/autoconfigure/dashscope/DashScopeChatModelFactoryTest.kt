package com.easy.easyai.autoconfigure.dashscope

import com.easy.easyai.api.model.ModelCapabilities
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
}
