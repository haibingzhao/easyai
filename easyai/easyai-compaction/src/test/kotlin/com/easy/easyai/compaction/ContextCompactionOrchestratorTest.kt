package com.easy.easyai.compaction

import com.easy.easyai.compaction.estimator.UsageAwareTokenEstimator
import com.easy.easyai.compaction.strategy.CompactionAgentStrategy
import com.easy.easyai.compaction.strategy.CompactionStrategy
import com.easy.easyai.compaction.strategy.StrategyOutput
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.event.AgentEvent
import com.easy.easyai.core.event.CompactionEndEvent
import com.easy.easyai.core.message.CommandMessageProjection
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.SystemMessage
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.ToolCallContent
import com.easy.easyai.core.model.ToolResultEntry
import com.easy.easyai.core.model.ToolResultMessage
import com.easy.easyai.core.model.Usage
import com.easy.easyai.core.model.UserMessage
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [ContextCompactionOrchestrator] structural guarantees:
 * - tail guard keeps the tool-calling assistant paired with its tool results
 * - currentTokens stays non-zero and within the context window after compaction
 */
class ContextCompactionOrchestratorTest {

    private val estimator = UsageAwareTokenEstimator()
    private val agentContext = AgentContext(agentId = "test-agent", sessionId = "test-session")

    private fun orchestrator(strategy: CompactionStrategy, config: CompactionConfig = CompactionConfig(tailTurns = 1)) =
        ContextCompactionOrchestrator(
            config = config,
            strategy = strategy,
            tokenEstimator = estimator
        )

    private fun summaryStrategy(summary: String = "SUMMARY"): CompactionStrategy {
        val strategy = mockk<CompactionStrategy>()
        coEvery { strategy.compactWithUsage(any(), any(), isNull(), any()) } returns StrategyOutput(summary)
        return strategy
    }

    private fun collectingPusher(events: MutableList<AgentEvent>) =
        ContextCompactionOrchestrator.EventPusher { events.add(it) }

    @Nested
    inner class `tail structure protection` {

        @Test
        fun `keeps tool-calling assistant paired with its tool result even over budget`() = runTest {
            // Oversized tool result so budget trimming would otherwise orphan it at the tail head
            val bigResult = "r".repeat(30_000)
            val user1 = UserMessage("First request " + "a".repeat(2_000))
            val assistant1 = AssistantMessage(
                content = listOf(TextContent("calling tool"), ToolCallContent("c1", "search", "{}"))
            )
            val toolResult1 = ToolResultMessage(
                toolResults = listOf(ToolResultEntry("c1", "search", "first result"))
            )
            val user2 = UserMessage("Follow-up request")
            val assistant2 = AssistantMessage(
                content = listOf(TextContent("calling tool again"), ToolCallContent("c2", "search", "{}"))
            )
            val toolResult2 = ToolResultMessage(
                toolResults = listOf(ToolResultEntry("c2", "search", bigResult))
            )
            val messages = listOf(user1, assistant1, toolResult1, user2, assistant2, toolResult2)

            // preserve budget clamps to the 2000-token floor, far below toolResult2's size
            val orchestrator = orchestrator(summaryStrategy())
            val events = mutableListOf<AgentEvent>()
            val result = orchestrator.compact(
                agentContext, messages, turnId = 1, modelContextLength = 18_000,
                eventScope = collectingPusher(events)
            )

            val resultIds = result.map { it.id }
            assertTrue(assistant2.id in resultIds, "assistant that issued the tool calls must stay in the tail")
            assertTrue(toolResult2.id in resultIds, "tool result must stay paired with its assistant")
            // Pairing must be adjacent: assistant immediately before its tool result
            assertEquals(resultIds.indexOf(assistant2.id) + 1, resultIds.indexOf(toolResult2.id))
            // The earlier turn was compacted away and replaced by the summary
            assertFalse(assistant1.id in resultIds)
            assertTrue(result.any { it is UserMessage && it.metadata["isCompactionSummary"] == "true" })
        }

        @Test
        fun `leaves well-sized tails untouched`() = runTest {
            val user1 = UserMessage("First request " + "a".repeat(2_000))
            val assistant1 = AssistantMessage(content = listOf(TextContent("first answer")))
            val user2 = UserMessage("Follow-up request")
            val assistant2 = AssistantMessage(content = listOf(TextContent("second answer")))
            val messages = listOf(user1, assistant1, user2, assistant2)

            val orchestrator = orchestrator(summaryStrategy())
            val result = orchestrator.compact(
                agentContext, messages, turnId = 1, modelContextLength = 202_752
            )

            val resultIds = result.map { it.id }
            assertTrue(user2.id in resultIds)
            assertTrue(assistant2.id in resultIds)
            assertFalse(assistant1.id in resultIds)
        }
    }

