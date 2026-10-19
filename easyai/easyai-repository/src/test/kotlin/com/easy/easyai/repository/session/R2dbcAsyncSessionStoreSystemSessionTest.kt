package com.easy.easyai.repository.session

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.PersistedSession
import com.easy.easyai.repository.database.DatabaseMigration
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.junit.jupiter.api.*
import java.time.Instant
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests verifying that a session the system opened for background work stays excluded
 * from the Chat History listings ([R2dbcAsyncSessionStore.findMetadataByLimit] and
 * [R2dbcAsyncSessionStore.findIdsByLimit]).
 *
 * The exclusion is a column on the session row, deliberately not a lookup into `async_task`: the
 * Tasks page offers delete and "Clear completed", and a reverse-existence test would hand every
 * hidden transcript back to Chat History the moment the user cleaned their list up.
 */
class R2dbcAsyncSessionStoreSystemSessionTest {

    companion object {
        private lateinit var db: R2dbcDatabase

        @BeforeAll
        @JvmStatic
        fun setupDb() = runTest {
            db = R2dbcDatabase.connect(
                url = "r2dbc:h2:mem:///system_session_test_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1",
                manager = { TransactionManager(it) }
            )
            DatabaseMigration.defaultTables().execute(db)
        }
    }

    private val sessionStore = R2dbcAsyncSessionStore(db)

    private fun context(sessionId: String, systemKind: String?) = AgentContext(
        agentId = "memory-consolidation",
        sessionId = sessionId,
        systemKind = systemKind
    )

    private suspend fun listedIds(): List<String> =
        sessionStore.findMetadataByLimit(limit = 200, offset = 0).first.map { it.id } +
            sessionStore.findIdsByLimit(limit = 200, offset = 0).ids

    @Nested
    inner class `hidden transcript stays hidden` {

        @Test
        fun `a marked session is excluded with no task row pointing at it`() = runTest {
            val chatSessionId = "chat-${UUID.randomUUID()}"
            val taskSessionId = "task-${UUID.randomUUID()}"

            sessionStore.ensureSessionExists(chatSessionId, context(chatSessionId, systemKind = null))
            sessionStore.ensureSessionExists(taskSessionId, context(taskSessionId, AgentContext.SYSTEM_KIND_ASYNC_TASK))

            val ids = listedIds()
            assertTrue(chatSessionId in ids, "an ordinary chat session should be listed")
            assertFalse(taskSessionId in ids, "a background run's session should be excluded")
        }

        @Test
        fun `a row created by the unmarked chat path is still hidden once the run claims it`() = runTest {
            // Whichever write lands first (save() here, the message listener's ensureSessionExists
            // there) decides whether the marker arrived with the insert, so the marker is applied to
            // an existing unmarked row too.
            val sessionId = "late-${UUID.randomUUID()}"

            sessionStore.save(
                PersistedSession(
                    id = sessionId,
                    messages = emptyList(),
                    createdAt = Instant.now(),
                    updatedAt = Instant.now()
                )
            )
            assertTrue(sessionId in listedIds(), "the row starts out as an ordinary session")

            sessionStore.ensureSessionExists(sessionId, context(sessionId, AgentContext.SYSTEM_KIND_ASYNC_TASK))

            assertFalse(sessionId in listedIds(), "the run's marker must survive a pre-existing row")
        }
    }
}
