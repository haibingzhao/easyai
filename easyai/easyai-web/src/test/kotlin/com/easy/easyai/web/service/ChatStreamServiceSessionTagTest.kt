package com.easy.easyai.web.service

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.ChatSession
import com.easy.easyai.core.agent.SessionManager
import com.easy.easyai.core.event.AgentEndEvent
import com.easy.easyai.core.event.AgentEvent
import com.easy.easyai.core.event.EventStream
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.UserMessage
import com.easy.easyai.repository.session.AsyncSessionStore
import com.easy.easyai.web.context.ChatSessionTagProvider
import com.easy.easyai.web.model.ChatRequest
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
import kotlin.test.assertTrue

/**
 * Session tags are written once for a fresh session, from the host's trusted agent context.
 * A tag failure is metadata-only and must not fail the chat turn.
 */
internal class ChatStreamServiceSessionTagTest {

    private val manager = mockk<SessionManager>(relaxed = true)
    private val configStore = mockk<ModelProviderConfigStore>()
    private val factory = mockk<ChatModelFactory>()
    private val session = mockk<ChatSession>(relaxed = true)
    private val sessionStore = mockk<AsyncSessionStore>(relaxed = true)
    private val stream = mockk<EventStream<AgentEvent, List<AssistantMessage>>>()
    private val config = ModelProviderConfig("model-1", "Test model", Protocol.OPENAI, false, modelId = "test-model")
    private val request = ChatRequest(
        sessionId = "session-1",
        message = "分析一下",
        agentId = "stock-analyst",
        modelProviderConfigId = config.id,
    )
    private val context = AgentContext(
        agentId = "stock-analyst",
        modelConfig = config,
        sessionId = "session-1",
        userId = "alice",
    )
    private val services = mutableListOf<ChatStreamService>()

    private fun service(provider: ChatSessionTagProvider) = ChatStreamService(
        manager,
        configStore,
        listOf(factory),
        sessionStore = sessionStore,
        sessionTagProviders = listOf(provider),
    ).also { services.add(it) }

    @BeforeEach
    fun setup() {
        coEvery { configStore.getConfig(config.id, any<Collection<String>>()) } returns config
        every { factory.supports(config.protocol) } returns true
        coEvery { sessionStore.isSessionOwnedByUser(any(), any()) } returns true
        coEvery { sessionStore.findById(any(), any()) } returns null
        coEvery { manager.getOrCreateSession(any(), config, factory) } returns session
        coEvery { manager.getSession(any(), any(), any<Collection<String>>()) } returns session
        coEvery { manager.loadMessages("session-1") } returns emptyList()
        every { session.id } returns "session-1"
        every { session.agentContext } returns context
        every { session.promptWithHistory(any()) } returns stream
        every { session.resume(messages = any()) } returns stream
        every { stream.asFlow() } returns flowOf(AgentEndEvent("session-1", "completed"))
        coEvery { stream.result() } returns emptyList()
    }

    @AfterEach
    fun cleanup() {
        services.forEach { it.destroy() }
    }

    @Test
    fun `a fresh session receives the provider tags once, normalized`() = runTest {
        service(ChatSessionTagProvider { listOf(" 股票 ", "股票", "报告") })
            .streamChat(request, "alice")
            .toList()

        coVerify(exactly = 1) {
            sessionStore.updateTags("session-1", listOf("股票", "报告"), "alice")
        }
    }

    @Test
    fun `a resumed session keeps its existing tags`() = runTest {
        coEvery { manager.loadMessages("session-1") } returns listOf(UserMessage("旧消息"))

        service(ChatSessionTagProvider { listOf("股票") })
            .streamChat(request, "alice")
            .toList()

        coVerify(exactly = 0) { sessionStore.updateTags(any(), any(), any()) }
    }

    @Test
    fun `a failing provider never fails the chat`() = runTest {
        val events = service(ChatSessionTagProvider { error("tag store down") })
            .streamChat(request, "alice")
            .toList()

        assertTrue(events.isNotEmpty())
        coVerify(exactly = 0) { sessionStore.updateTags(any(), any(), any()) }
    }
}
