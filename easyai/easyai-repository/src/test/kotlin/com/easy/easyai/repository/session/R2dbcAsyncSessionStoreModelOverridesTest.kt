package com.easy.easyai.repository.session

import com.easy.easyai.core.agent.PersistedSession
import com.easy.easyai.repository.database.DatabaseMigration
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Integration tests for the `session.model_overrides` column through [R2dbcAsyncSessionStore]:
 * the value is the JSON of the overrides actually applied to the last turn, so it must survive
 * a save/read round-trip, be replaceable, and be clearable (null = no overrides recorded).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class R2dbcAsyncSessionStoreModelOverridesTest {

    private lateinit var db: R2dbcDatabase

    @BeforeAll
    fun setupDb() = runTest {
        db = R2dbcDatabase.connect(
            url = "r2dbc:h2:mem:///session_overrides_test_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1",
            manager = { TransactionManager(it) }
        )
        DatabaseMigration.defaultTables().execute(db)
    }

    private fun store() = R2dbcAsyncSessionStore(db)

    private suspend fun createSession(): String {
        val store = store()
        val id = "s-${UUID.randomUUID()}"
        store.save(
            PersistedSession(
                id = id,
                messages = emptyList(),
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )
        )
        return id
    }

    @Nested
    inner class `model overrides` {

        @Test
        fun `a fresh session has no recorded overrides`() = runTest {
            val sessionId = createSession()

            assertNull(store().findModelOverrides(sessionId))
        }

        @Test
        fun `round-trips the json the turn applied`() = runTest {
            val sessionId = createSession()
            val json = """{"effort":"xhigh","contextToken":409600}"""

            store().saveModelOverrides(sessionId, json)

            assertEquals(json, store().findModelOverrides(sessionId))
        }

        @Test
        fun `a later turn replaces the recorded value`() = runTest {
            val sessionId = createSession()
            store().saveModelOverrides(sessionId, """{"effort":"high"}""")

            store().saveModelOverrides(sessionId, """{"contextToken":204800}""")

            assertEquals("""{"contextToken":204800}""", store().findModelOverrides(sessionId))
        }

        @Test
        fun `null clears the column`() = runTest {
            val sessionId = createSession()
            store().saveModelOverrides(sessionId, """{"effort":"max"}""")

            store().saveModelOverrides(sessionId, null)

            assertNull(store().findModelOverrides(sessionId))
        }

        @Test
        fun `an unknown session id reads as null`() = runTest {
            assertNull(store().findModelOverrides("s-${UUID.randomUUID()}"))
        }
    }
}
