package com.easy.easyai.compaction.estimator

import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.Usage
import com.easy.easyai.core.model.UserMessage
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Tests for [UsageAwareTokenEstimator], focusing on the monotonicity-based anomaly detection:
 * without compaction, context only grows so inputTokens must be non-decreasing.
 * If latest < previous, the estimator uses previous + delta of intervening messages.
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
            val messages = listOf(
                textMessage(8000),
                assistantWithUsage(inputTokens = 5000, cacheReadTokens = 2000, cacheWriteTokens = 500, outputTokens = 300)
            )
            // single assistant with usage -> trust directly
            // base = 5000 + 2000 + 500 + 300 = 7800, delta = 0 (nothing after)
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
        fun `anomaly uses previous plus delta when latest is lower`() {
            val estimator = UsageAwareTokenEstimator()
            val older = assistantWithUsage(inputTokens = 5000, outputTokens = 500) // totalInput 5000
            val newer = assistantWithUsage(inputTokens = 100, outputTokens = 50) // totalInput 100, anomalous
            val messages = listOf(textMessage(8000), older, newer)
            // latest(100) < previous(5000) -> anomaly -> use previous + deltaAfter(previous)
            // base = 5000 + 500 = 5500, delta = estimate([newer(empty content)]) = 0
            assertEquals(5500, estimator.estimateContextTokens(messages))
        }

        @Test
        fun `single assistant with low usage is trusted directly`() {
            val estimator = UsageAwareTokenEstimator()
            // Only one assistant with usage -> trust directly, no comparison needed
            val messages = listOf(textMessage(8000), assistantWithUsage(inputTokens = 100, outputTokens = 50))
            // base = 100 + 50 = 150, delta = 0 (assistant is last)
            assertEquals(150, estimator.estimateContextTokens(messages))
        }

        @Test
        fun `trusts latest when latest is not less than previous`() {
            val estimator = UsageAwareTokenEstimator()
            val previous = assistantWithUsage(inputTokens = 3000, outputTokens = 200)
            val latest = assistantWithUsage(inputTokens = 5000, outputTokens = 300)
            val messages = listOf(textMessage(8000), previous, latest)
            // latest(5000) >= previous(3000) -> trust latest
            // base = 5000 + 300 = 5300, delta = 0 (latest is last)
            assertEquals(5300, estimator.estimateContextTokens(messages))
        }

        @Test
        fun `falls back to char-based estimate when no usage data exists`() {
            val estimator = UsageAwareTokenEstimator()
            val messages = listOf(textMessage(8000))
            assertEquals(estimator.estimate(messages), estimator.estimateContextTokens(messages))
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
