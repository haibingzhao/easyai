package com.easy.easyai.compaction.strategy

import com.easy.easyai.compaction.model.CompactedRange
import com.easy.easyai.compaction.model.CompactionContext
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.ToolCallContent
import com.easy.easyai.core.model.ToolResultEntry
import com.easy.easyai.core.model.ToolResultMessage
import com.easy.easyai.core.model.UserMessage
import com.easy.easyai.core.model.EasyAiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompactionAgentStrategyTest {

    @Nested
    inner class `CompactionVariableTool coerce` {

        private fun createTool(): Pair<CompactionVariableTool, AtomicReference<Map<String, String>>> {
            val toolCalled = AtomicBoolean(false)
            val extracted = AtomicReference<Map<String, String>>(emptyMap())
            return CompactionVariableTool(toolCalled, extracted) to extracted
        }

        private val agentContext = AgentContext(agentId = "test")

        private suspend fun exec(tool: CompactionVariableTool, args: Map<String, Any?>) =
            tool.execute(agentContext, "call-1", null, args, CoroutineScope(Dispatchers.Unconfined)) {}

        @Test
        fun `map input extracts variables`() = runTest {
            val (tool, extracted) = createTool()
            exec(tool, mapOf("variables" to mapOf("price" to "170.69", "pe" to 80.28)))
            assertEquals(mapOf("price" to "170.69", "pe" to "80.28"), extracted.get())
        }

        @Test
        fun `string-encoded JSON input is parsed`() = runTest {
            val (tool, extracted) = createTool()
            exec(tool, mapOf("variables" to """{"stock_code": "SZ.002409", "gain_pct": 131.3}"""))
            assertEquals(mapOf("stock_code" to "SZ.002409", "gain_pct" to "131.3"), extracted.get())
        }

        @Test
        fun `nested map value is serialized as JSON string`() = runTest {
            val (tool, extracted) = createTool()
            exec(tool, mapOf("variables" to mapOf("share" to mapOf("Entegris" to "25-30%", "ADEKA" to "15-20%"))))
            val value = extracted.get()["share"]!!
            assertTrue(value.contains(""""Entegris":"25-30%"""") || value.contains(""""Entegris" : "25-30%""""), "Expected JSON: $value")
            assertTrue(value.startsWith("{"), "Expected JSON object string: $value")
        }

        @Test
        fun `nested list value is serialized as JSON string`() = runTest {
            val (tool, extracted) = createTool()
            exec(tool, mapOf("variables" to mapOf("customers" to listOf("SMIC", "CXMT"))))
            val value = extracted.get()["customers"]!!
            assertTrue(value.contains(""""SMIC"""") && value.contains(""""CXMT""""), "Expected JSON array: $value")
            assertTrue(value.startsWith("["), "Expected JSON array string: $value")
        }

        @Test
        fun `null variables results in empty map`() = runTest {
            val (tool, extracted) = createTool()
            exec(tool, mapOf("variables" to null))
            assertTrue(extracted.get().isEmpty())
        }

        @Test
        fun `invalid JSON string results in empty map`() = runTest {
            val (tool, extracted) = createTool()
            exec(tool, mapOf("variables" to "not-valid-json{{{"))
            assertTrue(extracted.get().isEmpty())
        }

        @Test
        fun `deleteKeys as list removes keys`() = runTest {
            val (tool, extracted) = createTool()
            exec(tool, mapOf("variables" to mapOf("a" to "1", "b" to "2")))
            exec(tool, mapOf("deleteKeys" to listOf("a")))
            assertEquals(mapOf("b" to "2"), extracted.get())
        }

        @Test
        fun `deleteKeys as JSON string removes keys`() = runTest {
            val (tool, extracted) = createTool()
            exec(tool, mapOf("variables" to mapOf("x" to "10", "y" to "20")))
            exec(tool, mapOf("deleteKeys" to """["x"]"""))
            assertEquals(mapOf("y" to "20"), extracted.get())
        }

        @Test
        fun `variables accumulate across calls`() = runTest {
            val (tool, extracted) = createTool()
            exec(tool, mapOf("variables" to mapOf("a" to "1")))
            exec(tool, mapOf("variables" to mapOf("b" to "2", "a" to "99")))
            assertEquals(mapOf("a" to "99", "b" to "2"), extracted.get())
        }

        @Test
        fun `null value in map becomes empty string`() = runTest {
            val (tool, extracted) = createTool()
            exec(tool, mapOf("variables" to mapOf("key" to null)))
            assertEquals(mapOf("key" to ""), extracted.get())
        }
    }

    @Nested
    inner class `fallback summary` {

        private val context = CompactionContext(
            range = CompactedRange(
                messageIds = listOf("m1", "m2", "m3", "m4"),
                estimatedTokensBefore = 42000
            ),
            currentTurnId = 3
        )

        private val strategy = CompactionAgentStrategy(
            agentServiceProvider = { throw IllegalStateException("agent unavailable") }
        )

        private val messages = listOf<EasyAiMessage>(
            UserMessage("Help me analyze /src/main/kotlin/App.kt"),
            AssistantMessage(content = listOf(
                TextContent("Let me read the file."),
                ToolCallContent(id = "tc1", name = "read_file", arguments = """{"path": "/src/main/kotlin/App.kt"}""")
            )),
            ToolResultMessage(toolResults = listOf(
                ToolResultEntry(toolCallId = "tc1", toolName = "read_file", result = "fun main() { }")
            )),
            AssistantMessage(content = listOf(TextContent("The main function is empty.")))
        )

        @Test
        fun `fallback contains reason and range info`() = runTest {
            val output = strategy.compactWithUsage(messages, context, null)
            assertTrue(output.summary.contains("fallback - Agent compaction failed: agent unavailable"))
            assertTrue(output.summary.contains("Messages compacted: 4"))
            assertTrue(output.summary.contains("Turn: 3"))
            assertTrue(output.summary.contains("42000"))
        }

        @Test
        fun `fallback extracts tool activity`() = runTest {
            val output = strategy.compactWithUsage(messages, context, null)
            assertTrue(output.summary.contains("## Tool Activity"))
            assertTrue(output.summary.contains("- read_file"))
        }

        @Test
        fun `fallback extracts file references`() = runTest {
            val output = strategy.compactWithUsage(messages, context, null)
            assertTrue(output.summary.contains("## Files Mentioned"))
            assertTrue(output.summary.contains("/src/main/kotlin/App.kt"))
        }

        @Test
        fun `fallback extracts recent highlights`() = runTest {
            val output = strategy.compactWithUsage(messages, context, null)
            assertTrue(output.summary.contains("## Recent Highlights From Compacted History"))
            assertTrue(output.summary.contains("[user]: Help me analyze"))
            assertTrue(output.summary.contains("[assistant tool call]: read_file(...)"))
            assertTrue(output.summary.contains("[tool result]: read_file ->"))
            assertTrue(output.summary.contains("[assistant]: The main function is empty."))
        }

        @Test
        fun `fallback has no usage`() = runTest {
            val output = strategy.compactWithUsage(messages, context, null)
            assertEquals(0, output.usage.inputTokens)
            assertEquals(0, output.usage.outputTokens)
        }

        @Test
        fun `fallback with empty messages still produces range section`() = runTest {
            val output = strategy.compactWithUsage(emptyList(), context, null)
            assertTrue(output.summary.contains("Messages compacted: 0"))
            assertFalse(output.summary.contains("## Tool Activity"))
        }
    }
}
