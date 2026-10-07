package com.easy.easyai.web.service

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.ChatSession
import com.easy.easyai.core.agent.PersistedSession
import com.easy.easyai.core.agent.SessionManager
import com.easy.easyai.core.event.AgentEndEvent
import com.easy.easyai.core.event.AgentEvent
import com.easy.easyai.core.event.EventStream
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.ProjectInfo
import com.easy.easyai.core.model.ProjectKind
import com.easy.easyai.repository.project.AsyncProjectStore
import com.easy.easyai.repository.session.AsyncSessionStore
import com.easy.easyai.repository.session.SessionExecutionService
import com.easy.easyai.web.model.ChatRequest
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
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A temporary workspace is per-session scratch space. A brand-new session must never adopt a
 * `tmp-` project id supplied by the client (stale frontend state pointing at another session's
 * workspace); an already-persisted session keeps its binding, which is how forks and resumed
 * streams legitimately share a workspace.
 */
internal class ChatStreamServiceTempWorkspaceTest {

    private val manager = mockk<SessionManager>(relaxed = true)
    private val configStore = mockk<ModelProviderConfigStore>()
    private val factory = mockk<ChatModelFactory>()
    private val sessionStore = mockk<AsyncSessionStore>(relaxed = true)
    private val projectStore = mockk<AsyncProjectStore>(relaxed = true)
    private val workspaceService = mockk<DefaultWorkspaceService>()
    private val execution = mockk<SessionExecutionService>(relaxed = true)
    private val session = mockk<ChatSession>(relaxed = true)
    private val stream = mockk<EventStream<AgentEvent, List<AssistantMessage>>>()
    private val config = ModelProviderConfig("model-1", "Test model", Protocol.OPENAI, false, modelId = "test-model")
    private val contextSlot = slot<AgentContext>()

    @TempDir
    lateinit var workspaceDir: Path

    private fun service() = ChatStreamService(
        manager, configStore, listOf(factory),
        sessionStore = sessionStore,
        projectStore = projectStore,
        executionService = execution,
        workspaceService = workspaceService
    )

    @BeforeEach
    fun setup() {
        coEvery { configStore.getConfig(config.id, "alice") } returns config
        every { factory.supports(config.protocol) } returns true
        coEvery { manager.getOrCreateSession(capture(contextSlot), config, factory) } returns session
        coEvery { manager.loadMessages(any()) } returns emptyList()
        // chatFlow reads session.id repeatedly after the context is captured — mirror the
        // per-test session id instead of a fixed value.
        every { session.id } answers { checkNotNull(contextSlot.captured.sessionId) }
        every { session.agentContext } answers { contextSlot.captured }
        every { session.promptWithHistory(any()) } returns stream
        every { stream.asFlow() } answers {
            flowOf<AgentEvent>(AgentEndEvent(checkNotNull(contextSlot.captured.sessionId), "completed"))
        }
        coEvery { stream.result() } returns emptyList()
        coEvery { sessionStore.isSessionOwnedByUser(any(), any()) } returns true
    }

    @AfterEach
    fun cleanup() {
        service().destroy()
    }

    @Nested
    inner class BrandNewSession {

        @Test
        fun `a stale client-supplied temp project id is dropped and a fresh workspace is minted`() = runTest {
            coEvery { sessionStore.findById("new-session-1", "alice") } returns null
            coEvery { workspaceService.resolveOrCreate("new-session-1", "alice") } returns
                Workspace("tmp-new-session-1", workspaceDir, ProjectKind.TEMP)

            val request = ChatRequest(
                sessionId = "new-session-1", message = "hi",
                modelProviderConfigId = config.id, projectId = "tmp-old-session"
            )
            service().streamChat(request, "alice").toList()

            val context = contextSlot.captured
            assertEquals("tmp-new-session-1", context.projectId)
            assertNotEquals("tmp-old-session", context.projectId)
            coVerify(exactly = 1) { workspaceService.resolveOrCreate("new-session-1", "alice") }
            coVerify(exactly = 0) { projectStore.findById("tmp-old-session", any()) }
        }
    }

    @Nested
    inner class PersistedSession {

        @Test
        fun `a persisted binding to a shared temp workspace is kept`() = runTest {
            coEvery { sessionStore.findById("forked-session", "alice") } returns PersistedSession(
                id = "forked-session", messages = emptyList(), projectId = "tmp-old-session",
                createdAt = Instant.now(), updatedAt = Instant.now()
            )
            coEvery { projectStore.findById("tmp-old-session", "alice") } returns ProjectInfo(
                id = "tmp-old-session", name = DefaultWorkspaceService.TEMP_PROJECT_NAME,
                path = workspaceDir.toString(), kind = ProjectKind.TEMP
            )

            val request = ChatRequest(
                sessionId = "forked-session", message = "hi",
                modelProviderConfigId = config.id, projectId = "tmp-old-session"
            )
            service().streamChat(request, "alice").toList()

            assertEquals("tmp-old-session", contextSlot.captured.projectId)
            coVerify(exactly = 0) { workspaceService.resolveOrCreate(any(), any()) }
        }
    }
}
