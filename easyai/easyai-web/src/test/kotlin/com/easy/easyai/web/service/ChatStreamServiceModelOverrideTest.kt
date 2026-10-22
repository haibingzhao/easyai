package com.easy.easyai.web.service

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.model.ModelCapabilities
import com.easy.easyai.api.model.ModelOptions
import com.easy.easyai.api.model.ModelOverrides
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.ReasoningMode
import com.easy.easyai.api.model.ReasoningProfile
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.ChatSession
import com.easy.easyai.core.agent.SessionManager
import com.easy.easyai.core.event.AgentEndEvent
import com.easy.easyai.core.event.AgentEvent
import com.easy.easyai.core.event.EventStream
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.repository.session.AsyncSessionStore
import com.easy.easyai.web.model.ChatRequest
import com.easy.easyai.web.model.ChatStreamEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The composer override path: request-side validation rejects unsupported values before a session
 * opens, and an accepted override is both applied to the run config and recorded on the session.
 */
internal class ChatStreamServiceModelOverrideTest {

    private val manager = mockk<SessionManager>(relaxed = true)
    private val configStore = mockk<ModelProviderConfigStore>()
    private val factory = mockk<ChatModelFactory>()
    private val sessionStore = mockk<AsyncSessionStore>(relaxed = true)
    private val session = mockk<ChatSession>(relaxed = true)
    private val stream = mockk<EventStream<AgentEvent, List<AssistantMessage>>>()
    private val appliedConfig = slot<ModelProviderConfig>()

    private val profile = ReasoningProfile(
        mode = ReasoningMode.TOGGLEABLE,
        effortLevels = listOf("high", "max"),
        defaultThinking = true,
        defaultEffort = "high",
        source = ReasoningProfile.SOURCE_MANUAL
    )
    private val config = ModelProviderConfig(
        id = "model-1",
        name = "Test model",
        protocol = Protocol.OPENAI,
        isCustom = false,
        modelId = "test-model",
        options = ModelOptions(thinking = true, maxContextTokens = 131_072, contextToken = 131_072),
        capabilities = ModelCapabilities(reasoning = profile)
    )
    private val service = ChatStreamService(
        manager,
        configStore,
        listOf(factory),
        sessionStore = sessionStore
    )
    private val request = ChatRequest(
        sessionId = "session-1",
        message = "hi",
        modelProviderConfigId = config.id
    )

    @BeforeEach
    fun setup() {
        coEvery { configStore.getConfig(config.id, any<Collection<String>>()) } returns config
        every { factory.supports(config.protocol) } returns true
        coEvery { sessionStore.isSessionOwnedByUser(any(), any()) } returns true
        coEvery { sessionStore.findById(any(), any()) } returns null
        coEvery { manager.getOrCreateSession(any(), any(), factory) } returns session
        coEvery { manager.loadMessages(any()) } returns emptyList()
        every { session.id } returns "session-1"
        every { session.agentContext } returns AgentContext(
            agentId = "default-agent",
            modelConfig = config,
            sessionId = "session-1",
            userId = "alice"
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
    fun `an invalid override is rejected before the session opens`() = runTest {
        val events = service.streamChat(
            request.copy(modelOverrides = ModelOverrides(effort = "low")),
            "alice"
        ).toList().mapNotNull { it.data() }

        val error = assertIs<ChatStreamEvent.Error>(events.single())
        assertTrue(error.errorMessage!!.contains("accepts effort"), error.errorMessage)
        coVerify(exactly = 0) { manager.getOrCreateSession(any(), any(), any()) }
    }

    @Test
    fun `an applied override reaches the run and the session record`() = runTest {
        val events = service.streamChat(
            request.copy(modelOverrides = ModelOverrides(effort = "max", contextToken = 65_536)),
            "alice"
        ).toList()

        coVerify(exactly = 1) { manager.getOrCreateSession(any(), capture(appliedConfig), factory) }
        assertEquals("max", appliedConfig.captured.options?.effort)
        assertEquals(65_536, appliedConfig.captured.options?.contextToken)
        coVerify(exactly = 1) {
            sessionStore.saveModelOverrides(
                "session-1",
                match {
                    it != null &&
                        it.contains("\"effort\":\"max\"") &&
                        it.contains("\"contextToken\":65536")
                }
            )
        }
        assertTrue(events.mapNotNull { it.data() }.any { it is ChatStreamEvent.UserMessageAck })
    }
}
