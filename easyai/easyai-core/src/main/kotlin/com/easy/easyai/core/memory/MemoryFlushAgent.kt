package com.easy.easyai.core.memory

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.ThinkingContent
import com.easy.easyai.core.model.ToolCallContent
import com.easy.easyai.core.model.ToolResultContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.chat.messages.UserMessage as SpringAiUserMessage
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Collections

/**
 * Extracts durable facts from conversation history before context compaction.
 *
 * Triggered by [CompactionTransformContextService] when context window usage ≥ threshold.
 * Runs a silent LLM turn to extract important facts, decisions, and context into a
 * daily memory file (memory/YYYY-MM-DD.md).
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
     * @return FlushResult if flush was executed, null if not needed.
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

        val dailyFileName = "project/daily-${LocalDate.now()}.md"

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

        // Append to daily file (or create if not exists)
        val existing = store.read(agentContext, dailyFileName, scope)
        val newContent = if (existing != null) {
            "$existing\n\n$content"
        } else {
            "# Daily Memory — ${LocalDate.now()}\n\n$content"
        }

        val entry = MemoryEntry(
            name = "daily-${LocalDate.now()}",
            description = "Daily memory for ${LocalDate.now()}",
            type = MemoryType.PROJECT,
            content = newContent,
            path = dailyFileName,
            created = LocalDate.now(),
            updated = LocalDate.now()
        )

        store.write(agentContext, entry, scope)
        flushedHashes.add(contextHash)
        // Evict oldest entries when capacity exceeded (LinkedHashSet guarantees insertion order)
        while (flushedHashes.size > maxHashes) {
            flushedHashes.remove(flushedHashes.first())
        }

        logger.info("Memory flush completed: wrote {} chars to {}", content.length, dailyFileName)
        return FlushResult(content = content, path = dailyFileName)
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
        appendLine("Format each fact as a bullet point: '- [category] fact or decision'")
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

    data class FlushResult(val content: String, val path: String)
}
