package com.easy.easyai.core.agent

import com.easy.easyai.api.llm.ChatModel
import com.easy.easyai.api.llm.DefaultChatOptions
import com.easy.easyai.api.model.ModelCapabilities
import com.easy.easyai.api.model.ModelOptions
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.SamplingIntent
import com.easy.easyai.api.model.SamplingSupport
import com.easy.easyai.core.message.DefaultMessageConverter
import com.easy.easyai.core.model.UserMessage
import com.easy.easyai.core.prompt.PromptTemplateService
import com.easy.easyai.core.tool.ToolDefinition
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the sampling seam in [AgentLoopRunner.preparePrompt]: the row a human tuned is passed to
 * the protocol factory untouched, an intent or a declared model capability rewrites it, and the
 * structured-output repair turn sends neither tool schemas nor a conversational temperature.
 *
 * The factory itself is mocked, so these assertions are about what easyai decides to ask for — the
 * adapters own what finally goes on the wire.
 */
class AgentLoopRunnerSamplingTest {

    private val capturedConfigs = mutableListOf<ModelProviderConfig>()
    private val capturedTools = mutableListOf<Int>()

    private fun runnerFor(
        config: ModelProviderConfig,
        intent: SamplingIntent
    ): AgentLoopRunner {
        val services = mockk<AgentService>(relaxed = true)
        val templateService = mockk<PromptTemplateService>(relaxed = true)
        every { templateService.build(any(), any()) } returns "system prompt"
        every { services.messageConverter } returns DefaultMessageConverter()
        every { services.promptTemplateService } returns templateService
        every { services.buildChatOptions(any(), any(), any()) } answers {
            capturedConfigs.add(firstArg())
            capturedTools.add(secondArg<List<com.easy.easyai.api.llm.ToolCallback>>().size)
            DefaultChatOptions(model = config.modelId)
        }
        return AgentLoopRunner(
            AgentContext(agentId = "agent-1", modelConfig = config, samplingIntent = intent),
            mockk<ChatModel>(relaxed = true),
            services
        )
    }

    private fun row(temperature: Double?, capabilities: ModelCapabilities? = null) = ModelProviderConfig(
        id = "cfg-1",
        name = "test",
        protocol = Protocol.OPENAI,
        isCustom = false,
        modelId = "qwen3.8-max",
        capabilities = capabilities,
        options = temperature?.let { ModelOptions(temperature = it, maxTokens = 65_536) }
    )

    private suspend fun AgentLoopRunner.prepare(vararg toolNames: String) =
        preparePrompt(
            listOf(UserMessage(content = listOf(com.easy.easyai.core.model.TextContent("go")))),
            toolNames.map { mockk<ToolDefinition>(relaxed = true).also { tool -> every { tool.name } returns it } }
        )

    @Test
    fun `follow config hands the factory the row a human tuned`() = runTest {
        val config = row(0.7)

        runnerFor(config, SamplingIntent.FOLLOW_CONFIG).prepare("bash")

        assertEquals(0.7, capturedConfigs.single().options?.temperature)
        assertEquals(1, capturedTools.single())
    }

    @Test
    fun `deterministic run pins sampling before the factory sees the row`() = runTest {
        val config = row(0.7)

        runnerFor(config, SamplingIntent.DETERMINISTIC).prepare("bash")

        val sent = capturedConfigs.single().options
        assertEquals(0.1, sent?.temperature)
        // Only sampling is rewritten; the row's budget and reasoning flags survive.
        assertEquals(65_536, sent?.maxTokens)
    }

    @Test
    fun `a model that rejects sampling never receives the row value`() = runTest {
        val config = row(0.7, ModelCapabilities(sampling = SamplingSupport.NOT_SETTABLE))

        runnerFor(config, SamplingIntent.FOLLOW_CONFIG).prepare("bash")

        assertNull(capturedConfigs.single().options?.temperature)
    }

    @Test
    fun `the repair turn sends no tool schemas and pins sampling`() = runTest {
        val runner = runnerFor(row(0.7), SamplingIntent.FOLLOW_CONFIG)

        runner.beginRepairTurn()
        runner.prepare("bash", "file_read")

        assertEquals(0.1, capturedConfigs.single().options?.temperature)
        assertEquals(0, capturedTools.single(), "a repair turn must not advertise tools")
        assertTrue(runner.repairTurn)
    }

    @Test
    fun `a row without an options block is not given one`() = runTest {
        val config = row(null)

        runnerFor(config, SamplingIntent.DETERMINISTIC).prepare("bash")

        assertNull(capturedConfigs.single().options)
    }
}
