package com.easy.easyai.compaction

import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.compaction.estimator.TokenEstimator
import com.easy.easyai.compaction.model.CompactionContext
import com.easy.easyai.compaction.strategy.CompactionStrategy
import com.easy.easyai.compaction.strategy.StrategyOutput
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.UserMessage
import com.easy.easyai.core.model.aux.AuxModelResolver
import com.easy.easyai.core.model.aux.AuxModelTask
import com.easy.easyai.core.model.aux.ResolvedAuxModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ChatModel
import kotlin.test.assertSame

/**
 * Verifies that [CompactionTransformContextService] overrides the compaction model with the
 * per-user configured COMPACTION model, and falls back to the session model when unconfigured.
 */
class CompactionTransformContextServiceTest {

    private val tokenEstimator = mockk<TokenEstimator>().apply {
        every { estimate(any()) } returns 10
        every { estimateContextTokens(any()) } returns 100
    }

    private val config = CompactionConfig(enabled = true, tailTurns = 1)

    private val sessionChatModel = mockk<ChatModel>()
    private val resolvedChatModel = mockk<ChatModel>()
    private val sessionConfig = mockk<ModelProviderConfig>()
    private val resolvedConfig = mockk<ModelProviderConfig>()

    private val contextSlot = slot<CompactionContext>()
    private val modelSlot = slot<ChatModel>()

    private val strategy = mockk<CompactionStrategy>().apply {
        coEvery { compactWithUsage(any(), capture(contextSlot), capture(modelSlot), any()) } returns
            StrategyOutput("summary")
    }

    private fun transcript(): List<EasyAiMessage> = listOf(
        UserMessage(content = listOf(TextContent("u1"))),
        AssistantMessage(content = listOf(TextContent("a1"))),
        UserMessage(content = listOf(TextContent("u2"))),
        AssistantMessage(content = listOf(TextContent("a2"))),
        UserMessage(content = listOf(TextContent("u3"))),
        AssistantMessage(content = listOf(TextContent("a3")))
    )

    private fun service(resolver: AuxModelResolver?) = CompactionTransformContextService(
        config = config,
        strategy = strategy,
        tokenEstimator = tokenEstimator,
        auxModelResolver = resolver
    )

    private suspend fun runManual(agentContext: AgentContext, resolver: AuxModelResolver?) =
        service(resolver).manualCompactWithPusher(
            agentContext = agentContext,
            messages = transcript(),
            turnId = 1,
            modelContextLength = 100_000,
            eventPusher = ContextCompactionOrchestrator.EventPusher { },
            chatModel = sessionChatModel
        )

    @Test
    fun `configured compaction model overrides session model and config`() = runTest {
        val resolver = mockk<AuxModelResolver>()
        coEvery { resolver.resolve("u1", AuxModelTask.COMPACTION) } returns
            ResolvedAuxModel(resolvedChatModel, resolvedConfig)

        runManual(AgentContext(agentId = "test", userId = "u1", modelConfig = sessionConfig), resolver)

        assertSame(resolvedChatModel, modelSlot.captured)
        assertSame(resolvedConfig, contextSlot.captured.modelConfig)
    }

    @Test
    fun `unconfigured falls back to the session model and config`() = runTest {
        val resolver = mockk<AuxModelResolver>()
        coEvery { resolver.resolve("u1", AuxModelTask.COMPACTION) } returns null

        runManual(AgentContext(agentId = "test", userId = "u1", modelConfig = sessionConfig), resolver)

        assertSame(sessionChatModel, modelSlot.captured)
        assertSame(sessionConfig, contextSlot.captured.modelConfig)
    }

    @Test
    fun `absent resolver falls back to the session model and config`() = runTest {
        runManual(AgentContext(agentId = "test", userId = "u1", modelConfig = sessionConfig), null)

        assertSame(sessionChatModel, modelSlot.captured)
        assertSame(sessionConfig, contextSlot.captured.modelConfig)
    }
}
