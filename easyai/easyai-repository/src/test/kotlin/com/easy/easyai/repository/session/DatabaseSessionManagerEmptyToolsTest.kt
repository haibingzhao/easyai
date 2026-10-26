package com.easy.easyai.repository.session

import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.core.agent.Agent
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentDefinition
import com.easy.easyai.core.agent.AgentToolConfig
import com.easy.easyai.core.agent.AsyncAgentStore
import com.easy.easyai.core.agent.TargetType
import com.easy.easyai.core.tool.ToolDefinition
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the session manager does with the lists a caller hands it. They are final: an empty tool list is
 * the zero a schema-only background run asked for, not a request to resolve the default bag, and skills
 * are never filled in behind the caller's back. Sub-agents are the one field the manager resolves
 * itself, and it must keep doing so for agents that do have a row.
 */
internal class DatabaseSessionManagerEmptyToolsTest {
    private val sessionStore = mockk<AsyncSessionStore>(relaxed = true)
    private val agentFactory = mockk<SessionAgentFactory>()
    private val toolResolver = mockk<SessionToolResolver>(relaxed = true)
    private val agentStore = mockk<AsyncAgentStore>(relaxed = true)
    private val capturedTools = slot<List<ToolDefinition>>()
    private val capturedContext = slot<AgentContext>()
    private val config = ModelProviderConfig(
        id = "model", name = "model", protocol = Protocol.OPENAI, isCustom = false, modelId = "model"
    )
    /** The shape MemoryConsolidationAgent sends: an agentId with no row, no tools, and only a schema. */
    private val context = AgentContext(
        agentId = "memory-consolidation",
        sessionId = "session-1",
        userId = "alice",
        projectPath = Path.of("/workspace/project")
    )
    private val agentDef = AgentDefinition.create(id = "agent-1", name = "Agent", instructionsEnabled = false)
    private val subAgentDef = AgentDefinition.create(id = "explorer", name = "Explorer", instructionsEnabled = false)

    init {
        coEvery { sessionStore.findById(any(), any()) } returns null
        coEvery { sessionStore.loadVariablesFromCompactionSummary(any(), any()) } returns null
        coEvery { toolResolver.createSessionTools(any()) } returns listOf(mockk<ToolDefinition>(relaxed = true))
        coEvery {
            agentFactory.createAgentWithConfig(any(), any(), capture(capturedTools), capture(capturedContext))
        } returns mockk<Agent>()
        coEvery {
            agentFactory.createAgentWithAgentDef(any(), any(), any(), any(), capture(capturedContext))
        } returns mockk<Agent>()
    }

    private fun managerWithoutAgentRow() = DatabaseSessionManager(
        sessionStore = sessionStore,
        agentFactory = agentFactory,
        toolResolver = toolResolver,
        agentLookup = { _, _ -> null }
    )

    @Nested
    inner class NoAgentRow {
        @Test
        fun `an empty tool list stays empty, and nothing else is filled in either`() = runTest {
            managerWithoutAgentRow().getOrCreateSession(context, config, mockk())

            assertTrue(capturedTools.captured.isEmpty())
            coVerify(exactly = 0) { toolResolver.createSessionTools(any()) }
            assertTrue(capturedContext.captured.skills.isEmpty())
            assertTrue(capturedContext.captured.allowedSkillNames.isEmpty())
        }

        @Test
        fun `explicit tools are handed to the agent untouched`() = runTest {
            val stub = mockk<ToolDefinition>(relaxed = true)

            managerWithoutAgentRow().getOrCreateSession(context.copy(tools = listOf(stub)), config, mockk())

            assertEquals(listOf(stub), capturedTools.captured)
        }
    }

    @Nested
    inner class RuntimeOwnedSession {
        @Test
        fun `a background run ignores an agent row that shares its internal id`() = runTest {
            val manager = DatabaseSessionManager(
                sessionStore = sessionStore,
                agentFactory = agentFactory,
                toolResolver = toolResolver,
                agentLookup = { _, _ -> agentDef },
                agentStore = agentStore
            )
            val stub = mockk<ToolDefinition>(relaxed = true)

            manager.getOrCreateSession(
                context.copy(
                    agentId = agentDef.id,
                    systemKind = AgentContext.SYSTEM_KIND_ASYNC_TASK,
                    tools = listOf(stub)
                ),
                config,
                mockk()
            )

            assertEquals(listOf(stub), capturedTools.captured)
            coVerify(exactly = 0) {
                agentFactory.createAgentWithAgentDef(any(), any(), any(), any(), any())
            }
        }
    }

    @Nested
    inner class WithAgentRow {
        @Test
        fun `sub-agents the caller left empty are still resolved from the whitelist`() = runTest {
            coEvery {
                agentStore.getAgentToolConfigs(agentDef.id, TargetType.SUBAGENT, agentDef.userId)
            } returns listOf(AgentToolConfig("config-1", agentDef.id, TargetType.SUBAGENT, subAgentDef.id))
            coEvery { agentStore.findSubAgents(any<Collection<String>>()) } returns listOf(subAgentDef)
            val manager = DatabaseSessionManager(
                sessionStore = sessionStore,
                agentFactory = agentFactory,
                toolResolver = toolResolver,
                agentLookup = { _, _ -> agentDef },
                agentStore = agentStore
            )

            manager.getOrCreateSession(context.copy(agentId = agentDef.id), config, mockk())

            assertEquals(listOf(subAgentDef.id), capturedContext.captured.subAgents.map { it["id"] })
        }
    }
}
