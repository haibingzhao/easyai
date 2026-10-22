package com.easy.easyai.repository.session

import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
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
 * [DatabaseSessionManager] resolves a session's model config while restoring it — both for
 * [DatabaseSessionManager.getSessionContext] (session details, checkpoints, diffs, file review) and
 * for [DatabaseSessionManager.getSession] (chat resume). Both lookups must use the caller's whole
 * owner set; resolving by the bare principal hides a family-owned model and turns those paths into
 * errors for every member.
 */
class DatabaseSessionManagerContextOwnersTest {

    private val store = mockk<R2dbcAsyncSessionStore>(relaxed = true)
    private val configStore = mockk<ModelProviderConfigStore>()

    private val familyConfig = ModelProviderConfig(
        id = "cfg-family",
        name = "family",
        protocol = Protocol.OPENAI,
        isCustom = false,
        modelId = "family-model"
    )

    @Test
    fun `session context resolves the family model across the caller owner set`() = runTest {
        coEvery { store.findById("s1", "alice") } returns PersistedSession(
            "s1", emptyList(), createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH
        )
        coEvery { store.getLastMessageConfig("s1") } returns
            LastMessageConfig(agentId = "agent-1", configId = "cfg-family", modelId = "family-model")
        coEvery { store.findModelOverrides("s1") } returns null
        val owners = listOf("alice", "g_fam-1", "system")
        coEvery { configStore.getConfig("cfg-family", owners) } returns familyConfig

        val manager = DatabaseSessionManager(
            sessionStore = store,
            agentFactory = mockk(relaxed = true),
            toolResolver = mockk(relaxed = true),
            configStore = configStore
        )

        val context = manager.getSessionContext("s1", "alice", owners)

        assertEquals("cfg-family", context?.modelConfig?.id)
        coVerify(exactly = 1) { configStore.getConfig("cfg-family", owners) }
    }

    @Test
    fun `session restoration resolves the family model across the caller owner set`() = runTest {
        coEvery { store.findById("s1", "alice") } returns PersistedSession(
            "s1", emptyList(), createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH
        )
        coEvery { store.getLastMessageConfig("s1") } returns
            LastMessageConfig(agentId = null, configId = "cfg-family", modelId = "family-model")
        val owners = listOf("alice", "g_fam-1", "system")
        coEvery { configStore.getConfig("cfg-family", owners) } returns familyConfig

        val agentFactory = mockk<SessionAgentFactory>(relaxed = true)
        val restoredContext = slot<AgentContext>()
        coEvery {
            agentFactory.createAgentWithConfig("s1", familyConfig, any(), capture(restoredContext))
        } returns mockk(relaxed = true)
        val manager = DatabaseSessionManager(
            sessionStore = store,
            agentFactory = agentFactory,
            toolResolver = mockk(relaxed = true),
            configStore = configStore
        )

        manager.getSession("s1", "alice", owners)

        assertEquals("cfg-family", restoredContext.captured.modelConfig?.id)
        coVerify(exactly = 1) { configStore.getConfig("cfg-family", owners) }
    }
}
