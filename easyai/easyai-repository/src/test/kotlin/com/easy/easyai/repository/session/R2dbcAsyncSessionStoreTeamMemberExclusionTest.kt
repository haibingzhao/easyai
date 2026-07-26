package com.easy.easyai.repository.session

import com.easy.easyai.core.agent.PersistedSession
import com.easy.easyai.core.team.TeamMemberExecutionEntity
import com.easy.easyai.core.team.TeamMemberStatus
import com.easy.easyai.repository.database.DatabaseMigration
import com.easy.easyai.repository.team.R2dbcTeamExecutionStore
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.junit.jupiter.api.*
import java.time.Instant
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests verifying that Team Agent member sub-sessions are excluded
 * from session listing queries ([R2dbcAsyncSessionStore.findMetadataByLimit] and
 * [R2dbcAsyncSessionStore.findIdsByLimit]).
 *
 * A member sub-session is any session whose ID is referenced as `member_session_id`
 * in the team member execution table. Such sessions are internal to team coordination
 * and must not appear in the History list.
 */
class R2dbcAsyncSessionStoreTeamMemberExclusionTest {

    companion object {
        private lateinit var db: R2dbcDatabase

        @BeforeAll
        @JvmStatic
        fun setupDb() = runTest {
            db = R2dbcDatabase.connect(
                url = "r2dbc:h2:mem:///team_exclusion_test_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1",
                manager = { TransactionManager(it) }
            )
            DatabaseMigration.defaultTables().execute(db)
        }
    }

    private val sessionStore = R2dbcAsyncSessionStore(db)
    private val teamStore = R2dbcTeamExecutionStore(db)

    private suspend fun createSession(sessionId: String) {
        sessionStore.save(
            PersistedSession(
                id = sessionId,
                messages = emptyList(),
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )
        )
    }

    private suspend fun registerMemberExecution(teamSessionId: String, memberSessionId: String) {
        teamStore.saveExecution(
            TeamMemberExecutionEntity(
                id = UUID.randomUUID().toString(),
                teamSessionId = teamSessionId,
                memberId = "member-${UUID.randomUUID()}",
                round = 1,
                assignment = "test assignment",
                status = TeamMemberStatus.RUNNING,
                memberSessionId = memberSessionId
            )
        )
    }

    @Nested
    inner class `findMetadataByLimit excludes team member sessions` {

        @Test
        fun `member sub-session is excluded while normal and team sessions remain`() = runTest {
            val teamSessionId = "team-${UUID.randomUUID()}"
            val normalSessionId = "normal-${UUID.randomUUID()}"
            val memberSessionId = "member-${UUID.randomUUID()}"

            createSession(teamSessionId)
            createSession(normalSessionId)
            createSession(memberSessionId)
            registerMemberExecution(teamSessionId, memberSessionId)

            val (metadata, _) = sessionStore.findMetadataByLimit(limit = 100, offset = 0)
            val ids = metadata.map { it.id }

            assertTrue(normalSessionId in ids, "normal session should be listed")
            assertTrue(teamSessionId in ids, "team leader session should be listed")
            assertFalse(memberSessionId in ids, "team member sub-session should be excluded")
        }
    }

    @Nested
    inner class `findIdsByLimit excludes team member sessions` {

        @Test
        fun `member sub-session is excluded while normal and team sessions remain`() = runTest {
            val teamSessionId = "team-${UUID.randomUUID()}"
            val normalSessionId = "normal-${UUID.randomUUID()}"
            val memberSessionId = "member-${UUID.randomUUID()}"

            createSession(teamSessionId)
            createSession(normalSessionId)
            createSession(memberSessionId)
            registerMemberExecution(teamSessionId, memberSessionId)

            val result = sessionStore.findIdsByLimit(limit = 100, offset = 0)

            assertTrue(normalSessionId in result.ids, "normal session should be listed")
            assertTrue(teamSessionId in result.ids, "team leader session should be listed")
            assertFalse(memberSessionId in result.ids, "team member sub-session should be excluded")
        }
    }
}
