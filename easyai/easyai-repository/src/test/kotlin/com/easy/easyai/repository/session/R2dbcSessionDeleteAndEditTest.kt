package com.easy.easyai.repository.session

import com.easy.easyai.core.team.TeamMemberExecutionEntity
import com.easy.easyai.core.team.TeamMemberStatus
import com.easy.easyai.core.team.TeamRoundRecord
import com.easy.easyai.repository.database.DatabaseMigration
import com.easy.easyai.repository.database.Tables
import com.easy.easyai.repository.team.R2dbcTeamExecutionStore
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.jupiter.api.*
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for session deletion cascade behavior and edit-history (deleteMessagesFrom).
 * Verifies that deleting a session cascades to messages, todos, and team records,
 * and that editing history correctly truncates messages and team records from a timestamp.
 */
class R2dbcSessionDeleteAndEditTest {

    companion object {
        private lateinit var db: R2dbcDatabase

        @BeforeAll
        @JvmStatic
        fun setupDb() = runTest {
            db = R2dbcDatabase.connect(
                url = "r2dbc:h2:mem:///session_delete_edit_test_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1",
                manager = { TransactionManager(it) }
            )
            DatabaseMigration.defaultTables().execute(db)
        }
    }

    private val sessionStore = R2dbcAsyncSessionStore(db)
    private val teamStore = R2dbcTeamExecutionStore(db)

    private suspend fun createSession(sessionId: String, userId: String = "system") {
        suspendTransaction(db) {
            Tables.Session.insert {
                it[Tables.Session.id] = sessionId
                it[Tables.Session.status] = "active"
                it[Tables.Session.userId] = userId
                it[Tables.Session.createdAt] = System.currentTimeMillis()
                it[Tables.Session.updatedAt] = System.currentTimeMillis()
            }
        }
    }

    /**
     * Insert a message with an explicit createdAt timestamp to avoid
     * timing issues in runTest (virtual time does not advance System.currentTimeMillis).
     */
    private suspend fun insertMessage(sessionId: String, messageId: String, text: String = "hello", createdAt: Long = System.currentTimeMillis()) {
        suspendTransaction(db) {
            Tables.Message.insert {
                it[Tables.Message.id] = messageId
                it[Tables.Message.sessionId] = sessionId
                it[Tables.Message.agentId] = "test-agent"
                it[Tables.Message.role] = "USER"
                it[Tables.Message.contentBlocks] = """[{"type":"text","text":"$text"}]"""
                it[Tables.Message.createdAt] = createdAt
            }
        }
    }

    private suspend fun insertTodo(sessionId: String, todoId: String) {
        suspendTransaction(db) {
            Tables.TodoTable.insert {
                it[Tables.TodoTable.id] = todoId
                it[Tables.TodoTable.sessionId] = sessionId
                it[Tables.TodoTable.content] = "test todo"
                it[Tables.TodoTable.status] = "PENDING"
                it[Tables.TodoTable.priority] = "medium"
                it[Tables.TodoTable.position] = 0
                it[Tables.TodoTable.createdAt] = System.currentTimeMillis()
            }
        }
    }

    private suspend fun countMessages(sessionId: String): Long {
        return suspendTransaction(db) {
            Tables.Message.selectAll()
                .where { Tables.Message.sessionId eq sessionId }
                .count()
        }
    }

    private suspend fun countTodos(sessionId: String): Long {
        return suspendTransaction(db) {
            Tables.TodoTable.selectAll()
                .where { Tables.TodoTable.sessionId eq sessionId }
                .count()
        }
    }

    private suspend fun countTeamExecutions(teamSessionId: String): Long {
        return suspendTransaction(db) {
            Tables.TeamMemberExecutionTable.selectAll()
                .where { Tables.TeamMemberExecutionTable.teamSessionId eq teamSessionId }
                .count()
        }
    }

