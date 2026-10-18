package com.easy.easyai.tools.memory

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.memory.MemoryEntry
import com.easy.easyai.core.memory.MemoryOwnerContext
import com.easy.easyai.core.memory.MemoryRef
import com.easy.easyai.core.memory.MemoryScope
import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import kotlinx.coroutines.CoroutineScope
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

internal class MemorySearchTool(
    metadata: ToolMetadata,
    private val store: MemoryStore
) : BaseToolDefinition(metadata) {

    private val logger = LoggerFactory.getLogger(javaClass)

    data class Parameters(
        val query: String,
        val timeRangeStart: String? = null,
        val timeRangeEnd: String? = null
    )
    override fun parameterType(): Class<*> = Parameters::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val query = args["query"] as? String
        if (query.isNullOrBlank()) {
            return errorResult("Error: 'query' parameter is required and must not be empty.")
        }
        val timeRangeStart = parseEpochSeconds(args["timeRangeStart"])
        val timeRangeEnd = parseEpochSeconds(args["timeRangeEnd"])
        val owner = MemoryOwnerContext(agentContext.userId, agentContext.projectPath)

        val hits = mutableListOf<Pair<MemoryScope, MemoryEntry>>()
        for (scope in listOf(MemoryScope.PROJECT, MemoryScope.GLOBAL)) {
            val entries = store.search(query, scope, owner, timeRangeStart = timeRangeStart, timeRangeEnd = timeRangeEnd)
            entries.forEachIndexed { index, entry ->
                // Record access for each search hit
                agentContext.memoryAccessTracker.recordAccess(MemoryRef(entry.name, entry.description, entry.type, scope))
                // Only the leading hits are realistic retrieval targets; recording every hit
                // would rewrite too many entries for a weak freshness signal.
                if (index < TOUCH_LIMIT) {
                    runCatching { store.touch(entry.path, scope, owner) }
                        .onFailure { logger.debug("Failed to record access for memory: {}", entry.path, it) }
                }
                hits.add(scope to entry)
            }
        }

        val text = if (hits.isEmpty()) {
            "No memories found matching '$query'."
        } else {
            "Found ${hits.size} memories:\n\n${hits.joinToString("\n\n") { (scope, entry) -> formatHit(scope, entry) }}"
        }
        return ToolResult(
            content = listOf(TextContent(text)),
            details = if (hits.isEmpty()) emptyMap() else mapOf("hits" to hits.map { (scope, entry) -> hitDetails(scope, entry) })
        )
    }

    /**
     * The same facts the prose carries, addressed to the renderer. [formatHit]'s text is what the
     * model reads and may be reworded or localized at will; this map is the UI's contract, so the
     * frontend never has to parse display text back into data. Freshness arrives as a date plus a
     * day count rather than `"2026-01-15 (3 days ago)"` so a label can be composed in the viewer's
     * own language.
     */
    private fun hitDetails(scope: MemoryScope, entry: MemoryEntry): Map<String, Any?> {
        val updated = entry.updated
        return mapOf(
            "scope" to scope.name.lowercase(),
            "path" to entry.path,
            "name" to entry.name,
            "description" to entry.description,
            "level" to entry.level,
            "updated" to updated?.toString(),
            "daysSinceUpdate" to updated?.let { ChronoUnit.DAYS.between(it, LocalDate.now()) },
            "maturity" to entry.maturity?.apiName,
            "contentPreview" to entry.content.take(CONTENT_PREVIEW_LIMIT)
        )
    }

    /**
     * Render one hit. The freshness tail is what lets the model judge a memory without a
     * follow-up lookup: `updated: unknown` is reported as unknown rather than dropped, so a
     * missing timestamp can never be misread as a recent one.
     */
    private fun formatHit(scope: MemoryScope, entry: MemoryEntry): String {
        val updated = entry.updated
        val updatedText = if (updated == null) {
            "unknown"
        } else {
            val days = ChronoUnit.DAYS.between(updated, LocalDate.now())
            "$updated ($days days ago)"
        }
        val maturityText = entry.maturity?.apiName ?: "unset"
        return "[${scope.name.lowercase()}:${entry.path}] ${entry.name} — ${entry.description}" +
            " | updated: $updatedText | maturity: $maturityText\n" +
            entry.content.take(CONTENT_PREVIEW_LIMIT)
    }

    /**
     * Normalize a time value to epoch seconds.
     * Accepts epoch seconds, epoch milliseconds, `yyyy-MM-dd`, or ISO-8601 datetime.
     * Returns null for absent, blank, or unparsable values.
     */
    private fun parseEpochSeconds(value: Any?): Long? {
        if (value == null) return null
        if (value is Number) {
            val num = value.toLong()
            return if (num > MILLIS_THRESHOLD) num / 1000 else num
        }
        val text = value.toString().trim()
        if (text.isEmpty()) return null
        text.toLongOrNull()?.let { num ->
            return if (num > MILLIS_THRESHOLD) num / 1000 else num
        }
        runCatching {
            return LocalDate.parse(text).atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
        }
        runCatching {
            return Instant.parse(text).epochSecond
        }
        return null
    }

    private companion object {
        /** Values above this are treated as epoch milliseconds (year ~2286 in seconds). */
        const val MILLIS_THRESHOLD = 10_000_000_000L

        /** Max characters of entry content returned per search hit, reducing follow-up memory_read calls. */
        const val CONTENT_PREVIEW_LIMIT = 1500

        /** Number of hits per scope whose access date is recorded as a freshness signal. */
        const val TOUCH_LIMIT = 3
    }
}
