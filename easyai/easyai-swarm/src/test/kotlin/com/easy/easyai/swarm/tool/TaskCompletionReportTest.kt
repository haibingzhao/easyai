package com.easy.easyai.swarm.tool

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.CompletionCheckInput
import com.easy.easyai.core.agent.CompletionCheckResult
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.swarm.model.SwarmTaskStatus
import com.easy.easyai.swarm.model.TaskReportResult
import com.easy.easyai.swarm.model.TaskReportStatus
import com.easy.easyai.swarm.model.WorkerResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TaskCompletionReportTest {

    private val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()

    private fun agentContext(sessionId: String? = "swarm-worker-test") = AgentContext(
        agentId = "test-agent",
        userId = "test-user",
        sessionId = sessionId,
    )

    @Nested
    inner class `TaskCompletionReportTool` {

        private fun tool(reportRef: AtomicReference<TaskReportResult>) = TaskCompletionReportTool(
            metadata = ToolMetadata(
                name = "report_task_result",
                description = "Report the final result of your task.",
                permissionCategory = "swarm",
                isDefaultTool = false,
            ),
            reportRef = reportRef,
        )

        @Test
        fun `SUCCESS status records report`() = runBlocking {
            val ref = AtomicReference<TaskReportResult>()
            val t = tool(ref)

            val result = t.execute(
                agentContext = agentContext(),
                toolCallId = "call-1",
                messageId = null,
                args = mapOf("status" to "SUCCESS", "reason" to ""),
                coroutineScope = this,
                onUpdate = {},
            )

            assertEquals("Task result reported: SUCCESS", (result.content.first() as TextContent).text)
            val report = ref.get()!!
            assertEquals(TaskReportStatus.SUCCESS, report.status)
            assertEquals("", report.reason)
        }

        @Test
        fun `FAILED status with reason records report`() = runBlocking {
            val ref = AtomicReference<TaskReportResult>()
            val t = tool(ref)

            val result = t.execute(
                agentContext = agentContext(),
                toolCallId = "call-2",
                messageId = null,
                args = mapOf("status" to "FAILED", "reason" to "Network timeout"),
                coroutineScope = this,
                onUpdate = {},
            )

            assertEquals("Task result reported: FAILED", (result.content.first() as TextContent).text)
            val report = ref.get()!!
            assertEquals(TaskReportStatus.FAILED, report.status)
            assertEquals("Network timeout", report.reason)
        }

        @Test
        fun `FAILED without reason returns error`() = runBlocking {
            val ref = AtomicReference<TaskReportResult>()
            val t = tool(ref)

            val result = t.execute(
                agentContext = agentContext(),
                toolCallId = "call-3",
                messageId = null,
                args = mapOf("status" to "FAILED", "reason" to ""),
                coroutineScope = this,
                onUpdate = {},
            )

            assert(result.isError)
            assertNull(ref.get())
        }

        @Test
        fun `invalid status returns error`() = runBlocking {
            val ref = AtomicReference<TaskReportResult>()
            val t = tool(ref)

            val result = t.execute(
                agentContext = agentContext(),
                toolCallId = "call-4",
                messageId = null,
                args = mapOf("status" to "UNKNOWN", "reason" to ""),
                coroutineScope = this,
                onUpdate = {},
            )

            assert(result.isError)
            assertNull(ref.get())
        }

        @Test
        fun `case-insensitive status parsing`() = runBlocking {
            val ref = AtomicReference<TaskReportResult>()
            val t = tool(ref)

            t.execute(
                agentContext = agentContext(),
                toolCallId = "call-5",
                messageId = null,
                args = mapOf("status" to "success", "reason" to ""),
                coroutineScope = this,
                onUpdate = {},
            )

            assertEquals(TaskReportStatus.SUCCESS, ref.get()!!.status)
        }
    }

    @Nested
    inner class `TaskCompletionReportCheck` {

        @Test
        fun `returns Done when tool was already called`(): Unit = runBlocking {
            val ref = AtomicReference(TaskReportResult(TaskReportStatus.SUCCESS, ""))
            val check = TaskCompletionReportCheck(ref)

            val result = check.check(CompletionCheckInput(
                agentContext = agentContext(),
                transcript = emptyList(),
                turnId = 1,
            ))

            assertIs<CompletionCheckResult.Done>(result)
        }

        @Test
        fun `returns Continue with nudge when tool not called`() = runBlocking {
            val ref = AtomicReference<TaskReportResult>()
            val check = TaskCompletionReportCheck(ref, maxRetries = 1)

            val result = check.check(CompletionCheckInput(
                agentContext = agentContext(),
                transcript = listOf(AssistantMessage(content = listOf(TextContent("I finished the task.")))),
                turnId = 1,
            ))

            assertIs<CompletionCheckResult.Continue>(result)
            assert(result.prompt!!.contains("report_task_result"))
        }

        @Test
        fun `returns Done after max retries exhausted`(): Unit = runBlocking {
            val ref = AtomicReference<TaskReportResult>()
            val check = TaskCompletionReportCheck(ref, maxRetries = 1)
            val sessionKey = "swarm-worker-test"

            // First call — Continue (retry 0/1)
            check.check(CompletionCheckInput(
                agentContext = agentContext(sessionKey),
                transcript = emptyList(),
                turnId = 1,
            ))

            // Second call — Done (retry 1/1 exhausted)
            val result = check.check(CompletionCheckInput(
                agentContext = agentContext(sessionKey),
                transcript = emptyList(),
                turnId = 2,
            ))

            assertIs<CompletionCheckResult.Done>(result)
        }

        @Test
        fun `returns Done when tool called after nudge`(): Unit = runBlocking {
            val ref = AtomicReference<TaskReportResult>()
            val check = TaskCompletionReportCheck(ref, maxRetries = 2)
            val sessionKey = "swarm-worker-test"

            // First call — Continue (tool not called)
            val first = check.check(CompletionCheckInput(
                agentContext = agentContext(sessionKey),
                transcript = emptyList(),
                turnId = 1,
            ))
            assertIs<CompletionCheckResult.Continue>(first)

            // Simulate tool call
            ref.set(TaskReportResult(TaskReportStatus.FAILED, "Could not fetch data"))

            // Second call — Done (tool was called)
            val second = check.check(CompletionCheckInput(
                agentContext = agentContext(sessionKey),
                transcript = emptyList(),
                turnId = 2,
            ))
            assertIs<CompletionCheckResult.Done>(second)
        }
    }

    @Nested
    inner class `TaskReportResult serialization` {

        @Test
        fun `round-trip serialization`() {
            val original = TaskReportResult(TaskReportStatus.FAILED, "Network error")
            val json = mapper.writeValueAsString(original)
            val deserialized = mapper.readValue(json, TaskReportResult::class.java)

            assertEquals(original, deserialized)
        }

        @Test
        fun `WorkerResult with taskReport round-trip`() {
            val result = WorkerResult(
                status = SwarmTaskStatus.COMPLETED,
                summary = "Done",
                taskReport = TaskReportResult(TaskReportStatus.SUCCESS, ""),
            )

            val json = mapper.writeValueAsString(result)
            val deserialized = mapper.readValue(json, WorkerResult::class.java)

            assertEquals(TaskReportStatus.SUCCESS, deserialized.taskReport?.status)
        }

        @Test
        fun `WorkerResult without taskReport is null`() {
            val result = WorkerResult(
                status = SwarmTaskStatus.COMPLETED,
                summary = "Done",
            )

            val json = mapper.writeValueAsString(result)
            val deserialized = mapper.readValue(json, WorkerResult::class.java)

            assertNull(deserialized.taskReport)
        }
    }
}
