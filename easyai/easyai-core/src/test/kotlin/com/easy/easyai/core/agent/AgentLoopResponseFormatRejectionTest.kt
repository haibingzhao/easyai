package com.easy.easyai.core.agent

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.llm.ChatModel
import com.easy.easyai.api.llm.ChatOptions
import com.easy.easyai.api.llm.ChatResponse
import com.easy.easyai.api.llm.DefaultChatOptions
import com.easy.easyai.api.llm.NonTransientAiException
import com.easy.easyai.api.llm.Prompt
import com.easy.easyai.api.llm.ToolCallback
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.core.event.AgentEvent
import com.easy.easyai.core.event.ErrorEvent
import com.easy.easyai.core.message.DefaultMessageConverter
import com.easy.easyai.core.model.UserMessage
import com.easy.easyai.core.prompt.PromptTemplateService
import com.easy.easyai.core.tool.DefaultToolExecutionEngine
import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for how [AgentLoop] answers a gateway that refuses the response format.
 *
 * The refusal is deterministic for the request as built, and the request as built is a configuration
 * fact about the model row: it declares a structured-output capability the model does not answer to.
 * So the turn must not be replayed, and the field must not be quietly dropped for the rest of the run
 * either — the user has to learn which row to edit, with the provider's own sentence attached.
 */
class AgentLoopResponseFormatRejectionTest {

    private val schema = """{"type":"object","properties":{"answer":{"type":"string"}}}"""

    private val gatewaySentence = "This response_format type is unavailable now"

    private val refusal = NonTransientAiException(
        "400: $gatewaySentence code=invalid_parameter_error type=invalid_request_error"
    )

    private val modelConfig = ModelProviderConfig(
        id = "cfg", name = "cfg", protocol = Protocol.OPENAI, isCustom = false, modelId = "deepseek-v4.1-flash"
    )

    private class RecordingFactory(private val chatModel: ChatModel) : ChatModelFactory {
        val askedSchemas = mutableListOf<String?>()

        override fun supports(protocol: Protocol): Boolean = true

        override fun create(config: ModelProviderConfig, observationRegistry: ObservationRegistry): ChatModel = chatModel

        override fun build(
            config: ModelProviderConfig,
            toolCallbacks: List<ToolCallback>,
            outputSchema: String?
        ): ChatOptions {
            askedSchemas += outputSchema
            return DefaultChatOptions(model = "test-model", outputSchema = outputSchema)
        }
    }

    private fun refusingModel(calls: MutableList<Prompt>): ChatModel {
        val mock = mockk<ChatModel>()
        every { mock.options } returns DefaultChatOptions(model = "test-model")
        every { mock.stream(any<Prompt>()) } answers {
            calls += firstArg<Prompt>()
            Flux.error<ChatResponse>(refusal)
        }
        return mock
    }

    private fun servicesWith(chatModel: ChatModel, factory: ChatModelFactory): DefaultAgentService {
        val promptService = mockk<PromptTemplateService>(relaxed = true)
        every { promptService.build(any(), any()) } returns "test prompt"
        return DefaultAgentService(
            chatModelFactories = listOf(factory),
            messageConverter = DefaultMessageConverter(),
            toolExecutor = DefaultToolExecutionEngine(),
            promptTemplateService = promptService,
            defaultChatModel = chatModel,
            eventListeners = emptyList(),
            completionChecks = emptyList(),
        )
    }

    private suspend fun run(context: AgentContext, chatModel: ChatModel, factory: ChatModelFactory): List<AgentEvent> {
        val events = CopyOnWriteArrayList<AgentEvent>()
        val runner = AgentRunner(
            agent = Agent(context, servicesWith(chatModel, factory)),
            messages = mutableListOf()
        )
        val stream = runner.prompt(listOf(UserMessage("group these")))
        stream.asFlow().collect { events += it }
        stream.result()
        return events.toList()
    }

    private fun contextWith(modelConfig: ModelProviderConfig?) = AgentContext(
        agentId = "memory-consolidation",
        customInstructions = "answer with json",
        modelConfig = modelConfig,
        outputSchema = schema,
        maxIterations = 2
    )

    private fun errorOf(events: List<AgentEvent>): ErrorEvent =
        events.filterIsInstance<ErrorEvent>().single()

    @Nested
    inner class `refused format surfaces the row to fix` {

        @Test
        fun `the turn is not replayed and the refusal is not swallowed`() = runBlocking {
            val prompts = mutableListOf<Prompt>()
            val chatModel = refusingModel(prompts)
            val factory = RecordingFactory(chatModel)

            val events = run(contextWith(modelConfig), chatModel, factory)

            assertEquals(1, prompts.size, "a refusal that cannot succeed on replay costs no second call")
            assertEquals(listOf<String?>(schema), factory.askedSchemas, "nothing is taken off the request behind the run")
            val error = errorOf(events)
            assertFalse(error.isRetryable, "a retry button offers an attempt that re-triggers the same 400")
            val message = error.error.message.orEmpty()
            assertTrue(message.contains("结构化输出能力"), "the message must name what to change: $message")
            assertTrue(message.contains(gatewaySentence), "the provider's own sentence must reach the user: $message")
        }

        @Test
        fun `the same holds when no model config resolved`() = runBlocking {
            // Without a row there is no protocol factory to consult, so the schema only rode the
            // portable options — the refusal still comes from the gateway and still needs the same fix.
            val prompts = mutableListOf<Prompt>()
            val chatModel = refusingModel(prompts)
            val factory = RecordingFactory(chatModel)

            val events = run(contextWith(null), chatModel, factory)

            assertEquals(1, prompts.size)
            assertTrue(factory.askedSchemas.isEmpty(), "no protocol factory was consulted at all")
            val error = errorOf(events)
            assertFalse(error.isRetryable)
            assertTrue(error.error.message.orEmpty().contains(gatewaySentence))
        }
    }
}
