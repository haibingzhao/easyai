package com.easy.easyai.repository.session

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.PersistedSession
import com.easy.easyai.core.model.*
import com.easy.easyai.repository.database.DatabaseMigration
import com.easy.easyai.repository.database.Tables
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.update
import org.junit.jupiter.api.*
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import com.easy.easyai.common.util.SharedObjectMapper
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for fork (branch session) operations in [R2dbcAsyncSessionStore]:
 * message copying, compaction-aware compactedAt handling, history exclusion,
 * multi-level fork lineage, and cascade deletion.
 * Uses an in-memory H2 R2DBC database.
 */
class R2dbcAsyncSessionStoreForkTest {

    companion object {
        private lateinit var db: R2dbcDatabase
        private val objectMapper: ObjectMapper = SharedObjectMapper.instance

        @BeforeAll
        @JvmStatic
        fun setupDb() = runTest {
            db = R2dbcDatabase.connect(
                url = "r2dbc:h2:mem:///fork_test_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1",
                manager = { TransactionManager(it) }
            )
            DatabaseMigration.defaultTables().execute(db)
        }
    }

    private fun ctx(sessionId: String) = AgentContext(
        agentId = "test-agent",
        sessionId = sessionId,
        customInstructions = "test"
    )

    private val store = R2dbcAsyncSessionStore(db)

    private suspend fun createSession(sessionId: String) {
        store.save(
            PersistedSession(
                id = sessionId,
                messages = emptyList(),
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )
        )
    }

    private fun userMessage(id: String, text: String, metadata: Map<String, String> = emptyMap()): UserMessage =
        UserMessage(id = id, content = listOf(TextContent(text)), metadata = metadata)

    /** Pin message rows to deterministic timestamps (upsert uses wall-clock time). */
    private suspend fun setCreatedAt(messageId: String, createdAt: Long) {
        suspendTransaction(db) {
            Tables.Message.update(where = { Tables.Message.id eq messageId }) {
                it[Tables.Message.createdAt] = createdAt
            }
        }
    }

    private suspend fun textsOf(sessionId: String): List<String> =
        store.loadMessagesWithTimestamps(sessionId).mapNotNull { m ->
            (m.message.content.firstOrNull() as? TextContent)?.text
        }

    private suspend fun activeIds(sessionId: String): List<String> =
        store.loadActiveMessages(sessionId).map { it.id }

    @Nested
    inner class `createFork copies history` {

        @Test
        fun `copies messages up to and including the anchor with new ids`() = runTest {
            val source = "src-${UUID.randomUUID()}"
            createSession(source)
            val msgs = (1..5).map { userMessage("m$it-${UUID.randomUUID()}", "msg-$it") }
            store.upsertMessages(ctx(source), source, msgs)
            msgs.forEachIndexed { i, m -> setCreatedAt(m.id, (i + 1) * 1000L) }

            val forkedId = store.createFork(source, msgs[2].id)!!

            val copied = store.loadMessagesWithTimestamps(forkedId)
            assertEquals(3, copied.size)
            assertEquals(listOf("msg-1", "msg-2", "msg-3"), copied.map { (it.message.content.first() as TextContent).text })
            assertTrue(copied.map { it.message.id }.intersect(msgs.map { it.id }.toSet()).isEmpty(),
                "fork must not reuse source message ids")
            // Source untouched
            assertEquals(5, store.loadMessagesWithTimestamps(source).size)

            val forkInfo = store.findForkInfo(forkedId)!!
            assertEquals(source, forkInfo.forkedFromSessionId)
            assertEquals(source, forkInfo.forkRootSessionId)
        }

        @Test
        fun `remaps parentMessageId to copied messages`() = runTest {
            val source = "src-${UUID.randomUUID()}"
            createSession(source)
            val parent = userMessage("p-${UUID.randomUUID()}", "parent")
            val child = userMessage("c-${UUID.randomUUID()}", "child")
            store.upsertMessages(ctx(source), source, listOf(parent))
            store.upsertMessages(ctx(source), source, listOf(child), parentMessageId = parent.id)
            setCreatedAt(parent.id, 1000L)
            setCreatedAt(child.id, 2000L)

            val forkedId = store.createFork(source, child.id)!!

            val copied = store.loadMessagesWithTimestamps(forkedId)
            assertEquals(2, copied.size)
            val copiedChild = copied.first { (it.message.content.first() as TextContent).text == "child" }
            val copiedParent = copied.first { (it.message.content.first() as TextContent).text == "parent" }
            assertEquals(copiedParent.message.id, copiedChild.parentMessageId)
        }

        @Test
        fun `returns null for unknown anchor or inaccessible source`() = runTest {
            val source = "src-${UUID.randomUUID()}"
            createSession(source)
            val msg = userMessage("x-${UUID.randomUUID()}", "only")
            store.upsertMessages(ctx(source), source, listOf(msg))

            assertNull(store.createFork(source, "no-such-message"))
            assertNull(store.createFork("no-such-session", msg.id))
        }
    }

