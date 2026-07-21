package com.easy.easyai.repository.session

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.PersistedSession
import com.easy.easyai.core.model.*
import com.easy.easyai.repository.database.DatabaseMigration
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.junit.jupiter.api.*
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for compaction-related operations in [R2dbcAsyncSessionStore].
 * Uses an in-memory H2 R2DBC database.
 */
class R2dbcAsyncSessionStoreCompactionTest {

    companion object {
        private lateinit var db: R2dbcDatabase

        @BeforeAll
        @JvmStatic
        fun setupDb() = runTest {
            db = R2dbcDatabase.connect(
                url = "r2dbc:h2:mem:///compaction_test_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1",
                manager = { TransactionManager(it) }
            )
            DatabaseMigration.defaultTables().execute(db)
        }
    }

    private val testAgentContext = AgentContext(
        agentId = "test-agent",
        sessionId = "test-session-${UUID.randomUUID()}",
        customInstructions = "test"
    )

    private fun createStore() = R2dbcAsyncSessionStore(db)

    private fun createUserMessage(
        id: String = generateMessageId(),
        text: String = "hello",
        metadata: Map<String, String> = emptyMap()
    ): UserMessage = UserMessage(
        id = id,
        content = listOf(TextContent(text)),
        metadata = metadata
    )

    @Nested
    inner class `loadActiveMessages - compaction summary` {

        @Test
        fun `returns compaction summary after messages are compacted`() = runTest {
            val store = createStore()
            val sessionId = testAgentContext.sessionId!!

            // 1. Insert a session row (required for FK-like relationship)
            store.save(
                PersistedSession(
                    id = sessionId,
                    messages = emptyList(),
                    createdAt = Instant.now(),
                    updatedAt = Instant.now()
                )
            )

            // 2. Insert 5 messages with known timestamps
            val baseTime = 1000000L
            val messages = (1..5).map { i ->
                createUserMessage(text = "message-$i")
            }
            store.upsertMessages(testAgentContext, sessionId, messages)

            // Manually set createdAt to known values for ordering verification
            // (upsertMessages uses System.currentTimeMillis(), so we just verify relative ordering)

            // 3. Mark messages 1-3 as compacted
            val compactedIds = messages.take(3).map { it.id }
            val compactedAt = baseTime + 500
            store.markCompacted(sessionId, compactedIds, compactedAt)

            // 4. Save compaction summary with createdAt = last compacted message's timestamp
            val summaryMessage = createUserMessage(
                text = "This is a summary of messages 1-3",
                metadata = mapOf("isCompactionSummary" to "true")
            )
            store.saveCompactionSummary(testAgentContext, summaryMessage, createdAt = baseTime + 3)

            // 5. Load active messages
            val activeMessages = store.loadActiveMessages(sessionId)

            // 6. Verify: should contain summary + messages 4-5 (NOT compacted messages 1-3)
            assertEquals(3, activeMessages.size, "Should have 3 active messages: summary + 2 recent")

            val summaryResult = activeMessages.filterIsInstance<UserMessage>()
                .find { it.metadata["isCompactionSummary"] == "true" }
            assertNotNull(summaryResult, "Compaction summary should be in active messages")
            assertEquals("This is a summary of messages 1-3",
                (summaryResult!!.content.first() as TextContent).text)

            // Verify compacted messages are NOT returned
            val returnedIds = activeMessages.map { it.id }.toSet()
            compactedIds.forEach { id ->
                assertFalse(returnedIds.contains(id), "Compacted message $id should not be in active messages")
            }
        }

        @Test
        fun `excludes CUSTOM role messages`() = runTest {
            val store = createStore()
            val sessionId = testAgentContext.sessionId!!

            store.save(
                PersistedSession(
                    id = sessionId,
                    messages = emptyList(),
                    createdAt = Instant.now(),
                    updatedAt = Instant.now()
                )
            )

            // Insert a normal message
            val normalMsg = createUserMessage(text = "normal")
            store.upsertMessages(testAgentContext, sessionId, listOf(normalMsg))

            // Save a compaction indicator (CUSTOM role)
            val indicator = UserMessage(
                id = generateMessageId(),
                content = listOf(
                    CustomContent(
                        customType = "compaction",
                        metadata = mapOf("compactedCount" to 3, "tokensSaved" to 100)
                    )
                ),
                metadata = mapOf("isCompactionIndicator" to "true")
            )
            store.saveCompactionIndicator(testAgentContext, indicator, createdAt = System.currentTimeMillis())

            // Load active messages
            val activeMessages = store.loadActiveMessages(sessionId)

            // Should contain normal message only, NOT the CUSTOM indicator
            val roles = activeMessages.map { it.role }
            assertFalse(roles.contains(Role.CUSTOM), "CUSTOM role messages should be excluded")
            assertTrue(activeMessages.any { it.id == normalMsg.id }, "Normal message should be present")
        }

        @Test
        fun `summary metadata survives serialization round-trip`() = runTest {
            val store = createStore()
            val sessionId = testAgentContext.sessionId!!

            store.save(
                PersistedSession(
                    id = sessionId,
                    messages = emptyList(),
                    createdAt = Instant.now(),
                    updatedAt = Instant.now()
                )
            )

            // Save summary with rich metadata (simulating what R2dbcCompactionListener does)
            val summaryId = generateMessageId()
            val compactedIds = listOf("msg_1", "msg_2", "msg_3")
            val summary = UserMessage(
                id = summaryId,
                content = listOf(TextContent("Summary text")),
                metadata = mapOf(
                    "isCompactionSummary" to "true",
                    "compactionStrategy" to "summary",
                    "compactedMessageIds" to """["msg_1","msg_2","msg_3"]""",
                    "compactionRound" to "1"
                )
            )
            store.saveCompactionSummary(testAgentContext, summary, createdAt = 999L)

            // Load it back
            val activeMessages = store.loadActiveMessages(sessionId)
            val loaded = activeMessages.find { it.id == summaryId } as? UserMessage

            assertNotNull(loaded, "Summary should be loadable")
            assertEquals("true", loaded!!.metadata["isCompactionSummary"])
            assertEquals("summary", loaded.metadata["compactionStrategy"])
            assertEquals("1", loaded.metadata["compactionRound"])
            assertEquals("""["msg_1","msg_2","msg_3"]""", loaded.metadata["compactedMessageIds"])
        }

        @Test
        fun `summary sorts correctly between prefix and recent messages`() = runTest {
            val store = createStore()
            val sessionId = testAgentContext.sessionId!!

            store.save(
                PersistedSession(
                    id = sessionId,
                    messages = emptyList(),
                    createdAt = Instant.now(),
                    updatedAt = Instant.now()
                )
            )

            // Insert messages with explicit timestamps by directly using upsert
            // Prefix messages (t=100, t=200)
            val prefix1 = createUserMessage(text = "prefix-1")
            val prefix2 = createUserMessage(text = "prefix-2")
            store.upsertMessages(testAgentContext, sessionId, listOf(prefix1, prefix2))

            // Mark prefix as compacted
            store.markCompacted(sessionId, listOf(prefix1.id, prefix2.id), System.currentTimeMillis())

            // Save summary at t=200 (same as last compacted)
            val summary = createUserMessage(
                text = "summary",
                metadata = mapOf("isCompactionSummary" to "true")
            )
            store.saveCompactionSummary(testAgentContext, summary, createdAt = 200L)

            // Insert recent messages (will get current time, which is > 200)
            val recent = createUserMessage(text = "recent-1")
            store.upsertMessages(testAgentContext, sessionId, listOf(recent))

            // Load active messages
            val activeMessages = store.loadActiveMessages(sessionId)

            // Verify ordering: summary should come before recent messages
            assertEquals(2, activeMessages.size, "Should have summary + recent")
            val summaryIndex = activeMessages.indexOfFirst { it.id == summary.id }
            val recentIndex = activeMessages.indexOfFirst { it.id == recent.id }
            assertTrue(summaryIndex < recentIndex,
                "Summary (index=$summaryIndex) should come before recent (index=$recentIndex)")
        }
    }

