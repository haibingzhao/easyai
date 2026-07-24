package com.easy.easyai.swarm.runtime

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.CompletionCheckInput
import com.easy.easyai.core.agent.CompletionCheckResult
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.swarm.model.DeliberationOrder
import com.easy.easyai.swarm.model.DeliberationSpec
import com.easy.easyai.swarm.model.EscalationEntry
import com.easy.easyai.swarm.model.MemberStatus
import com.easy.easyai.swarm.model.SwarmTask
import com.easy.easyai.swarm.model.SwarmTaskStatus
import com.easy.easyai.swarm.model.TaskType
import com.easy.easyai.swarm.model.TeamMemberExecution
import com.easy.easyai.swarm.model.TeamRoundRecord
import com.easy.easyai.swarm.model.TeamSpec
import com.easy.easyai.swarm.model.WorkerResult
import com.easy.easyai.swarm.tool.EscalationCompletionCheck
import com.easy.easyai.swarm.tool.EscalationResult
import com.easy.easyai.swarm.tool.EscalationTool
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwarmRuntimeTeamTest {

    private val mapper: ObjectMapper = jacksonObjectMapper()

    // ── A. LeaderDecisionParser ────────────────────────────────────────────

    @Nested
    inner class `LeaderDecisionParser tests` {

        @Test
        fun `parse valid JSON decision`() {
            val json = """
                {
                  "analysis": "All members have completed their tasks.",
                  "newTasks": [
                    {"memberId": "m1", "assignment": "Write summary"}
                  ],
                  "reassignments": [
                    {"fromMemberId": "m2", "toMemberId": "m3", "reason": "m2 is blocked"}
                  ],
                  "isComplete": true
                }
            """.trimIndent()

            val result = LeaderDecisionParser.parse(json)

            assertEquals("All members have completed their tasks.", result.analysis)
            assertEquals(1, result.newTasks.size)
            assertEquals("m1", result.newTasks[0].memberId)
            assertEquals("Write summary", result.newTasks[0].assignment)
            assertEquals(1, result.reassignments.size)
            assertEquals("m2", result.reassignments[0].fromMemberId)
            assertEquals("m3", result.reassignments[0].toMemberId)
            assertTrue(result.isComplete)
        }

        @Test
        fun `parse JSON wrapped in markdown code block`() {
            val wrapped = """
                Here is my decision:
                ```json
                {
                  "analysis": "Wrapping up.",
                  "newTasks": [],
                  "reassignments": [],
                  "isComplete": false
                }
                ```
                End of decision.
            """.trimIndent()

            val result = LeaderDecisionParser.parse(wrapped)

            assertEquals("Wrapping up.", result.analysis)
            assertTrue(result.newTasks.isEmpty())
            assertTrue(result.reassignments.isEmpty())
            assertFalse(result.isComplete)
        }

        @Test
        fun `parse non-JSON fallback to regex with COMPLETE keyword`() {
            val text = "The team has finished all work. COMPLETE. No further tasks needed."

            val result = LeaderDecisionParser.parse(text)

            assertTrue(result.isComplete)
            assertTrue(result.newTasks.isEmpty())
            assertTrue(result.reassignments.isEmpty())
            assertTrue(result.analysis.contains("COMPLETE"))
        }

        @Test
        fun `parse non-JSON fallback to regex with DONE keyword`() {
            val text = "Everything is DONE. No more work required."

            val result = LeaderDecisionParser.parse(text)

            assertTrue(result.isComplete)
        }

        @Test
        fun `parse non-JSON fallback without complete keyword`() {
            val text = "The team is still working on their assignments. Progress is being made."

            val result = LeaderDecisionParser.parse(text)

            assertFalse(result.isComplete)
            assertTrue(result.newTasks.isEmpty())
        }

        @Test
        fun `parse complete garbage returns safe default`() {
            val garbage = "}{][!!@@##\$\$^^&&**((()))))not json at all {{{"

            val result = LeaderDecisionParser.parse(garbage)

            assertFalse(result.isComplete)
            assertTrue(result.newTasks.isEmpty())
            assertTrue(result.reassignments.isEmpty())
            assertNotNull(result.analysis)
        }

        @Test
        fun `parse empty string returns safe default`() {
            val result = LeaderDecisionParser.parse("")

            assertFalse(result.isComplete)
            assertTrue(result.newTasks.isEmpty())
            assertTrue(result.reassignments.isEmpty())
        }

        @Test
        fun `parse JSON with suspendAndAssist field`() {
            val json = """
                {
                  "analysis": "Member m1 is blocked, sending helper.",
                  "newTasks": [],
                  "reassignments": [],
                  "suspendAndAssist": [
                    {"blockedMemberId": "m1", "helperMemberId": "m2", "assistTask": "Fix the API key issue"}
                  ],
                  "suspendAndConsultUser": [],
                  "isComplete": false
                }
            """.trimIndent()

            val result = LeaderDecisionParser.parse(json)

            assertEquals(1, result.suspendAndAssist.size)
            assertEquals("m1", result.suspendAndAssist[0].blockedMemberId)
            assertEquals("m2", result.suspendAndAssist[0].helperMemberId)
            assertEquals("Fix the API key issue", result.suspendAndAssist[0].assistTask)
            assertTrue(result.suspendAndConsultUser.isEmpty())
            assertFalse(result.isComplete)
        }

        @Test
        fun `parse JSON with suspendAndConsultUser field`() {
            val json = """
                {
                  "analysis": "Need user input for m3.",
                  "newTasks": [],
                  "reassignments": [],
                  "suspendAndAssist": [],
                  "suspendAndConsultUser": [
                    {"blockedMemberId": "m3", "question": "Which database to use?", "options": ["PostgreSQL", "MySQL"]}
                  ],
                  "isComplete": false
                }
            """.trimIndent()

            val result = LeaderDecisionParser.parse(json)

            assertEquals(1, result.suspendAndConsultUser.size)
            assertEquals("m3", result.suspendAndConsultUser[0].blockedMemberId)
            assertEquals("Which database to use?", result.suspendAndConsultUser[0].question)
            assertEquals(listOf("PostgreSQL", "MySQL"), result.suspendAndConsultUser[0].options)
            assertTrue(result.suspendAndAssist.isEmpty())
        }

        @Test
        fun `parse JSON without new fields defaults to empty lists (backward compat)`() {
            val json = """
                {
                  "analysis": "Old format without suspend fields.",
                  "newTasks": [{"memberId": "m1", "assignment": "Do work"}],
                  "reassignments": [],
                  "isComplete": false
                }
            """.trimIndent()

            val result = LeaderDecisionParser.parse(json)

            assertTrue(result.suspendAndAssist.isEmpty())
            assertTrue(result.suspendAndConsultUser.isEmpty())
            assertEquals(1, result.newTasks.size)
        }
    }

    // ── B. Team data model serialization ────────────────────────────────────

    @Nested
    inner class `Team data model serialization` {

        @Test
        fun `TeamSpec serialization round-trip`() {
            val spec = TeamSpec(
                leader = "leader-agent",
                members = listOf("member-a", "member-b"),
                maxIterations = 7,
                maxDynamicTasks = 15,
                roundTimeoutSeconds = 900,
                memberTimeoutSeconds = 120,
                contextTemplate = "Analyze the user query: {{ user_input }}",
            )

            val json = mapper.writeValueAsString(spec)
            val deserialized = mapper.readValue(json, TeamSpec::class.java)

            assertEquals(spec.leader, deserialized.leader)
            assertEquals(spec.members, deserialized.members)
            assertEquals(spec.maxIterations, deserialized.maxIterations)
            assertEquals(spec.maxDynamicTasks, deserialized.maxDynamicTasks)
            assertEquals(spec.roundTimeoutSeconds, deserialized.roundTimeoutSeconds)
            assertEquals(spec.memberTimeoutSeconds, deserialized.memberTimeoutSeconds)
            assertEquals(spec.contextTemplate, deserialized.contextTemplate)
        }

        @Test
        fun `TeamSpec default values are correct`() {
            val spec = TeamSpec(leader = "ldr", members = listOf("m1"))

            assertEquals(5, spec.maxIterations)
            assertEquals(10, spec.maxDynamicTasks)
            assertEquals(600, spec.roundTimeoutSeconds)
            assertEquals(0, spec.memberTimeoutSeconds)
            assertEquals("", spec.contextTemplate)
        }

        @Test
        fun `SwarmTask without team field deserializes with null team`() {
            // Backward compatibility: old JSON without `team` field
            val json = """
                {
                  "id": "task-1",
                  "agentId": "agent-a",
                  "promptTemplate": "Do something",
                  "type": "single"
                }
            """.trimIndent()

            val task = mapper.readValue(json, SwarmTask::class.java)

            assertEquals("task-1", task.id)
            assertEquals(TaskType.SINGLE, task.type)
            assertNull(task.team)
            assertNull(task.deliberation)
        }

        @Test
        fun `SwarmTask with team field round-trip`() {
            val teamSpec = TeamSpec(
                leader = "leader-1",
                members = listOf("worker-a", "worker-b"),
                maxIterations = 3,
            )
            val task = SwarmTask(
                id = "team-task-1",
                type = TaskType.TEAM,
                team = teamSpec,
                promptTemplate = "Coordinate the team.",
            )

            val json = mapper.writeValueAsString(task)
            val deserialized = mapper.readValue(json, SwarmTask::class.java)

            assertEquals("team-task-1", deserialized.id)
            assertEquals(TaskType.TEAM, deserialized.type)
            assertNotNull(deserialized.team)
            assertEquals("leader-1", deserialized.team!!.leader)
            assertEquals(listOf("worker-a", "worker-b"), deserialized.team!!.members)
            assertEquals(3, deserialized.team!!.maxIterations)
        }

        @Test
        fun `MemberStatus enum has five values`() {
            val values = MemberStatus.entries
            assertEquals(5, values.size)
            assertTrue(values.contains(MemberStatus.RUNNING))
            assertTrue(values.contains(MemberStatus.COMPLETED))
            assertTrue(values.contains(MemberStatus.ESCALATED))
            assertTrue(values.contains(MemberStatus.SUSPENDED))
            assertTrue(values.contains(MemberStatus.REASSIGNED))
        }

        @Test
        fun `EscalationEntry with nullable fields`() {
            val entry = EscalationEntry(
                memberId = "member-x",
                round = 2,
                reason = "Cannot proceed without API key",
            )

            val json = mapper.writeValueAsString(entry)
            val deserialized = mapper.readValue(json, EscalationEntry::class.java)

            assertEquals("member-x", deserialized.memberId)
            assertEquals(2, deserialized.round)
            assertEquals("Cannot proceed without API key", deserialized.reason)
            assertNull(deserialized.resolution)
            assertNull(deserialized.reassignedTo)
        }

        @Test
        fun `EscalationEntry with all fields populated`() {
            val entry = EscalationEntry(
                memberId = "m1",
                round = 1,
                reason = "Blocked by dependency",
                resolution = "Reassigned to m2",
                reassignedTo = "m2",
            )

            val json = mapper.writeValueAsString(entry)
            val deserialized = mapper.readValue(json, EscalationEntry::class.java)

            assertEquals("m1", deserialized.memberId)
            assertEquals("Blocked by dependency", deserialized.reason)
            assertEquals("Reassigned to m2", deserialized.resolution)
            assertEquals("m2", deserialized.reassignedTo)
        }

        @Test
        fun `TeamMemberExecution round-trip`() {
            val exec = TeamMemberExecution(
                memberId = "worker-a",
                round = 1,
                assignment = "Research market data",
                status = MemberStatus.COMPLETED,
                summary = "Done.",
                inputTokens = 100,
                outputTokens = 200,
            )

            val json = mapper.writeValueAsString(exec)
            val deserialized = mapper.readValue(json, TeamMemberExecution::class.java)

            assertEquals(exec.memberId, deserialized.memberId)
            assertEquals(exec.status, deserialized.status)
            assertEquals(exec.summary, deserialized.summary)
            assertEquals(exec.inputTokens, deserialized.inputTokens)
        }

        @Test
        fun `TeamRoundRecord round-trip`() {
            val record = TeamRoundRecord(
                round = 3,
                leaderAnalysis = "Good progress.",
                delegatedMembers = listOf("m1", "m2"),
                completedMembers = listOf("m3"),
                escalations = listOf("m4 reported BLOCKED"),
            )

            val json = mapper.writeValueAsString(record)
            val deserialized = mapper.readValue(json, TeamRoundRecord::class.java)

            assertEquals(3, deserialized.round)
            assertEquals("Good progress.", deserialized.leaderAnalysis)
            assertEquals(listOf("m1", "m2"), deserialized.delegatedMembers)
            assertEquals(listOf("m3"), deserialized.completedMembers)
            assertEquals(listOf("m4 reported BLOCKED"), deserialized.escalations)
        }

        @Test
        fun `WorkerResult with team fields round-trip`() {
            val result = WorkerResult(
                status = SwarmTaskStatus.COMPLETED,
                summary = "Team task done.",
                iterations = 4,
                escalationHistory = listOf(
                    EscalationEntry("m1", 1, "stuck", resolution = "helped by leader")
                ),
                memberExecutions = listOf(
                    TeamMemberExecution("m1", 1, "task A", MemberStatus.COMPLETED)
                ),
                roundRecords = listOf(
                    TeamRoundRecord(1, "analyze", listOf("m1"), emptyList(), emptyList())
                ),
            )

            val json = mapper.writeValueAsString(result)
            val deserialized = mapper.readValue(json, WorkerResult::class.java)

            assertEquals(SwarmTaskStatus.COMPLETED, deserialized.status)
            assertEquals(1, deserialized.escalationHistory.size)
            assertEquals(1, deserialized.memberExecutions.size)
            assertEquals(1, deserialized.roundRecords.size)
        }

        @Test
        fun `TeamExecutionState initial state is empty`() {
            val state = TeamExecutionState()

            assertEquals(0, state.iterations)
            assertTrue(state.escalationHistory.isEmpty())
            assertTrue(state.memberExecutions.isEmpty())
            assertTrue(state.roundRecords.isEmpty())
            assertTrue(state.leaderAnalyses.isEmpty())
            assertTrue(state.delegationHistory.isEmpty())
            assertTrue(state.runningJobs.isEmpty())
            assertTrue(state.runningMemberIds.isEmpty())
            assertTrue(state.suspendedMembers.isEmpty())
            assertTrue(state.pendingConsultations.isEmpty())
            assertTrue(state.memberSessionIds.isEmpty())
            assertTrue(state.memberAssignments.isEmpty())
        }
    }

    // ── B2. DeliberationSpec serialization ───────────────────────────────

    @Nested
    inner class `DeliberationSpec serialization` {

        @Test
        fun `DeliberationSpec serialization round-trip`() {
            val spec = DeliberationSpec(
                participants = listOf("reviewer-a", "reviewer-b"),
                judge = "senior-reviewer",
                maxRounds = 5,
                order = DeliberationOrder.ROUND_ROBIN,
                contextTemplate = "Review this proposal: {{ user_input }}",
            )

            val json = mapper.writeValueAsString(spec)
            val deserialized = mapper.readValue(json, DeliberationSpec::class.java)

            assertEquals(spec.participants, deserialized.participants)
            assertEquals(spec.judge, deserialized.judge)
            assertEquals(spec.maxRounds, deserialized.maxRounds)
            assertEquals(spec.order, deserialized.order)
            assertEquals(spec.contextTemplate, deserialized.contextTemplate)
        }

        @Test
        fun `DeliberationSpec default values are correct`() {
            val spec = DeliberationSpec(
                participants = listOf("p1"),
                judge = "j1"
            )

            assertEquals(3, spec.maxRounds)
            assertEquals(DeliberationOrder.SEQUENTIAL, spec.order)
            assertEquals("", spec.contextTemplate)
        }

        @Test
        fun `DeliberationSpec ignores unknown properties for backward compatibility`() {
            val json = """
                {
                  "participants": ["p1", "p2"],
                  "judge": "j1",
                  "maxRounds": 3,
                  "order": "SEQUENTIAL",
                  "openingPrompt": "old field",
                  "roundPrompt": "old field",
                  "judgePrompt": "old field",
                  "participantPrompts": {},
                  "openingAgentPromptEnabled": true,
                  "openingSystemPromptEnabled": false
                }
            """.trimIndent()

            val deserialized = mapper.readValue(json, DeliberationSpec::class.java)

            assertEquals(listOf("p1", "p2"), deserialized.participants)
            assertEquals("j1", deserialized.judge)
            assertEquals("", deserialized.contextTemplate)
        }
    }

    // ── C. EscalationTool & EscalationCompletionCheck ────────────────────

    @Nested
    inner class `EscalationTool behavior` {

        private fun createTool(): Pair<EscalationTool, AtomicReference<EscalationResult?>> {
            val ref = AtomicReference<EscalationResult?>(null)
            val tool = EscalationTool(
                metadata = ToolMetadata(
                    name = "escalate",
                    description = "test",
                    permissionCategory = "swarm",
                    isDefaultTool = false
                ),
                escalationRef = ref
            )
            return tool to ref
        }

        @Test
        fun `execute records escalation reason`() = runBlocking {
            val (tool, ref) = createTool()
            val ctx = AgentContext(agentId = "test-member")

            val result = tool.execute(
                agentContext = ctx,
                toolCallId = "call-1",
                messageId = null,
                args = mapOf("reason" to "Cannot proceed without API key"),
                coroutineScope = this,
                onUpdate = {}
            )

            assertNotNull(ref.get())
            assertEquals("Cannot proceed without API key", ref.get()!!.reason)
            assertTrue(result.content.filterIsInstance<TextContent>().first().text.contains("Escalation recorded"))
        }

        @Test
        fun `execute returns error on invalid params`() = runBlocking {
            val (tool, ref) = createTool()
            val ctx = AgentContext(agentId = "test-member")

            val result = tool.execute(
                agentContext = ctx,
                toolCallId = "call-2",
                messageId = null,
                args = mapOf("wrongField" to "value"),
                coroutineScope = this,
                onUpdate = {}
            )

            assertNull(ref.get())
            assertTrue(result.content.filterIsInstance<TextContent>().first().text.contains("Invalid parameters"))
        }

        @Test
        fun `execute overwrites previous escalation`() = runBlocking {
            val (tool, ref) = createTool()
            val ctx = AgentContext(agentId = "test-member")

            tool.execute(ctx, "call-1", null, mapOf("reason" to "First reason"), this, {})
            tool.execute(ctx, "call-2", null, mapOf("reason" to "Second reason"), this, {})

            assertEquals("Second reason", ref.get()!!.reason)
        }
    }

    @Nested
    inner class `EscalationCompletionCheck behavior` {

        private fun makeInput(
            messages: List<com.easy.easyai.core.model.EasyAiMessage>,
            sessionId: String? = "test-session"
        ): CompletionCheckInput {
            return CompletionCheckInput(
                agentContext = AgentContext(agentId = "test-member", sessionId = sessionId),
                transcript = messages,
                turnId = 1
            )
        }

        private fun assistantMsg(text: String): AssistantMessage =
            AssistantMessage(content = listOf(TextContent(text)))

        @Test
        fun `returns Done when escalation already recorded`() = runBlocking {
            val ref = AtomicReference(EscalationResult("already escalated"))
            val check = EscalationCompletionCheck(ref)

            val result = check.check(makeInput(listOf(assistantMsg("I am BLOCKED"))))

            assertEquals(CompletionCheckResult.Done, result)
        }

        @Test
        fun `returns Done when no AssistantMessage in transcript`() = runBlocking {
            val ref = AtomicReference<EscalationResult?>(null)
            val check = EscalationCompletionCheck(ref)

            val result = check.check(makeInput(emptyList()))

            assertEquals(CompletionCheckResult.Done, result)
        }

        @Test
        fun `returns Done when output has no escalation signal`() = runBlocking {
            val ref = AtomicReference<EscalationResult?>(null)
            val check = EscalationCompletionCheck(ref)

            val result = check.check(makeInput(listOf(assistantMsg("Task completed successfully."))))

            assertEquals(CompletionCheckResult.Done, result)
        }

        @Test
        fun `returns Continue when signal detected but tool not called`() = runBlocking {
            val ref = AtomicReference<EscalationResult?>(null)
            val check = EscalationCompletionCheck(ref)

            val result = check.check(makeInput(listOf(assistantMsg("I am BLOCKED and cannot proceed."))))

            assertTrue(result is CompletionCheckResult.Continue)
            assertTrue((result as CompletionCheckResult.Continue).prompt!!.contains("escalate"))
        }

        @Test
        fun `returns Done after max retries exceeded`() = runBlocking {
            val ref = AtomicReference<EscalationResult?>(null)
            val check = EscalationCompletionCheck(ref, maxRetries = 1)
            val input = makeInput(listOf(assistantMsg("I am BLOCKED")))

            // First call: Continue
            val first = check.check(input)
            assertTrue(first is CompletionCheckResult.Continue)

            // Second call: Done (max retries exceeded)
            val second = check.check(input)
            assertEquals(CompletionCheckResult.Done, second)
        }

        @Test
        fun `returns Done when assistant text is blank`() = runBlocking {
            val ref = AtomicReference<EscalationResult?>(null)
            val check = EscalationCompletionCheck(ref)

            val result = check.check(makeInput(listOf(assistantMsg(""))))

            assertEquals(CompletionCheckResult.Done, result)
        }

        @Test
        fun `UNABLE signal word triggers Continue`() = runBlocking {
            val ref = AtomicReference<EscalationResult?>(null)
            val check = EscalationCompletionCheck(ref)

            val result = check.check(makeInput(listOf(assistantMsg("UNABLE to complete this task."))))

            assertTrue(result is CompletionCheckResult.Continue)
        }

        @Test
        fun `ESCALATE signal word triggers Continue`() = runBlocking {
            val ref = AtomicReference<EscalationResult?>(null)
            val check = EscalationCompletionCheck(ref)

            val result = check.check(makeInput(listOf(assistantMsg("I need to ESCALATE this issue."))))

            assertTrue(result is CompletionCheckResult.Continue)
        }
    }
}
