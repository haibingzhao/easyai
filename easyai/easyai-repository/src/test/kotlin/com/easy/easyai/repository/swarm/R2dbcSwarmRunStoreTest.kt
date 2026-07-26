package com.easy.easyai.repository.swarm

import com.easy.easyai.core.agent.PersistedSession
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.UserMessage
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.team.TeamMemberExecution
import com.easy.easyai.core.team.TeamMemberStatus
import com.easy.easyai.repository.database.DatabaseMigration
import com.easy.easyai.repository.database.Tables
import com.easy.easyai.repository.session.R2dbcAsyncSessionStore
import com.easy.easyai.swarm.model.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.jupiter.api.*
import java.time.Instant
import java.util.UUID
import kotlin.test.*

/**
 * Integration tests for [R2dbcSwarmRunStore].
 * Verifies CRUD operations on swarm runs and tasks,
 * and cascade deletion of all associated records (tasks, sessions, messages,
 * deliberation history, team executions, round records).
 */
class R2dbcSwarmRunStoreTest {

    companion object {
        private lateinit var db: R2dbcDatabase

        @BeforeAll
        @JvmStatic
        fun setupDb() = runTest {
            db = R2dbcDatabase.connect(
                url = "r2dbc:h2:mem:///swarm_run_test_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1",
                manager = { TransactionManager(it) }
            )
            DatabaseMigration.defaultTables().execute(db)
        }
    }

    private val swarmStore = R2dbcSwarmRunStore(db)
    private val sessionStore = R2dbcAsyncSessionStore(db)

    private val testAgentContext = AgentContext(
        agentId = "swarm-test-agent",
        sessionId = "swarm-test-session",
        customInstructions = "test"
    )

    private fun createRun(
        id: String = "run-${UUID.randomUUID()}",
        presetName: String = "test-preset",
        title: String = "Test Run",
        status: SwarmRunStatus = SwarmRunStatus.PENDING,
        tasks: List<SwarmTask> = emptyList()
    ) = SwarmRun(
        id = id,
        presetName = presetName,
        title = title,
        status = status,
        agents = listOf(
            SwarmAgentSpec(id = "agent-1", role = "analyst", agentDefinitionId = "def-1")
        ),
        tasks = tasks,
        userVars = mutableMapOf("var1" to "value1"),
        createdAt = Instant.now()
    )

    private fun createTask(
        id: String = "task-${UUID.randomUUID()}",
        agentId: String = "agent-1",
        type: TaskType = TaskType.SINGLE,
        status: SwarmTaskStatus = SwarmTaskStatus.COMPLETED
    ) = SwarmTask(
        id = id,
        agentId = agentId,
        type = type,
        status = status,
        summary = "Task completed successfully",
        inputTokens = 100,
        outputTokens = 50,
        startedAt = Instant.now(),
        completedAt = Instant.now()
    )

    private suspend fun createSessionWithMessages(sessionId: String, swarmRunId: String? = null) {
        sessionStore.save(
            PersistedSession(
                id = sessionId,
                messages = emptyList(),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
                swarmRunId = swarmRunId
            )
        )
        sessionStore.upsertMessages(
            context = testAgentContext,
            sessionId = sessionId,
            messages = listOf(
                UserMessage(id = "msg-${UUID.randomUUID()}", content = listOf(TextContent("hello from swarm")))
            ),
            parentMessageId = null,
            parentToolCallId = null
        )
    }

    private suspend fun countSessions(swarmRunId: String): Long {
        return suspendTransaction(db) {
            Tables.Session.selectAll()
                .where { Tables.Session.swarmRunId eq swarmRunId }
                .count()
        }
    }

    private suspend fun countMessagesForRun(swarmRunId: String): Long {
        return suspendTransaction(db) {
            val sessionIds = Tables.Session.selectAll()
                .where { Tables.Session.swarmRunId eq swarmRunId }
                .toList()
                .map { it[Tables.Session.id] }
            if (sessionIds.isEmpty()) return@suspendTransaction 0L
            Tables.Message.selectAll()
                .where { Tables.Message.sessionId inList sessionIds }
                .count()
        }
    }
    private suspend fun countTasks(runId: String): Long {
        return suspendTransaction(db) {
            Tables.SwarmTaskTable.selectAll()
                .where { Tables.SwarmTaskTable.runId eq runId }
                .count()
        }
    }