    @Nested
    inner class `loadMessagesByIds` {

        @Test
        fun `loads compacted messages by IDs`() = runTest {
            val store = createStore()
            val sessionId = testAgentContext.sessionId!!

            store.save(
                PersistedSession(
                    id = sessionId,
                    messages = emptyList(),
                    createdAt = Instant.now(),
                    updatedAt = Instant.now()
                )
            )

            // Insert and then compact messages
            val msgs = (1..3).map { createUserMessage(text = "msg-$it") }
            store.upsertMessages(testAgentContext, sessionId, msgs)
            store.markCompacted(sessionId, msgs.map { it.id }, System.currentTimeMillis())

            // Verify they are NOT in active messages
            val active = store.loadActiveMessages(sessionId)
            assertEquals(0, active.size, "All messages should be compacted")

            // But loadMessagesByIds SHOULD return them (including compacted)
            val loaded = store.loadMessagesByIds(msgs.map { it.id })
            assertEquals(3, loaded.size, "Should load compacted messages by IDs")
        }

        @Test
        fun `excludes CUSTOM role messages`() = runTest {
            val store = createStore()
            val sessionId = testAgentContext.sessionId!!

            store.save(
                PersistedSession(
                    id = sessionId,
                    messages = emptyList(),
                    createdAt = Instant.now(),
                    updatedAt = Instant.now()
                )
            )

            // Save a CUSTOM role indicator
            val indicator = UserMessage(
                id = generateMessageId(),
                content = listOf(CustomContent(customType = "compaction", metadata = emptyMap())),
                metadata = mapOf("isCompactionIndicator" to "true")
            )
            store.saveCompactionIndicator(testAgentContext, indicator, createdAt = System.currentTimeMillis())

            // Try to load it by ID
            val loaded = store.loadMessagesByIds(listOf(indicator.id))
            assertEquals(0, loaded.size, "CUSTOM role messages should be excluded from loadMessagesByIds")
        }

        @Test
        fun `returns empty list for empty input`() = runTest {
            val store = createStore()
            val loaded = store.loadMessagesByIds(emptyList())
            assertEquals(0, loaded.size)
        }
    }
}
