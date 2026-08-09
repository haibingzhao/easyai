package com.easy.easyai.core.agent

import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.core.resilience.CircuitBreakerOpenException
import com.easy.easyai.core.resilience.LlmCircuitBreakerRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.web.client.ResourceAccessException
import reactor.core.publisher.Flux
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Integration test: AgentLoopRunner + endpoint circuit breaker.
 *
 * Scenario: the endpoint is unreachable. The first 3 outage-class failures
 * trip the breaker; the 4th attempt (normally another full-timeout retry)
 * fails fast with [CircuitBreakerOpenException] without touching the model,
 * and subsequent calls never reach the model at all.
 */
class AgentLoopRunnerCircuitBreakerTest {

    @BeforeEach
    fun setUp() {
        LlmCircuitBreakerRegistry.reset()
    }

    @AfterEach
    fun tearDown() {
        LlmCircuitBreakerRegistry.reset()
    }

    private val modelConfig = ModelProviderConfig(
        id = "cfg-1",
        name = "broken-gateway",
        protocol = Protocol.OPENAI,
        isCustom = true,
        baseUrl = "https://broken-gateway.example.com",
        modelId = "test-model"
    )

    private fun createRunner(chatModel: ChatModel): AgentLoopRunner {
        val context = AgentContext(
            agentId = "test",
            modelConfig = modelConfig,
            maxRetries = 3
        )
        return AgentLoopRunner(context, chatModel, mockk<AgentService>(relaxed = true))
    }

    private fun failingModel(): ChatModel {
        val mock = mockk<ChatModel>()
        every { mock.stream(any<Prompt>()) } answers {
            Flux.error(ResourceAccessException("I/O error on POST request: Connection refused"))
        }
        return mock
    }

    @Test
    fun `consecutive outage failures trip the breaker and the next attempt fails fast`() = runBlocking {
        val chatModel = failingModel()
        val runner = createRunner(chatModel)
        val prompt = Prompt(listOf(org.springframework.ai.chat.messages.UserMessage("hi")))

        // Attempts 1-3 fail and count toward the breaker (threshold 3);
        // attempt 4 is rejected by the open breaker before any model call.
        val e = assertFailsWith<CircuitBreakerOpenException> {
            runner.callLLMAndBuildResponse(mutableListOf(), prompt, "m1", 1) { }
        }
        assertTrue(e.message!!.contains("service unavailable"))
        verify(exactly = 3) { chatModel.stream(any<Prompt>()) }
    }

    @Test
    fun `once open a fresh call fails fast without touching the model`() = runBlocking {
        val chatModel = failingModel()
        val prompt = Prompt(listOf(org.springframework.ai.chat.messages.UserMessage("hi")))

        // Trip the breaker with the first runner.
        val first = createRunner(chatModel)
        assertFailsWith<CircuitBreakerOpenException> {
            first.callLLMAndBuildResponse(mutableListOf(), prompt, "m1", 1) { }
        }

        // A second runner (e.g. another session on the same endpoint) shares
        // the breaker state and fails fast with zero model calls.
        val second = createRunner(chatModel)
        assertFailsWith<CircuitBreakerOpenException> {
            second.callLLMAndBuildResponse(mutableListOf(), prompt, "m2", 1) { }
        }
        verify(exactly = 3) { chatModel.stream(any<Prompt>()) }
    }

    @Test
    fun `rate limit failures never trip the breaker`() = runBlocking {
        val chatModel = mockk<ChatModel>()
        every { chatModel.stream(any<Prompt>()) } answers {
            Flux.error(org.springframework.ai.retry.NonTransientAiException("429 Too Many Requests"))
        }
        val runner = createRunner(chatModel)
        val prompt = Prompt(listOf(org.springframework.ai.chat.messages.UserMessage("hi")))

        // 429 is not retryable by the runner either, so the first call throws
        // the original error — but crucially the breaker stays closed and a
        // later call is admitted (no CircuitBreakerOpenException).
        repeat(3) {
            runCatching { runner.callLLMAndBuildResponse(mutableListOf(), prompt, "m$it", 1) { } }
        }
        val e = assertFailsWith<org.springframework.ai.retry.NonTransientAiException> {
            runner.callLLMAndBuildResponse(mutableListOf(), prompt, "m-final", 1) { }
        }
        assertTrue(e.message!!.contains("429"))
        verify(exactly = 4) { chatModel.stream(any<Prompt>()) }
    }
}