    private suspend fun countTeamRounds(teamSessionId: String): Long {
        return suspendTransaction(db) {
            Tables.TeamRoundRecordTable.selectAll()
                .where { Tables.TeamRoundRecordTable.teamSessionId eq teamSessionId }
                .count()
        }
    }

    private suspend fun sessionExists(sessionId: String): Boolean {
        return suspendTransaction(db) {
            Tables.Session.selectAll()
                .where { Tables.Session.id eq sessionId }
                .count() > 0
        }
    }

    @Nested
    inner class `session delete cascade` {

        @Test
        fun `delete session cascades messages`() = runTest {
            val sessionId = "del-msg-${UUID.randomUUID()}"
            createSession(sessionId)
            insertMessage(sessionId, "msg-1-${UUID.randomUUID()}")
            insertMessage(sessionId, "msg-2-${UUID.randomUUID()}")
            insertMessage(sessionId, "msg-3-${UUID.randomUUID()}")

            assertEquals(3, countMessages(sessionId))

            sessionStore.delete(sessionId)

            assertEquals(0, countMessages(sessionId), "Messages should be cascade deleted")
            assertTrue(!sessionExists(sessionId), "Session should be deleted")
        }

        @Test
        fun `delete session cascades todos`() = runTest {
            val sessionId = "del-todo-${UUID.randomUUID()}"
            createSession(sessionId)
            insertTodo(sessionId, "todo-1-${UUID.randomUUID()}")
            insertTodo(sessionId, "todo-2-${UUID.randomUUID()}")

            assertEquals(2, countTodos(sessionId))

            sessionStore.delete(sessionId)

            assertEquals(0, countTodos(sessionId), "Todos should be cascade deleted")
        }

        @Test
        fun `delete session with wrong userId does not delete`() = runTest {
            val sessionId = "del-owner-${UUID.randomUUID()}"
            createSession(sessionId, userId = "user-alice")
            insertMessage(sessionId, "msg-owner-${UUID.randomUUID()}")

            // Try to delete with wrong user
            sessionStore.delete(sessionId, userId = "user-bob")

            assertTrue(sessionExists(sessionId), "Session should NOT be deleted by wrong user")
            assertEquals(1, countMessages(sessionId), "Messages should remain")
        }

        @Test
        fun `delete team session cascades team executions and rounds`() = runTest {
            val teamSessionId = "team-del-${UUID.randomUUID()}"
            val memberSessionId = "member-del-${UUID.randomUUID()}"
            createSession(teamSessionId)
            createSession(memberSessionId)

            // Insert team execution records
            teamStore.saveExecution(
                TeamMemberExecutionEntity(
                    id = "exec-1-${UUID.randomUUID()}",
                    teamSessionId = teamSessionId,
                    memberId = "member-a",
                    round = 1,
                    assignment = "task A",
                    status = TeamMemberStatus.COMPLETED,
                    memberSessionId = memberSessionId,
                    startedAt = System.currentTimeMillis(),
                    completedAt = System.currentTimeMillis()
                )
            )
            teamStore.saveExecution(
                TeamMemberExecutionEntity(
                    id = "exec-2-${UUID.randomUUID()}",
                    teamSessionId = teamSessionId,
                    memberId = "member-b",
                    round = 1,
                    assignment = "task B",
                    status = TeamMemberStatus.RUNNING,
                    startedAt = System.currentTimeMillis()
                )
            )
            // Insert team round records
            teamStore.saveRound(
                TeamRoundRecord(
                    id = "round-1-${UUID.randomUUID()}",
                    teamSessionId = teamSessionId,
                    round = 1,
                    delegatedMembers = listOf("member-a", "member-b"),
                    completedMembers = listOf("member-a"),
                    createdAt = System.currentTimeMillis()
                )
            )

            assertEquals(2, countTeamExecutions(teamSessionId))
            assertEquals(1, countTeamRounds(teamSessionId))

            // Simulate service-level cascade: delete team records + member sub-sessions + main session
            val memberSessionIds = teamStore.getExecutions(teamSessionId)
                .mapNotNull { it.memberSessionId }
                .distinct()
            teamStore.deleteByTeamSession(teamSessionId)
            memberSessionIds.forEach { sid -> sessionStore.delete(sid) }
            sessionStore.delete(teamSessionId)

            assertEquals(0, countTeamExecutions(teamSessionId), "Team executions should be deleted")
            assertEquals(0, countTeamRounds(teamSessionId), "Team rounds should be deleted")
            assertTrue(!sessionExists(memberSessionId), "Member sub-session should be deleted")
            assertTrue(!sessionExists(teamSessionId), "Team session should be deleted")
        }
    }

