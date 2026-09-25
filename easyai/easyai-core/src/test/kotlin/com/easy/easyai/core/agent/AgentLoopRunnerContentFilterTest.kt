package com.easy.easyai.core.agent

import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.core.event.AgentEvent
import com.easy.easyai.core.event.MessageEndEvent
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.StopReason
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.AssistantMessage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage as SpringAiAssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * When the provider's content safety policy aborts the stream mid-generation, the
 * chunks already produced must be persisted (mirroring the user-abort path) instead
 * of being discarded along with the [ContentFilteredException].
 */
class AgentLoopRunnerContentFilterTest {

    @BeforeEach
    fun setUp() {
        com.easy.easyai.core.resilience.LlmCircuitBreakerRegistry.reset()
    }

    @AfterEach
    fun tearDown() {
        com.easy.easyai.core.resilience.LlmCircuitBreakerRegistry.reset()
    }

    private val modelConfig = ModelProviderConfig(
        id = "cfg-1",
        name = "deepseek-gateway",
        protocol = Protocol.ANTHROPIC,
        isCustom = true,
        baseUrl = "https://gateway.example.com",
        modelId = "test-model"
    )

    private val contentFilterFrame =
        """200: {"request_id":"x","code":"InvalidParameter","message":"Output data may contain inappropriate content."}"""

    private fun streamingThenBlockedModel(vararg texts: String): ChatModel {
        val mock = mockk<ChatModel>()
        val chunks = texts.map { ChatResponse(listOf(Generation(SpringAiAssistantMessage(it)))) }
        every { mock.stream(any<Prompt>()) } answers {
            // Delay the error frame so the consumer drains the content chunks first,
            // mirroring production where chunks arrive over the network well before
            // the gateway aborts the stream.
            Flux.concat(
                Flux.fromIterable(chunks),
                Flux.error<ChatResponse>(RuntimeException(contentFilterFrame))
                    .delaySubscription(java.time.Duration.ofMillis(200))
            )
        }
        return mock
    }

    private fun createRunner(chatModel: ChatModel): AgentLoopRunner {
        val context = AgentContext(agentId = "investment-analyst", modelConfig = modelConfig)
        return AgentLoopRunner(context, chatModel, mockk<AgentService>(relaxed = true))
    }

    @Test
    fun `partial output is saved before throwing ContentFilteredException`() = runBlocking {
        val runner = createRunner(streamingThenBlockedModel("部分", "分析结果"))
        val transcript = mutableListOf<EasyAiMessage>()
        val events = mutableListOf<AgentEvent>()
        val prompt = Prompt(listOf(org.springframework.ai.chat.messages.UserMessage("hi")))

        assertFailsWith<ContentFilteredException> {
            runner.callLLMAndBuildResponse(transcript, prompt, "m1", 1) { events.add(it) }
        }

        val saved = transcript.filterIsInstance<AssistantMessage>().single()
        assertTrue(saved.stopReason == StopReason.ERROR)
        val text = saved.content.filterIsInstance<TextContent>().joinToString("") { it.text }
        assertTrue(text.contains("部分分析结果"), "expected streamed text to be persisted, got: $text")
        assertTrue(text.contains("内容安全策略截断"))
        assertTrue(events.any { it is MessageEndEvent && it.messageId == "m1" })
    }

    @Test
    fun `nothing is saved when the filter trips before any output`() = runBlocking {
        val runner = createRunner(streamingThenBlockedModel())
        val transcript = mutableListOf<EasyAiMessage>()
        val events = mutableListOf<AgentEvent>()
        val prompt = Prompt(listOf(org.springframework.ai.chat.messages.UserMessage("hi")))

        assertFailsWith<ContentFilteredException> {
            runner.callLLMAndBuildResponse(transcript, prompt, "m2", 1) { events.add(it) }
        }

        assertTrue(transcript.filterIsInstance<AssistantMessage>().isEmpty())
        assertTrue(events.none { it is MessageEndEvent })
    }
}
