package com.easy.easyai.core.message

import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.ThinkingContent
import com.easy.easyai.core.model.ToolCallContent
import com.easy.easyai.core.model.ToolResultEntry
import com.easy.easyai.core.model.ToolResultMessage
import com.easy.easyai.core.model.UserMessage
import com.easy.easyai.common.util.SharedObjectMapper
import org.slf4j.LoggerFactory
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

/**
 * Configuration for cross-run tool-message folding (send-time projection only).
 *
 * @param enabled Master switch. When false, [ToolFoldProjection.project] returns the input untouched.
 * @param keepRecentRuns Number of completed runs (user requests) kept verbatim in addition to the
 *   current run. Older runs have their tool-call arguments and results folded to placeholder lines.
 * @param foldThinking When true, thinking blocks in folded runs are dropped entirely.
 */
data class ToolFoldConfig(
    val enabled: Boolean = true,
    val keepRecentRuns: Int = 1,
    val foldThinking: Boolean = true
)

/**
 * Summary of one [ToolFoldProjection.project] invocation.
 * Character counts drive the SSE `tool_fold` event; token figures are the char/3.5 heuristic.
 */
data class FoldReport(
    val foldedRunCount: Int = 0,
    val foldedToolCallCount: Int = 0,
    val charsBefore: Int = 0,
    val charsAfter: Int = 0,
    val foldedRefs: List<String> = emptyList()
) {
    val hasFolds: Boolean get() = foldedToolCallCount > 0
    val tokensSavedEstimate: Int get() = ((charsBefore - charsAfter) / CHARS_PER_TOKEN).toInt().coerceAtLeast(0)

    companion object {
        const val CHARS_PER_TOKEN = 3.5
    }
}

/**
 * Send-time projection that folds tool-call details of historical runs.
 *
 * A "run" is one user request: a real UserMessage (no injection marker) plus every message
 * until the next real user message. The current run and the last [ToolFoldConfig.keepRecentRuns]
 * completed runs are kept verbatim; older runs keep user messages and assistant text but their
 * tool-call arguments and tool results are replaced by one-line placeholders pointing at
 * `recall_tool_result`. The most recent compaction summary message is always kept verbatim —
 * it is the only representative of everything compaction removed.
 *
 * Invariants (required by downstream consumers):
 * - Message count, order and ids are unchanged — assistant tool_calls and their ToolResultMessage
 *   stay paired, so the Spring AI conversion and provider protocol validation are unaffected.
 * - Deterministic: the same input always produces byte-identical output, keeping prompt
 *   prefix caching stable across turns within a run.
 * - Never written back to the transcript or persisted; the transcript always holds originals.
 *
 * Fail-safe: any unrecognized message shape, unparseable arguments, or missing run boundary
 * leaves the affected content un folded — under-folding is always acceptable, over-folding is not.
 */
object ToolFoldProjection {

    private val logger = LoggerFactory.getLogger(ToolFoldProjection::class.java)

    /** Placeholder results below this length are kept verbatim (cheap, high-signal: errors, confirmations). */
    private const val KEEP_SHORT_RESULT_CHARS = 200

    /** Keys probed first when summarizing tool-call arguments; ordered by specificity. */
    private val KEY_ARG_ORDER = listOf(
        "path", "file", "file_path", "filepath", "pattern", "query", "glob",
        "command", "url", "commandName", "skill", "name", "id", "ref"
    )

    private val ANY_ARG_KEY_PATTERN = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")

    private const val MAX_ARG_SUMMARY_CHARS = 120

    /** Ref format shared with the recall tool: `<messageId>#<toolCallId>`. */
    const val REF_SEPARATOR = "#"