    @Nested
    inner class `command compaction lifecycle` {

        @Test
        fun `compacted commands become summary text without reviving metadata on later rounds`() = runTest {
            val prefix = UserMessage("Initial request")
            val firstAnswer = AssistantMessage(content = listOf(TextContent("Initial answer")))
            val oldCommand = UserMessage("/old").copy(metadata = mapOf(UserMessage.COMMAND_EXPANSION to "Captured old constraints"))
            val oldAnswer = AssistantMessage(content = listOf(TextContent("Old command done")))
            val recentCommand = UserMessage("/recent").copy(metadata = mapOf(UserMessage.COMMAND_EXPANSION to "Captured recent constraints"))
            val recentAnswer = AssistantMessage(content = listOf(TextContent("Recent command done")))
            val messages = listOf(prefix, firstAnswer, oldCommand, oldAnswer, recentCommand, recentAnswer)
            val compactedIds = mutableListOf<List<String>>()
            val savedSummaries = mutableListOf<UserMessage>()
            val listener = mockk<CompactionListener>()
            coEvery {
                listener.onMessagesCompacted(any(), capture(compactedIds), capture(savedSummaries), any(), any(), any(), any(), any(), any(), any(), any())
            } returns Unit
            val strategy = CompactionAgentStrategy({ throw IllegalStateException("Use local fallback") })
            val orchestrator = ContextCompactionOrchestrator(CompactionConfig(tailTurns = 1), strategy, estimator, listener)
            val first = orchestrator.compact(agentContext, messages, 1, 202_752)
            val firstSummary = first.filterIsInstance<UserMessage>().single { it.metadata["isCompactionSummary"] == "true" }

            assertTrue(firstSummary.content.filterIsInstance<TextContent>().single().text.contains("Captured old constraints"))
            assertEquals(mapOf("isCompactionSummary" to "true"), firstSummary.metadata)
            assertFalse(first.any { it.id == oldCommand.id })
            assertEquals(listOf("Captured recent constraints"), CommandMessageProjection.project(first).filterIsInstance<SystemMessage>().map { it.text })
            assertEquals(listOf(firstAnswer.id, oldCommand.id, oldAnswer.id), compactedIds.single())
            assertEquals(listOf(firstSummary), savedSummaries)
            assertTrue(first.none { it is SystemMessage })

            val next = first + UserMessage("Newest question")
            val second = orchestrator.compact(agentContext, next, 2, 202_752)
            assertTrue(CommandMessageProjection.project(second).none { it is SystemMessage })
            assertFalse(second.any { it.id == recentCommand.id })
            assertEquals(2, savedSummaries.size)
            assertTrue(savedSummaries.all { it.metadata == mapOf("isCompactionSummary" to "true") })
            assertTrue(compactedIds.flatten().none { it.startsWith("command_expansion_") })
            assertEquals("Captured old constraints", oldCommand.metadata[UserMessage.COMMAND_EXPANSION])
        }

        @Test
        fun `token savings include the compacted expansion exactly once`() = runTest {
            val prefix = UserMessage("Initial request")
            val command = UserMessage("/large").copy(metadata = mapOf(
                UserMessage.COMMAND_EXPANSION to "Long command instructions that must count toward context. ".repeat(200)
            ))
            val answer = AssistantMessage(content = listOf(TextContent("Done")))
            val recent = UserMessage("Continue")
            val messages = listOf(prefix, command, answer, recent)
            val events = mutableListOf<AgentEvent>()
            val result = orchestrator(summaryStrategy()).compact(
                agentContext, messages, 1, 202_752, eventScope = collectingPusher(events)
            )
            val summary = result.filterIsInstance<UserMessage>().single { it.metadata["isCompactionSummary"] == "true" }
            val expectedSavings = estimator.estimate(listOf(command, answer)) - estimator.estimate(listOf(summary))
            assertTrue(expectedSavings > 0)
            assertEquals(expectedSavings, events.filterIsInstance<CompactionEndEvent>().single().tokensSaved)
        }
    }

    @Nested
    inner class `currentTokens accounting` {

        @Test
        fun `stays non-zero and within the context window after compaction`() = runTest {
            val user1 = UserMessage("Initial analysis request " + "b".repeat(2_000))
            val assistant1 = AssistantMessage(
                content = listOf(TextContent("analysis output")),
                usage = Usage(inputTokens = 3_000, outputTokens = 200)
            )
            val user2 = UserMessage("Follow-up question")
            val messages = listOf(user1, assistant1, user2)

            val orchestrator = orchestrator(summaryStrategy())
            val events = mutableListOf<AgentEvent>()
            orchestrator.compact(
                agentContext, messages, turnId = 1, modelContextLength = 202_752,
                eventScope = collectingPusher(events)
            )

            val endEvent = events.filterIsInstance<CompactionEndEvent>().single()
            assertTrue(endEvent.currentTokens > 0, "currentTokens must stay positive so the UI can update")
            assertTrue(endEvent.currentTokens <= 202_752, "currentTokens must not exceed the context window")
            assertTrue(endEvent.tokensSaved >= 0)
        }

        @Test
        fun `floors currentTokens at the estimated result size when savings exceed baseline`() = runTest {
            // Usage-based baseline is small; a large compacted region could otherwise drive
            // before - saved negative. The floor keeps currentTokens at result size.
            val user1 = UserMessage("short request")
            val assistant1 = AssistantMessage(
                content = listOf(TextContent("verbose output " + "c".repeat(10_000))),
                usage = Usage(inputTokens = 100, outputTokens = 50)
            )
            val user2 = UserMessage("Follow-up question")
            val messages = listOf(user1, assistant1, user2)

            val orchestrator = orchestrator(summaryStrategy())
            val events = mutableListOf<AgentEvent>()
            val result = orchestrator.compact(
                agentContext, messages, turnId = 1, modelContextLength = 202_752,
                eventScope = collectingPusher(events)
            )

            val endEvent = events.filterIsInstance<CompactionEndEvent>().single()
            val floor = estimator.estimate(result)
            assertTrue(endEvent.currentTokens >= floor, "currentTokens must not drop below the result estimate")
        }
    }
}
