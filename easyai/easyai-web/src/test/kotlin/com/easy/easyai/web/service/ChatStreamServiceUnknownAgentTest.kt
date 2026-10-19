package com.easy.easyai.web.service

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentDefinition
import com.easy.easyai.core.agent.AsyncAgentStore
import com.easy.easyai.core.agent.ChatSession
import com.easy.easyai.core.agent.SessionManager
import com.easy.easyai.core.event.AgentEndEvent
import com.easy.easyai.core.event.AgentEvent
import com.easy.easyai.core.event.EventStream
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.repository.session.AsyncSessionStore
import com.easy.easyai.repository.session.SessionExecutionService
import com.easy.easyai.web.model.ChatRequest
import com.easy.easyai.web.model.ChatStreamEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * An agentId with no row cannot resolve tools, and the session manager no longer substitutes a default
 * bag for one — so the chat entry rejects it here, where the id came from a user, instead of opening a
 * session that can do nothing and leaving the model to say so.
 */
internal class ChatStreamServiceUnknownAgentTest {

    private val manager = mockk<SessionManager>(relaxed = true)
    private val configStore = mockk<ModelProviderConfigStore>()
    private val factory = mockk<ChatModelFactory>()
    private val agentStore = mockk<AsyncAgentStore>()
    private val sessionStore = mockk<AsyncSessionStore>(relaxed = true)
    private val execution = mockk<SessionExecutionService>(relaxed = true)
    private val session = mockk<ChatSession>(relaxed = true)
    private val stream = mockk<EventStream<AgentEvent, List<AssistantMessage>>>()
    private val config = ModelProviderConfig("model-1", "Test model", Protocol.OPENAI, false, modelId = "test-model")
    private val service = ChatStreamService(
        manager, configStore, listOf(factory),
        sessionStore = sessionStore,
        executionService = execution,
        agentStore = agentStore
    )
    private val request = ChatRequest(
        sessionId = "session-1", message = "hi", modelProviderConfigId = config.id, agentId = "ghost-agent"
    )

    @BeforeEach
    fun setup() {
        coEvery { configStore.getConfig(config.id, any<Collection<String>>()) } returns config
        every { factory.supports(config.protocol) } returns true
        coEvery { sessionStore.isSessionOwnedByUser(any(), any()) } returns true
        // Relaxed would hand back a child mock here, and the turn would resolve its project against it.
        coEvery { sessionStore.findById(any(), any()) } returns null
        coEvery { manager.getOrCreateSession(any(), config, factory) } returns session
        coEvery { manager.loadMessages(any()) } returns emptyList()
        every { session.id } returns "session-1"
        every { session.agentContext } returns AgentContext(
            agentId = "ghost-agent", modelConfig = config, sessionId = "session-1"
        )
        every { session.promptWithHistory(any()) } returns stream
        every { stream.asFlow() } returns flowOf<AgentEvent>(AgentEndEvent("session-1", "completed"))
        coEvery { stream.result() } returns emptyList()
    }

    @AfterEach
    fun cleanup() {
        service.destroy()
    }

    @Test
    fun `an agentId with no row is rejected before a session is opened`() = runTest {
        // The Collection overload is the one the guard calls; a relaxed mock would answer the
        // (id, userId) one and steer the run straight into the session manager.
        coEvery { agentStore.findById(any(), any<Collection<String>>()) } returns null

        val events = service.streamChat(request, "alice").toList().mapNotNull { it.data() }

        val error = assertIs<ChatStreamEvent.Error>(events.single())
        assertTrue(error.errorMessage!!.contains("Agent not found: 'ghost-agent'"), error.errorMessage)
        coVerify(exactly = 0) { manager.getOrCreateSession(any(), any(), any()) }
    }

    @Test
    fun `a known agentId opens the session as before`() = runTest {
        coEvery {
            agentStore.findById(any(), any<Collection<String>>())
        } returns AgentDefinition.create(id = "ghost-agent", name = "Ghost")

        val events = service.streamChat(request, "alice").toList().mapNotNull { it.data() }

        val errors = events.filterIsInstance<ChatStreamEvent.Error>()
        assertTrue(errors.isEmpty(), errors.joinToString { it.errorMessage.orEmpty() })
        coVerify(exactly = 1) { manager.getOrCreateSession(any(), config, factory) }
    }
}
