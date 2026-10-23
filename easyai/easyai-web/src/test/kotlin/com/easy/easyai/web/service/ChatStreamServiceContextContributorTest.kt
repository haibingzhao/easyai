package com.easy.easyai.web.service

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.compaction.CompactionTransformContextService
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.ChatSession
import com.easy.easyai.core.agent.SessionManager
import com.easy.easyai.core.event.AgentEndEvent
import com.easy.easyai.core.event.AgentEvent
import com.easy.easyai.core.event.EventStream
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.ProjectKind
import com.easy.easyai.core.permission.PermissionService
import com.easy.easyai.repository.session.AsyncSessionStore
import com.easy.easyai.repository.session.ExecutionHandle
import com.easy.easyai.repository.session.SessionExecutionService
import com.easy.easyai.web.context.ChatContextContributor
import com.easy.easyai.web.context.ChatContextRejectedException
import com.easy.easyai.web.context.ChatTurnContext
import com.easy.easyai.web.controller.ChatController
import com.easy.easyai.web.model.ChatRequest
import com.easy.easyai.web.model.ChatStreamEvent
import com.easy.easyai.web.security.ApiExceptionHandler
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Contributors are the server-trusted half of session variables: fresh chats must see them before
 * the session opens, resumed runs must re-resolve them instead of trusting compaction history, and
 * a rejection must abort the run without opening a session.
 */
internal class ChatStreamServiceContextContributorTest {

    private val manager = mockk<SessionManager>(relaxed = true)
    private val configStore = mockk<ModelProviderConfigStore>()
    private val factory = mockk<ChatModelFactory>()
    private val session = mockk<ChatSession>(relaxed = true)
    private val sessionStore = mockk<AsyncSessionStore>(relaxed = true)
    private val permissions = mockk<PermissionService>(relaxed = true)
    private val execution = mockk<SessionExecutionService>(relaxed = true)
    private val stream = mockk<EventStream<AgentEvent, List<AssistantMessage>>>()
    private val config = ModelProviderConfig("model-1", "Test model", Protocol.OPENAI, false, modelId = "test-model")
    private val request = ChatRequest(
        sessionId = "session-1", message = "hi", modelProviderConfigId = config.id
    )
    private val context = AgentContext(
        agentId = "default-agent", modelConfig = config, sessionId = "session-1", userId = "alice"
    )
    private val createdContext = slot<AgentContext>()
    private val compaction = mockk<CompactionTransformContextService>(relaxed = true)
    private val services = mutableListOf<ChatStreamService>()

    private fun service(
        contributors: List<ChatContextContributor>,
        executionService: SessionExecutionService = execution,
    ) = ChatStreamService(
        manager, configStore, listOf(factory),
        transformContextService = compaction,
        permissionService = permissions,
        sessionStore = sessionStore,
        executionService = executionService,
        contextContributors = contributors
    ).also { services.add(it) }

    private fun turn() = ChatTurnContext(
        userId = "alice",
        owners = setOf("alice", "system"),
        sessionId = "session-1",
        groupId = null,
        headers = emptyMap()
    )