    private suspend fun countDeliberationHistory(runId: String): Long {
        return suspendTransaction(db) {
            Tables.SwarmDeliberationHistoryTable.selectAll()
                .where { Tables.SwarmDeliberationHistoryTable.runId eq runId }
                .count()
        }
    }

    private suspend fun countTeamMemberExecutions(runId: String): Long {
        return suspendTransaction(db) {
            Tables.SwarmTeamMemberExecutionTable.selectAll()
                .where { Tables.SwarmTeamMemberExecutionTable.runId eq runId }
                .count()
        }
    }

    private suspend fun countTeamRoundRecords(runId: String): Long {
        return suspendTransaction(db) {
            Tables.SwarmTeamRoundRecordTable.selectAll()
                .where { Tables.SwarmTeamRoundRecordTable.runId eq runId }
                .count()
        }
    }

    @Nested
    inner class `saveRun and getRun` {

        @Test
        fun `saves and retrieves a run`() = runTest {
            val runId = "run-save-${UUID.randomUUID()}"
            val run = createRun(id = runId, title = "My Run")

            swarmStore.saveRun(run, userId = "system")
            val loaded = swarmStore.getRun(runId, userId = "system")

            assertNotNull(loaded)
            assertEquals(runId, loaded.id)
            assertEquals("My Run", loaded.title)
            assertEquals("test-preset", loaded.presetName)
            assertEquals(SwarmRunStatus.PENDING, loaded.status)
            assertEquals(1, loaded.agents.size)
            assertEquals("agent-1", loaded.agents[0].id)
        }

        @Test
        fun `getRun returns null for non-existent run`() = runTest {
            val result = swarmStore.getRun("non-existent-${UUID.randomUUID()}", userId = "system")
            assertNull(result)
        }

        @Test
        fun `getRun with wrong userId returns null`() = runTest {
            val runId = "run-isolation-${UUID.randomUUID()}"
            val run = createRun(id = runId)

            swarmStore.saveRun(run, userId = "user-alice")

            val result = swarmStore.getRun(runId, userId = "user-bob")
            assertNull(result, "Run should not be visible to a different user")
        }
    }

    @Nested
    inner class `updateRun` {

        @Test
        fun `updates run status and tokens`() = runTest {
            val runId = "run-update-${UUID.randomUUID()}"
            val run = createRun(id = runId, status = SwarmRunStatus.PENDING)
            swarmStore.saveRun(run, userId = "system")

            // Update the run
            run.status = SwarmRunStatus.COMPLETED
            run.totalInputTokens = 500
            run.totalOutputTokens = 200
            run.completedAt = Instant.now()
            swarmStore.updateRun(run, userId = "system")

            val loaded = swarmStore.getRun(runId, userId = "system")
            assertNotNull(loaded)
            assertEquals(SwarmRunStatus.COMPLETED, loaded.status)
            assertEquals(500, loaded.totalInputTokens)
            assertEquals(200, loaded.totalOutputTokens)
            assertNotNull(loaded.completedAt)
        }

        @Test
        fun `updateRun with wrong userId does not modify`() = runTest {
            val runId = "run-update-wrong-${UUID.randomUUID()}"
            val run = createRun(id = runId, status = SwarmRunStatus.PENDING)
            swarmStore.saveRun(run, userId = "user-alice")

            run.status = SwarmRunStatus.FAILED
            swarmStore.updateRun(run, userId = "user-bob")

            val loaded = swarmStore.getRun(runId, userId = "user-alice")
            assertNotNull(loaded)
            assertEquals(SwarmRunStatus.PENDING, loaded.status, "Status should not be changed by wrong user")
        }
    }

