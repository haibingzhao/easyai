package com.easy.easyai.web.service

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.SessionManager
import com.easy.easyai.core.team.TeamExecutionStore
import com.easy.easyai.core.team.TeamMemberExecutionEntity
import com.easy.easyai.core.team.TeamMemberStatus
import com.easy.easyai.repository.session.AsyncSessionStore
import com.easy.easyai.speech.SpeechScriptStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Deleting a session has to retire the read-aloud scripts captured from it.
 *
 * A script's target id is the *message*, so this cannot go through `archiveForTarget`: the only
 * column that spans a whole conversation is `session_id`, which is what the hook uses.
 */
class SessionServiceSpeechArchiveTest {

    private val manager = mockk<SessionManager>(relaxed = true)
    private val sessions = mockk<AsyncSessionStore>(relaxed = true)
    private val scripts = mockk<SpeechScriptStore>(relaxed = true)
    private val owners = listOf("alice", "system")

    private fun service(): SessionService {
        // A resolvable context is what proves the caller owns the session; archiving hangs off it.
        coEvery { manager.getSessionContext(any(), any(), any()) } returns ownedContext()
        return SessionService(manager, sessions, speechScriptStore = scripts)
    }

    private fun ownedContext() = AgentContext(
        agentId = "agent-1",
        sessionId = "session-1",
        userId = "alice"
    )

    @Nested
    inner class `Session deletion` {

        @Test
        fun `archives every script captured from the session`() = runTest {
            coEvery { scripts.archiveForSession("session-1") } returns 3

            service().deleteSession("session-1", "alice", owners)

            coVerify(exactly = 1) { scripts.archiveForSession("session-1") }
        }

        @Test
        fun `deletes the session even when archiving fails`() = runTest {
            coEvery { scripts.archiveForSession(any()) } throws IllegalStateException("db down")

            // Losing a convenience must never turn a successful delete into a 500.
            service().deleteSession("session-1", "alice", owners)

            coVerify(exactly = 1) { sessions.delete("session-1", "alice") }
            coVerify(exactly = 1) { manager.closeSession("session-1") }
        }

        @Test
        fun `archives the scripts of team member sub-sessions too`() = runTest {
            val teams = mockk<TeamExecutionStore>(relaxed = true)
            coEvery { manager.getSessionContext(any(), any(), any()) } returns ownedContext()
            coEvery { teams.getExecutions("session-1") } returns listOf(
                TeamMemberExecutionEntity(
                    id = "exec-1",
                    teamSessionId = "session-1",
                    memberId = "researcher",
                    round = 1,
                    assignment = "look it up",
                    status = TeamMemberStatus.COMPLETED,
                    memberSessionId = "member-session-1"
                )
            )
            val withTeams = SessionService(manager, sessions, teamExecutionStore = teams, speechScriptStore = scripts)

            withTeams.deleteSession("session-1", "alice", owners)

            coVerify(exactly = 1) { sessions.delete("member-session-1", "alice") }
            coVerify(exactly = 1) { scripts.archiveForSession("member-session-1") }
            coVerify(exactly = 1) { scripts.archiveForSession("session-1") }
        }

        @Test
        fun `does not archive another account's scripts`() = runTest {
            // The context lookup is scoped by user id, so a foreign session resolves to null. The
            // archive itself is not owner-scoped and must not run on a session this caller cannot see.
            val ownedService = service()
            coEvery { manager.getSessionContext(any(), any(), any()) } returns null

            ownedService.deleteSession("session-1", "mallory", owners)

            coVerify(exactly = 0) { scripts.archiveForSession(any()) }
        }

        @Test
        fun `deletes normally without a store`() = runTest {
            coEvery { manager.getSessionContext(any(), any(), any()) } returns ownedContext()
            val withoutStore = SessionService(manager, sessions)

            withoutStore.deleteSession("session-1", "alice", owners)

            coVerify(exactly = 1) { sessions.delete("session-1", "alice") }
            coVerify(exactly = 1) { manager.closeSession("session-1") }
        }
    }
}
