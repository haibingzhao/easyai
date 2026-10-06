package com.easy.easyai.compaction

import com.easy.easyai.compaction.estimator.TokenEstimator
import com.easy.easyai.compaction.strategy.CompactionStrategy
import com.easy.easyai.compaction.strategy.StrategyOutput
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.CompactionTriggerType
import com.easy.easyai.core.agent.TransformContextInput
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.ToolCallContent
import com.easy.easyai.core.model.ToolResultContent
import com.easy.easyai.core.model.ToolResultEntry
import com.easy.easyai.core.model.ToolResultMessage
import com.easy.easyai.core.model.UserMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the tool-fold coupling of [CompactionTransformContextService]: the trigger measures the
 * optional [TransformContextInput.compactionMeasureMessages] view (the folded projection actually
 * sent to the LLM), while selection and summarization keep operating on the original transcript.
 */
class CompactionTransformContextServiceFoldTest {

    private val agentContext = AgentContext(agentId = "test-agent", sessionId = "test-session")

    /** Char/4 estimator without usage anchoring, so the thresholds below are explicit. */
    private val estimator = object : TokenEstimator {
        override fun estimate(messages: List<EasyAiMessage>): Int =
            messages.sumOf { message -> message.content.sumOf { block -> textOf(block).length } } / 4
    }

    private fun textOf(block: com.easy.easyai.core.model.ContentBlock): String = when (block) {
        is TextContent -> block.text
        is ToolCallContent -> block.arguments
        is ToolResultContent -> block.output
        else -> ""
    }

    private val config = CompactionConfig(
        enabled = true,
        threshold = 0.8,
        reservedTokens = 0,
        tailTurns = 1,
        minMessagesForCompaction = 1
    )

    private fun strategyInvoking(seen: MutableList<List<EasyAiMessage>>): CompactionStrategy {
        val strategy = mockk<CompactionStrategy>()
        val messagesSlot = slot<List<EasyAiMessage>>()
        coEvery { strategy.compactWithUsage(capture(messagesSlot), any(), any(), any()) } answers {
            seen.add(messagesSlot.captured)
            StrategyOutput("SUMMARY")
        }
        return strategy
    }

    /** Two runs; the first carries a bulky tool result and a marker proving originality. */
    private fun originals(): List<EasyAiMessage> = listOf(
        UserMessage(content = listOf(TextContent("first request"))),
        AssistantMessage(
            content = listOf(TextContent("calling tool"), ToolCallContent("c1", "search", "{\"query\":\"x\"}"))
        ),
        ToolResultMessage(
            toolResults = listOf(ToolResultEntry("c1", "search", "O".repeat(30_000) + " ORIGINAL_BODY_MARKER"))
        ),
        AssistantMessage(content = listOf(TextContent("first answer"))),
        UserMessage(content = listOf(TextContent("second request"))),
        AssistantMessage(content = listOf(TextContent("second answer")))
    )

    /** Folded projection of [originals]: placeholders instead of bulky bodies. */
    private fun foldedView(): List<EasyAiMessage> = listOf(
        UserMessage(content = listOf(TextContent("first request"))),
        AssistantMessage(
            content = listOf(
                TextContent("calling tool"),
                ToolCallContent("c1", "search", "[tool: search query=x (details folded)]")
            )
        ),
        ToolResultMessage(
            toolResults = listOf(
                ToolResultEntry("c1", "search", "[tool result: search ok, 30019 chars folded; recall: ref]")
            )
        ),
        AssistantMessage(content = listOf(TextContent("first answer"))),
        UserMessage(content = listOf(TextContent("second request"))),
        AssistantMessage(content = listOf(TextContent("second answer")))
    )

    private fun service(strategy: CompactionStrategy) = CompactionTransformContextService(
        config = config,
        strategy = strategy,
        tokenEstimator = estimator
    )

    private fun input(
        messages: List<EasyAiMessage>,
        measureView: List<EasyAiMessage>?,
        modelContextLength: Int
    ) = TransformContextInput(
        agentContext = agentContext,
        messages = messages,
        turnId = 1,
        modelContextLength = modelContextLength,
        compactionTriggerType = CompactionTriggerType.Auto,
        compactionMeasureMessages = measureView
    )

    @Nested
    inner class TriggerMeasurement {

        @Test
        fun `skips compaction when the folded view fits although originals overflow`() = runTest {
            val seen = mutableListOf<List<EasyAiMessage>>()
            val strategy = strategyInvoking(seen)
            val messages = originals()
            assertTrue(estimator.estimate(messages) > estimator.estimate(foldedView()))

            val result = service(strategy).transform(input(messages, foldedView(), modelContextLength = 4_000))

            assertEquals(messages, result)
            coVerify(exactly = 0) { strategy.compactWithUsage(any(), any(), any(), any()) }
        }

        @Test
        fun `compacts on the originals when no measure view is given`() = runTest {
            val seen = mutableListOf<List<EasyAiMessage>>()
            val strategy = strategyInvoking(seen)

            service(strategy).transform(input(originals(), null, modelContextLength = 4_000))

            assertTrue(seen.isNotEmpty(), "expected the compaction strategy to run")
        }

        @Test
        fun `summarizes originals even when the folded view drives the trigger`() = runTest {
            val seen = mutableListOf<List<EasyAiMessage>>()
            val strategy = strategyInvoking(seen)
            // An equally oversized view, so the trigger fires while originals stay the summary input
            val overflowingView = foldedView().map { message ->
                if (message is AssistantMessage) {
                    message.copy(content = message.content + TextContent("V".repeat(20_000)))
                } else {
                    message
                }
            }

            service(strategy).transform(input(originals(), overflowingView, modelContextLength = 4_000))

            assertTrue(seen.isNotEmpty(), "expected the compaction strategy to run")
            val compactedText = seen.first().joinToString("\n") { message ->
                message.content.joinToString("") { block -> textOf(block) }
            }
            assertTrue(compactedText.contains("ORIGINAL_BODY_MARKER"), "summary input must be the unfolded transcript")
            assertTrue(!compactedText.contains("chars folded"), "summary input must not be the folded view")
        }
    }
}