    /**
     * Projects [messages] into a folded view plus a [FoldReport].
     *
     * @param placeholderCache optional per-run memo for tool-call placeholders (ref → text).
     *   Callers that project repeatedly over the same history (e.g. every turn of a run) pass a
     *   shared map to skip re-parsing historical arguments JSON. Never shared across runs.
     */
    @JvmStatic
    @JvmOverloads
    fun project(
        messages: List<EasyAiMessage>,
        config: ToolFoldConfig = ToolFoldConfig(),
        placeholderCache: MutableMap<String, String>? = null
    ): Pair<List<EasyAiMessage>, FoldReport> {
        if (!config.enabled || messages.isEmpty()) {
            return messages to FoldReport()
        }

        val segments = segmentRuns(messages)
        if (segments.size <= 1) {
            return messages to FoldReport()
        }
        // Segments are chronological; keep the current run plus the last keepRecentRuns completed runs.
        val retainCount = (config.keepRecentRuns.coerceAtLeast(0) + 1).coerceAtMost(segments.size)
        val foldableSegments = segments.dropLast(retainCount)
        if (foldableSegments.isEmpty()) {
            return messages to FoldReport()
        }

        val lastSummaryIndex = messages.indexOfLast { isCompactionSummary(it) }
        val foldableSegmentGroups = foldableSegments.map { segment -> segment.toMutableList() }
            .toMutableList()
        // A compaction summary is the sole survivor of everything the previous compaction removed:
        // never fold it away. Earlier summaries may be folded.
        if (lastSummaryIndex >= 0) {
            foldableSegmentGroups.forEach { group -> group.remove(lastSummaryIndex) }
        }
        val indicesToFold = foldableSegmentGroups.flatten().toSet()

        if (indicesToFold.isEmpty()) {
            return messages to FoldReport()
        }

        // Map each foldable message index to its run group so foldedRunCount reflects runs that
        // actually had a tool call/result folded — not merely runs containing an assistant message.
        val indexToGroup = HashMap<Int, Int>()
        foldableSegmentGroups.forEachIndexed { groupIndex, group ->
            group.forEach { messageIndex -> indexToGroup[messageIndex] = groupIndex }
        }
        val foldedGroups = mutableSetOf<Int>()

        val objectMapper = SharedObjectMapper.instance
        val foldedRefs = mutableListOf<String>()
        var foldedToolCallCount = 0
        var charsBefore = 0
        var charsAfter = 0

        val result = messages.mapIndexed { index, message ->
            if (index !in indicesToFold) {
                charsBefore += messageContentChars(message)
                charsAfter += messageContentChars(message)
                return@mapIndexed message
            }
            val before = messageContentChars(message)
            val folded = foldMessage(message, config, objectMapper, foldedRefs, placeholderCache) {
                foldedToolCallCount++
                indexToGroup[index]?.let { foldedGroups.add(it) }
            }
            charsBefore += before
            charsAfter += messageContentChars(folded)
            folded
        }

        val foldedRunCount = foldedGroups.size

        val report = FoldReport(
            foldedRunCount = foldedRunCount,
            foldedToolCallCount = foldedToolCallCount,
            charsBefore = charsBefore,
            charsAfter = charsAfter,
            foldedRefs = foldedRefs.toList()
        )
        if (foldedToolCallCount > 0) {
            logger.info(
                "Tool fold applied: {} runs, {} tool calls/results folded, {} -> {} chars (~{} tokens)",
                foldedRunCount, foldedToolCallCount, charsBefore, charsAfter, report.tokensSavedEstimate
            )
        }
        return result to report
    }

    /**
     * Splits messages into chronological run segments.
     * A real user message (no source/summary/system-origin marker) opens a new segment;
     * everything else (assistant, tool results, injected user messages, system/error messages)
     * joins the current segment. Leading injected messages before the first real request stay
     * in the same segment as that request. Empty segments are never emitted.
     */
    internal fun segmentRuns(messages: List<EasyAiMessage>): List<List<Int>> {
        val segments = mutableListOf<MutableList<Int>>()
        var current = mutableListOf<Int>()
        messages.forEachIndexed { index, message ->
            if (message is UserMessage && isNewRequest(message)) {
                if (current.isNotEmpty()) segments.add(current)
                current = mutableListOf()
            }
            current.add(index)
        }
        if (current.isNotEmpty()) segments.add(current)
        return segments
    }

    /**
     * Reverse rule: every system-injected user message carries a marker
     * (SOURCE_KEY / isCompactionSummary / SYSTEM_ORIGIN_KEY). A user message without any
     * marker is a genuine new request and therefore a run boundary.
     */
    internal fun isNewRequest(message: UserMessage): Boolean {
        val meta = message.metadata
        return meta[UserMessage.SOURCE_KEY] == null &&
            meta["isCompactionSummary"] != "true" &&
            meta[UserMessage.SYSTEM_ORIGIN_KEY] == null
    }

    private fun isCompactionSummary(message: EasyAiMessage): Boolean =
        message is UserMessage && message.metadata["isCompactionSummary"] == "true"

    private fun foldMessage(
        message: EasyAiMessage,
        config: ToolFoldConfig,
        objectMapper: ObjectMapper,
        foldedRefs: MutableList<String>,
        placeholderCache: MutableMap<String, String>?,
        onToolCallFolded: () -> Unit
    ): EasyAiMessage = when (message) {
        is AssistantMessage -> {
            val newContent = message.content.mapNotNull { block ->
                when (block) {
                    is ThinkingContent -> if (config.foldThinking) null else block
                    is ToolCallContent -> {
                        onToolCallFolded()
                        val ref = "${message.id}$REF_SEPARATOR${block.id}"
                        foldedRefs.add(ref)
                        block.copy(
                            arguments = resolveToolCallPlaceholder(
                                placeholderCache, ref, block.name, block.arguments, objectMapper
                            )
                        )
                    }
                    else -> block
                }
            }
            message.copy(content = newContent)
        }
        is ToolResultMessage -> {
            val newEntries = message.toolResults.map { entry ->
                if (entry.result.length <= KEEP_SHORT_RESULT_CHARS) {
                    entry
                } else {
                    onToolCallFolded()
                    val ref = "${message.id}$REF_SEPARATOR${entry.toolCallId}"
                    foldedRefs.add(ref)
                    entry.copy(result = toolResultPlaceholder(ref, entry.toolName, entry))
                }
            }
            message.copy(toolResults = newEntries)
        }
        else -> message
    }

