package com.easy.easyai.compaction.estimator

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
    inner class `estimateContextTokens` {

        @Test
        fun `trusts plausible usage report plus tokenizer delta`() {
            val estimator = UsageAwareTokenEstimator()
            val user = textMessage("Initial research request about semiconductor supply chains.")
            val assistant = assistantWithUsage(text = "Here is the first pass analysis result.")
            // Pick a reported input that lies inside the plausibility window
            val baseline = estimator.estimate(listOf(user, assistant))
            val trailing = textMessage("Follow-up question about the latest earnings.")
            val messages = listOf(user, assistant, trailing)

            val outputTokens = 120
            val reported = baseline + outputTokens
            val withUsage = assistant.copy(
                usage = Usage(inputTokens = baseline, outputTokens = outputTokens)
            )
            val expected = reported + estimator.estimate(listOf(trailing))
            assertEquals(expected, estimator.estimateContextTokens(listOf(user, withUsage, trailing)))
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
            // Tiny baseline (<= 500 tokens): window check is skipped, report trusted as-is
            val messages = listOf(textMessage("hi"), assistantWithUsage(inputTokens = 50, outputTokens = 10))
            assertEquals(60, estimator.estimateContextTokens(messages))
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
            val baseline = estimator.estimate(listOf(user, assistant))
            assertTrue(baseline > 0)

            val withUsage = assistant.copy(
                // Split so input + cacheRead sums to exactly `baseline` regardless of parity
                usage = Usage(inputTokens = baseline / 2, cacheReadTokens = baseline - baseline / 2, outputTokens = 10)
            )
            // totalInput = input + cacheRead (+ cacheWrite=0) stays inside the window
            assertEquals(baseline + 10, estimator.estimateContextTokens(listOf(user, withUsage)))
        }
    }
}
