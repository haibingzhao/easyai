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
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Integration tests for endReason persistence in [R2dbcAsyncSessionStore].
 * Verifies save/find round-trip and userId-based data isolation.
 */
class R2dbcAsyncSessionStoreEndReasonTest {

    companion object {
        private lateinit var db: R2dbcDatabase

        @BeforeAll
        @JvmStatic
        fun setupDb() = runTest {
            db = R2dbcDatabase.connect(
                url = "r2dbc:h2:mem:///endreason_test_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1",
                manager = { TransactionManager(it) }
            )
            DatabaseMigration.defaultTables().execute(db)
        }
    }

    private fun createStore() = R2dbcAsyncSessionStore(db)

    private fun createSession(store: R2dbcAsyncSessionStore, sessionId: String, userId: String = "system") {
        runTest {
            store.save(
                PersistedSession(
                    id = sessionId,
                    messages = emptyList(),
                    createdAt = Instant.now(),
                    updatedAt = Instant.now()
                ),
                userId = userId
            )
        }
    }

    @Nested
    inner class `saveEndReason and findEndReason` {

        @Test
        fun `saves and reads endReason for normal`() = runTest {
            val store = createStore()
            val sessionId = "test-normal-${UUID.randomUUID()}"
            createSession(store, sessionId)

            store.saveEndReason(sessionId, "normal")
            val result = store.findEndReason(sessionId)
            assertEquals("normal", result)
        }

        @Test
        fun `saves and reads endReason for max_iterations`() = runTest {
            val store = createStore()
            val sessionId = "test-maxiter-${UUID.randomUUID()}"
            createSession(store, sessionId)

            store.saveEndReason(sessionId, "max_iterations")
            val result = store.findEndReason(sessionId)
            assertEquals("max_iterations", result)
        }

        @Test
        fun `saves and reads endReason for cancelled`() = runTest {
            val store = createStore()
            val sessionId = "test-cancelled-${UUID.randomUUID()}"
            createSession(store, sessionId)

            store.saveEndReason(sessionId, "cancelled")
            val result = store.findEndReason(sessionId)
            assertEquals("cancelled", result)
        }

        @Test
        fun `saves and reads endReason for error`() = runTest {
            val store = createStore()
            val sessionId = "test-error-${UUID.randomUUID()}"
            createSession(store, sessionId)

            store.saveEndReason(sessionId, "error")
            val result = store.findEndReason(sessionId)
            assertEquals("error", result)
        }

        @Test
        fun `findEndReason returns null for non-existent session`() = runTest {
            val store = createStore()
            val result = store.findEndReason("non-existent-${UUID.randomUUID()}")
            assertNull(result)
        }

        @Test
        fun `saveEndReason overwrites previous value`() = runTest {
            val store = createStore()
            val sessionId = "test-overwrite-${UUID.randomUUID()}"
            createSession(store, sessionId)

            store.saveEndReason(sessionId, "max_iterations")
            assertEquals("max_iterations", store.findEndReason(sessionId))

            store.saveEndReason(sessionId, "normal")
            assertEquals("normal", store.findEndReason(sessionId))
        }

        @Test
        fun `findEndReason returns null when endReason column is null`() = runTest {
            val store = createStore()
            val sessionId = "test-null-${UUID.randomUUID()}"
            createSession(store, sessionId)

            // Don't save any endReason — column should be null by default
            val result = store.findEndReason(sessionId)
            assertNull(result)
        }
    }

    @Nested
    inner class `userId isolation` {

        @Test
        fun `findEndReason with matching userId returns value`() = runTest {
            val store = createStore()
            val sessionId = "test-user-match-${UUID.randomUUID()}"
            val userId = "user-alice"
            createSession(store, sessionId, userId)

            store.saveEndReason(sessionId, "max_iterations", userId)
            val result = store.findEndReason(sessionId, userId)
            assertEquals("max_iterations", result)
        }

        @Test
        fun `findEndReason with wrong userId returns null`() = runTest {
            val store = createStore()
            val sessionId = "test-user-wrong-${UUID.randomUUID()}"
            createSession(store, sessionId, "user-alice")

            store.saveEndReason(sessionId, "max_iterations", "user-alice")

            // Different user should not see the endReason
            val result = store.findEndReason(sessionId, "user-bob")
            assertNull(result, "endReason should not be visible to a different user")
        }

        @Test
        fun `saveEndReason with wrong userId does not update`() = runTest {
            val store = createStore()
            val sessionId = "test-save-wrong-${UUID.randomUUID()}"
            createSession(store, sessionId, "user-alice")

            // Try to save with wrong userId — update WHERE clause won't match
            store.saveEndReason(sessionId, "max_iterations", "user-bob")

            // Original user reads null (no update happened)
            val result = store.findEndReason(sessionId, "user-alice")
            assertNull(result, "saveEndReason with wrong userId should not modify the row")
        }

        @Test
        fun `system user can only access sessions owned by system`() = runTest {
            val store = createStore()
            val sessionId = "test-system-${UUID.randomUUID()}"
            // Create session owned by "system" user
            createSession(store, sessionId, "system")

            store.saveEndReason(sessionId, "cancelled", "system")

            // "system" user can read its own session
            val result = store.findEndReason(sessionId, "system")
            assertEquals("cancelled", result)

            // A different user cannot see system-owned session
            val otherResult = store.findEndReason(sessionId, "user-bob")
            assertNull(otherResult, "Non-system user should not see system-owned endReason")
        }
    }
}
