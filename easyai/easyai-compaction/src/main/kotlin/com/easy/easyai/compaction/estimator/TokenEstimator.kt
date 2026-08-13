package com.easy.easyai.compaction.estimator

import com.easy.easyai.core.model.*
import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.EncodingType
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Interface for estimating token counts from message lists.
 * Implementations should balance accuracy vs performance.
 */
interface TokenEstimator {
    /**
     * Estimate total token count for the given messages.
     * Used for sizing individual messages or subsets (e.g., recent message trimming).
     */
    fun estimate(messages: List<EasyAiMessage>): Int

    /**
     * Estimate the current context window token count.
     * Anchors on the latest AssistantMessage usage report, validated against a
     * plausibility window derived from content estimation; falls back to pure
     * [estimate] when the report is implausible or absent.
     */
    fun estimateContextTokens(messages: List<EasyAiMessage>): Int = estimate(messages)
}

/**
 * Token estimator backed by a real tokenizer (jtokkit), anchored on actual LLM usage data.
 *
 * Strategy:
 * - [estimate]: per-message tokenizer counting (O200K_BASE encoding), cached by message id
 *   plus a content-char fingerprint. Tool result content is counted in full: the send layer
 *   transmits the persisted text as-is (oversized results are spilled to the temp dir and
 *   replaced with a small pointer notice at generation time, so persisted text is bounded
 *   in practice), so the estimate matches what is actually transmitted to the model.
 * - [estimateContextTokens]: trusts the latest AssistantMessage usage report
 *   (input + cacheRead + cacheWrite + output) plus a tokenizer-estimated delta of messages
 *   after it. The report is rejected and replaced by pure [estimate] when it falls outside
 *   the plausibility window `[baseline * 0.25, baseline * 4]`, guarding against gateway
 *   under-reporting (e.g., message_delta events missing input_tokens) and reporting spikes.
 */
class UsageAwareTokenEstimator : TokenEstimator {

    private val logger = LoggerFactory.getLogger(UsageAwareTokenEstimator::class.java)

    /**
     * Token counts cached per `messageId:contentChars`. The char fingerprint keeps entries
     * correct on the rare paths that replace content under the same message id (pending
     * tool-result merge on resume, steering message updates), without invalidation hooks.
     * Bounded: the whole cache is dropped when it exceeds [MAX_CACHE_ENTRIES] (it is a
     * pure accelerator, so clearing never affects correctness).
     */
    private val tokenCountCache = ConcurrentHashMap<String, Int>()

    /**
     * Estimate total token count for the given messages via tokenizer counting.
     * Per-message results are cached by message id.
     */
    override fun estimate(messages: List<EasyAiMessage>): Int =
        messages.sumOf { msg -> estimateMessage(msg) }

    /**
     * Estimate the current context window token count.
     *
     * - no usage report → pure tokenizer [estimate]
     * - report within plausibility window → trust report + tokenizer delta of trailing messages
     * - report implausibly low (gateway under-reporting) or high (reporting spike) →
     *   WARN and fall back to pure tokenizer [estimate]
     *
     * The plausibility window is only enforced when the content baseline exceeds
     * [MIN_BASELINE_FOR_CHECK]; tiny baselines would degenerate the interval.
     */
    override fun estimateContextTokens(messages: List<EasyAiMessage>): Int {
        val lastUsageIndex = messages.indexOfLast { msg ->
            msg is AssistantMessage && totalInputTokens(msg.usage) > 0
        }
        if (lastUsageIndex < 0) {
            val estimated = estimate(messages)
            logger.debug("Context estimate: no usage report, pure estimate={}", estimated)
            return estimated
        }

        val assistant = messages[lastUsageIndex] as AssistantMessage
        val reported = totalInputTokens(assistant.usage) + assistant.usage.outputTokens
        val baseline = estimate(messages.subList(0, lastUsageIndex + 1))

        if (baseline > MIN_BASELINE_FOR_CHECK &&
            (reported < baseline * LOW_REPORT_RATIO || reported > baseline * HIGH_REPORT_RATIO)
        ) {
            val estimated = estimate(messages)
            logger.warn(
                "Usage report implausible: reported={} outside window [{}, {}] (baseline={}), " +
                    "falling back to pure estimate={}",
                reported,
                (baseline * LOW_REPORT_RATIO).toInt(),
                (baseline * HIGH_REPORT_RATIO).toInt(),
                baseline,
                estimated
            )
            return estimated
        }

        val delta = deltaAfter(lastUsageIndex, messages)
        logger.debug(
            "Context estimate: trusted usage, reported={}, baseline={}, delta={}",
            reported, baseline, delta
        )
        return reported + delta
    }