    @Nested
    inner class `edit history - deleteMessagesFrom` {

        @Test
        fun `deleteMessagesFrom removes target and subsequent messages`() = runTest {
            val sessionId = "edit-msgs-${UUID.randomUUID()}"
            createSession(sessionId)
            val baseTime = System.currentTimeMillis()
            val msgId1 = "edit-m1-${UUID.randomUUID()}"
            val msgId2 = "edit-m2-${UUID.randomUUID()}"
            val msgId3 = "edit-m3-${UUID.randomUUID()}"
            insertMessage(sessionId, msgId1, "first", createdAt = baseTime)
            insertMessage(sessionId, msgId2, "second", createdAt = baseTime + 1000)
            insertMessage(sessionId, msgId3, "third", createdAt = baseTime + 2000)

            assertEquals(3, countMessages(sessionId))

            // Delete from msgId2 onwards (inclusive)
            val deleted = sessionStore.deleteMessagesFrom(sessionId, msgId2)

            assertEquals(2, deleted, "Should delete msg2 and msg3")
            assertEquals(1, countMessages(sessionId), "Only msg1 should remain")
        }

        @Test
        fun `deleteMessagesFrom preserves earlier messages`() = runTest {
            val sessionId = "edit-preserve-${UUID.randomUUID()}"
            createSession(sessionId)
            val baseTime = System.currentTimeMillis()
            val msgId1 = "preserve-m1-${UUID.randomUUID()}"
            val msgId2 = "preserve-m2-${UUID.randomUUID()}"
            val msgId3 = "preserve-m3-${UUID.randomUUID()}"
            val msgId4 = "preserve-m4-${UUID.randomUUID()}"
            insertMessage(sessionId, msgId1, "first", createdAt = baseTime)
            insertMessage(sessionId, msgId2, "second", createdAt = baseTime + 1000)
            insertMessage(sessionId, msgId3, "third", createdAt = baseTime + 2000)
            insertMessage(sessionId, msgId4, "fourth", createdAt = baseTime + 3000)

            // Delete from msgId3 onwards
            val deleted = sessionStore.deleteMessagesFrom(sessionId, msgId3)

            assertEquals(2, deleted)
            val remaining = sessionStore.loadMessagesWithTimestamps(sessionId)
            assertEquals(2, remaining.size, "msg1 and msg2 should remain")
            assertEquals(msgId1, remaining[0].message.id)
            assertEquals(msgId2, remaining[1].message.id)
        }

        @Test
        fun `deleteMessagesFrom returns 0 for non-existent message`() = runTest {
            val sessionId = "edit-noexist-${UUID.randomUUID()}"
            createSession(sessionId)
            insertMessage(sessionId, "some-msg-${UUID.randomUUID()}", createdAt = System.currentTimeMillis())

            val deleted = sessionStore.deleteMessagesFrom(sessionId, "non-existent-id")

            assertEquals(0, deleted, "Should return 0 for non-existent message")
            assertEquals(1, countMessages(sessionId), "No messages should be deleted")
        }

        @Test
        fun `deleteMessagesFrom on first message deletes all`() = runTest {
            val sessionId = "edit-all-${UUID.randomUUID()}"
            createSession(sessionId)
            val baseTime = System.currentTimeMillis()
            val msgId1 = "all-m1-${UUID.randomUUID()}"
            insertMessage(sessionId, msgId1, "first", createdAt = baseTime)
            insertMessage(sessionId, "all-m2-${UUID.randomUUID()}", "second", createdAt = baseTime + 1000)
            insertMessage(sessionId, "all-m3-${UUID.randomUUID()}", "third", createdAt = baseTime + 2000)

            val deleted = sessionStore.deleteMessagesFrom(sessionId, msgId1)

            assertEquals(3, deleted, "All messages should be deleted")
            assertEquals(0, countMessages(sessionId))
        }
    }