    @Nested
    inner class `listRuns` {

        @Test
        fun `lists runs ordered by createdAt desc`() = runTest {
            val userId = "list-user-${UUID.randomUUID()}"
            val baseTime = Instant.now()
            val run1 = createRun(id = "list-run1-${UUID.randomUUID()}").copy(createdAt = baseTime.minusSeconds(10))
            val run2 = createRun(id = "list-run2-${UUID.randomUUID()}").copy(createdAt = baseTime)

            swarmStore.saveRun(run1, userId = userId)
            swarmStore.saveRun(run2, userId = userId)

            val runs = swarmStore.listRuns(limit = 10, offset = 0, userId = userId)

            assertTrue(runs.size >= 2)
            // Most recent first
            assertEquals(run2.id, runs[0].id)
            assertEquals(run1.id, runs[1].id)
        }

        @Test
        fun `listRuns respects userId isolation`() = runTest {
            val runId = "list-isolated-${UUID.randomUUID()}"
            val run = createRun(id = runId)
            swarmStore.saveRun(run, userId = "user-alice")

            val aliceRuns = swarmStore.listRuns(limit = 100, offset = 0, userId = "user-alice")
            val bobRuns = swarmStore.listRuns(limit = 100, offset = 0, userId = "user-bob")

            assertTrue(aliceRuns.any { it.id == runId })
            assertFalse(bobRuns.any { it.id == runId })
        }
    }

    @Nested
    inner class `saveTasks and getTasks` {

        @Test
        fun `saves and retrieves tasks for a run`() = runTest {
            val runId = "run-tasks-${UUID.randomUUID()}"
            val run = createRun(id = runId)
            swarmStore.saveRun(run, userId = "system")

            val task1 = createTask(id = "task-1-${UUID.randomUUID()}", status = SwarmTaskStatus.COMPLETED)
            val task2 = createTask(id = "task-2-${UUID.randomUUID()}", status = SwarmTaskStatus.FAILED)
            swarmStore.saveTasks(runId, listOf(task1, task2))

            val tasks = swarmStore.getTasks(runId)
            assertEquals(2, tasks.size)
            assertTrue(tasks.any { it.id == task1.id && it.status == SwarmTaskStatus.COMPLETED })
            assertTrue(tasks.any { it.id == task2.id && it.status == SwarmTaskStatus.FAILED })
        }

        @Test
        fun `saveTasks replaces existing tasks`() = runTest {
            val runId = "run-replace-${UUID.randomUUID()}"
            val run = createRun(id = runId)
            swarmStore.saveRun(run, userId = "system")

            val task1 = createTask(id = "replace-t1-${UUID.randomUUID()}")
            swarmStore.saveTasks(runId, listOf(task1))
            assertEquals(1, swarmStore.getTasks(runId).size)

            // Replace with new tasks
            val task2 = createTask(id = "replace-t2-${UUID.randomUUID()}")
            val task3 = createTask(id = "replace-t3-${UUID.randomUUID()}")
            swarmStore.saveTasks(runId, listOf(task2, task3))

            val tasks = swarmStore.getTasks(runId)
            assertEquals(2, tasks.size)
            assertFalse(tasks.any { it.id == task1.id }, "Old task should be replaced")
        }

        @Test
        fun `saveTask upserts a single task`() = runTest {
            val runId = "run-upsert-${UUID.randomUUID()}"
            val run = createRun(id = runId)
            swarmStore.saveRun(run, userId = "system")

            val taskId = "upsert-task-${UUID.randomUUID()}"
            val task = createTask(id = taskId, status = SwarmTaskStatus.IN_PROGRESS)
            swarmStore.saveTask(runId, task)

            var tasks = swarmStore.getTasks(runId)
            assertEquals(1, tasks.size)
            assertEquals(SwarmTaskStatus.IN_PROGRESS, tasks[0].status)

            // Upsert with updated status
            task.status = SwarmTaskStatus.COMPLETED
            task.summary = "Done"
            swarmStore.saveTask(runId, task)

            tasks = swarmStore.getTasks(runId)
            assertEquals(1, tasks.size, "Should still be 1 task (upsert)")
            assertEquals(SwarmTaskStatus.COMPLETED, tasks[0].status)
            assertEquals("Done", tasks[0].summary)
        }
    }

