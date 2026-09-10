package com.easy.easyai.core.memory

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.domain.DomainCatalog
import com.easy.easyai.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.Prompt
import java.security.MessageDigest
import java.time.LocalDate
import java.util.*
import org.springframework.ai.chat.messages.UserMessage as SpringAiUserMessage

/**
 * Extracts durable facts from conversation history before context compaction.
 *
 * Triggered by [CompactionTransformContextService] when context window usage ≥ threshold.
 * Runs a silent LLM turn that returns structured JSON; each extracted fact becomes one
 * independent memory entry with full metadata (category, keywords, scenarios, maturity).
 *
 * Dedup: SHA-256 hash of recent messages prevents duplicate flushes.
 *
 * Reconciliation: extracted facts are compared against the entries already held by [store], so
 * a fact repeating an existing name updates that entry instead of leaving two contradicting
 * documents behind. Removals stay behind [allowRemovals] because they are irreversible.
 *
 * @param store The memory store to write extracted facts to.
 * @param threshold Context window usage ratio that triggers flush (default: 0.75 = 75%).
 * @param allowRemovals Whether a flush may delete existing entries (default: false). Removal is
 *   physical, so a blocked removal is still reported as a review candidate instead.
 */
class MemoryFlushAgent(
    private val store: MemoryStore,
    private val threshold: Float = 0.75f,
    private val allowRemovals: Boolean = false
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper = SharedObjectMapper.instance

    /** Tracks already-flushed context hashes to prevent duplicate flushes. Thread-safe LinkedHashSet for FIFO eviction. */
    private val flushedHashes: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    private val maxHashes = 1000

    /**
     * Check if memory flush is needed and execute if so.
     *
     * @param agentContext Agent context providing runtime project path.
     * @param messages Current conversation messages.
     * @param modelContextLength Total context window size in tokens.
     * @param estimatedTokenCount Current estimated token usage.
     * @param chatModel ChatModel to use for extraction.
     * @param scope Memory scope to write to (default: PROJECT).
     * @return FlushResult if flush was executed, null if not needed or nothing was written.
     */
    suspend fun maybeFlush(
        agentContext: AgentContext,
        messages: List<EasyAiMessage>,
        modelContextLength: Int,
        estimatedTokenCount: Int,
        chatModel: ChatModel,
        scope: MemoryScope = MemoryScope.PROJECT
    ): FlushResult? {
        val usageRatio = if (modelContextLength > 0) {
            estimatedTokenCount.toFloat() / modelContextLength
        } else 0f

        if (!agentContext.memoryAutoGeneration) return null
        if (usageRatio < threshold) return null
        if (messages.size < 5) return null  // Too few messages to extract from

        // Dedup: hash recent messages
        val recentMessages = messages.takeLast(20)
        val contextHash = computeHash(recentMessages)
        if (contextHash in flushedHashes) return null

        logger.info("Memory flush triggered (usage: {}%, messages: {})", String.format("%.1f", usageRatio * 100), messages.size)

        val owner = MemoryOwnerContext(agentContext.userId, agentContext.projectPath)

        // Snapshot what is already stored so extraction can reconcile instead of blindly appending.
        // This goes through list(), never search(): the document store is written synchronously
        // (so a fresh entry is immediately visible) while the vector index is submitted
        // fire-and-forget and would miss entries stored moments ago. A snapshot failure degrades
        // to an empty map rather than cancelling the flush.
        val existingByName: Map<String, MemoryEntry> = runCatching {
            store.list(scope, owner).associateBy { it.name }
        }.getOrElse { e ->
            logger.warn("Memory flush: failed to snapshot existing memories: {}", e.message)
            emptyMap()
        }

        val flushPrompt = buildFlushPrompt(recentMessages, existingByName.values)
        val response = try {
            withContext(Dispatchers.IO) {
                chatModel.call(Prompt(SpringAiUserMessage(flushPrompt)))
            }
        } catch (e: Exception) {
            logger.warn("Memory flush LLM call failed: {}", e.message)
            return null
        }

        val content = response.result?.output?.text ?: return null
        if (content.isBlank()) return null

        val entries = parseEntries(content)
        if (entries.isEmpty()) {
            logger.warn("Memory flush produced no parseable entries; skipping")
            // Mark the hash so the same context is not retried on every subsequent turn.
            flushedHashes.add(contextHash)
            return null
        }

        val counts = FlushCounts()
        for (item in entries) {
            when (applyEntry(item, existingByName, counts, scope, owner)) {
                ApplyOutcome.WRITTEN -> counts.written++
                ApplyOutcome.UPDATED -> counts.updated++
                ApplyOutcome.REMOVED -> counts.removed++
                ApplyOutcome.REVIEW -> counts.reviewCandidates++
                ApplyOutcome.SKIPPED, ApplyOutcome.FAILED -> counts.failed++
            }
        }
        flushedHashes.add(contextHash)
        // Evict oldest entries when capacity exceeded (LinkedHashSet guarantees insertion order)
        while (flushedHashes.size > maxHashes) {
            flushedHashes.remove(flushedHashes.first())
        }

        logger.info(
            "Memory flush: {} written, {} updated, {} removed, {} review candidates",
            counts.written, counts.updated, counts.removed, counts.reviewCandidates
        )
        if (counts.failed > 0) {
            logger.warn("Memory flush completed with errors: {} entries were not applied", counts.failed)
        }
        return FlushResult(
            written = counts.written,
            updated = counts.updated,
            removed = counts.removed,
            reviewCandidates = counts.reviewCandidates
        )
    }

    /**
     * Persist one extracted item, choosing add / update / remove against the [existingByName]
     * snapshot. Never throws: a failing entry is isolated so the rest of the batch still lands.
     */
    private suspend fun applyEntry(
        item: FlushEntry,
        existingByName: Map<String, MemoryEntry>,
        counts: FlushCounts,
        scope: MemoryScope,
        owner: MemoryOwnerContext
    ): ApplyOutcome {
        val name = slugify(item.title.orEmpty())
        val existing = existingByName[name]
        return try {
            when (item.action?.lowercase()) {
                "update" -> if (existing == null) {
                    logger.warn("Memory flush: update for unknown entry '{}' skipped", name)
                    ApplyOutcome.SKIPPED
                } else {
                    store.write(mergeInto(item, existing), scope, owner)
                    ApplyOutcome.UPDATED
                }
                "remove" -> when {
                    existing == null -> {
                        logger.warn("Memory flush: remove for unknown entry '{}' skipped", name)
                        ApplyOutcome.SKIPPED
                    }
                    !canRemove(existing, item.reason, counts.removed) -> ApplyOutcome.REVIEW
                    else -> {
                        store.delete(existing.path, scope, owner)
                        ApplyOutcome.REMOVED
                    }
                }
                else -> if (existing == null) {
                    store.write(buildEntry(item), scope, owner)
                    ApplyOutcome.WRITTEN
                } else {
                    // The model asked to add a name that already exists: merge into it instead of
                    // creating a second document that contradicts the first.
                    logger.info("Memory flush: add of existing entry '{}' applied as update", name)
                    store.write(mergeInto(item, existing), scope, owner)
                    ApplyOutcome.UPDATED
                }
            }
        } catch (e: Exception) {
            // Isolate per-entry failures (e.g. RAG indexing errors) so one bad
            // entry does not discard the rest of the flush batch.
            logger.warn("Memory flush: failed to apply entry '{}': {}", name, e.message)
            ApplyOutcome.FAILED
        }
    }

    /**
     * Removal needs all three gates: the operator enabled it, the entry really exists in this
     * flush's snapshot, and the model justified it. Anything else is surfaced as a review
     * candidate, because a wrong deletion is unrecoverable while a stale entry is not.
     */
    private fun canRemove(existing: MemoryEntry, reason: String?, removalsSoFar: Int): Boolean {
        val rejection = when {
            !allowRemovals -> "removals are disabled"
            reason.isNullOrBlank() -> "no reason given"
            removalsSoFar >= MAX_REMOVALS_PER_FLUSH -> "per-flush removal cap reached"
            else -> null
        } ?: return true
        logger.info("Memory flush: stale review candidate '{}' ({})", existing.name, rejection)
        return false
    }

    /** Apply an extracted item onto a stored entry, keeping the fields the item omits. */
    private fun mergeInto(item: FlushEntry, existing: MemoryEntry): MemoryEntry {
        val content = item.content?.trim().orEmpty()
        val description = item.description?.trim().orEmpty()
        return existing.copy(
            description = description.ifBlank { existing.description },
            content = content.ifBlank { existing.content },
            keywords = cleanList(item.keywords).ifEmpty { existing.keywords },
            scenarios = cleanList(item.scenarios).ifEmpty { existing.scenarios },
            maturity = item.maturity?.let { MemoryMaturity.fromApiName(it) } ?: existing.maturity,
            updated = LocalDate.now()
        )
    }

    private fun computeHash(messages: List<EasyAiMessage>): String {
        val text = messages.joinToString("\n") { it.toString() }
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun buildFlushPrompt(messages: List<EasyAiMessage>, existing: Collection<MemoryEntry>): String = buildString {
        // Read the active domain at call time: bean construction order relative to
        // domain configuration is not guaranteed, so capturing it in the constructor races.
        val categoryList = MemoryType.entriesFor(DomainCatalog.activeDomain).joinToString("|") { it.dirName }
        val categoryCount = MemoryType.entriesFor(DomainCatalog.activeDomain).size
        appendLine("Extract the most important durable facts, decisions, and context from the following conversation.")
        appendLine("Focus on information that should persist across sessions:")
        appendLine("- User preferences and working style")
        appendLine("- Project decisions and architectural choices")
        appendLine("- Important constraints or requirements")
        appendLine("- Behavioral feedback (what to do or avoid)")
        appendLine()
        appendLine("Do NOT include: task progress, PR/issue numbers, commit SHAs, or ephemeral details.")
        appendLine()
        appendLine("Return ONLY a single JSON object (no markdown fences, no commentary) with this exact shape:")
        appendLine(
            """{"memories": [{"title": "short unique title", "description": "one-line summary", "category": "$categoryList", "keywords": ["keyword"], "scenarios": ["when this applies"], "maturity": "low|medium|high", "action": "add|update|remove", "reason": "why this change is needed", "content": "markdown body with the full fact"}]}"""
        )
        appendLine("Each entry in \"memories\" must be self-contained. \"category\" must be one of the $categoryCount values listed. \"maturity\" must be one of low/medium/high. If no durable facts exist, return {\"memories\": []}.")
        appendLine()
        appendLine("Reconcile against the existing entries listed below instead of accumulating duplicates:")
        appendLine("- An existing entry already covering the same fact: return \"action\": \"update\" and copy that entry's name EXACTLY into \"title\".")
        appendLine("- An existing entry this conversation proves obsolete: return \"action\": \"remove\" and explain the proof in \"reason\".")
        appendLine("- Never \"add\" a fact that contradicts an existing entry; update or remove that entry instead.")
        appendExistingMemories(this, existing)
        appendLine("<conversation>")
        messages.forEach { msg ->
            val text = msg.content.mapNotNull { block ->
                when (block) {
                    is TextContent -> block.text
                    is ToolCallContent -> "[tool_call: ${block.name}(${block.arguments.take(200)})]"
                    is ToolResultContent -> {
                        val output = if (block.output.length > 500) block.output.take(500) + "..." else block.output
                        "[tool_result: ${block.toolName} → $output]"
                    }
                    is ThinkingContent -> null  // Skip thinking to reduce noise
                    else -> null
                }
            }.joinToString("")
            if (text.isNotBlank()) {
                appendLine("${msg.role}: $text")
            }
        }
        appendLine("</conversation>")
    }

    /**
     * Metadata-only index of the entries already stored, which is what the model needs to decide
     * add vs update vs remove. Full bodies are deliberately left out: a flush runs exactly when
     * the context window is nearly exhausted, so K entries of text would cost K * ~1200 tokens.
     */
    private fun appendExistingMemories(sb: StringBuilder, existing: Collection<MemoryEntry>) {
        if (existing.isEmpty()) return
        with(sb) {
            appendLine()
            appendLine("<existing_memories>")
            val shown = existing.sortedByDescending { it.updated ?: LocalDate.MIN }.take(MAX_EXISTING_IN_PROMPT)
            shown.forEach { entry ->
                appendLine(
                    "- ${entry.type.dirName}/${entry.name} | updated ${entry.updated ?: "unknown"}" +
                        " | maturity ${entry.maturity?.apiName ?: "unset"} — ${entry.description.take(EXISTING_DESCRIPTION_LIMIT)}"
                )
            }
            val hidden = existing.size - shown.size
            if (hidden > 0) appendLine("(... and $hidden more, truncated)")
            appendLine("</existing_memories>")
            appendLine()
        }
    }

    /** Extract the JSON payload from the LLM response and deserialize the memory list. */
    private fun parseEntries(text: String): List<FlushEntry> {
        val json = extractJsonObject(text) ?: return emptyList()
        return try {
            val payload = objectMapper.readValue(json, FlushPayload::class.java)
            payload.memories ?: emptyList()
        } catch (e: Exception) {
            logger.warn("Memory flush JSON parse failed: {}", e.message)
            emptyList()
        }
    }

    /** Locate the outermost JSON object; tolerates stray prose around the payload. */
    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start !in 0..<end) return null
        return text.substring(start, end + 1)
    }

    /** Build a [MemoryEntry] from a parsed flush item, applying safe defaults. */
    private fun buildEntry(item: FlushEntry): MemoryEntry {
        val type = item.category?.let { MemoryType.fromDirName(it) } ?: MemoryType.OTHER
        val name = slugify(item.title.orEmpty())
        val today = LocalDate.now()
        return MemoryEntry(
            name = name,
            description = item.description?.trim() ?: "",
            type = type,
            content = item.content?.trim().orEmpty(),
            path = "${type.dirName}/$name.md",
            keywords = cleanList(item.keywords),
            created = today,
            updated = today,
            maturity = item.maturity?.let { MemoryMaturity.fromApiName(it) },
            scenarios = cleanList(item.scenarios)
        )
    }

    private fun cleanList(values: List<String>?): List<String> =
        values?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct() ?: emptyList()

    /** Lowercase, non-alphanumeric chars become `-`, collapse duplicates, cap length. */
    private fun slugify(title: String): String {
        val slug = title.lowercase()
            .map { c -> if (c.isLetterOrDigit() || c == '-' || c == '_' || c == '.') c else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-{2,}"), "-")
        return slug.take(MAX_NAME_LENGTH).ifEmpty { "memory-${System.currentTimeMillis()}" }
    }

    /**
     * @param written brand-new entries
     * @param updated existing entries rewritten with fresher content or metadata
     * @param removed entries deleted because the conversation proved them obsolete
     * @param reviewCandidates removals that were requested or suspected but gated off, so a
     *   human or a later governed tool call can decide
     */
    data class FlushResult(
        val written: Int,
        val updated: Int = 0,
        val removed: Int = 0,
        val reviewCandidates: Int = 0
    )

    /** How one extracted item was handled; SKIPPED means it was recognised as unapplicable. */
    private enum class ApplyOutcome { WRITTEN, UPDATED, REMOVED, REVIEW, SKIPPED, FAILED }

    /** Tally of one flush run; the removal gate reads [removed] to cap a single batch. */
    private class FlushCounts {
        var written = 0
        var updated = 0
        var removed = 0
        var reviewCandidates = 0
        var failed = 0
    }

    /** JSON payload shape returned by the LLM. */
    private data class FlushPayload(val memories: List<FlushEntry>? = null)

    /** A single memory entry as returned by the LLM. */
    private data class FlushEntry(
        val title: String? = null,
        val description: String? = null,
        val category: String? = null,
        val keywords: List<String>? = null,
        val scenarios: List<String>? = null,
        val maturity: String? = null,
        val action: String? = null,
        val reason: String? = null,
        val content: String? = null
    )

    private companion object {
        const val MAX_NAME_LENGTH = 64

        /** Cap on how many stored entries the flush prompt lists. */
        const val MAX_EXISTING_IN_PROMPT = 50

        /** Description truncation per listed entry, enough to recognise the topic. */
        const val EXISTING_DESCRIPTION_LIMIT = 90

        /** Hard limit on physical deletions per flush, even with removals enabled. */
        const val MAX_REMOVALS_PER_FLUSH = 3
    }
}
