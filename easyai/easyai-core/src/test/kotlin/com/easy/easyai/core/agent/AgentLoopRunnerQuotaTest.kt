package com.easy.easyai.core.agent

import com.easy.easyai.api.llm.ChatModel
import com.easy.easyai.api.llm.Prompt
import com.easy.easyai.api.llm.TransientAiException
import com.easy.easyai.api.llm.UserMessage
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.core.event.AgentEvent
import com.easy.easyai.core.event.RetryEvent
import com.easy.easyai.core.resilience.LlmCircuitBreakerRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Integration test: AgentLoopRunner + spent-quota classification.
 *
 * A 429 whose body says the purchased quota is gone is deterministic until the reset instant the
 * provider prints, so the runner must fail fast with [QuotaExhaustedException] instead of spending
 * the retry budget on attempts that cannot succeed — and it must not count the failure as an
 * endpoint outage, since the endpoint answered correctly.
 */
class AgentLoopRunnerQuotaTest {

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
        name = "token-plan-gateway",
        protocol = Protocol.ANTHROPIC,
        isCustom = true,
        baseUrl = "https://gateway.example.com",
        modelId = "test-model"
    )

    /** Verbatim shape of the Bailian Token Plan answer, as the adapter's mapping leaves it. */
    private val spentQuota =
        "429: 429: Your token-plan 1-month quota has been exhausted. The quota will reset at 10-19 16:00:00 UTC."

    private fun createRunner(chatModel: ChatModel): AgentLoopRunner {
        val context = AgentContext(agentId = "test", modelConfig = modelConfig, maxRetries = 3)
        return AgentLoopRunner(context, chatModel, mockk<AgentService>(relaxed = true))
    }

    @Test
    fun `a spent quota fails fast with the reset instant and spends no retry`() = runBlocking {
        val chatModel = mockk<ChatModel>()
        every { chatModel.stream(any<Prompt>()) } answers { Flux.error(TransientAiException(spentQuota)) }
        val pushed = mutableListOf<AgentEvent>()
        val prompt = Prompt(listOf(UserMessage("hi")))

        val e = assertFailsWith<QuotaExhaustedException> {
            createRunner(chatModel).callLLMAndBuildResponse(mutableListOf(), prompt, "m1", 1) { pushed.add(it) }
        }

        assertTrue(e.message!!.contains("额度已耗尽"), "expected a quota message, got: ${e.message}")
        assertTrue(e.message!!.contains("10-19 16:00:00 UTC"), "expected the reset instant, got: ${e.message}")
        assertTrue(pushed.none { it is RetryEvent }, "expected no RetryEvent, got $pushed")
        verify(exactly = 1) { chatModel.stream(any<Prompt>()) }
    }

    @Test
    fun `a quota error without a reset instant points at the plan instead`() = runBlocking {
        val chatModel = mockk<ChatModel>()
        every { chatModel.stream(any<Prompt>()) } answers {
            Flux.error(TransientAiException("429: 429: {\"error\":{\"code\":\"insufficient_quota\"}}"))
        }
        val prompt = Prompt(listOf(UserMessage("hi")))

        val e = assertFailsWith<QuotaExhaustedException> {
            createRunner(chatModel).callLLMAndBuildResponse(mutableListOf(), prompt, "m1", 1) { }
        }

        assertTrue(e.message!!.contains("套餐额度"), "expected a plan/billing hint, got: ${e.message}")
    }

    @Test
    fun `a spent quota never trips the endpoint breaker`() = runBlocking {
        val chatModel = mockk<ChatModel>()
        every { chatModel.stream(any<Prompt>()) } answers { Flux.error(TransientAiException(spentQuota)) }
        val runner = createRunner(chatModel)
        val prompt = Prompt(listOf(UserMessage("hi")))

        // More attempts than the breaker threshold: a healthy-but-unfunded endpoint keeps being
        // admitted, so the user sees the quota verdict rather than "endpoint unavailable".
        repeat(4) {
            assertFailsWith<QuotaExhaustedException> {
                runner.callLLMAndBuildResponse(mutableListOf(), prompt, "m$it", 1) { }
            }
        }
        verify(exactly = 4) { chatModel.stream(any<Prompt>()) }
    }
}