    @Nested
    inner class `saveTeamHistory and getTeamHistory` {

        @Test
        fun `saves and retrieves team history`() = runTest {
            val runId = "run-team-${UUID.randomUUID()}"
            val taskId = "team-task-${UUID.randomUUID()}"
            val run = createRun(id = runId)
            swarmStore.saveRun(run, userId = "system")

            val executions = listOf(
                TeamMemberExecution(
                    memberId = "member-a",
                    round = 1,
                    assignment = "analyze data",
                    status = TeamMemberStatus.COMPLETED,
                    summary = "Analysis done",
                    inputTokens = 100,
                    outputTokens = 50
                ),
                TeamMemberExecution(
                    memberId = "member-b",
                    round = 1,
                    assignment = "write report",
                    status = TeamMemberStatus.COMPLETED,
                    summary = "Report written",
                    inputTokens = 80,
                    outputTokens = 40
                )
            )
            val rounds = listOf(
                TeamRoundRecord(
                    round = 1,
                    leaderAnalysis = "Delegate tasks",
                    delegatedMembers = listOf("member-a", "member-b"),
                    completedMembers = emptyList(),
                    escalations = emptyList()
                )
            )

            swarmStore.saveTeamHistory(runId, taskId, executions, rounds)

            val (loadedExecs, loadedRounds) = swarmStore.getTeamHistory(runId, taskId)
            assertEquals(2, loadedExecs.size)
            assertEquals("member-a", loadedExecs[0].memberId)
            assertEquals("member-b", loadedExecs[1].memberId)
            assertEquals(1, loadedRounds.size)
            assertEquals(1, loadedRounds[0].round)
            assertEquals(listOf("member-a", "member-b"), loadedRounds[0].delegatedMembers)
        }
    }

