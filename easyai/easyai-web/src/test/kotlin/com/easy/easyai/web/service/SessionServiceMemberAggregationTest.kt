package com.easy.easyai.web.service

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.SessionManager
import com.easy.easyai.core.model.UserMessage
import com.easy.easyai.core.team.TeamExecutionStore
import com.easy.easyai.core.team.TeamMemberExecutionEntity
import com.easy.easyai.core.team.TeamMemberStatus
import com.easy.easyai.repository.session.AsyncSessionStore
import com.easy.easyai.repository.session.MessageWithTimestamp
import com.easy.easyai.snapshot.SnapshotService
import com.easy.easyai.snapshot.model.FileChangeStatus
import com.easy.easyai.snapshot.model.FileDiff
import com.easy.easyai.snapshot.model.GitCheckpoint
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SessionServiceMemberAggregationTest {

    private lateinit var sessionManager: SessionManager
    private lateinit var sessionStore: AsyncSessionStore
    private lateinit var snapshotService: SnapshotService
    private lateinit var teamExecutionStore: TeamExecutionStore

    private val projectPath: Path = Path.of("/tmp/test-project")
    private val sessionId = "parent-session-1"
    private val memberSessionId = "member-session-abc"
    private val memberId = "researcher"

    private fun createContext(): AgentContext = AgentContext(
        agentId = "team-agent",
        sessionId = sessionId,
        projectPath = projectPath
    )

    private fun createMemberExecution(
        memberSessId: String? = memberSessionId,
        memId: String = memberId
    ): TeamMemberExecutionEntity = TeamMemberExecutionEntity(
        id = "exec-1",
        teamSessionId = sessionId,
        memberId = memId,
        round = 1,
        assignment = "research task",
        status = TeamMemberStatus.COMPLETED,
        memberSessionId = memberSessId,
        startedAt = System.currentTimeMillis()
    )

    @BeforeEach
    fun setup() {
        sessionManager = mockk(relaxed = true)
        sessionStore = mockk(relaxed = true)
        snapshotService = mockk(relaxed = true)
        teamExecutionStore = mockk(relaxed = true)
    }

    private fun createService(
        withTeamStore: Boolean = true,
        withSnapshot: Boolean = true
    ): SessionService = SessionService(
        sessionManager = sessionManager,
        sessionStore = sessionStore,
        snapshotService = if (withSnapshot) snapshotService else null,
        teamExecutionStore = if (withTeamStore) teamExecutionStore else null
    )

    @Nested
    inner class `getCheckpoints member aggregation` {

        @Test
        fun `aggregates member checkpoints with correct synthetic key`() = runTest {
            val service = createService()

            coEvery { sessionManager.getSessionContext(sessionId, any()) } returns createContext()
            every { snapshotService.isEnabled(projectPath) } returns true
            // Parent session has no checkpoints
            coEvery { snapshotService.listCheckpoints(projectPath, sessionId) } returns emptyList()
            coEvery { snapshotService.getStagedChanges(projectPath, sessionId) } returns emptyList()

            // Member has checkpoints
            val memberCheckpoint = GitCheckpoint(
                commitHash = "abc123",
                sessionId = memberSessionId,
                messageId = "msg-1",
                timestamp = 1000L
            )
            coEvery { snapshotService.listCheckpoints(projectPath, memberSessionId) } returns listOf(memberCheckpoint)
            coEvery { snapshotService.ensureBaseline(projectPath, memberSessionId) } returns "baseline-hash"
            coEvery { snapshotService.diff(projectPath, "baseline-hash", "abc123") } returns listOf(
                FileDiff(path = "src/NewFile.kt", additions = 10, deletions = 0, status = FileChangeStatus.ADDED)
            )

            // Team execution store returns member execution
            coEvery { teamExecutionStore.getExecutions(sessionId) } returns listOf(createMemberExecution())

            val result = service.getCheckpoints(sessionId)

            assertEquals(1, result.size)
            val memberCp = result[0]
            assertEquals("member:$memberSessionId", memberCp.messageId)
            assertNull(memberCp.assistantMessageId)
            assertEquals("abc123", memberCp.snapshotHash)
            assertEquals(10, memberCp.additions)
            assertEquals(0, memberCp.deletions)
            assertEquals(1, memberCp.filesChanged.size)
            assertEquals("src/NewFile.kt", memberCp.filesChanged[0].path)
            assertEquals("llm", memberCp.filesChanged[0].changedBy)
            assertEquals(memberId, memberCp.filesChanged[0].memberId)
        }

        @Test
        fun `no impact when teamExecutionStore is null`() = runTest {
            val service = createService(withTeamStore = false)

            coEvery { sessionManager.getSessionContext(sessionId, any()) } returns createContext()
            every { snapshotService.isEnabled(projectPath) } returns true
            coEvery { snapshotService.listCheckpoints(projectPath, sessionId) } returns emptyList()
            coEvery { snapshotService.getStagedChanges(projectPath, sessionId) } returns emptyList()

            val result = service.getCheckpoints(sessionId)

            assertTrue(result.isEmpty())
        }

        @Test
        fun `skips member with no checkpoints gracefully`() = runTest {
            val service = createService()

            coEvery { sessionManager.getSessionContext(sessionId, any()) } returns createContext()
            every { snapshotService.isEnabled(projectPath) } returns true
            coEvery { snapshotService.listCheckpoints(projectPath, sessionId) } returns emptyList()
            coEvery { snapshotService.getStagedChanges(projectPath, sessionId) } returns emptyList()

            // Member has NO checkpoints
            coEvery { snapshotService.listCheckpoints(projectPath, memberSessionId) } returns emptyList()
            coEvery { teamExecutionStore.getExecutions(sessionId) } returns listOf(createMemberExecution())

            val result = service.getCheckpoints(sessionId)

            assertTrue(result.isEmpty())
        }

        @Test
        fun `skips member when baseline fails`() = runTest {
            val service = createService()

            coEvery { sessionManager.getSessionContext(sessionId, any()) } returns createContext()
            every { snapshotService.isEnabled(projectPath) } returns true
            coEvery { snapshotService.listCheckpoints(projectPath, sessionId) } returns emptyList()
            coEvery { snapshotService.getStagedChanges(projectPath, sessionId) } returns emptyList()

            val memberCheckpoint = GitCheckpoint("hash1", memberSessionId, "msg-1", 1000L)
            coEvery { snapshotService.listCheckpoints(projectPath, memberSessionId) } returns listOf(memberCheckpoint)
            coEvery { snapshotService.ensureBaseline(projectPath, memberSessionId) } throws RuntimeException("no baseline")
            coEvery { teamExecutionStore.getExecutions(sessionId) } returns listOf(createMemberExecution())

            val result = service.getCheckpoints(sessionId)

            assertTrue(result.isEmpty())
        }

        @Test
        fun `skips member with empty diff`() = runTest {
            val service = createService()

            coEvery { sessionManager.getSessionContext(sessionId, any()) } returns createContext()
            every { snapshotService.isEnabled(projectPath) } returns true
            coEvery { snapshotService.listCheckpoints(projectPath, sessionId) } returns emptyList()
            coEvery { snapshotService.getStagedChanges(projectPath, sessionId) } returns emptyList()

            val memberCheckpoint = GitCheckpoint("hash1", memberSessionId, "msg-1", 1000L)
            coEvery { snapshotService.listCheckpoints(projectPath, memberSessionId) } returns listOf(memberCheckpoint)
            coEvery { snapshotService.ensureBaseline(projectPath, memberSessionId) } returns "baseline"
            coEvery { snapshotService.diff(projectPath, "baseline", "hash1") } returns emptyList()
            coEvery { teamExecutionStore.getExecutions(sessionId) } returns listOf(createMemberExecution())

            val result = service.getCheckpoints(sessionId)

            assertTrue(result.isEmpty())
        }

        @Test
        fun `deduplicates member sessions by memberSessionId`() = runTest {
            val service = createService()

            coEvery { sessionManager.getSessionContext(sessionId, any()) } returns createContext()
            every { snapshotService.isEnabled(projectPath) } returns true
            coEvery { snapshotService.listCheckpoints(projectPath, sessionId) } returns emptyList()
            coEvery { snapshotService.getStagedChanges(projectPath, sessionId) } returns emptyList()

            val memberCheckpoint = GitCheckpoint("hash1", memberSessionId, "msg-1", 1000L)
            coEvery { snapshotService.listCheckpoints(projectPath, memberSessionId) } returns listOf(memberCheckpoint)
            coEvery { snapshotService.ensureBaseline(projectPath, memberSessionId) } returns "baseline"
            coEvery { snapshotService.diff(projectPath, "baseline", "hash1") } returns listOf(
                FileDiff(path = "file.kt", additions = 5, deletions = 2, status = FileChangeStatus.MODIFIED)
            )

            // Two executions with the SAME memberSessionId (e.g. resumed)
            coEvery { teamExecutionStore.getExecutions(sessionId) } returns listOf(
                createMemberExecution(),
                createMemberExecution().copy(id = "exec-2", round = 2)
            )

            val result = service.getCheckpoints(sessionId)

            // Should only produce ONE aggregated checkpoint
            assertEquals(1, result.size)
            assertEquals("member:$memberSessionId", result[0].messageId)
        }

        @Test
        fun `member aggregation failure does not affect parent checkpoints`() = runTest {
            val service = createService()

            coEvery { sessionManager.getSessionContext(sessionId, any()) } returns createContext()
            every { snapshotService.isEnabled(projectPath) } returns true

            // Parent has a checkpoint
            val parentCheckpoint = GitCheckpoint("parent-hash", sessionId, "user-msg-1", 2000L)
            coEvery { snapshotService.listCheckpoints(projectPath, sessionId) } returns listOf(parentCheckpoint)
            coEvery { snapshotService.resolveSessionRef(projectPath, sessionId) } returns null
            coEvery { snapshotService.ensureBaseline(projectPath, sessionId) } returns "parent-baseline"
            coEvery { snapshotService.diff(projectPath, "parent-baseline", "parent-hash") } returns listOf(
                FileDiff(path = "parent.kt", additions = 3, deletions = 1, status = FileChangeStatus.MODIFIED)
            )
            coEvery { snapshotService.determineFileAuthors(projectPath, "parent-baseline", "parent-hash") } returns mapOf("parent.kt" to "llm")
            coEvery { snapshotService.getStagedChanges(projectPath, sessionId) } returns emptyList()
            coEvery { sessionStore.loadMessagesWithTimestamps(sessionId) } returns listOf(
                MessageWithTimestamp(message = UserMessage("user-msg-1", "hello"), timestamp = 1000L)
            )

            // Team store throws
            coEvery { teamExecutionStore.getExecutions(sessionId) } throws RuntimeException("DB error")

            val result = service.getCheckpoints(sessionId)

            // Parent checkpoint still returned
            assertEquals(1, result.size)
            assertEquals("user-msg-1", result[0].messageId)
        }
    }

    @Nested
    inner class `getSessionDiffWithMembers` {

        @Test
        fun `aggregates member-only file diffs`() = runTest {
            val service = createService()

            coEvery { sessionManager.getSessionContext(sessionId, any()) } returns createContext()
            every { snapshotService.isEnabled(projectPath) } returns true

            // Parent diff
            val parentCheckpoint = GitCheckpoint("parent-hash", sessionId, "msg-1", 1000L)
            coEvery { snapshotService.listCheckpoints(projectPath, sessionId) } returns listOf(parentCheckpoint)
            coEvery { snapshotService.ensureBaseline(projectPath, sessionId) } returns "parent-baseline"
            coEvery { snapshotService.diff(projectPath, "parent-baseline", "parent-hash") } returns listOf(
                FileDiff(path = "shared.kt", additions = 5, deletions = 0, status = FileChangeStatus.MODIFIED)
            )

            // Member diff — one shared file, one unique file
            val memberCheckpoint = GitCheckpoint("member-hash", memberSessionId, "msg-2", 2000L)
            coEvery { snapshotService.listCheckpoints(projectPath, memberSessionId) } returns listOf(memberCheckpoint)
            coEvery { snapshotService.ensureBaseline(projectPath, memberSessionId) } returns "member-baseline"
            coEvery { snapshotService.diff(projectPath, "member-baseline", "member-hash") } returns listOf(
                FileDiff(path = "shared.kt", additions = 3, deletions = 1, status = FileChangeStatus.MODIFIED),
                FileDiff(path = "member-only.kt", additions = 20, deletions = 0, status = FileChangeStatus.ADDED)
            )

            coEvery { teamExecutionStore.getExecutions(sessionId) } returns listOf(createMemberExecution())

            val result = service.getSessionDiffWithMembers(sessionId)

            // shared.kt from parent + member-only.kt from member
            assertEquals(2, result.size)
            assertTrue(result.any { it.path == "shared.kt" && it.additions == 5 })
            assertTrue(result.any { it.path == "member-only.kt" && it.additions == 20 && it.changedBy == "llm" })
        }

        @Test
        fun `returns empty when snapshotService is null`() = runTest {
            val service = createService(withSnapshot = false)

            val result = service.getSessionDiffWithMembers(sessionId)

            assertTrue(result.isEmpty())
        }
    }
}