    @BeforeEach
    fun setup() {
        coEvery { configStore.getConfig(config.id, any<Collection<String>>()) } returns config
        every { factory.supports(config.protocol) } returns true
        coEvery { sessionStore.isSessionOwnedByUser(any(), any()) } returns true
        coEvery { sessionStore.findById(any(), any()) } returns null
        coEvery { manager.getOrCreateSession(capture(createdContext), config, factory) } returns session
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
    fun `a fresh chat receives contributor values before the session opens`() = runTest {
        val contributor = ChatContextContributor { current -> mapOf("home.userId" to current.userId) }

        service(listOf(contributor)).streamChat(request, "alice", turn = turn()).toList()

        assertEquals("alice", createdContext.captured.sessionVariables.getAll()["home.userId"])
    }

    @Test
    fun `without a contributor the context is unchanged`() = runTest {
        service(emptyList()).streamChat(request, "alice", turn = turn()).toList()

        assertEquals(emptyMap(), createdContext.captured.sessionVariables.getAll())
    }

    @Test
    fun `a rejection aborts the run before a session row is opened`() = runTest {
        val rejecting = ChatContextContributor { throw ChatContextRejectedException("wrong family") }

        assertFailsWith<ChatContextRejectedException> {
            service(listOf(rejecting)).streamChat(request, "alice", turn = turn()).toList()
        }
        coVerify(exactly = 0) { manager.getOrCreateSession(any(), any(), any()) }
    }

    @Test
    fun `a rejected project-less chat never creates a temporary workspace`() = runTest {
        val workspace = mockk<DefaultWorkspaceService>(relaxed = true)
        val rejecting = ChatContextContributor { throw ChatContextRejectedException("wrong family") }
        val service = ChatStreamService(
            manager, configStore, listOf(factory),
            transformContextService = compaction,
            permissionService = permissions,
            sessionStore = sessionStore,
            executionService = execution,
            workspaceService = workspace,
            contextContributors = listOf(rejecting)
        ).also { services.add(it) }

        assertFailsWith<ChatContextRejectedException> {
            service.streamChat(request, "alice", turn = turn()).toList()
        }
        coVerify(exactly = 0) { workspace.resolveOrCreate(any(), any()) }
    }

    @Test
    fun `a compaction flow that is never collected registers no execution`() = runTest {
        service(emptyList()).compactChat("session-1", "alice")

        verify(exactly = 0) { execution.beginExecutionIfIdle(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a cancel that lands before the compaction job is published still ends the stream`() = runTest {
        val onCancel = slot<() -> Unit>()
        val handle = mockk<ExecutionHandle>(relaxed = true)
        every { execution.beginExecutionIfIdle("session-1", "alice", any(), any(), capture(onCancel)) } answers {
            // The cancel thread can run between admission and the job publication: it removes the
            // handle and sees no job to cancel. The stream must still report a terminal event.
            onCancel.captured.invoke()
            handle
        }

        val events = service(emptyList()).compactChat("session-1", "alice").toList()

        assertEquals("cancelled", (events.last().data() as ChatStreamEvent.Done).reason)
    }

    @Test
    fun `a cancel during compaction reports cancelled instead of completion`() = runTest {
        val onCancel = slot<() -> Unit>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        every { execution.beginExecutionIfIdle("session-1", "alice", any(), any(), capture(onCancel)) } returns
            mockk<ExecutionHandle>(relaxed = true)
        coEvery { compaction.manualCompactWithPusher(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            started.complete(Unit)
            release.await()
            emptyList()
        }

        val events = async { service(emptyList()).compactChat("session-1", "alice").toList() }
        started.await()
        onCancel.captured.invoke()
        release.complete(Unit)

        assertEquals("cancelled", (events.await().last().data() as ChatStreamEvent.Done).reason)
    }

    @Test
    fun `a resumed context is re-injected from the current request`() = runTest {
        val contributor = ChatContextContributor { current -> mapOf("home.familyId" to "family-2") }

        service(listOf(contributor)).resumeChat("session-1", "alice", null, turn = turn()).toList()

        assertEquals("family-2", context.sessionVariables.getAll()["home.familyId"])
    }

    @Test
    fun `header snapshots lowercase names and drop credentials`() {
        val headers = HttpHeaders()
        headers.add("X-Home-Family-Id", "family-1")
        headers.add("Authorization", "Bearer secret")
        headers.add("Cookie", "session=secret")

        val snapshot = ChatTurnContext.from("alice", setOf("alice"), "session-1", "family-1", headers)

        assertEquals(mapOf("x-home-family-id" to "family-1"), snapshot.headers)
    }

    @Test
    fun `a rejected permission reply never persists the remember rule`() = runTest {
        val rejecting = ChatContextContributor { throw ChatContextRejectedException("wrong family") }
        val sessionContext = mockk<AgentContext>(relaxed = true)
        every { sessionContext.projectId } returns "project-1"
        every { sessionContext.projectKind } returns ProjectKind.USER
        coEvery { manager.getSessionContext("session-1", "alice", any<Collection<String>>()) } returns sessionContext

        assertFailsWith<ChatContextRejectedException> {
            service(listOf(rejecting)).allowPermissionAndResume(
                sessionId = "session-1",
                userId = "alice",
                toolCallId = "tool-1",
                remember = true,
                permission = "tool.execute.home",
                pattern = "*",
                turn = turn(),
            )
        }
        assertFailsWith<ChatContextRejectedException> {
            service(listOf(rejecting)).denyPermissionAndResume(
                sessionId = "session-1",
                userId = "alice",
                toolCallId = "tool-1",
                remember = true,
                reason = null,
                permission = "tool.execute.home",
                pattern = "*",
                turn = turn(),
            )
        }

        coVerify(exactly = 0) { permissions.addAllowRule(any(), any(), any()) }
        coVerify(exactly = 0) { permissions.addDenyRule(any(), any(), any()) }
    }

    @Test
    fun `a remember rule on a missing session is a readable 404, not a 500`() = runTest {
        coEvery { manager.getSession(any(), any(), any<Collection<String>>()) } returns null

        val error = assertFailsWith<ResponseStatusException> {
            service(listOf(ChatContextContributor { emptyMap() })).allowPermissionAndResume(
                sessionId = "session-1",
                userId = "alice",
                toolCallId = "tool-1",
                remember = true,
                permission = "tool.execute.home",
                pattern = "*",
                turn = turn(),
            )
        }

        assertEquals(HttpStatus.NOT_FOUND, error.statusCode)
        assertEquals("Session not found: session-1", error.reason)
        coVerify(exactly = 0) { permissions.addAllowRule(any(), any(), any()) }
    }

    @Test
    fun `a rejected compaction does not register an execution`() = runTest {
        val rejecting = ChatContextContributor { throw ChatContextRejectedException("wrong family") }

        assertFailsWith<ChatContextRejectedException> {
            service(listOf(rejecting)).compactChat("session-1", "alice", turn = turn())
        }

        verify { listOf(execution, compaction) wasNot Called }
        coVerify(exactly = 0) { manager.loadMessages(any()) }
    }

    @Test
    fun `a running chat keeps its handle when compaction is rejected`() = runTest {
        val realStore = mockk<AsyncSessionStore>(relaxed = true)
        val realExecution = SessionExecutionService(realStore, StandardTestDispatcher(testScheduler))
        try {
            realExecution.beginExecution("session-1", "alice", session)
            testScheduler.advanceUntilIdle()

            val flow = service(listOf(ChatContextContributor { emptyMap() }), realExecution)
                .compactChat("session-1", "alice", turn = turn())
            // Admission happens at collection, so the rejection surfaces here, not at call time.
            val error = assertFailsWith<ResponseStatusException> { flow.toList() }

            assertEquals(HttpStatus.CONFLICT, error.statusCode)
            // The chat still owns the slot: compaction did not overwrite it with a sessionless handle.
            assertTrue(realExecution.isLocallyExecuting("session-1"))
            assertEquals(session, realExecution.getActiveSession("session-1"))
        } finally {
            realExecution.destroy()
        }
    }

    @Nested
    inner class CompactionErrors {
        @Test
        fun `missing session returns 404 without any side effects`() = runTest {
            val contributor = mockk<ChatContextContributor>()
            coEvery { manager.getSession(any(), any(), any<Collection<String>>()) } returns null
            val currentTurn = turn()

            val error = assertFailsWith<ResponseStatusException> {
                service(listOf(contributor)).compactChat(
                    "session-1", "alice", currentTurn.owners, currentTurn
                ).toList()
            }

            assertEquals(HttpStatus.NOT_FOUND, error.statusCode)
            assertEquals("Session not found: session-1", error.reason)
            coVerify(exactly = 1) { manager.getSession("session-1", "alice", currentTurn.owners) }
            confirmVerified(manager)
            verify { listOf(contributor, session, sessionStore, execution, permissions, compaction) wasNot Called }
        }
    }

    @Nested
    inner class HttpErrorResponses {
        private fun client(contributors: List<ChatContextContributor>): WebTestClient {
            val controller = ChatController(
                chatStreamService = service(contributors),
                quickAskStreamService = mockk(),
                sessionManager = manager,
                revertService = mockk(),
                sessionService = mockk(),
                sessionStore = sessionStore
            )
            val auth = ReactiveSecurityContextHolder.withAuthentication(
                UsernamePasswordAuthenticationToken("alice", null, emptyList())
            )
            return WebTestClient.bindToController(controller)
                .controllerAdvice(ApiExceptionHandler())
                .webFilter<WebTestClient.ControllerSpec>({ exchange, chain -> chain.filter(exchange).contextWrite(auth) })
                .build()
        }

        @Test
        fun `all chat entry points return readable JSON for a rejected context`() {
            val rejecting = ChatContextContributor { throw ChatContextRejectedException("wrong family") }
            val client = client(listOf(rejecting))
            val requests = listOf(
                "/api/chat/" to """{"message":"hi","modelProviderConfigId":"model-1"}""",
                "/api/chat/resume" to """{"sessionId":"session-1"}""",
                "/api/chat/question/session-1/tool-1/answer" to """{"answers":[["yes"]]}""",
                "/api/chat/question/session-1/tool-1/reject" to null,
                "/api/chat/permission/session-1/tool-1/allow" to
                    """{"remember":true,"permission":"tool.execute.home","pattern":"*"}""",
                "/api/chat/permission/session-1/tool-1/deny" to
                    """{"remember":true,"permission":"tool.execute.home","pattern":"*"}""",
                "/api/chat/session/session-1/compact" to null
            )
            requests.forEach { (uri, body) ->
                val request = client.post().uri(uri).contentType(MediaType.APPLICATION_JSON)
                val response = if (body == null) request.exchange() else request.bodyValue(body).exchange()
                response.expectStatus().isForbidden
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                    .expectBody().jsonPath("$.error").isEqualTo("wrong family")
            }

            coVerify(exactly = 0) { manager.getOrCreateSession(any(), any(), any()) }
            coVerify(exactly = 0) { manager.loadMessages(any()) }
            verify { listOf(execution, permissions, compaction) wasNot Called }
        }

        @Test
        fun `ownership rejection precedes session restoration and context contribution`() {
            val contributor = mockk<ChatContextContributor>()
            coEvery { sessionStore.isSessionOwnedByUser("session-1", "alice") } returns false

            client(listOf(contributor)).post().uri("/api/chat/session/session-1/compact")
                .exchange().expectStatus().isForbidden
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody().jsonPath("$.error").isEqualTo("Session not accessible: session-1")

            verify { listOf(manager, contributor, execution, compaction) wasNot Called }
        }

        @Test
        fun `missing compaction session returns readable 404 instead of 500 or SSE`() {
            val contributor = mockk<ChatContextContributor>()
            coEvery { manager.getSession(any(), any(), any<Collection<String>>()) } returns null

            client(listOf(contributor)).post().uri("/api/chat/session/session-1/compact")
                .exchange().expectStatus().isNotFound
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody().jsonPath("$.error").isEqualTo("Session not found: session-1")

            coVerify(exactly = 1) { manager.getSession("session-1", "alice", setOf("alice", "system")) }
            coVerify(exactly = 1) { sessionStore.isSessionOwnedByUser("session-1", "alice") }
            confirmVerified(manager, sessionStore)
            verify { listOf(contributor, session, execution, permissions, compaction) wasNot Called }
        }

        @Test
        fun `running session preserves readable 409 without starting compaction`() {
            every {
                execution.beginExecutionIfIdle("session-1", "alice", any(), any(), any())
            } returns null

            client(emptyList()).post().uri("/api/chat/session/session-1/compact")
                .exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody().jsonPath("$.error").isEqualTo("Session is running; cannot compact")

            verify(exactly = 1) {
                execution.beginExecutionIfIdle("session-1", "alice", any(), any(), any())
            }
            verify(exactly = 0) { execution.isLocallyExecuting(any()) }
            confirmVerified(execution)
            coVerify(exactly = 0) { manager.loadMessages(any()) }
            verify { compaction wasNot Called }
        }
    }
}
