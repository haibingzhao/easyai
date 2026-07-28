package com.easy.easyai.compaction.estimator

import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.ThinkingContent
import com.easy.easyai.core.model.Usage
import com.easy.easyai.core.model.UserMessage
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [UsageAwareTokenEstimator], focusing on the gateway under-reporting defense:
 * some LLM proxy gateways return usage reports that dramatically undercount the real
 * prompt size (e.g. ~17K reported for a ~148K prompt) on the first request after a
 * prompt-cache invalidation. The estimator must not trust such reports for compaction
 * trigger decisions.
 */
class UsageAwareTokenEstimatorTest {

    private fun textMessage(chars: Int) = UserMessage("x".repeat(chars))

    private fun assistantWithUsage(
        inputTokens: Int = 0,
        outputTokens: Int = 0,
        cacheReadTokens: Int = 0,
        cacheWriteTokens: Int = 0,
        textChars: Int = 0
    ): AssistantMessage {
        val content = if (textChars > 0) listOf(TextContent("y".repeat(textChars))) else emptyList()
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
    inner class `estimateContextTokens` {

        @Test
        fun `uses exact input tokens plus delta estimate when report is plausible`() {
            val estimator = UsageAwareTokenEstimator()
            // 8000 prompt chars -> plausibility floor = 8000 / 8 = 1000 tokens
            val messages = listOf(
                textMessage(8000),
                assistantWithUsage(inputTokens = 5000, cacheReadTokens = 2000, cacheWriteTokens = 500, outputTokens = 300)
            )
            // base = 5000 + 2000 + 500 + 300 = 7800 >= 1000 -> plausible
            // delta = 0 (nothing after the assistant)
            assertEquals(7800, estimator.estimateContextTokens(messages))
        }

        @Test
        fun `adds delta for messages after the usage report`() {
            val estimator = UsageAwareTokenEstimator()
            // assistant with usage, followed by a new user message
            val messages = listOf(
                textMessage(8000),
                assistantWithUsage(inputTokens = 5000, outputTokens = 200, textChars = 700),
                textMessage(3500) // new user message after the assistant
            )
            // base = 5000 + 200 = 5200, delta = estimate([user(3500 chars)]) = 3500/3.5 = 1000
            assertEquals(5200 + 1000, estimator.estimateContextTokens(messages))
        }

        @Test
        fun `walks back to older usage when latest report is implausibly low`() {
            val estimator = UsageAwareTokenEstimator()
            // floor = 8000 / 8 = 1000
            val older = assistantWithUsage(inputTokens = 5000, outputTokens = 500) // totalInput 5000, plausible
            val newer = assistantWithUsage(inputTokens = 100, outputTokens = 50) // totalInput 100, anomalous
            val messages = listOf(textMessage(8000), older, newer)
            // newest (100) fails the floor -> walk back to older
            // base = 5000 + 500 = 5500, delta = estimate([newer(empty)]) = 0
            assertEquals(5500, estimator.estimateContextTokens(messages))
        }

        @Test
        fun `falls back to char-based estimate when all reports are implausibly low`() {
            val estimator = UsageAwareTokenEstimator()
            // floor = 8000 / 8 = 1000; reported totalInput 100 is below it
            val messages = listOf(textMessage(8000), assistantWithUsage(inputTokens = 100, outputTokens = 50))
            val result = estimator.estimateContextTokens(messages)
            // must not trust the anomalous 100; falls back to char-based estimate
            assertTrue(result > 150, "expected fallback > anomalous report, got $result")
            assertEquals(estimator.estimate(messages), result)
        }

        @Test
        fun `falls back to char-based estimate when no usage data exists`() {
            val estimator = UsageAwareTokenEstimator()
            val messages = listOf(textMessage(8000))
            assertEquals(estimator.estimate(messages), estimator.estimateContextTokens(messages))
        }

        @Test
        fun `excludes thinking blocks from plausibility floor`() {
            val estimator = UsageAwareTokenEstimator()
            // 80 text chars -> floor = 80 / 8 = 10.
            // 8000 thinking chars must NOT inflate the floor (thinking is not sent back as input).
            val thinking = AssistantMessage(
                content = listOf(ThinkingContent("z".repeat(8000))),
                usage = Usage(inputTokens = 40, outputTokens = 10) // totalInput 40 >= 10 -> plausible
            )
            val messages = listOf(textMessage(80), thinking)
            // If thinking were counted in the floor, it would be (80 + 8000) / 8 = 1010 and 40 would be
            // wrongly rejected. The usage-based path is used (not char-based fallback).
            // base = 40 + 10 = 50, delta = 0 (nothing after)
            assertEquals(50, estimator.estimateContextTokens(messages))
        }
    }

    @Nested
    inner class `updateRatio` {

        @Test
        fun `calibrates ratio from plausible usage`() {
            val estimator = UsageAwareTokenEstimator()
            // 7000 chars, totalInputTokens = 1000 -> floor = 875, plausible -> ratio = 7.0
            val messages = listOf(textMessage(7000), assistantWithUsage(inputTokens = 1000))
            estimator.updateRatio(messages)
            // estimate now uses calibrated ratio: 7000 / 7.0 = 1000 (default would be 7000 / 3.5 = 2000)
            assertEquals(1000, estimator.estimate(messages))
        }

        @Test
        fun `skips calibration when usage report is implausibly low`() {
            val estimator = UsageAwareTokenEstimator()
            // 8000 chars, totalInputTokens = 100 -> floor = 1000, anomalous -> keep default ratio 3.5
            val messages = listOf(textMessage(8000), assistantWithUsage(inputTokens = 100))
            estimator.updateRatio(messages)
            assertEquals((8000 / 3.5).toInt(), estimator.estimate(messages))
        }
    }
}
