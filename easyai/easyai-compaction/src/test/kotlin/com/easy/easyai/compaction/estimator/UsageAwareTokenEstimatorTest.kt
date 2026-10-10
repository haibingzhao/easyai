package com.easy.easyai.compaction.estimator

import com.easy.easyai.core.message.CommandMessageProjection
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.ToolResultEntry
import com.easy.easyai.core.model.ToolResultMessage
import com.easy.easyai.core.model.Usage
import com.easy.easyai.core.model.UserMessage
import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingType
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [UsageAwareTokenEstimator], backed by the jtokkit tokenizer:
 * - [UsageAwareTokenEstimator.estimate] counts tokens per message via the tokenizer (cached).
 * - [UsageAwareTokenEstimator.estimateContextTokens] trusts the latest usage report when it
 *   falls inside the plausibility window [baseline * 0.25, baseline * 4], and falls back
 *   to pure estimation for under-reported or spiked reports.
 */
class UsageAwareTokenEstimatorTest {

    private val encoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.O200K_BASE)

    private fun textMessage(text: String) = UserMessage(text)

    private fun assistantWithUsage(
        inputTokens: Int = 0,
        outputTokens: Int = 0,
        cacheReadTokens: Int = 0,
        cacheWriteTokens: Int = 0,
        text: String = ""
    ): AssistantMessage {
        val content = if (text.isNotEmpty()) listOf(TextContent(text)) else emptyList()
        return AssistantMessage(
            content = content,
            usage = Usage(
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                cacheReadTokens = cacheReadTokens,
                cacheWriteTokens = cacheWriteTokens
            )
        )
    }

    @Nested
    inner class `estimate` {

        @Test
        fun `counts text content via tokenizer`() {
            val estimator = UsageAwareTokenEstimator()
            val text = "The quick brown fox jumps over the lazy dog."
            assertEquals(encoding.countTokens(text), estimator.estimate(listOf(textMessage(text))))
        }

        @Test
        fun `counts oversized tool result content in full`() {
            val estimator = UsageAwareTokenEstimator()
            val oversized = "x".repeat(250_000)
            val message = ToolResultMessage(
                toolResults = listOf(ToolResultEntry(toolCallId = "call_big", toolName = "search", result = oversized))
            )
            // The send layer transmits the persisted text as-is (generation-time spill bounds
            // new data), so the estimate must match the full content, not a truncated view.
            assertEquals(encoding.countTokens(oversized), estimator.estimate(listOf(message)))
        }

        @Test
        fun `sums tokens across messages and content types`() {
            val estimator = UsageAwareTokenEstimator()
            val userText = "Please run the analysis pipeline now."
            val assistantText = "Running the pipeline on the selected candidates."
            val toolOutput = "status: ok, 3 candidates matched"
            val messages = listOf(
                textMessage(userText),
                assistantWithUsage(text = assistantText),
                ToolResultMessage(
                    toolResults = listOf(
                        ToolResultEntry(toolCallId = "call_1", toolName = "search", result = toolOutput)
                    )
                )
            )
            val expected = encoding.countTokens(userText) +
                encoding.countTokens(assistantText) +
                encoding.countTokens(toolOutput)
            assertEquals(expected, estimator.estimate(messages))
        }

        @Test
        fun `is additive and stable across repeated calls`() {
            val estimator = UsageAwareTokenEstimator()
            val first = listOf(textMessage("Alpha report generated for sector rotation."))
            val second = listOf(textMessage("Beta report generated for momentum screening."))
            val combined = estimator.estimate(first + second)
            assertEquals(estimator.estimate(first) + estimator.estimate(second), combined)
            // Cached per-message results must stay stable on repeated calls
            assertEquals(combined, estimator.estimate(first + second))
        }
    }

    @Nested
    inner class `command snapshots` {

        @Test
        fun `counts expansion exactly once for raw and already projected snapshots`() {
            val estimator = UsageAwareTokenEstimator()
            val text = "/review source"
            val expansion = "Review the source using the captured server instructions."
            val command = UserMessage(text).copy(metadata = mapOf(UserMessage.COMMAND_EXPANSION to expansion))
            val messages = listOf(command)
            val expected = encoding.countTokens(text) + encoding.countTokens(expansion)
            assertEquals(expected, estimator.estimate(messages))
            assertEquals(expected, estimator.estimateContextTokens(messages))
            assertEquals(expected, estimator.estimate(CommandMessageProjection.project(messages)))
            assertEquals(listOf(command), messages)
        }

        @Test
        fun `same-id same-length expansion replacement cannot reuse stale token counts`() {
            val estimator = UsageAwareTokenEstimator()
            val text = "/review"
            val oldExpansion = "a".repeat(32)
            val newExpansion = "中".repeat(32)
            val command = UserMessage(text).copy(metadata = mapOf(UserMessage.COMMAND_EXPANSION to oldExpansion))
            assertEquals(encoding.countTokens(text) + encoding.countTokens(oldExpansion), estimator.estimate(listOf(command)))
            val updated = command.copy(metadata = mapOf(UserMessage.COMMAND_EXPANSION to newExpansion))
            assertTrue(encoding.countTokens(newExpansion) != encoding.countTokens(oldExpansion))
            assertEquals(encoding.countTokens(text) + encoding.countTokens(newExpansion), estimator.estimate(listOf(updated)))
            assertEquals(encoding.countTokens(text), estimator.estimate(listOf(updated.copy(metadata = emptyMap()))))
        }

        @Test
        fun `usage anchor includes only trailing expansion delta instead of adding old commands again`() {
            val estimator = UsageAwareTokenEstimator()
            val command = UserMessage("/first").copy(metadata = mapOf(
                UserMessage.COMMAND_EXPANSION to "Previously submitted command instructions. ".repeat(200)
            ))
            val baseline = estimator.estimate(listOf(command))
            // The assistant has no persisted content, so it contributes 0; its reported
            // outputTokens=10 is phantom and must NOT be added to the next-request estimate.
            val assistant = assistantWithUsage(inputTokens = baseline, outputTokens = 10)
            val trailing = UserMessage("/next").copy(metadata = mapOf(UserMessage.COMMAND_EXPANSION to "Next command snapshot"))
            val messages = listOf(command, assistant, trailing)
            val expected = baseline + estimator.estimate(listOf(trailing))
            assertEquals(expected, estimator.estimateContextTokens(messages))
            assertEquals(expected, estimator.estimateContextTokens(CommandMessageProjection.project(messages)))
        }

        @Test
        fun `summary with legacy command metadata only counts summary text`() {
            val estimator = UsageAwareTokenEstimator()
            val summary = UserMessage("Compacted command context").copy(metadata = mapOf(
                "isCompactionSummary" to "true",
                UserMessage.COMMAND_EXPANSION to "Obsolete snapshot ".repeat(100)
            ))
            assertEquals(encoding.countTokens("Compacted command context"), estimator.estimate(listOf(summary)))
        }
    }

    @Nested
    inner class `estimateContextTokens` {

        @Test
        fun `trusts plausible usage report plus tokenizer delta`() {
            val estimator = UsageAwareTokenEstimator()
            val user = textMessage("Initial research request about semiconductor supply chains.")
            val assistant = assistantWithUsage(text = "Here is the first pass analysis result.")
            val trailing = textMessage("Follow-up question about the latest earnings.")

            // Realistic accounting: usage.inputTokens is the input that PRODUCED the assistant
            // turn (everything before it), so it excludes the assistant's own output.
            val userTokens = estimator.estimate(listOf(user))
            val assistantTokens = estimator.estimate(listOf(assistant))
            val withUsage = assistant.copy(
                usage = Usage(inputTokens = userTokens, outputTokens = 120)
            )
            val reported = userTokens + assistantTokens
            val expected = reported + estimator.estimate(listOf(trailing))
            assertEquals(expected, estimator.estimateContextTokens(listOf(user, withUsage, trailing)))
        }

        @Test
        fun `excludes phantom reasoning output tokens from the reported context`() {
            val estimator = UsageAwareTokenEstimator()
            // Reasoning model: usage.outputTokens counts ephemeral thinking that is neither
            // persisted nor re-sent, so it must not inflate the next-request estimate and
            // trip compaction prematurely.
            val longText = "The quick brown fox jumps over the lazy dog. ".repeat(400)
            val user = textMessage(longText)
            val assistant = assistantWithUsage(text = "Short visible answer.")
            val userTokens = estimator.estimate(listOf(user))
            val assistantTokens = estimator.estimate(listOf(assistant))
            val withUsage = assistant.copy(
                usage = Usage(inputTokens = userTokens, outputTokens = 50_000) // 50k phantom reasoning
            )
            // reported = userTokens + assistantTokens; the 50k output is ignored (the old
            // userTokens + 50_000 would have far exceeded the real re-sendable context).
            assertEquals(userTokens + assistantTokens, estimator.estimateContextTokens(listOf(user, withUsage)))
        }

        @Test
        fun `falls back to pure estimate when report is implausibly low`() {
            val estimator = UsageAwareTokenEstimator()
            // Long transcript so the content baseline is well above the 500-token guard
            val longText = "The quick brown fox jumps over the lazy dog. ".repeat(400)
            val user = textMessage(longText)
            val assistant = assistantWithUsage(inputTokens = 100, outputTokens = 50)
            val messages = listOf(user, assistant)

            // 150 reported is far below baseline * 0.25 -> gateway under-reporting -> pure estimate
            assertEquals(estimator.estimate(messages), estimator.estimateContextTokens(messages))
        }

        @Test
        fun `falls back to pure estimate when report is implausibly high`() {
            val estimator = UsageAwareTokenEstimator()
            // Long enough for the baseline to exceed the 500-token guard
            val longText = "Market liquidity conditions tightened across emerging economies. ".repeat(200)
            val user = textMessage(longText)
            val baseline = estimator.estimate(listOf(user))
            // Spike well above baseline * 4 -> reporting anomaly -> pure estimate
            val spiked = assistantWithUsage(inputTokens = baseline * 10, outputTokens = 50)
            val messages = listOf(user, spiked)

            assertEquals(estimator.estimate(messages), estimator.estimateContextTokens(messages))
        }

        @Test
        fun `trusts report directly when baseline is small`() {
            val estimator = UsageAwareTokenEstimator()
            // Tiny baseline (<= 500 tokens): window check is skipped, report trusted as-is.
            // The assistant carries no persisted content, so reported = inputTokens(50) + 0;
            // the phantom outputTokens=10 is excluded.
            val messages = listOf(textMessage("hi"), assistantWithUsage(inputTokens = 50, outputTokens = 10))
            assertEquals(50, estimator.estimateContextTokens(messages))
        }

        @Test
        fun `uses pure estimate when no usage data exists`() {
            val estimator = UsageAwareTokenEstimator()
            val messages = listOf(textMessage("No usage data anywhere in this transcript."))
            assertEquals(estimator.estimate(messages), estimator.estimateContextTokens(messages))
        }

        @Test
        fun `includes cache tokens in the reported total`() {
            val estimator = UsageAwareTokenEstimator()
            val user = textMessage("Cache-heavy conversation with lots of prior context.")
            val assistant = assistantWithUsage(text = "Answer with cache accounting.")
            val userTokens = estimator.estimate(listOf(user))
            val assistantTokens = estimator.estimate(listOf(assistant))
            assertTrue(userTokens > 0)

            val withUsage = assistant.copy(
                // input + cacheRead sum to the prior context (the user turn); split by parity.
                usage = Usage(inputTokens = userTokens / 2, cacheReadTokens = userTokens - userTokens / 2, outputTokens = 10)
            )
            // reported = totalInput(input + cacheRead) + assistant's own content; outputTokens ignored.
            assertEquals(userTokens + assistantTokens, estimator.estimateContextTokens(listOf(user, withUsage)))
        }
    }
}