    @Nested
    inner class `createFork is compaction-aware` {

        /** m1,m2 compacted at t=1500 with summary at t=1201 covering them. */
        private suspend fun compactedSource(anchorIndex: Int): Pair<String, List<EasyAiMessage>> {
            val source = "src-${UUID.randomUUID()}"
            createSession(source)
            val msgs = (1..4).map { userMessage("m$it-${UUID.randomUUID()}", "msg-$it") }
            store.upsertMessages(ctx(source), source, msgs)
            msgs.forEachIndexed { i, m -> setCreatedAt(m.id, (i + 1) * 1000L) }
            val compactedIds = listOf(msgs[0].id, msgs[1].id)
            store.markCompacted(source, compactedIds, compactedAt = 1500L)
            val summary = userMessage(
                "s-${UUID.randomUUID()}", "summary",
                mapOf("isCompactionSummary" to "true", "compactedMessageIds" to objectMapper.writeValueAsString(compactedIds))
            )
            // Anchor before the compaction (case B) vs after (case A) — caller picks.
            val summaryCreatedAt = if (anchorIndex >= 2) 2001L else 9000L
            store.saveCompactionSummary(ctx(source), summary, createdAt = summaryCreatedAt)
            return source to msgs
        }

        @Test
        fun `case A anchor after compaction keeps compacted marks and remaps compactedMessageIds`() = runTest {
            val (source, msgs) = compactedSource(anchorIndex = 3)

            val forkedId = store.createFork(source, msgs[3].id)!!

            // Active context mirrors the source: summary + msg3 + msg4 (msg1/msg2 stay compacted)
            val forked = store.loadMessagesWithTimestamps(forkedId)
            assertEquals(5, forked.size, "summary + 4 messages copied")
            assertEquals(3, store.loadActiveMessages(forkedId).size)

            val summaryMessage = forked.first { (it.message as? UserMessage)?.metadata?.get("isCompactionSummary") == "true" }
            val remappedIds: List<String> = objectMapper.readValue(
                (summaryMessage.message as UserMessage).metadata["compactedMessageIds"]!!, object : TypeReference<List<String>>() {})
            val forkedIds = forked.map { it.message.id }
            assertEquals(2, remappedIds.size)
            assertTrue(remappedIds.all { it in forkedIds }, "compactedMessageIds must point at fork-local ids")

            // undoCompactionAfter works inside the fork: restoring from before msg1 revives it
            store.undoCompactionAfter(forkedId, 1000L)
            assertEquals(4, activeIds(forkedId).size, "restored fork replays msg1..msg4 without the summary")
        }

        @Test
        fun `case B anchor predates the compaction restores compacted marks`() = runTest {
            val (source, msgs) = compactedSource(anchorIndex = 1)

            val forkedId = store.createFork(source, msgs[1].id)!!

            val forked = store.loadMessagesWithTimestamps(forkedId)
            assertEquals(2, forked.size, "only msg1+msg2 copied; summary/indicator cut away")
            assertTrue(forked.all { it.compactedAt == null }, "orphaned compactedAt must be cleared")
            assertEquals(2, store.loadActiveMessages(forkedId).size, "fork replays full uncompressed history")
        }

        @Test
        fun `skips compaction indicator whose summary was not copied`() = runTest {
            val source = "src-${UUID.randomUUID()}"
            createSession(source)
            val m1 = userMessage("m1-${UUID.randomUUID()}", "msg-1")
            val m2 = userMessage("m2-${UUID.randomUUID()}", "msg-2")
            store.upsertMessages(ctx(source), source, listOf(m1, m2))
            setCreatedAt(m1.id, 1000L)
            setCreatedAt(m2.id, 2000L)
            val summary = userMessage(
                "s-${UUID.randomUUID()}", "summary",
                mapOf("isCompactionSummary" to "true", "compactedMessageIds" to objectMapper.writeValueAsString(listOf(m1.id)))
            )
            store.saveCompactionSummary(ctx(source), summary, createdAt = 5000L)
            val indicator = UserMessage(
                id = "i-${UUID.randomUUID()}",
                content = listOf(
                    CustomContent(
                        customType = "compaction",
                        metadata = mapOf("summaryMessageId" to summary.id)
                    )
                ),
                metadata = mapOf("isCompactionIndicator" to "true")
            )
            store.saveCompactionIndicator(ctx(source), indicator, createdAt = 1500L)

            val forkedId = store.createFork(source, m2.id)!!

            val forked = store.loadMessagesWithTimestamps(forkedId)
            assertEquals(2, forked.size, "orphan indicator must not be copied")
        }
    }