    @Nested
    inner class `edit history - team records cleanup from timestamp` {

        @Test
        fun `deleteByTeamSessionFrom removes only records at or after timestamp`() = runTest {
            val teamSessionId = "team-edit-${UUID.randomUUID()}"
            createSession(teamSessionId)

            // Use explicit distinct timestamps (runTest virtual time doesn't advance System.currentTimeMillis)
            val baseTime = System.currentTimeMillis()
            val earlyTime = baseTime - 10_000  // 10 seconds before
            val lateTime = baseTime + 10_000    // 10 seconds after

            // Early execution (before the edit point)
            teamStore.saveExecution(
                TeamMemberExecutionEntity(
                    id = "early-exec-${UUID.randomUUID()}",
                    teamSessionId = teamSessionId,
                    memberId = "member-early",
                    round = 1,
                    assignment = "early task",
                    status = TeamMemberStatus.COMPLETED,
                    startedAt = earlyTime,
                    completedAt = earlyTime
                )
            )
            // Late execution (after the edit point)
            teamStore.saveExecution(
                TeamMemberExecutionEntity(
                    id = "late-exec-${UUID.randomUUID()}",
                    teamSessionId = teamSessionId,
                    memberId = "member-late",
                    round = 2,
                    assignment = "late task",
                    status = TeamMemberStatus.COMPLETED,
                    startedAt = lateTime + 10,
                    completedAt = lateTime + 20
                )
            )
            // Early round
            teamStore.saveRound(
                TeamRoundRecord(
                    id = "early-round-${UUID.randomUUID()}",
                    teamSessionId = teamSessionId,
                    round = 1,
                    delegatedMembers = listOf("member-early"),
                    completedMembers = listOf("member-early"),
                    createdAt = earlyTime
                )
            )
            // Late round
            teamStore.saveRound(
                TeamRoundRecord(
                    id = "late-round-${UUID.randomUUID()}",
                    teamSessionId = teamSessionId,
                    round = 2,
                    delegatedMembers = listOf("member-late"),
                    completedMembers = listOf("member-late"),
                    createdAt = lateTime + 10
                )
            )

            assertEquals(2, countTeamExecutions(teamSessionId))
            assertEquals(2, countTeamRounds(teamSessionId))

            // Delete from lateTime onwards (simulates editing a message at lateTime)
            teamStore.deleteByTeamSessionFrom(teamSessionId, lateTime)

            // Only early records should remain
            assertEquals(1, countTeamExecutions(teamSessionId), "Only early execution should remain")
            assertEquals(1, countTeamRounds(teamSessionId), "Only early round should remain")

            val remainingExecs = teamStore.getExecutions(teamSessionId)
            assertEquals("member-early", remainingExecs[0].memberId)

            val remainingRounds = teamStore.getRounds(teamSessionId)
            assertEquals(1, remainingRounds[0].round)
        }

        @Test
        fun `deleteByTeamSessionFrom with future timestamp deletes nothing`() = runTest {
            val teamSessionId = "team-future-${UUID.randomUUID()}"
            createSession(teamSessionId)

            teamStore.saveExecution(
                TeamMemberExecutionEntity(
                    id = "exec-future-${UUID.randomUUID()}",
                    teamSessionId = teamSessionId,
                    memberId = "member-x",
                    round = 1,
                    assignment = "task",
                    status = TeamMemberStatus.COMPLETED,
                    startedAt = System.currentTimeMillis()
                )
            )

            // Use a far-future timestamp
            teamStore.deleteByTeamSessionFrom(teamSessionId, System.currentTimeMillis() + 1_000_000)

            assertEquals(1, countTeamExecutions(teamSessionId), "No records should be deleted")
        }

        @Test
        fun `full edit flow - delete messages and team records from timestamp`() = runTest {
            val sessionId = "edit-flow-${UUID.randomUUID()}"
            val memberSessionId = "edit-flow-member-${UUID.randomUUID()}"
            createSession(sessionId)
            createSession(memberSessionId)

            // Insert messages with distinct timestamps
            val baseTime = System.currentTimeMillis()
            val msgId1 = "flow-m1-${UUID.randomUUID()}"
            val msgId2 = "flow-m2-${UUID.randomUUID()}"
            val msgId3 = "flow-m3-${UUID.randomUUID()}"
            insertMessage(sessionId, msgId1, "first", createdAt = baseTime)
            insertMessage(sessionId, msgId2, "second", createdAt = baseTime + 1000)
            insertMessage(sessionId, msgId3, "third", createdAt = baseTime + 2000)

            // Get the timestamp of msg2 (the edit point)
            val editTimestamp = sessionStore.getMessageCreatedAt(sessionId, msgId2)!!

            // Insert team records: one before edit point, one after
            teamStore.saveExecution(
                TeamMemberExecutionEntity(
                    id = "flow-exec-early-${UUID.randomUUID()}",
                    teamSessionId = sessionId,
                    memberId = "member-before",
                    round = 1,
                    assignment = "before edit",
                    status = TeamMemberStatus.COMPLETED,
                    startedAt = editTimestamp - 100,
                    completedAt = editTimestamp - 50
                )
            )
            teamStore.saveExecution(
                TeamMemberExecutionEntity(
                    id = "flow-exec-late-${UUID.randomUUID()}",
                    teamSessionId = sessionId,
                    memberId = "member-after",
                    round = 2,
                    assignment = "after edit",
                    status = TeamMemberStatus.COMPLETED,
                    memberSessionId = memberSessionId,
                    startedAt = editTimestamp + 100,
                    completedAt = editTimestamp + 200
                )
            )
            teamStore.saveRound(
                TeamRoundRecord(
                    id = "flow-round-late-${UUID.randomUUID()}",
                    teamSessionId = sessionId,
                    round = 2,
                    delegatedMembers = listOf("member-after"),
                    completedMembers = listOf("member-after"),
                    createdAt = editTimestamp + 100
                )
            )

            // Simulate edit-message flow:
            // 1. Delete messages from msgId2 onwards
            val deletedMsgs = sessionStore.deleteMessagesFrom(sessionId, msgId2)
            assertEquals(2, deletedMsgs, "msg2 and msg3 should be deleted")

            // 2. Delete team records from editTimestamp
            val memberSessionIds = teamStore.getExecutions(sessionId)
                .filter { it.startedAt != null && it.startedAt!! >= editTimestamp }
                .mapNotNull { it.memberSessionId }
                .distinct()
            teamStore.deleteByTeamSessionFrom(sessionId, editTimestamp)
            memberSessionIds.forEach { sid -> sessionStore.delete(sid) }

            // Verify: only msg1 remains
            assertEquals(1, countMessages(sessionId))
            // Verify: only early team execution remains
            assertEquals(1, countTeamExecutions(sessionId))
            val remainingExecs = teamStore.getExecutions(sessionId)
            assertEquals("member-before", remainingExecs[0].memberId)
            // Verify: late round is deleted
            assertEquals(0, countTeamRounds(sessionId))
            // Verify: member sub-session is deleted
            assertTrue(!sessionExists(memberSessionId), "Member sub-session should be cascade deleted")
        }
    }
}
