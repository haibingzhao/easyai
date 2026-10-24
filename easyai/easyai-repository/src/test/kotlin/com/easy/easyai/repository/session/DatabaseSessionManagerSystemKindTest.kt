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
 * `AgentContext.systemKind` / `AgentContext.sessionScope` are the markers the session row carries.
 * A new row must carry them in the insert; only a pre-existing row missing either marker needs the
 * follow-up write, so a scoped session's repeated turns cost no extra statements.
 */
class DatabaseSessionManagerSystemKindTest {

    private val store = mockk<AsyncSessionStore>(relaxed = true)
    private val agentFactory = mockk<SessionAgentFactory>(relaxed = true)
    private val toolResolver = mockk<SessionToolResolver>(relaxed = true)
    private val config = ModelProviderConfig("cfg", "Test", Protocol.OPENAI, false, modelId = "m")

    private fun manager() = DatabaseSessionManager(store, agentFactory, toolResolver)

    private fun context(kind: String?, scope: String? = null) = AgentContext(
        agentId = "page-assistant",
        sessionId = "s1",
        userId = "u1",
        systemKind = kind,
        sessionScope = scope,
    )

    private fun persist(sessionId: String, systemKind: String?, sessionScope: String? = null) = PersistedSession(
        id = sessionId,
        messages = emptyList(),
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        systemKind = systemKind,
        sessionScope = sessionScope,
    )

    private suspend fun run(context: AgentContext = context("page_assistant", SCOPE)) {
        manager().getOrCreateSession(context, config, mockk<ChatModelFactory>(relaxed = true))
    }

    @Test
    fun `a new marked session carries the markers in its insert`() = runTest {
        coEvery { store.findById("s1", "u1") } returns null
        coEvery { agentFactory.createAgentWithConfig(any(), any(), any(), any()) } returns
            mockk<Agent>(relaxed = true)
        val saved = slot<PersistedSession>()
        coEvery { store.save(capture(saved), "u1") } returns Unit

        run()

        assertEquals("page_assistant", saved.captured.systemKind)
        assertEquals(SCOPE, saved.captured.sessionScope)
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
    fun `an existing hidden row without a scope gets it backfilled once`() = runTest {
        coEvery { store.findById("s1", "u1") } returns persist("s1", systemKind = "page_assistant")
        coEvery { agentFactory.createAgentWithConfig(any(), any(), any(), any()) } returns
            mockk<Agent>(relaxed = true)

        run()

        coVerify(exactly = 1) { store.ensureSessionExists("s1", any()) }
        coVerify(exactly = 0) { store.save(any(), any()) }
    }

    @Test
    fun `an already marked and scoped row takes no marker write`() = runTest {
        coEvery { store.findById("s1", "u1") } returns
            persist("s1", systemKind = "page_assistant", sessionScope = SCOPE)
        coEvery { agentFactory.createAgentWithConfig(any(), any(), any(), any()) } returns
            mockk<Agent>(relaxed = true)

        run()

        coVerify(exactly = 0) { store.ensureSessionExists(any(), any()) }
    }

    @Test
    fun `a kind-only hidden row is not rewritten every turn`() = runTest {
        coEvery { store.findById("s1", "u1") } returns
            persist("s1", systemKind = AgentContext.SYSTEM_KIND_ASYNC_TASK)
        coEvery { agentFactory.createAgentWithConfig(any(), any(), any(), any()) } returns
            mockk<Agent>(relaxed = true)

        run(context(AgentContext.SYSTEM_KIND_ASYNC_TASK))

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
        assertEquals(null, saved.captured.sessionScope)
        coVerify(exactly = 0) { store.ensureSessionExists(any(), any()) }
    }

    private companion object {
        const val SCOPE = "page_assistant|f1|finance.transactions|"
    }
}
