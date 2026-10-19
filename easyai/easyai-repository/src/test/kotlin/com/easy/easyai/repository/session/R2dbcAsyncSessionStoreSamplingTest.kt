package com.easy.easyai.repository.session

import com.easy.easyai.api.model.SamplingIntent
import com.easy.easyai.api.model.SamplingTrace
import com.easy.easyai.api.model.TemperatureSource
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.PersistedSession
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.StopReason
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.generateMessageId
import com.easy.easyai.repository.database.DatabaseMigration
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.junit.jupiter.api.*
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Verifies the per-reply sampling record survives a persist/reload round-trip, including the case
 * that used to be dropped: an assistant message with nothing else in its metadata column.
 *
 * This is the reproducibility anchor for a trading run — without it, editing a model row silently
 * rewrites what past sessions can claim about how their answers were produced.
 */
class R2dbcAsyncSessionStoreSamplingTest {

    companion object {
        private lateinit var db: R2dbcDatabase

        @BeforeAll
        @JvmStatic
        fun setupDb() = runTest {
            db = R2dbcDatabase.connect(
                url = "r2dbc:h2:mem:///sampling_test_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1",
                manager = { TransactionManager(it) }
            )
            DatabaseMigration.defaultTables().execute(db)
        }
    }

    private fun createStore() = R2dbcAsyncSessionStore(db)

    private suspend fun seededSession(store: R2dbcAsyncSessionStore): Pair<AgentContext, String> {
        val context = AgentContext(agentId = "test-agent", sessionId = "sampling-${UUID.randomUUID()}")
        val sessionId = context.sessionId!!
        store.save(
            PersistedSession(
                id = sessionId,
                messages = emptyList(),
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )
        )
        return context to sessionId
    }

    @Test
    fun `sampling round-trips even when the message carries no references`() = runTest {
        val store = createStore()
        val (context, sessionId) = seededSession(store)
        val trace = SamplingTrace(
            intent = SamplingIntent.DETERMINISTIC,
            source = TemperatureSource.PINNED,
            requestedTemperature = 0.1,
            sentTemperature = 0.1
        )
        val message = AssistantMessage(
            id = generateMessageId(),
            content = listOf(TextContent("偏多，止损 128")),
            stopReason = StopReason.STOP,
            sampling = trace
        )

        store.upsertMessages(context, sessionId, listOf(message))

        val loaded = store.loadMessagesWithTimestamps(sessionId).map { it.message }
            .filterIsInstance<AssistantMessage>().single()
        // The old writer keyed metadata on references alone; a sampling-only row would have been
        // written as NULL and this assertion is what catches that regression.
        assertEquals(trace, loaded.sampling)
    }

    @Test
    fun `an omitted temperature round-trips as omitted, not as zero`() = runTest {
        val store = createStore()
        val (context, sessionId) = seededSession(store)
        val trace = SamplingTrace(
            intent = SamplingIntent.FOLLOW_MODEL_DEFAULT,
            source = TemperatureSource.UNSET_BY_MODEL_CAPABILITY,
            requestedTemperature = null,
            sentTemperature = null
        )

        store.upsertMessages(
            context,
            sessionId,
            listOf(
                AssistantMessage(
                    id = generateMessageId(),
                    content = listOf(TextContent("ok")),
                    sampling = trace
                )
            )
        )

        val loaded = store.loadMessagesWithTimestamps(sessionId).map { it.message }
            .filterIsInstance<AssistantMessage>().single()

        assertNull(loaded.sampling?.requestedTemperature)
        assertEquals(TemperatureSource.UNSET_BY_MODEL_CAPABILITY, loaded.sampling?.source)
    }

    @Test
    fun `a request dropped by the adapter is distinguishable from one never asked for`() = runTest {
        val store = createStore()
        val (context, sessionId) = seededSession(store)
        // asked for 0.1, sent nothing: a reasoning-mode turn on a thinking-enabled row.
        val dropped = SamplingTrace(
            intent = SamplingIntent.DETERMINISTIC,
            source = TemperatureSource.PINNED,
            requestedTemperature = 0.1,
            sentTemperature = null
        )

        store.upsertMessages(
            context,
            sessionId,
            listOf(
                AssistantMessage(
                    id = generateMessageId(),
                    content = listOf(TextContent("ok")),
                    sampling = dropped
                )
            )
        )

        val loaded = store.loadMessagesWithTimestamps(sessionId).map { it.message }
            .filterIsInstance<AssistantMessage>().single()

        assertNotNull(loaded.sampling)
        assertEquals(0.1, loaded.sampling?.requestedTemperature)
        assertNull(loaded.sampling?.sentTemperature)
    }

    @Test
    fun `rows written before the sampling field still load`() = runTest {
        val store = createStore()
        val (context, sessionId) = seededSession(store)

        store.upsertMessages(
            context,
            sessionId,
            listOf(
                AssistantMessage(
                    id = generateMessageId(),
                    content = listOf(TextContent("legacy")),
                    stopReason = StopReason.STOP
                )
            )
        )

        val loaded = store.loadMessagesWithTimestamps(sessionId).map { it.message }
            .filterIsInstance<AssistantMessage>().single()

        assertNull(loaded.sampling)
    }
}