    @Nested
    inner class `deleteRun cascade` {

        @Test
        fun `deleteRun cascades tasks and sessions with messages`() = runTest {
            val runId = "run-del-cascade-${UUID.randomUUID()}"
            val run = createRun(id = runId)
            swarmStore.saveRun(run, userId = "system")

            // Save tasks
            val task1 = createTask(id = "del-task1-${UUID.randomUUID()}")
            val task2 = createTask(id = "del-task2-${UUID.randomUUID()}")
            swarmStore.saveTasks(runId, listOf(task1, task2))

            // Create linked sessions with messages
            val sessionId1 = "del-session1-${UUID.randomUUID()}"
            val sessionId2 = "del-session2-${UUID.randomUUID()}"
            createSessionWithMessages(sessionId1, swarmRunId = runId)
            createSessionWithMessages(sessionId2, swarmRunId = runId)

            // Verify pre-conditions
            assertEquals(2, countTasks(runId))
            assertEquals(2, countSessions(runId))
            assertTrue(countMessagesForRun(runId) > 0)

            // Delete the run
            swarmStore.deleteRun(runId, userId = "system")

            // Verify cascade
            assertNull(swarmStore.getRun(runId, userId = "system"), "Run should be deleted")
            assertEquals(0, countTasks(runId), "Tasks should be cascade deleted")
            assertEquals(0, countSessions(runId), "Linked sessions should be cascade deleted")
        }

        @Test
        fun `deleteRun cascades team member executions and round records`() = runTest {
            val runId = "run-del-team-${UUID.randomUUID()}"
            val taskId = "del-team-task-${UUID.randomUUID()}"
            val run = createRun(id = runId)
            swarmStore.saveRun(run, userId = "system")

            // Save team history
            val executions = listOf(
                TeamMemberExecution(
                    memberId = "member-x",
                    round = 1,
                    assignment = "task X",
                    status = TeamMemberStatus.COMPLETED,
                    inputTokens = 50,
                    outputTokens = 25
                )
            )
            val rounds = listOf(
                TeamRoundRecord(
                    round = 1,
                    leaderAnalysis = "analysis",
                    delegatedMembers = listOf("member-x"),
                    completedMembers = listOf("member-x"),
                    escalations = emptyList()
                )
            )
            swarmStore.saveTeamHistory(runId, taskId, executions, rounds)

            assertEquals(1, countTeamMemberExecutions(runId))
            assertEquals(1, countTeamRoundRecords(runId))

            // Delete the run
            swarmStore.deleteRun(runId, userId = "system")

            assertEquals(0, countTeamMemberExecutions(runId), "Team executions should be cascade deleted")
            assertEquals(0, countTeamRoundRecords(runId), "Team round records should be cascade deleted")
        }

        @Test
        fun `deleteRun cascades deliberation history`() = runTest {
            val runId = "run-del-delib-${UUID.randomUUID()}"
            val taskId = "del-delib-task-${UUID.randomUUID()}"
            val run = createRun(id = runId)
            swarmStore.saveRun(run, userId = "system")

            // Save deliberation history
            val entries = listOf(
                DeliberationEntry(
                    agentId = "agent-a",
                    round = 1,
                    response = "My analysis...",
                    inputTokens = 100,
                    outputTokens = 50
                ),
                DeliberationEntry(
                    agentId = "agent-b",
                    round = 1,
                    response = "I disagree...",
                    inputTokens = 90,
                    outputTokens = 45
                )
            )
            swarmStore.saveDeliberationHistory(runId, taskId, entries)

            assertEquals(2, countDeliberationHistory(runId))

            // Delete the run
            swarmStore.deleteRun(runId, userId = "system")

            assertEquals(0, countDeliberationHistory(runId), "Deliberation history should be cascade deleted")
        }

        @Test
        fun `deleteRun with wrong userId does not delete`() = runTest {
            val runId = "run-del-wrong-${UUID.randomUUID()}"
            val run = createRun(id = runId)
            swarmStore.saveRun(run, userId = "user-alice")

            val task = createTask(id = "wrong-del-task-${UUID.randomUUID()}")
            swarmStore.saveTasks(runId, listOf(task))

            // Try to delete with wrong user
            swarmStore.deleteRun(runId, userId = "user-bob")

            // Run and tasks should still exist
            assertNotNull(swarmStore.getRun(runId, userId = "user-alice"), "Run should NOT be deleted by wrong user")
            assertEquals(1, countTasks(runId), "Tasks should remain")
        }

        @Test
        fun `deleteRun does not affect other runs`() = runTest {
            val runId1 = "run-del-keep1-${UUID.randomUUID()}"
            val runId2 = "run-del-keep2-${UUID.randomUUID()}"
            val run1 = createRun(id = runId1)
            val run2 = createRun(id = runId2)
            swarmStore.saveRun(run1, userId = "system")
            swarmStore.saveRun(run2, userId = "system")

            val task1 = createTask(id = "keep-task1-${UUID.randomUUID()}")
            val task2 = createTask(id = "keep-task2-${UUID.randomUUID()}")
            swarmStore.saveTasks(runId1, listOf(task1))
            swarmStore.saveTasks(runId2, listOf(task2))

            // Delete only run1
            swarmStore.deleteRun(runId1, userId = "system")

            assertNull(swarmStore.getRun(runId1, userId = "system"))
            assertNotNull(swarmStore.getRun(runId2, userId = "system"), "Other run should not be affected")
            assertEquals(0, countTasks(runId1))
            assertEquals(1, countTasks(runId2), "Other run's tasks should not be affected")
        }
    }

    @Nested
    inner class `listRunsByStatus` {

        @Test
        fun `lists runs filtered by status`() = runTest {
            val runId1 = "status-run1-${UUID.randomUUID()}"
            val runId2 = "status-run2-${UUID.randomUUID()}"
            val run1 = createRun(id = runId1, status = SwarmRunStatus.RUNNING)
            val run2 = createRun(id = runId2, status = SwarmRunStatus.COMPLETED)
            swarmStore.saveRun(run1, userId = "system")
            swarmStore.saveRun(run2, userId = "system")

            val runningRuns = swarmStore.listRunsByStatus(SwarmRunStatus.RUNNING)
            val completedRuns = swarmStore.listRunsByStatus(SwarmRunStatus.COMPLETED)

            assertTrue(runningRuns.any { it.id == runId1 })
            assertFalse(runningRuns.any { it.id == runId2 })
            assertTrue(completedRuns.any { it.id == runId2 })
            assertFalse(completedRuns.any { it.id == runId1 })
        }
    }

}
