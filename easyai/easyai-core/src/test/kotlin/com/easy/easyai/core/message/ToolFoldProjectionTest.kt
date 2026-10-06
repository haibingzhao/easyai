package com.easy.easyai.core.message

import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.StopReason
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.ThinkingContent
import com.easy.easyai.core.model.ToolCallContent
import com.easy.easyai.core.model.ToolResultEntry
import com.easy.easyai.core.model.ToolResultMessage
import com.easy.easyai.core.model.UserMessage
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ToolFoldProjectionTest {

    private fun toolResult(toolCallId: String, toolName: String, result: String) =
        ToolResultMessage(
            toolResults = listOf(
                ToolResultEntry(toolCallId = toolCallId, toolName = toolName, result = result)
            )
        )

    /** One complete run: user request → assistant (thinking + text + tool call) → tool result. */
    private fun run(userText: String, toolCallId: String, toolName: String, args: String, result: String): List<EasyAiMessage> = listOf(
        UserMessage(userText),
        AssistantMessage(
            id = "asst_$toolCallId",
            content = listOf(
                ThinkingContent("some reasoning that should be folded away"),
                TextContent("final answer for $userText"),
                ToolCallContent(toolCallId, toolName, args)
            ),
            stopReason = StopReason.TOOL_USE
        ),
        toolResult(toolCallId, toolName, result)
    )

    private fun threeRunTranscript(): List<EasyAiMessage> =
        run("q1", "tc1", "read", """{"path":"/abs/Foo.kt","offset":10}""", "R".repeat(500)) +
            run("q2", "tc2", "bash", """{"command":"ls -la"}""", "B".repeat(500)) +
            run("q3", "tc3", "grep", """{"pattern":"needle"}""", "G".repeat(500))

    @Nested
    inner class PassThrough {

        @Test
        fun `disabled config returns the identical list`() {
            val messages = threeRunTranscript()
            val (result, report) = ToolFoldProjection.project(messages, ToolFoldConfig(enabled = false))
            assertSame(messages, result)
            assertEquals(0, report.foldedToolCallCount)
        }

        @Test
        fun `single run is never folded`() {
            val messages = run("q1", "tc1", "read", """{"path":"/a"}""", "x".repeat(500))
            val (result, report) = ToolFoldProjection.project(messages)
            assertSame(messages, result)
            assertEquals(0, report.foldedToolCallCount)
        }

        @Test
        fun `empty list returns empty report`() {
            val (result, report) = ToolFoldProjection.project(emptyList())
            assertTrue(result.isEmpty())
            assertEquals(0, report.foldedRunCount)
        }
    }

    @Nested
    inner class BoundaryRules {

        @Test
        fun `current run and last completed run stay verbatim, older runs fold`() {
            val messages = threeRunTranscript()
            val (result, report) = ToolFoldProjection.project(messages, ToolFoldConfig(keepRecentRuns = 1))

            assertEquals(1, report.foldedRunCount)
            // Segment 0 (run q1): indices 0..2 folded
            val firstAssistant = result[1] as AssistantMessage
            val foldedCall = firstAssistant.toolCalls().single()
            assertTrue(foldedCall.arguments.startsWith("[tool: read path=/abs/Foo.kt"))
            assertTrue(foldedCall.arguments.contains("recall_tool_result"))
            val foldedResult = (result[2] as ToolResultMessage).toolResults.single()
            assertTrue(foldedResult.result.startsWith("[tool result: read ok"))
            // Run q2 and q3 untouched
            assertSame(messages[3], result[3])
            assertSame(messages[5], result[5])
        }

        @Test
        fun `keepRecentRuns zero folds the last completed run too`() {
            val messages = threeRunTranscript()
            val (result, report) = ToolFoldProjection.project(messages, ToolFoldConfig(keepRecentRuns = 0))
            assertEquals(2, report.foldedRunCount)
            val run2Call = (result[4] as AssistantMessage).toolCalls().single()
            assertTrue(run2Call.arguments.startsWith("[tool: bash"))
        }

        @Test
        fun `steering messages do not create run boundaries`() {
            val steering = UserMessage(
                content = listOf(TextContent("stop and summarize")),
                metadata = mapOf(UserMessage.SOURCE_KEY to UserMessage.SOURCE_STEERING)
            )
            val messages = run("q1", "tc1", "read", """{"path":"/a"}""", "x".repeat(500)) +
                listOf(steering) +
                run("q2", "tc2", "bash", """{"command":"ls"}""", "y".repeat(500)) +
                run("q3", "tc3", "grep", """{"pattern":"p"}""", "z".repeat(500))
            // Real boundaries only at q1/q2/q3 → 3 segments; steering joins the q2 run.
            val segments = ToolFoldProjection.segmentRuns(messages)
            assertEquals(3, segments.size)
            val (result, report) = ToolFoldProjection.project(messages)
            assertEquals(1, report.foldedRunCount) // only run q1 folds; steering+q2 is the kept last completed run
            val steeringIndex = messages.indexOf(steering)
            assertSame(messages[steeringIndex], result[steeringIndex])
        }

        @Test
        fun `latest compaction summary is never folded`() {
            val summary = UserMessage(
                content = listOf(TextContent("condensed history ".repeat(50))),
                metadata = mapOf("isCompactionSummary" to "true")
            )
            val messages = run("q1", "tc1", "read", """{"path":"/a"}""", "x".repeat(500)) +
                listOf(summary) +
                run("q2", "tc2", "bash", """{"command":"ls"}""", "y".repeat(500)) +
                run("q3", "tc3", "grep", """{"pattern":"p"}""", "z".repeat(500))
            val (result, _) = ToolFoldProjection.project(messages)
            assertSame(summary, result[3])
        }

        @Test
        fun `resume guidance as first message still allows boundary at next real user message`() {
            val guidance = UserMessage(
                content = listOf(TextContent("[System: resume]")),
                metadata = mapOf(UserMessage.SYSTEM_ORIGIN_KEY to UserMessage.ORIGIN_RESUME_GUIDANCE)
            )
            val messages = listOf(guidance) +
                run("q1", "tc1", "read", """{"path":"/a"}""", "x".repeat(500)) +
                run("q2", "tc2", "bash", """{"command":"ls"}""", "y".repeat(500)) +
                run("q3", "tc3", "grep", """{"pattern":"p"}""", "z".repeat(500))
            val (result, report) = ToolFoldProjection.project(messages)
            // Segments: [guidance+q1 run], [q2 run], [q3 run] → first segment folds
            assertTrue(report.foldedToolCallCount > 0)
            val firstAssistant = result[2] as AssistantMessage
            assertTrue(firstAssistant.toolCalls().single().arguments.startsWith("[tool: read"))
        }
    }

    @Nested
    inner class ContentFidelity {

        @Test
        fun `message count ids and pairing are preserved`() {
            val messages = threeRunTranscript()
            val (result, _) = ToolFoldProjection.project(messages)
            assertEquals(messages.size, result.size)
            assertEquals(messages.map { it.id }, result.map { it.id })
            // Assistant still carries its tool call and the ToolResultMessage its matching entry
            val firstAssistant = result[1] as AssistantMessage
            val firstResult = (result[2] as ToolResultMessage).toolResults.single()
            assertEquals(firstAssistant.toolCalls().single().id, firstResult.toolCallId)
        }

        @Test
        fun `user messages and assistant text and thinking survive folding`() {
            val messages = threeRunTranscript()
            val (result, _) = ToolFoldProjection.project(messages)
            assertEquals("q1", (result[0] as UserMessage).text())
            val assistant = result[1] as AssistantMessage
            assertTrue(assistant.content.any { it is TextContent && it.text == "final answer for q1" })
            assertTrue(assistant.content.none { it is ThinkingContent })
        }

        @Test
        fun `short tool results stay verbatim`() {
            val messages = run("q1", "tc1", "read", """{"path":"/a"}""", "short ok") +
                run("q2", "tc2", "bash", """{"command":"ls"}""", "b".repeat(500)) +
                run("q3", "tc3", "grep", """{"pattern":"p"}""", "g".repeat(500))
            val (result, _) = ToolFoldProjection.project(messages)
            assertEquals("short ok", (result[2] as ToolResultMessage).toolResults.single().result)
        }

        @Test
        fun `folding is deterministic across invocations`() {
            val messages = threeRunTranscript()
            val first = ToolFoldProjection.project(messages).first
            val second = ToolFoldProjection.project(messages).first
            assertEquals(
                first.map { m -> (m as? AssistantMessage)?.toolCalls()?.map { it.arguments } },
                second.map { m -> (m as? AssistantMessage)?.toolCalls()?.map { it.arguments } }
            )
        }

        @Test
        fun `folded entries are recorded in refs and char savings`() {
            val messages = threeRunTranscript()
            val (_, report) = ToolFoldProjection.project(messages)
            assertEquals(2, report.foldedRefs.size) // 1 tool call + 1 long result from run q1
            assertTrue(report.charsBefore > report.charsAfter)
            assertTrue(report.hasFolds)
        }

        @Test
        fun `foldedRunCount counts only runs that actually folded a tool call or result`() {
            // Run q0 is pure prose (assistant text, no tool call, no tool result) → nothing to fold.
            val pureTextRun = listOf(
                UserMessage("q0"),
                AssistantMessage(
                    id = "asst_text",
                    content = listOf(TextContent("just prose, no tools")),
                    stopReason = StopReason.STOP
                )
            )
            val messages = pureTextRun +
                run("q1", "tc1", "read", """{"path":"/a"}""", "x".repeat(500)) +
                run("q2", "tc2", "bash", """{"command":"ls"}""", "y".repeat(500))
            // keepRecentRuns=0 → q0 and q1 are foldable; only q1 actually folds.
            val (_, report) = ToolFoldProjection.project(messages, ToolFoldConfig(keepRecentRuns = 0))
            assertTrue(report.foldedToolCallCount > 0)
            assertEquals(1, report.foldedRunCount) // q0 must not be counted
        }
    }

    @Nested
    inner class ArgumentSummaries {

        private fun callArgs(args: String): String {
            val messages = run("q1", "tc1", "read", args, "x".repeat(500)) +
                run("q2", "tc2", "bash", """{"command":"ls"}""", "y".repeat(500)) +
                run("q3", "tc3", "grep", """{"pattern":"p"}""", "z".repeat(500))
            val (result, _) = ToolFoldProjection.project(messages)
            return (result[1] as AssistantMessage).toolCalls().single().arguments
        }

        @Test
        fun `preferred keys appear first with value truncation`() {
            val huge = "H".repeat(300)
            val placeholder = callArgs("""{"path":"$huge","offset":10,"extra":true}""")
            assertTrue(placeholder.contains("path=H"))
            assertTrue(placeholder.contains("+2 more"))
            assertTrue(placeholder.length <= 200)
        }

        @Test
        fun `malformed json falls back to neutral marker`() {
            assertTrue(callArgs("not-json{").contains("(args folded)"))
        }

        @Test
        fun `non-object json falls back to neutral marker`() {
            assertTrue(callArgs("""["a","b"]""").contains("(args folded)"))
        }

        @Test
        fun `bespoke mcp keys surface as key list`() {
            val placeholder = callArgs("""{"subscriptionId":"abc","dateRange":{"from":"2026-01-01"}}""")
            assertTrue(placeholder.contains("keys=[subscriptionId, dateRange]"))
        }
    }
}

private fun UserMessage.text(): String = content.filterIsInstance<TextContent>().joinToString("") { it.text }
