package com.easy.easyai.compaction.strategy

import com.easy.easyai.compaction.model.CompactedRange
import com.easy.easyai.compaction.model.CompactionContext
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.DefaultAgentService
import com.easy.easyai.core.event.MessageListener
import com.easy.easyai.core.message.DefaultMessageConverter
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.ToolCallContent
import com.easy.easyai.core.model.ToolResultEntry
import com.easy.easyai.core.model.ToolResultMessage
import com.easy.easyai.core.model.UserMessage
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.prompt.PromptTemplateService
import com.easy.easyai.core.tool.DefaultToolExecutionEngine
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.ai.chat.messages.AssistantMessage as SpringAiAssistantMessage
import org.springframework.ai.chat.messages.SystemMessage as SpringAiSystemMessage

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
    inner class `command summary input` {

        @Test
        fun `summary agent sees snapshot once despite reentering preparePrompt and never persists it`() = runTest {
            val model = mockk<ChatModel>()
            val prompts = CopyOnWriteArrayList<Prompt>()
            every { model.stream(any<Prompt>()) } answers {
                prompts.add(firstArg())
                val response = SpringAiAssistantMessage.builder()
                    .content("Summary with captured command constraints")
                    .toolCalls(listOf(SpringAiAssistantMessage.ToolCall("variables", "function", "update_variable", "{\"variables\":{}}")))
                    .build()
                Flux.just(ChatResponse(listOf(Generation(response))))
            }
            val promptService = mockk<PromptTemplateService>()
            every { promptService.build(any(), any()) } returns ""
            val listener = mockk<MessageListener>(relaxed = true)
            val services = DefaultAgentService(
                chatModelFactories = emptyList(),
                messageConverter = DefaultMessageConverter(),
                toolExecutor = DefaultToolExecutionEngine(),
                promptTemplateService = promptService,
                defaultChatModel = model,
                messageListener = listener
            )
            val command = UserMessage("/review").copy(metadata = mapOf(UserMessage.COMMAND_EXPANSION to "Captured review constraints"))
            val previousSummary = UserMessage("Previous summary").copy(metadata = mapOf(
                "isCompactionSummary" to "true", UserMessage.COMMAND_EXPANSION to "Obsolete instructions"
            ))
            val messages = listOf(previousSummary, command)
            val context = CompactionContext(range = CompactedRange(messages.map { it.id }, 1000), currentTurnId = 2)
            val output = CompactionAgentStrategy({ services }).compactWithUsage(messages, context, model)

            assertEquals("Summary with captured command constraints", output.summary)
            val prompt = prompts.single()
            val systemTexts = prompt.instructions.filterIsInstance<SpringAiSystemMessage>().map { it.text }
            assertEquals(1, systemTexts.count { it == "Captured review constraints" })
            assertFalse(systemTexts.contains("Obsolete instructions"))
            val index = prompt.instructions.indexOfFirst { it.text == "/review" }
            assertEquals("Captured review constraints", prompt.instructions[index - 1].text)
            assertEquals(listOf(previousSummary, command), messages)
            coVerify(exactly = 0) { listener.onMessageAdded(any()) }
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
        fun `fallback includes expanded command instructions and referenced files`() = runTest {
            val command = UserMessage("/review").copy(metadata = mapOf(
                UserMessage.COMMAND_EXPANSION to "Captured constraints: preserve /project/src/App.kt behavior"
            ))
            val output = strategy.compactWithUsage(listOf(command), context, null)
            assertTrue(output.summary.contains("Captured constraints: preserve /project/src/App.kt behavior"))
            assertTrue(output.summary.contains("- /project/src/App.kt"))
            assertTrue(output.summary.contains("[user]: /review"))
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
