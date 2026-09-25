package com.easy.easyai.repository.session

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.PersistedSession
import com.easy.easyai.core.model.*
import com.easy.easyai.repository.database.DatabaseMigration
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round-trip coverage for ERROR-role messages: [AgentRunner] persists an [ErrorMessage]
 * when an LLM call fails, and the frontend session-loader reads history via
 * [loadMessagesWithTimestamps]. If parseMessageRow drops the ERROR row the UI falls back
 * to a generic "interrupted" banner and hides the real failure reason.
 */
class R2dbcAsyncSessionStoreErrorMessageTest {

    companion object {
        private lateinit var db: R2dbcDatabase

        @BeforeAll
        @JvmStatic
        fun setupDb() = runTest {
            db = R2dbcDatabase.connect(
                url = "r2dbc:h2:mem:///error_msg_test_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1",
                manager = { TransactionManager(it) }
            )
            DatabaseMigration.defaultTables().execute(db)
        }
    }

    private fun createStore() = R2dbcAsyncSessionStore(db)

    @Test
    fun `ErrorMessage survives persist and reload via loadMessagesWithTimestamps`() = runTest {
        val store = createStore()
        val context = AgentContext(agentId = "test-agent", sessionId = "err-session-${UUID.randomUUID()}")
        val sessionId = context.sessionId!!

        store.save(
            PersistedSession(
                id = sessionId,
                messages = emptyList(),
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )
        )

        val errorText = "模型输出被内容安全策略拦截，请调整问题后重试"
        val errorMessage = ErrorMessage(id = generateMessageId(), error = errorText, isRetryable = false)
        val assistantMessage = AssistantMessage(
            id = generateMessageId(),
            content = listOf(TextContent("部分分析结果")),
            stopReason = StopReason.ERROR
        )
        store.upsertMessages(context, sessionId, listOf(assistantMessage, errorMessage))

        val loaded = store.loadMessagesWithTimestamps(sessionId).map { it.message }

        val reloadedError = loaded.filterIsInstance<ErrorMessage>().singleOrNull()
        assertNotNull(reloadedError, "ErrorMessage must be reconstructed on reload, not dropped")
        assertEquals(errorMessage.id, reloadedError!!.id)
        assertEquals(errorText, reloadedError.error)
        assertEquals(errorText, (reloadedError.content.first() as TextContent).text)

        // The partial assistant message saved alongside it must also survive.
        val reloadedAssistant = loaded.filterIsInstance<AssistantMessage>().single()
        assertEquals(StopReason.ERROR, reloadedAssistant.stopReason)
        assertTrue((reloadedAssistant.content.first() as TextContent).text.contains("部分分析结果"))
    }
}