    private fun toolCallPlaceholder(ref: String, toolName: String, argumentsJson: String, objectMapper: ObjectMapper): String {
        val summary = summarizeArguments(argumentsJson, objectMapper)
        return "[tool: $toolName $summary (details folded; recall: recall_tool_result ref=\"$ref\")]"
    }

    /**
     * Resolves a tool-call placeholder, memoizing on [cache] when provided.
     *
     * The cache is owned per run (one [com.easy.easyai.core.agent.AgentLoop]) and keyed by ref
     * (`messageId#callId`); a ref's arguments never change within a run because folding is a
     * send-time projection that never mutates the transcript. [project] runs multiple times per
     * turn over the same historical runs, so memoizing avoids re-parsing their arguments JSON.
     */
    private fun resolveToolCallPlaceholder(
        cache: MutableMap<String, String>?,
        ref: String,
        toolName: String,
        argumentsJson: String,
        objectMapper: ObjectMapper
    ): String {
        if (cache == null) return toolCallPlaceholder(ref, toolName, argumentsJson, objectMapper)
        val hit = cache[ref]
        if (hit != null) return hit
        val made = toolCallPlaceholder(ref, toolName, argumentsJson, objectMapper)
        cache[ref] = made
        return made
    }

    private fun toolResultPlaceholder(ref: String, toolName: String, entry: ToolResultEntry): String {
        val status = when {
            entry.isSkipped -> "skipped"
            entry.isError -> "error"
            else -> "ok"
        }
        val exit = entry.exitCode?.let { ", exit=$it" } ?: ""
        val duration = entry.durationMs?.let { ", ${it}ms" } ?: ""
        val omitted = entry.result.length
        return "[tool result: $toolName $status$exit$duration, ${omitted} chars folded; recall: recall_tool_result ref=\"$ref\"]"
    }

    /**
     * Extracts a compact `key=value` summary from a tool-call JSON arguments string.
     * Preferred keys come first; remaining scalar keys fill the budget. Anything that is not
     * a flat JSON object with at least one string key is replaced by a neutral marker.
     */
    internal fun summarizeArguments(argumentsJson: String, objectMapper: ObjectMapper): String {
        if (argumentsJson.isBlank()) return "(no args)"
        val map: Map<String, Any?> = try {
            objectMapper.readValue(argumentsJson, object : TypeReference<Map<String, Any?>>() {})
        } catch (e: Exception) {
            return "(args folded)"
        }

        val parts = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val allKeys = map.keys.toList()
        for (key in KEY_ARG_ORDER) {
            if (!map.containsKey(key) || key in seen) continue
            val value = map[key]
            if (value == null || value is Number || value is Boolean || value is String) {
                seen.add(key)
                parts.add("$key=${truncate(value?.toString() ?: "null", 60)}")
            }
        }
        if (parts.isEmpty()) {
            // No preferred key matched (e.g. MCP tools with bespoke params): surface key names
            // so the model still knows what was called, without the bulky values.
            val scalarKeys = allKeys.filter { ANY_ARG_KEY_PATTERN.matches(it) }.take(4)
            if (scalarKeys.isNotEmpty()) {
                parts.add("keys=[${scalarKeys.joinToString(", ")}]")
            }
        } else {
            val remaining = allKeys.count { it !in seen }
            if (remaining > 0) parts.add("+$remaining more")
        }
        if (parts.isEmpty()) return "(args folded)"
        return truncate(parts.joinToString(", "), MAX_ARG_SUMMARY_CHARS)
    }

    private fun truncate(text: String, maxChars: Int): String =
        if (text.length <= maxChars) text else text.take(maxChars - 1) + "…"

    private fun messageContentChars(message: EasyAiMessage): Int = when (message) {
        is ToolResultMessage -> message.toolResults.sumOf { it.result.length }
        is AssistantMessage -> message.content.sumOf { block ->
            when (block) {
                is TextContent -> block.text.length
                is ThinkingContent -> block.thinking.length
                is ToolCallContent -> block.arguments.length
                else -> 0
            }
        }
        is UserMessage -> message.content.sumOf { block ->
            when (block) {
                is TextContent -> block.text.length
                else -> 0
            }
        }
        else -> 0
    }
}
