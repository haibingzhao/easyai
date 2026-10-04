package com.easy.easyai.web.service

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.PersistedSession
import com.easy.easyai.core.agent.SessionManager
import com.easy.easyai.repository.session.AsyncSessionStore
import com.easy.easyai.snapshot.SnapshotService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant

class SessionServiceSnapshotEnabledTest {

    private val sessionManager: SessionManager = mockk(relaxed = true)
    private val sessionStore: AsyncSessionStore = mockk(relaxed = true)
    private val snapshotService: SnapshotService = mockk(relaxed = true)

    private val projectPath: Path = Path.of("/tmp/test-project")
    private val sessionId = "session-1"

    private fun createService(withSnapshot: Boolean = true): SessionService = SessionService(
        sessionManager = sessionManager,
        sessionStore = sessionStore,
        snapshotService = if (withSnapshot) snapshotService else null
    )

    private fun stubSession() {
        val persisted = PersistedSession(
            id = sessionId,
            messages = emptyList(),
            projectId = "project-1",
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        coEvery { sessionManager.getSessionDetail(sessionId, any()) } returns persisted
        coEvery { sessionStore.loadMessagesWithTimestamps(sessionId) } returns emptyList()
    }

    private fun stubContext(projectPath: Path?) {
        coEvery { sessionManager.getSessionContext(sessionId, any()) } returns AgentContext(
            agentId = "default",
            sessionId = sessionId,
            projectPath = projectPath
        )
    }

    @Nested
    inner class `getSessionDetail snapshotEnabled` {

        @Test
        fun `is true when snapshot service reports the project enabled`() = runTest {
            stubSession()
            stubContext(projectPath)
            every { snapshotService.isEnabled(projectPath) } returns true

            val detail = createService().getSessionDetail(sessionId)!!

            assertTrue(detail.snapshotEnabled)
        }

        @Test
        fun `is false when snapshot service is a no-op override`() = runTest {
            stubSession()
            stubContext(projectPath)
            every { snapshotService.isEnabled(projectPath) } returns false

            val detail = createService().getSessionDetail(sessionId)!!

            assertFalse(detail.snapshotEnabled)
        }

        @Test
        fun `is false when snapshot service bean is absent`() = runTest {
            stubSession()
            stubContext(projectPath)

            val detail = createService(withSnapshot = false).getSessionDetail(sessionId)!!

            assertFalse(detail.snapshotEnabled)
        }

        @Test
        fun `is false when session has no project path`() = runTest {
            stubSession()
            stubContext(null)

            val detail = createService().getSessionDetail(sessionId)!!

            assertFalse(detail.snapshotEnabled)
        }

        @Test
        fun `is false when context resolution fails`() = runTest {
            stubSession()
            coEvery { sessionManager.getSessionContext(sessionId, any()) } throws
                IllegalStateException("Config not found")

            val detail = createService().getSessionDetail(sessionId)!!

            assertFalse(detail.snapshotEnabled)
        }
    }
}
