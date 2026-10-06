package com.easy.easyai.core.tool

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.ToolCallContent
import com.easy.easyai.core.model.ToolResultEntry
import com.easy.easyai.core.model.ToolResultMessage
import com.easy.easyai.core.model.UserMessage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecallToolResultToolTest {

    private val agentContext = AgentContext(agentId = "test-agent")

    private fun toolResultMessage(id: String, vararg entries: Pair<String, String>): ToolResultMessage =
        ToolResultMessage(
            id = id,
            toolResults = entries.map { (callId, result) ->
                ToolResultEntry(toolCallId = callId, toolName = "read", result = result)
            }
        )

    private fun invoke(tool: RecallToolResultTool, transcript: List<EasyAiMessage>, args: Map<String, Any?>): ToolResult =
        runBlocking {
            tool.execute(agentContext, "tc_query", null, args, this) { }
        }

    private fun ToolResult.text(): String =
        content.joinToString("\n") { (it as? TextContent)?.text ?: (it as? com.easy.easyai.core.model.ToolResultContent)?.output ?: "" }

    @Nested
    inner class HappyPath {

        @Test
        fun `returns the original entry content for a valid ref`() {
            val transcript = listOf(
                UserMessage("q"),
                toolResultMessage("msg_1", "call_a" to "ORIGINAL RESULT CONTENT")
            )
            val tool = RecallToolResultTool(transcriptProvider = { transcript })
            val result = invoke(tool, transcript, mapOf("ref" to "msg_1#call_a"))
            assertEquals(false, result.isError)
            assertTrue(result.text().contains("ORIGINAL RESULT CONTENT"))
            assertTrue(result.text().contains("[recalled: read call_a from ref msg_1#call_a]"))
        }

        @Test
        fun `picks the matching entry among several tool results`() {
            val transcript = listOf(
                toolResultMessage("msg_1", "call_a" to "AAA", "call_b" to "BBB")
            )
            val tool = RecallToolResultTool(transcriptProvider = { transcript })
            val result = invoke(tool, transcript, mapOf("ref" to "msg_1#call_b"))
            assertEquals(false, result.isError)
            assertTrue(result.text().contains("BBB"))
            assertEquals(false, result.text().contains("AAA"))
        }

        @Test
        fun `returns the original arguments for a folded tool-call ref`() {
            val transcript = listOf<EasyAiMessage>(
                AssistantMessage(
                    id = "msg_a",
                    content = listOf(ToolCallContent(id = "call_a", name = "bash", arguments = """{"command":"ls -la"}"""))
                )
            )
            val tool = RecallToolResultTool(transcriptProvider = { transcript })
            val result = invoke(tool, transcript, mapOf("ref" to "msg_a#call_a"))
            assertEquals(false, result.isError)
            assertTrue(result.text().contains("""{"command":"ls -la"}"""))
            assertTrue(result.text().contains("[recalled call arguments: bash call_a from ref msg_a#call_a]"))
        }
    }

    @Nested
    inner class ErrorPaths {

        @Test
        fun `missing ref argument returns an error`() {
            val tool = RecallToolResultTool(transcriptProvider = { emptyList() })
            val result = invoke(tool, emptyList(), emptyMap())
            assertEquals(true, result.isError)
            assertTrue(result.text().contains("ref"))
        }

        @Test
        fun `ref without separator returns an error`() {
            val tool = RecallToolResultTool(transcriptProvider = { emptyList() })
            val result = invoke(tool, emptyList(), mapOf("ref" to "msg_1"))
            assertEquals(true, result.isError)
            assertTrue(result.text().contains("Invalid ref"))
        }

        @Test
        fun `unknown message id returns an error`() {
            val transcript = listOf(toolResultMessage("msg_1", "call_a" to "AAA"))
            val tool = RecallToolResultTool(transcriptProvider = { transcript })
            val result = invoke(tool, transcript, mapOf("ref" to "msg_99#call_a"))
            assertEquals(true, result.isError)
            assertTrue(result.text().contains("No folded tool result"))
        }

        @Test
        fun `unknown tool call id in existing message returns an error`() {
            val transcript = listOf(toolResultMessage("msg_1", "call_a" to "AAA"))
            val tool = RecallToolResultTool(transcriptProvider = { transcript })
            val result = invoke(tool, transcript, mapOf("ref" to "msg_1#call_x"))
            assertEquals(true, result.isError)
            assertTrue(result.text().contains("No tool call"))
        }

        @Test
        fun `ref pointing at a non tool-result message returns an error`() {
            val transcript = listOf<EasyAiMessage>(UserMessage("msg body"))
            val tool = RecallToolResultTool(transcriptProvider = { transcript })
            val result = invoke(tool, transcript, mapOf("ref" to "${transcript[0].id}#call_a"))
            assertEquals(true, result.isError)
        }
    }
}
