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
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.repository.session.SessionExecutionService
import com.easy.easyai.skills.selection.SkillTurnRouter
import com.easy.easyai.web.model.ChatRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Skill routing is a per-message narrowing of the turn's prompt visibility: a router hit must
 * rewrite `AgentContext.skills` before the prompt is built, and any other outcome must leave the
 * session exactly as the baseline recovery built it.
 */
internal class ChatStreamServiceSkillRoutingTest {

    private val manager = mockk<SessionManager>(relaxed = true)
    private val configStore = mockk<ModelProviderConfigStore>()
    private val factory = mockk<ChatModelFactory>()
    private val session = mockk<ChatSession>(relaxed = true)
    private val router = mockk<SkillTurnRouter>()
    private val execution = mockk<SessionExecutionService>(relaxed = true)
    private val stream = mockk<EventStream<AgentEvent, List<AssistantMessage>>>()
    private val config = ModelProviderConfig("model-1", "Test model", Protocol.OPENAI, false, modelId = "test-model")
    private val context = AgentContext(
        "default-agent", modelConfig = config, sessionId = "session-1", userId = "alice",
        allowedSkillNames = listOf("poster", "review")
    )
    private val routed = listOf(mapOf("name" to "poster", "description" to "海报生成"))
    private val prompted = slot<List<EasyAiMessage>>()

    private fun service(withRouting: Boolean) = ChatStreamService(
        manager, configStore, listOf(factory), executionService = execution,
        skillTurnRouter = if (withRouting) router else null
    )

    private val request = ChatRequest(sessionId = "session-1", message = "帮我生成一张海报", modelProviderConfigId = config.id)

    @BeforeEach
    fun setup() {
        coEvery { configStore.getConfig(config.id, "alice") } returns config
        every { factory.supports(config.protocol) } returns true
        coEvery { manager.getOrCreateSession(any(), config, factory) } returns session
        coEvery { manager.loadMessages("session-1") } returns emptyList()
        every { session.id } returns "session-1"
        every { session.agentContext } returns context
        every { session.promptWithHistory(capture(prompted)) } returns stream
        every { execution.getActiveSession("session-1") } returns session
        every { stream.asFlow() } returns flowOf(AgentEndEvent("session-1", "completed"))
        coEvery { stream.result() } returns emptyList()
    }

    @AfterEach
    fun cleanup() {
        service(withRouting = false).destroy()
    }

    @Nested
    inner class ChatEntry {

        @Test
        fun `a routing hit rewrites this turn's skills before prompting`() = runTest {
            val service = service(withRouting = true)
            coEvery { router.route("alice", listOf("poster", "review"), "帮我生成一张海报") } returns routed

            service.streamChat(request, "alice").toList()

            verify(exactly = 1) { session.updateTurnSkills(routed) }
        }

        @Test
        fun `a routing miss leaves the session's baseline visibility untouched`() = runTest {
            val service = service(withRouting = true)
            coEvery { router.route(any(), any(), any()) } returns null

            service.streamChat(request, "alice").toList()

            verify(exactly = 0) { session.updateTurnSkills(any()) }
        }

        @Test
        fun `without a router bean nothing is consulted`() = runTest {
            val service = service(withRouting = false)

            service.streamChat(request, "alice").toList()

            coVerify(exactly = 0) { router.route(any(), any(), any()) }
            verify(exactly = 0) { session.updateTurnSkills(any()) }
        }
    }

    @Nested
    inner class ResumeEntry {

        @Test
        fun `resume with a message routes on that raw message`() = runTest {
            val service = service(withRouting = true)
            coEvery { manager.getSession("session-1", "alice") } returns session
            coEvery { router.route("alice", listOf("poster", "review"), "再做一版") } returns routed

            service.resumeChat("session-1", "alice", "再做一版").toList()

            verify(exactly = 1) { session.updateTurnSkills(routed) }
            assertEquals(1, prompted.captured.size, "empty history plus the resumed user message")
        }

        @Test
        fun `resume without a message never routes`() = runTest {
            val service = service(withRouting = true)
            coEvery { manager.getSession("session-1", "alice") } returns session
            every { session.resume(messages = emptyList()) } returns stream

            service.resumeChat("session-1", "alice", null).toList()

            coVerify(exactly = 0) { router.route(any(), any(), any()) }
            verify(exactly = 0) { session.updateTurnSkills(any()) }
        }
    }
}
