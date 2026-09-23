package com.easy.easyai.autoconfigure.r2dbc

import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.autoconfigure.core.EasyAiProperties
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentDefinition
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.agent.AsyncAgentStore
import com.easy.easyai.repository.session.AsyncSessionStore
import com.easy.easyai.repository.session.SessionToolResolver
import com.easy.easyai.skills.SkillPromptSource
import com.easy.easyai.skills.SkillRegistry
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class R2dbcSkillWiringTest {
    private val properties = EasyAiProperties()
    private val configuration = R2dbcRepositoryAutoConfiguration(R2dbcProperties(), properties)
    private val registry = mockk<SkillRegistry>()
    private val toolResolver = mockk<SessionToolResolver>(relaxed = true)
    private val agentStore = mockk<AsyncAgentStore>(relaxed = true)
    private val projectPath = Path.of("/workspace/project")
    private val context = AgentContext(
        agentId = "default-agent",
        sessionId = "session-1",
        userId = "alice",
        projectPath = projectPath
    )
    private val inlineAgent = AgentDefinition.create(
        id = "inline:worker", name = "worker", instructionsEnabled = false
    )

    @Nested
    inner class MissingPromptSource {
        @Test
        fun `subagent never reads registry as a fallback for prompt skills`() = runBlocking {
            val resolver = configuration.subAgentContextResolver(toolResolver, agentStore, registry)
            val (resolved, _) = resolver.resolve(inlineAgent, context.copy(allowedSkillNames = listOf("pdf")))

            assertTrue(resolved.skills.isEmpty())
            assertEquals(listOf("pdf"), resolved.allowedSkillNames)
            verify { registry wasNot Called }
        }

        @Test
        fun `default agent has neither prompt skills nor authorization without prompt source`() = runBlocking {
            val sessionStore = mockk<AsyncSessionStore>(relaxed = true)
            coEvery { sessionStore.findById(any(), any()) } returns null
            coEvery { sessionStore.loadVariablesFromCompactionSummary(any(), any()) } returns null
            coEvery { agentStore.findById(any(), any()) } returns null
            val manager = configuration.sessionManager(
                sessionStore = sessionStore,
                agentStore = agentStore,
                agentService = mockk<AgentService>(relaxed = true),
                configStore = mockk(),
                sessionToolResolver = toolResolver,
                skillRegistry = registry
            )
            val config = ModelProviderConfig(
                id = "model", name = "model", protocol = Protocol.OPENAI, isCustom = false, modelId = "model"
            )
            val session = manager.getOrCreateSession(context, config, mockk())

            assertTrue(session.agentContext.skills.isEmpty())
            assertTrue(session.agentContext.allowedSkillNames.isEmpty())
            coVerify { toolResolver.createSessionTools(match { it.skills.isEmpty() && it.allowedSkillNames.isEmpty() }) }
            verify { registry wasNot Called }
        }
    }

    @Nested
    inner class PromptSourceWiring {
        @Test
        fun `registry presence only determines search availability for the prompt source`() = runBlocking {
            properties.skills.rag.enabled = true
            val source = mockk<SkillPromptSource>()
            val skills = listOf(mapOf<String, Any?>("name" to "pdf", "description" to "Read PDF"))
            coEvery { source.skillsForPrompt("alice", projectPath, listOf("pdf"), true) } returns skills
            coEvery { source.skillsForPrompt("alice", projectPath, listOf("pdf"), false) } returns skills
            val parent = context.copy(allowedSkillNames = listOf("pdf"))
            val withRegistry = configuration.subAgentContextResolver(toolResolver, agentStore, registry, source)
            val withoutRegistry = configuration.subAgentContextResolver(toolResolver, agentStore, skillPromptSource = source)

            assertEquals(skills, withRegistry.resolve(inlineAgent, parent).first.skills)
            assertEquals(skills, withoutRegistry.resolve(inlineAgent, parent).first.skills)
            coVerify(exactly = 1) { source.skillsForPrompt("alice", projectPath, listOf("pdf"), true) }
            coVerify(exactly = 1) { source.skillsForPrompt("alice", projectPath, listOf("pdf"), false) }
            verify { registry wasNot Called }
        }
    }
}
