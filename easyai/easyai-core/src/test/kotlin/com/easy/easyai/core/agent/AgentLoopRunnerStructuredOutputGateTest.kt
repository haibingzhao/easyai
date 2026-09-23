package com.easy.easyai.core.agent

import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.core.message.DefaultMessageConverter
import com.easy.easyai.core.model.UserMessage
import com.easy.easyai.core.prompt.PromptTemplateService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.ChatOptions
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for the timing gate in AgentLoopRunner.preparePrompt: the output schema is only
 * handed to the protocol factory when it is due (single-turn always, multi-turn only
 * after the completion check enabled forced structured output). Capability support and
 * protocol expressibility are gated downstream inside ChatModelFactory.build.
 */
class AgentLoopRunnerStructuredOutputGateTest {

    private val schema = """{"type":"object","properties":{"answer":{"type":"string"}}}"""

    private val modelConfig = ModelProviderConfig(
        id = "cfg-1",
        name = "test",
        protocol = Protocol.ANTHROPIC,
        isCustom = false,
        modelId = "test-model"
    )

    private fun stubBuildChatOptions(services: AgentService, capturedSchemas: MutableList<String?>) {
        every { services.buildChatOptions(any(), any(), any()) } answers {
            capturedSchemas.add(thirdArg<String?>())
            ChatOptions.builder().model("test-model").build()
        }
    }

    private fun createRunner(multiTurn: Boolean, capturedSchemas: MutableList<String?>): AgentLoopRunner {
        val services = mockk<AgentService>(relaxed = true)
        val templateService = mockk<PromptTemplateService>(relaxed = true)
        every { templateService.build(any(), any()) } returns "system prompt"
        every { services.messageConverter } returns DefaultMessageConverter()
        every { services.promptTemplateService } returns templateService
        stubBuildChatOptions(services, capturedSchemas)
        val context = AgentContext(
            agentId = "agent-1",
            modelConfig = modelConfig,
            outputSchema = schema,
            outputSchemaMultiTurn = multiTurn
        )
        return AgentLoopRunner(context, mockk<ChatModel>(relaxed = true), services)
    }

    @Test
    fun `single-turn mode passes schema every turn`() = runTest {
        val captured = mutableListOf<String?>()
        val runner = createRunner(multiTurn = false, capturedSchemas = captured)
        runner.preparePrompt(listOf(UserMessage("hi")), emptyList())
        assertEquals<List<String?>>(listOf(schema), captured)
    }

    @Test
    fun `multi-turn mode defers schema until forced`() = runTest {
        val captured = mutableListOf<String?>()
        val runner = createRunner(multiTurn = true, capturedSchemas = captured)
        runner.preparePrompt(listOf(UserMessage("hi")), emptyList())
        runner.enableForcedStructuredOutput()
        runner.preparePrompt(listOf(UserMessage("hi")), emptyList())
        assertEquals(listOf(null, schema), captured)
    }

    @Test
    fun `context without outputSchema always passes null`() = runTest {
        val captured = mutableListOf<String?>()
        val services = mockk<AgentService>(relaxed = true)
        val templateService = mockk<PromptTemplateService>(relaxed = true)
        every { templateService.build(any(), any()) } returns "system prompt"
        every { services.messageConverter } returns DefaultMessageConverter()
        every { services.promptTemplateService } returns templateService
        stubBuildChatOptions(services, captured)
        val context = AgentContext(agentId = "agent-1", modelConfig = modelConfig)
        val runner = AgentLoopRunner(context, mockk<ChatModel>(relaxed = true), services)

        runner.preparePrompt(listOf(UserMessage("hi")), emptyList())
        runner.enableForcedStructuredOutput()
        runner.preparePrompt(listOf(UserMessage("hi")), emptyList())
        assertEquals(listOf<String?>(null, null), captured)
    }
}
