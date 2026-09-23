package com.easy.easyai.repository.session

import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.core.agent.Agent
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentDefinition
import com.easy.easyai.core.agent.AgentToolConfig
import com.easy.easyai.core.agent.AsyncAgentStore
import com.easy.easyai.core.agent.TargetType
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

internal class DatabaseSessionManagerSkillTest {
    private val sessionStore = mockk<AsyncSessionStore>(relaxed = true)
    private val agentFactory = mockk<SessionAgentFactory>()
    private val toolResolver = mockk<SessionToolResolver>(relaxed = true)
    private val agentStore = mockk<AsyncAgentStore>(relaxed = true)
    private val capturedContext = slot<AgentContext>()
    private val projectPath = Path.of("/workspace/project")
    private val agentDef = AgentDefinition.create(id = "agent-1", name = "Agent", instructionsEnabled = false)
    private val config = ModelProviderConfig(
        id = "model", name = "model", protocol = Protocol.OPENAI, isCustom = false, modelId = "model"
    )
    private val context = AgentContext(
        agentId = agentDef.id, sessionId = "session-1", userId = "alice", projectPath = projectPath
    )

    init {
        coEvery { sessionStore.findById(any(), any()) } returns null
        coEvery { sessionStore.loadVariablesFromCompactionSummary(any(), any()) } returns null
        coEvery { agentStore.getAgentToolConfigs(agentDef.id, TargetType.SKILL) } returns listOf(
            AgentToolConfig("config-1", agentDef.id, TargetType.SKILL, "pdf")
        )
        coEvery {
            agentFactory.createAgentWithAgentDef(any(), any(), any(), any(), capture(capturedContext))
        } returns mockk<Agent>()
        coEvery {
            agentFactory.createAgentWithConfig(any(), any(), any(), capture(capturedContext))
        } returns mockk<Agent>()
    }

    @Nested
    inner class MissingSupplier {
        @Test
        fun `configured agent exposes no prompt skills even with a whitelist`() = runTest {
            val manager = DatabaseSessionManager(
                sessionStore = sessionStore,
                agentFactory = agentFactory,
                toolResolver = toolResolver,
                agentLookup = { _, _ -> agentDef },
                agentStore = agentStore
            )
            manager.getOrCreateSession(context, config, mockk())

            assertTrue(capturedContext.captured.skills.isEmpty())
            assertEquals(listOf("pdf"), capturedContext.captured.allowedSkillNames)
        }

        @Test
        fun `default agent has no skills or authorization when suppliers are absent`() = runTest {
            val manager = DatabaseSessionManager(sessionStore, agentFactory, toolResolver)
            manager.getOrCreateSession(context.copy(agentId = "default-agent"), config, mockk())

            assertTrue(capturedContext.captured.skills.isEmpty())
            assertTrue(capturedContext.captured.allowedSkillNames.isEmpty())
        }
    }

    @Nested
    inner class LiveSupplier {
        @Test
        fun `configured agent filters the supplied view by its whitelist on every request`() = runTest {
            val allowed = mapOf<String, Any?>("name" to "pdf", "description" to "Read PDF")
            val other = mapOf<String, Any?>("name" to "other", "description" to "Other skill")
            var view = listOf(allowed, other)
            val manager = DatabaseSessionManager(
                sessionStore = sessionStore,
                agentFactory = agentFactory,
                toolResolver = toolResolver,
                agentLookup = { _, _ -> agentDef },
                agentStore = agentStore,
                skillsSupplier = { userId, path, names ->
                    assertEquals("alice", userId)
                    assertEquals(projectPath, path)
                    assertEquals(listOf("pdf"), names)
                    view
                }
            )
            manager.getOrCreateSession(context, config, mockk())
            assertEquals(listOf(allowed), capturedContext.captured.skills)
            assertEquals(listOf("pdf"), capturedContext.captured.allowedSkillNames)
            coVerify { toolResolver.resolveToolsForAgent(agentDef, match { it.skills == listOf(allowed) }) }

            view = emptyList()
            manager.getOrCreateSession(context, config, mockk())
            assertTrue(capturedContext.captured.skills.isEmpty())
        }
    }
}
