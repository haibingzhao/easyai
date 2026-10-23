package com.easy.easyai.repository.session

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.core.agent.Agent
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.PersistedSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

/**
 * `AgentContext.systemKind` is the marker Chat History filters on. A new row must carry it in the
 * insert; only a pre-existing unmarked row needs the follow-up write, so a hidden session's
 * repeated turns cost no extra statements.
 */
class DatabaseSessionManagerSystemKindTest {

    private val store = mockk<AsyncSessionStore>(relaxed = true)
    private val agentFactory = mockk<SessionAgentFactory>(relaxed = true)
    private val toolResolver = mockk<SessionToolResolver>(relaxed = true)
    private val config = ModelProviderConfig("cfg", "Test", Protocol.OPENAI, false, modelId = "m")

    private fun manager() = DatabaseSessionManager(store, agentFactory, toolResolver)

    private fun context(kind: String?) = AgentContext(
        agentId = "page-assistant",
        sessionId = "s1",
        userId = "u1",
        systemKind = kind,
    )

    private fun persist(sessionId: String, systemKind: String?) = PersistedSession(
        id = sessionId,
        messages = emptyList(),
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        systemKind = systemKind,
    )

    private suspend fun run(context: AgentContext = context("page_assistant")) {
        manager().getOrCreateSession(context, config, mockk<ChatModelFactory>(relaxed = true))
    }

    @Test
    fun `a new marked session carries the marker in its insert`() = runTest {
        coEvery { store.findById("s1", "u1") } returns null
        coEvery { agentFactory.createAgentWithConfig(any(), any(), any(), any()) } returns
            mockk<Agent>(relaxed = true)
        val saved = slot<PersistedSession>()
        coEvery { store.save(capture(saved), "u1") } returns Unit

        run()

        assertEquals("page_assistant", saved.captured.systemKind)
        coVerify(exactly = 0) { store.ensureSessionExists(any(), any()) }
    }

    @Test
    fun `an existing unmarked row is marked once`() = runTest {
        coEvery { store.findById("s1", "u1") } returns persist("s1", systemKind = null)
        coEvery { agentFactory.createAgentWithConfig(any(), any(), any(), any()) } returns
            mockk<Agent>(relaxed = true)

        run()

        coVerify(exactly = 1) { store.ensureSessionExists("s1", any()) }
        coVerify(exactly = 0) { store.save(any(), any()) }
    }

    @Test
    fun `an already marked row takes no marker write`() = runTest {
        coEvery { store.findById("s1", "u1") } returns persist("s1", systemKind = "page_assistant")
        coEvery { agentFactory.createAgentWithConfig(any(), any(), any(), any()) } returns
            mockk<Agent>(relaxed = true)

        run()

        coVerify(exactly = 0) { store.ensureSessionExists(any(), any()) }
    }

    @Test
    fun `an ordinary session is not marked`() = runTest {
        coEvery { store.findById("s1", "u1") } returns null
        coEvery { agentFactory.createAgentWithConfig(any(), any(), any(), any()) } returns
            mockk<Agent>(relaxed = true)
        val saved = slot<PersistedSession>()
        coEvery { store.save(capture(saved), "u1") } returns Unit

        run(context(null))

        assertEquals(null, saved.captured.systemKind)
        coVerify(exactly = 0) { store.ensureSessionExists(any(), any()) }
    }
}
