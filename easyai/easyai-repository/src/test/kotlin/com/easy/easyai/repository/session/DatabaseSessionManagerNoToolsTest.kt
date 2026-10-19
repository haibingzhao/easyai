package com.easy.easyai.repository.session

import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.core.agent.Agent
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.tool.ToolDefinition
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the two ways a caller's tool list is read: an empty one means "not specified" and pulls in the
 * default bag, while [AgentContext.noTools] must actually deliver the zero the caller asked for — the
 * schema-only background runs whose prompt tells the model it has no tools.
 */
internal class DatabaseSessionManagerNoToolsTest {
    private val sessionStore = mockk<AsyncSessionStore>(relaxed = true)
    private val agentFactory = mockk<SessionAgentFactory>()
    private val toolResolver = mockk<SessionToolResolver>(relaxed = true)
    private val capturedTools = slot<List<ToolDefinition>>()
    private val config = ModelProviderConfig(
        id = "model", name = "model", protocol = Protocol.OPENAI, isCustom = false, modelId = "model"
    )
    private val context = AgentContext(
        agentId = "memory-consolidation",
        sessionId = "session-1",
        userId = "alice",
        projectPath = Path.of("/workspace/project")
    )

    init {
        coEvery { sessionStore.findById(any(), any()) } returns null
        coEvery { sessionStore.loadVariablesFromCompactionSummary(any(), any()) } returns null
        coEvery { toolResolver.createSessionTools(any()) } returns listOf(mockk<ToolDefinition>(relaxed = true))
        coEvery {
            agentFactory.createAgentWithConfig(any(), any(), capture(capturedTools), any())
        } returns mockk<Agent>()
    }

    private fun manager() = DatabaseSessionManager(
        sessionStore = sessionStore,
        agentFactory = agentFactory,
        toolResolver = toolResolver,
        agentLookup = { _, _ -> null }
    )

    @Test
    fun `no tools means no tools, and the default bag is never built`() = runTest {
        manager().getOrCreateSession(context.copy(noTools = true), config, mockk())

        assertTrue(capturedTools.captured.isEmpty())
        coVerify(exactly = 0) { toolResolver.createSessionTools(any()) }
    }

    @Test
    fun `an unspecified tool list still resolves the default bag`() = runTest {
        manager().getOrCreateSession(context, config, mockk())

        assertEquals(1, capturedTools.captured.size)
    }
}