    private fun estimateMessage(message: EasyAiMessage): Int {
        val id = message.id
        if (id.isEmpty()) return countContentTokens(message.content)
        val key = "$id:${contentChars(message.content)}"
        if (tokenCountCache.size >= MAX_CACHE_ENTRIES) tokenCountCache.clear()
        return tokenCountCache.computeIfAbsent(key) { countContentTokens(message.content) }
    }

    private fun contentChars(content: List<ContentBlock>): Int = content.sumOf { block ->
        when (block) {
            is TextContent -> block.text.length
            is ThinkingContent -> block.thinking.length
            is ToolCallContent -> block.arguments.length
            is ToolResultContent -> block.output.length
            else -> 0
        }
    }

    private fun countContentTokens(content: List<ContentBlock>): Int = content.sumOf { block ->
        val text = when (block) {
            is TextContent -> block.text
            is ThinkingContent -> block.thinking
            is ToolCallContent -> block.arguments
            // Count the full persisted text: the send layer transmits tool results as-is.
            // Oversized results are spilled at generation time (ToolResultGuard), so new
            // data is bounded; historic oversized entries are counted in full so the
            // window estimate does not under-report them.
            is ToolResultContent -> block.output
            else -> null
        }
        countTokens(text)
    }

    private fun countTokens(text: String?): Int {
        if (text.isNullOrEmpty()) return 0
        return try {
            encoding.countTokens(text)
        } catch (ex: RuntimeException) {
            logger.warn("Tokenizer counting failed for {} chars, falling back to char heuristic", text.length, ex)
            (text.length / DEFAULT_CHARS_PER_TOKEN).toInt()
        }
    }

    private fun deltaAfter(usageIndex: Int, messages: List<EasyAiMessage>): Int =
        if (usageIndex < messages.size - 1) {
            estimate(messages.subList(usageIndex + 1, messages.size))
        } else 0

    companion object {
        /**
         * Thread-safe tokenizer encoding. O200K_BASE is used as a stable approximation
         * basis for models without an exact tokenizer (e.g., qwen); it is an order of
         * magnitude more accurate than character heuristics for CJK and JSON content.
         */
        private val encoding: Encoding =
            Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.O200K_BASE)

        /** Upper bound of the per-message token cache; cleared wholesale when exceeded. */
        private const val MAX_CACHE_ENTRIES = 20_000

        /**
         * Fallback divisor when tokenizer counting throws; also retained as a documented
         * heuristic constant. Not used on the normal path.
         */
        private const val DEFAULT_CHARS_PER_TOKEN = 3.5

        /**
         * Below this content baseline the plausibility window degenerates (too narrow to
         * be meaningful), so usage reports are trusted unconditionally.
         */
        private const val MIN_BASELINE_FOR_CHECK = 500

        /**
         * Lower bound of the plausibility window as a multiple of the content baseline.
         * Reports below this are gateway under-counts (observed: 772 reported vs ~180K real).
         */
        private const val LOW_REPORT_RATIO = 0.25

        /**
         * Upper bound of the plausibility window as a multiple of the content baseline.
         * Reports above this are reporting spikes (observed: cacheRead=244,992).
         */
        private const val HIGH_REPORT_RATIO = 4.0

        /**
         * Total input tokens represented by a usage report, including cached portions
         * (inputTokens alone excludes cache reads/writes in Anthropic-style accounting).
         */
        private fun totalInputTokens(usage: Usage): Int =
            usage.inputTokens + usage.cacheReadTokens + usage.cacheWriteTokens
    }
}
