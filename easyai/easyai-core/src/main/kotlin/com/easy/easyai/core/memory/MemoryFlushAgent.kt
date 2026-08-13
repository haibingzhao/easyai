package com.easy.easyai.core.memory

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.AgentContext
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
 * @param store The memory store to write extracted facts to.
 * @param threshold Context window usage ratio that triggers flush (default: 0.75 = 75%).
 */
class MemoryFlushAgent(
    private val store: MemoryStore,
    private val threshold: Float = 0.75f
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

        val flushPrompt = buildFlushPrompt(recentMessages)
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

        var written = 0
        for (item in entries) {
            val entry = buildEntry(item)
            store.write(entry, scope, owner)
            written++
        }
        flushedHashes.add(contextHash)
        // Evict oldest entries when capacity exceeded (LinkedHashSet guarantees insertion order)
        while (flushedHashes.size > maxHashes) {
            flushedHashes.remove(flushedHashes.first())
        }

        logger.info("Memory flush completed: wrote {} memory entries", written)
        return FlushResult(written = written)
    }

    private fun computeHash(messages: List<EasyAiMessage>): String {
        val text = messages.joinToString("\n") { it.toString() }
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun buildFlushPrompt(messages: List<EasyAiMessage>): String = buildString {
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
            """{"memories": [{"title": "short unique title", "description": "one-line summary", "category": "user_preferences|project_information|development_standards|task_summary|experience_lessons|other", "keywords": ["keyword"], "scenarios": ["when this applies"], "maturity": "low|medium|high", "content": "markdown body with the full fact"}]}"""
        )
        appendLine("Each entry in \"memories\" must be self-contained. \"category\" must be one of the six values listed. \"maturity\" must be one of low/medium/high. If no durable facts exist, return {\"memories\": []}.")
        appendLine()
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
        return MemoryEntry(
            name = name,
            description = item.description?.trim() ?: "",
            type = type,
            content = item.content?.trim().orEmpty(),
            path = "${type.dirName}/$name.md",
            keywords = cleanList(item.keywords),
            created = LocalDate.now(),
            updated = LocalDate.now(),
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

    data class FlushResult(val written: Int)

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
        val content: String? = null
    )

    private companion object {
        const val MAX_NAME_LENGTH = 64
    }
}