    @Nested
    inner class `fork sessions are excluded from history listings` {

        @Test
        fun `findMetadataByLimit and findIdsByLimit skip forks`() = runTest {
            val source = "src-${UUID.randomUUID()}"
            createSession(source)
            val msg = userMessage("m-${UUID.randomUUID()}", "only")
            store.upsertMessages(ctx(source), source, listOf(msg))
            val forkedId = store.createFork(source, msg.id)!!

            val (metadata, _) = store.findMetadataByLimit(limit = 100)
            assertTrue(source in metadata.map { it.id })
            assertFalse(forkedId in metadata.map { it.id }, "fork must not appear in history")

            val page = store.findIdsByLimit(limit = 100)
            assertFalse(forkedId in page.ids, "fork must not appear in history ids")
        }
    }

    @Nested
    inner class `multi-level fork lineage` {

        @Test
        fun `fork of a fork keeps root and direct source`() = runTest {
            val root = "root-${UUID.randomUUID()}"
            createSession(root)
            val m1 = userMessage("m1-${UUID.randomUUID()}", "msg-1")
            val m2 = userMessage("m2-${UUID.randomUUID()}", "msg-2")
            store.upsertMessages(ctx(root), root, listOf(m1, m2))
            setCreatedAt(m1.id, 1000L)
            setCreatedAt(m2.id, 2000L)

            val branch1 = store.createFork(root, m1.id)!!
            val b1Messages = store.loadMessagesWithTimestamps(branch1)
            val branch2 = store.createFork(branch1, b1Messages.last().message.id)!!

            val info = store.findForkInfo(branch2)!!
            assertEquals(branch1, info.forkedFromSessionId, "direct source is the branch it was forked from")
            assertEquals(root, info.forkRootSessionId, "root stays the main session")

            val forks = store.listForks(root)
            assertEquals(setOf(branch1, branch2), forks.map { it.id }.toSet())
            assertEquals(branch1, forks.first { it.id == branch2 }.forkedFromSessionId)
        }
    }

    @Nested
    inner class `delete cascades to fork descendants` {

        @Test
        fun `deleting the root removes all nested forks and their messages`() = runTest {
            val root = "root-${UUID.randomUUID()}"
            createSession(root)
            val m1 = userMessage("m1-${UUID.randomUUID()}", "msg-1")
            store.upsertMessages(ctx(root), root, listOf(m1))

            val branch1 = store.createFork(root, m1.id)!!
            val b1Messages = store.loadMessagesWithTimestamps(branch1)
            val branch2 = store.createFork(branch1, b1Messages.last().message.id)!!

            store.delete(root)

            assertNull(store.findForkInfo(root))
            assertNull(store.findForkInfo(branch1))
            assertNull(store.findForkInfo(branch2))
            assertTrue(store.loadMessagesWithTimestamps(branch2).isEmpty())
        }

        @Test
        fun `deleting a middle branch removes only its own descendants`() = runTest {
            val root = "root-${UUID.randomUUID()}"
            createSession(root)
            val m1 = userMessage("m1-${UUID.randomUUID()}", "msg-1")
            store.upsertMessages(ctx(root), root, listOf(m1))

            val branchA = store.createFork(root, m1.id)!!
            val branchB = store.createFork(root, m1.id)!!
            val aMessages = store.loadMessagesWithTimestamps(branchA)
            val subOfA = store.createFork(branchA, aMessages.last().message.id)!!

            store.delete(branchA)

            assertNotNull(store.findForkInfo(root))
            assertNotNull(store.findForkInfo(branchB), "sibling branch survives")
            assertNull(store.findForkInfo(subOfA), "descendant of the deleted branch is gone")
        }
    }
}
