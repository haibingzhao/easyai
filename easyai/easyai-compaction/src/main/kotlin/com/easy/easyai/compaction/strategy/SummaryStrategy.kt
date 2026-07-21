package com.easy.easyai.compaction.strategy

import com.easy.easyai.compaction.model.CompactionContext
import com.easy.easyai.core.model.*
import org.springframework.ai.chat.model.ChatModel

/**
 * Default compaction strategy that generates summaries using local template logic.
 * No LLM calls required - extracts metadata from messages mechanically.
 *
 * Extracts:
 * - Tool activity (names of tools used)
 * - File references (paths mentioned in tool calls/results)
 * - Recent highlights (preview of last N messages)
 */
class SummaryStrategy(
    private val maxHighlights: Int = 6
) : CompactionStrategy {

    override fun compact(
        messages: List<EasyAiMessage>,
        context: CompactionContext,
        chatModel: ChatModel?
    ): String {
        val tools = extractToolNames(messages)
        val files = extractFileReferences(messages)
        val highlights = extractHighlights(messages)

        val previousSummarySection = context.previousSummary?.let {
            "\n## Previous Summary\n\n$it"
        } ?: ""

        return buildString {
            append("Context summary:")
            append(previousSummarySection)
            append("\n\n## Compacted Range\n")
            append("- Messages compacted: ${messages.size}\n")
            append("- Estimated tokens before compaction: ${context.range.estimatedTokensBefore}\n")
            append("- User messages: ${context.range.userRoleCount}\n")
            append("- Assistant messages: ${context.range.assistantRoleCount}\n")

            if (tools.isNotEmpty()) {
                append("\n## Tool Activity\n")
                tools.forEach { append("- $it\n") }
            }

            if (files.isNotEmpty()) {
                append("\n## Files Mentioned\n")
                files.forEach { append("- $it\n") }
            }

            if (highlights.isNotEmpty()) {
                append("\n## Recent Highlights From Compacted History\n")
                highlights.forEach { append("- $it\n") }
            }
        }
    }

    private fun extractToolNames(messages: List<EasyAiMessage>): List<String> {
        val toolNames = mutableSetOf<String>()
        messages.forEach { msg ->
            when (msg) {
                is AssistantMessage -> msg.content.filterIsInstance<ToolCallContent>()
                    .forEach { toolNames.add(it.name) }
                is ToolResultMessage -> msg.toolResults.forEach { toolNames.add(it.toolName) }
                else -> {}
            }
        }
        return toolNames.sorted()
    }

    private fun extractFileReferences(messages: List<EasyAiMessage>): List<String> {
        val files = mutableSetOf<String>()
        // Regex patterns for common file path formats
        val filePattern = Regex("([\\w./-]+\\.[a-zA-Z0-9]+)")

        messages.forEach { msg ->
            msg.content.forEach { block ->
                when (block) {
                    is TextContent -> {
                        filePattern.findAll(block.text).forEach { match ->
                            val path = match.groupValues[1]
                            if (isValidFilePath(path)) {
                                files.add(path)
                            }
                        }
                    }
                    is ToolCallContent -> {
                        filePattern.findAll(block.arguments).forEach { match ->
                            val path = match.groupValues[1]
                            if (isValidFilePath(path)) {
                                files.add(path)
                            }
                        }
                    }
                    is ToolResultContent -> {
                        filePattern.findAll(block.output).forEach { match ->
                            val path = match.groupValues[1]
                            if (isValidFilePath(path)) {
                                files.add(path)
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
        return files.sorted().take(50) // Limit to avoid huge summaries
    }

    private fun isValidFilePath(path: String): Boolean {
        // Basic validation: must contain at least one slash and extension
        return path.contains("/") && path.contains(".") && path.length > 3 && path.length < 200
    }

    private fun extractHighlights(messages: List<EasyAiMessage>): List<String> {
        return messages.takeLast(maxHighlights).map { msg ->
            when (msg) {
                is UserMessage -> "[user]: ${previewMessage(msg)}"
                is AssistantMessage -> {
                    val toolCalls = msg.content.filterIsInstance<ToolCallContent>()
                    if (toolCalls.isNotEmpty()) {
                        "[assistant tool call]: ${toolCalls.first().name}(...)"
                    } else {
                        "[assistant]: ${previewMessage(msg)}"
                    }
                }
                is ToolResultMessage -> {
                    val firstResult = msg.toolResults.firstOrNull()
                    "[tool result]: ${firstResult?.toolName ?: "unknown"} -> ${firstResult?.result?.take(100) ?: ""}"
                }
                else -> "[${msg.role}]: ${previewMessage(msg)}"
            }
        }
    }

    private fun previewMessage(msg: EasyAiMessage): String {
        val text = msg.content.filterIsInstance<TextContent>().joinToString("") { it.text }
        return text.take(100).replace("\n", " ")
    }
}