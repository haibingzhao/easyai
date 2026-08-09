package com.easy.easyai.core.resilience

import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.core.agent.AgentContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Unit tests for [LlmCircuitBreakerRegistry] key normalization,
 * instance sharing and the kill switch.
 */
class LlmCircuitBreakerRegistryTest {

    @BeforeEach
    fun setUp() {
        LlmCircuitBreakerRegistry.reset()
        System.clearProperty("easyai.llm.circuit-breaker.enabled")
    }

    @AfterEach
    fun tearDown() {
        LlmCircuitBreakerRegistry.reset()
        System.clearProperty("easyai.llm.circuit-breaker.enabled")
    }

    private fun context(baseUrl: String?, modelId: String = "test-model") = AgentContext(
        agentId = "test",
        modelConfig = ModelProviderConfig(
            id = "cfg-1",
            name = "test",
            protocol = Protocol.OPENAI,
            isCustom = true,
            baseUrl = baseUrl,
            modelId = modelId
        )
    )

    @Nested
    inner class `key normalization` {

        @Test
        fun `lowercases and strips trailing slash`() {
            assertEquals(
                "OPENAI:https://api.example.com/v1",
                LlmCircuitBreakerRegistry.keyFor("OPENAI", "https://API.Example.com/v1/")
            )
        }

        @Test
        fun `null blank or slash-only baseUrl falls back to default`() {
            assertEquals("OPENAI:default", LlmCircuitBreakerRegistry.keyFor("OPENAI", null))
            assertEquals("OPENAI:default", LlmCircuitBreakerRegistry.keyFor("OPENAI", ""))
            assertEquals("OPENAI:default", LlmCircuitBreakerRegistry.keyFor("OPENAI", "  "))
            assertEquals("OPENAI:default", LlmCircuitBreakerRegistry.keyFor("OPENAI", "///"))
        }

        @Test
        fun `different protocols never share a key`() {
            // Content comparison: assertNotSame on Strings would always pass,
            // because interpolation produces a fresh instance every time.
            assertNotEquals(
                LlmCircuitBreakerRegistry.keyFor("OPENAI", "https://api.example.com"),
                LlmCircuitBreakerRegistry.keyFor("ANTHROPIC", "https://api.example.com")
            )
        }
    }

    @Nested
    inner class `breaker resolution` {

        @Test
        fun `same endpoint shares one breaker across models`() {
            val a = LlmCircuitBreakerRegistry.forContext(context("https://gw.example.com", "model-a"))
            val b = LlmCircuitBreakerRegistry.forContext(context("https://gw.example.com/", "model-b"))
            assertNotNull(a)
            assertSame(a, b)
        }

        @Test
        fun `different endpoints get isolated breakers`() {
            val a = LlmCircuitBreakerRegistry.forContext(context("https://gw-a.example.com"))
            val b = LlmCircuitBreakerRegistry.forContext(context("https://gw-b.example.com"))
            assertNotNull(a)
            assertNotNull(b)
            assertNotSame(a, b)
        }

        @Test
        fun `returns null when context has no model config`() {
            assertNull(LlmCircuitBreakerRegistry.forContext(AgentContext(agentId = "test")))
        }

        @Test
        fun `kill switch disables the breaker`() {
            System.setProperty("easyai.llm.circuit-breaker.enabled", "false")
            assertNull(LlmCircuitBreakerRegistry.forContext(context("https://gw.example.com")))
        }

        @Test
        fun `reset clears all breaker state`() {
            val before = LlmCircuitBreakerRegistry.forContext(context("https://gw.example.com"))
            LlmCircuitBreakerRegistry.reset()
            val after = LlmCircuitBreakerRegistry.forContext(context("https://gw.example.com"))
            assertNotNull(before)
            assertNotNull(after)
            assertNotSame(before, after)
        }
    }
}
