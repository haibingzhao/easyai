package com.easy.easyai.repository.session

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.UserMessage
import com.easy.easyai.core.team.TeamMemberExecutionEntity
import com.easy.easyai.core.team.TeamMemberStatus
import com.easy.easyai.repository.database.DatabaseMigration
import com.easy.easyai.repository.team.R2dbcTeamExecutionStore
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A host-owned scope is written with the session row and gives that host a list of its own hidden
 * sessions. Chat History still excludes every row carrying a `system_kind`, so the scope is an
 * additional index, not a replacement for the hide marker.
 */
class R2dbcAsyncSessionStoreSessionScopeTest {

    companion object {
        private lateinit var db: R2dbcDatabase

        @BeforeAll
        @JvmStatic
        fun setupDb() = runTest {
            db = R2dbcDatabase.connect(
                url = "r2dbc:h2:mem:///session_scope_test_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1",
                manager = { TransactionManager(it) }
            )
            DatabaseMigration.defaultTables().execute(db)
        }
    }

    private val sessionStore = R2dbcAsyncSessionStore(db)
    private val teamStore = R2dbcTeamExecutionStore(db)

    // One DB per class, so each test method needs its own scope to stay independent.
    private val scope = "page_assistant|f1|finance.transactions|${UUID.randomUUID()}"
    private val otherScope = "page_assistant|f1|finance.budgets|${UUID.randomUUID()}"

    private fun context(
        sessionId: String,
        userId: String = "alice",
        systemKind: String? = "page_assistant",
        sessionScope: String? = scope,
    ) = AgentContext(
        agentId = "page-assistant",
        sessionId = sessionId,
        userId = userId,
        systemKind = systemKind,
        sessionScope = sessionScope,
    )

    private suspend fun listedIds(): List<String> =
        sessionStore.findMetadataByLimit(limit = 200, offset = 0, userId = "alice").first.map { it.id }

    @Test
    fun `a scoped hidden session is listable by scope but not by Chat History`() = runTest {
        val sessionId = "scoped-${UUID.randomUUID()}"
        sessionStore.ensureSessionExists(sessionId, context(sessionId))
        sessionStore.upsertMessages(context(sessionId), sessionId, listOf(UserMessage("本月花了多少")), null, null)

        val (summaries, hasMore) = sessionStore.findSummariesBySessionScope(scope, "alice", limit = 20)

        assertEquals(1, summaries.size)
        assertEquals(sessionId, summaries.single().id)
        assertEquals("本月花了多少", summaries.single().title)
        assertFalse(hasMore)
        assertFalse(sessionId in listedIds(), "the hide marker must still win for Chat History")
    }

    @Test
    fun `the listing is exact on scope and user`() = runTest {
        val mine = "mine-${UUID.randomUUID()}"
        val otherPage = "other-page-${UUID.randomUUID()}"
        val otherUser = "other-user-${UUID.randomUUID()}"
        sessionStore.ensureSessionExists(mine, context(mine))
        sessionStore.ensureSessionExists(otherPage, context(otherPage, sessionScope = otherScope))
        sessionStore.ensureSessionExists(otherUser, context(otherUser, userId = "bob"))

        val ids = sessionStore.findSummariesBySessionScope(scope, "alice", limit = 20).first.map { it.id }

        assertEquals(listOf(mine), ids)
    }

    @Test
    fun `team member sub-sessions sharing the scope stay out of the listing`() = runTest {
        val leader = "leader-${UUID.randomUUID()}"
        val member = "member-${UUID.randomUUID()}"
        sessionStore.ensureSessionExists(leader, context(leader))
        // A member context inherits the leader's scope, and its row is written by the message
        // listener, so without the exclusion it would share the host's index.
        sessionStore.ensureSessionExists(member, context(member))
        teamStore.saveExecution(
            TeamMemberExecutionEntity(
                id = UUID.randomUUID().toString(),
                teamSessionId = leader,
                memberId = "backend",
                round = 1,
                assignment = "do the work",
                status = TeamMemberStatus.RUNNING,
                memberSessionId = member,
            )
        )

        val ids = sessionStore.findSummariesBySessionScope(scope, "alice", limit = 20).first.map { it.id }

        assertEquals(listOf(leader), ids)
    }

    @Test
    fun `the marker lookup exposes the scope`() = runTest {
        val sessionId = "marker-${UUID.randomUUID()}"
        sessionStore.ensureSessionExists(sessionId, context(sessionId))

        assertEquals(
            AsyncSessionStore.SessionMarker(systemKind = "page_assistant", sessionScope = scope),
            sessionStore.findSessionMarker(sessionId),
        )
    }

    @Test
    fun `an existing row without a scope gets one and a non-null scope is never overwritten`() = runTest {
        val sessionId = "adopt-${UUID.randomUUID()}"
        sessionStore.ensureSessionExists(
            sessionId,
            context(sessionId, systemKind = "page_assistant", sessionScope = null),
        )
        assertNull(sessionStore.findSessionMarker(sessionId)?.sessionScope)

        sessionStore.ensureSessionExists(sessionId, context(sessionId))
        assertEquals(scope, sessionStore.findSessionMarker(sessionId)?.sessionScope)

        sessionStore.ensureSessionExists(sessionId, context(sessionId, sessionScope = otherScope))
        assertEquals(scope, sessionStore.findSessionMarker(sessionId)?.sessionScope)
    }

    @Test
    fun `a missing scope returns an empty page`() = runTest {
        val (summaries, hasMore) = sessionStore.findSummariesBySessionScope(
            "missing-${UUID.randomUUID()}", "alice", limit = 20,
        )
        assertTrue(summaries.isEmpty())
        assertFalse(hasMore)
    }

    @Test
    fun `pages report hasMore and cover every scoped session once`() = runTest {
        val ids = (1..3).map { "paged-${UUID.randomUUID()}" }
        ids.forEach { sessionStore.ensureSessionExists(it, context(it)) }

        val (firstPage, firstHasMore) = sessionStore.findSummariesBySessionScope(scope, "alice", limit = 2)
        val (secondPage, secondHasMore) =
            sessionStore.findSummariesBySessionScope(scope, "alice", limit = 2, offset = 2)

        assertEquals(2, firstPage.size)
        assertTrue(firstHasMore)
        assertEquals(1, secondPage.size)
        assertFalse(secondHasMore)
        assertEquals(ids.toSet(), (firstPage.map { it.id } + secondPage.map { it.id }).toSet())
    }
}
