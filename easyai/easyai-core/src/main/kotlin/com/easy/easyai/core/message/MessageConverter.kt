package com.easy.easyai.core.message

import com.easy.easyai.core.model.*
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.content.Media
import org.springframework.util.MimeType
import java.nio.file.Files
import java.nio.file.Path
import org.springframework.ai.chat.messages.AssistantMessage as SpringAiAssistantMessage
import org.springframework.ai.chat.messages.SystemMessage as SpringAiSystemMessage
import org.springframework.ai.chat.messages.UserMessage as SpringAiUserMessage

interface MessageConverter {
    fun toSpringAiMessages(messages: List<EasyAiMessage>): List<org.springframework.ai.chat.messages.Message>
    fun fromSpringAiResponse(response: ChatResponse): AssistantMessage
}

class DefaultMessageConverter(
    /**
     * Base directory that FileRefContent paths must reside within.
     * When set, any file path outside this directory is rejected (security: prevents arbitrary file reads).
     * Typically configured by the web layer to point at FileStorageService's images root.
     */
    var allowedBaseDir: Path? = null,
    /**
     * Maximum combined size (bytes) of all inline file references in a single user message.
     * When total exceeds this limit, all text file refs are converted to path-only references
     * (the LLM can then read them on demand via tools). Images are still inlined as Media.
     */
    var maxTotalInlineFileBytes: Long = DEFAULT_MAX_TOTAL_INLINE_FILE_BYTES
) : MessageConverter {
    companion object {
        const val DEFAULT_MAX_TOTAL_INLINE_FILE_BYTES: Long = 10L * 1024 * 1024 // 10 MB
    }

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun toSpringAiMessages(messages: List<EasyAiMessage>): List<org.springframework.ai.chat.messages.Message> =
        messages.flatMap { msg ->
            when (msg) {
                is UserMessage -> {
                    val textParts = msg.content.filterIsInstance<TextContent>().map { it.text }.toMutableList()
                    val images = msg.content.filterIsInstance<ImageContent>()
                    val fileRefs = msg.content.filterIsInstance<FileRefContent>()

                    // Process FileRefContent: images → Media, text → inline text
                    val mediaList = mutableListOf<Media>()
                    val maxFileBytes = 10L * 1024 * 1024 // 10 MB per-file safety limit

                    // Validate and resolve all file refs first, collecting sizes
                    data class ResolvedRef(val ref: FileRefContent, val path: Path, val size: Long)
                    val resolvedRefs = mutableListOf<ResolvedRef>()
                    for (img in images) {
                        mediaList.add(
                            Media.builder()
                                .mimeType(MimeType.valueOf(img.mimeType))
                                .data(img.data)
                                .build()
                        )
                    }
                    for (ref in fileRefs) {
                        val resolvedPath = try {
                            Path.of(ref.filePath).toAbsolutePath().normalize()
                        } catch (_: Exception) {
                            logger.warn("FileRefContent: invalid path, skipping: {}", ref.filePath)
                            continue
                        }
                        // Security: enforce path is within allowed base directory
                        // (skip for inline @ refs — already validated against project dir by AttachmentProcessor)
                        val baseDir = allowedBaseDir
                        if (baseDir != null && ref.source != "inline" && !resolvedPath.startsWith(baseDir)) {
                            logger.warn("FileRefContent: path outside allowed directory, skipping: {}", ref.filePath)
                            continue
                        }
                        if (!Files.isRegularFile(resolvedPath)) {
                            logger.warn("FileRefContent: file not found, skipping: {}", ref.filePath)
                            continue
                        }
                        val fileSize = try { Files.size(resolvedPath) } catch (_: Exception) { Long.MAX_VALUE }
                        if (fileSize > maxFileBytes) {
                            logger.warn("FileRefContent: file too large ({} bytes), skipping: {}", fileSize, ref.filePath)
                            continue
                        }
                        resolvedRefs.add(ResolvedRef(ref, resolvedPath, fileSize))
                    }

                    // Check total size of text file refs (images are handled separately as Media)
                    val textRefs = resolvedRefs.filter { !it.ref.mimeType.startsWith("image/") }
                    val totalTextBytes = textRefs.sumOf { it.size }
                    val exceedsTotalLimit = totalTextBytes > maxTotalInlineFileBytes

                    if (exceedsTotalLimit) {
                        logger.info(
                            "FileRefContent: total text file size ({} bytes) exceeds limit ({} bytes), " +
                                "converting {} files to path-only references",
                            totalTextBytes, maxTotalInlineFileBytes, textRefs.size
                        )
                        // Build a path-only summary for the LLM; it can read files via tools
                        val pathSummary = textRefs.joinToString("\n") { resolved ->
                            "- ${resolved.ref.name}: ${resolved.path}"
                        }
                        textParts.add(
                            "<attached-files>\n" +
                            "The following files were attached but not inlined due to total size limit " +
                            "(${totalTextBytes} bytes exceeds ${maxTotalInlineFileBytes} bytes limit). " +
                            "Use the read tool to access their contents as needed:\n" +
                            pathSummary +
                            "\n</attached-files>"
                        )
                    }

                    // Refs are re-inserted into the original text at their recorded
                    // displayOffset so the LLM sees which sentence each ref belongs to
                    // (uploaded attachments are anchored at the end of the text by
                    // AttachmentProcessor).
                    val anchoredInsertions = mutableListOf<Pair<Int, String>>() // offset to snippet

                    for (resolved in resolvedRefs) {
                        val ref = resolved.ref
                        val path = resolved.path
                        if (ref.mimeType.startsWith("image/")) {
                            // Image file → Media object (always inline)
                            val bytes = try { Files.readAllBytes(path) } catch (e: Exception) {
                                logger.warn("FileRefContent: failed to read image file: {}", ref.filePath, e)
                                continue
                            }
                            mediaList.add(
                                Media.builder()
                                    .mimeType(MimeType.valueOf(ref.mimeType))
                                    .data(bytes)
                                    .build()
                            )
                        } else if (!exceedsTotalLimit) {
                            // Text file → inline into message text (only when within total limit)
                            val fileText = try { Files.readString(path) } catch (e: Exception) {
                                logger.warn("FileRefContent: failed to read text file: {}", ref.filePath, e)
                                continue
                            }
                            // Wrap file content in CDATA to safely embed arbitrary text in XML
                            val snippet = "<file name=\"${escapeXmlAttr(ref.name)}\">\n<![CDATA[$fileText]]>\n</file>"
                            anchoredInsertions.add(ref.displayOffset to snippet)
                        }
                    }

                    // Folder references → inline [folder name: path] markers at their recorded
                    // offsets (one marker per occurrence — sentence-to-folder mapping preserved).
                    // Directory contents are never inlined — explored via list/read tools.
                    val folderRefs = msg.content.filterIsInstance<FolderRefContent>()
                    for (folder in folderRefs) {
                        anchoredInsertions.add(folder.displayOffset to "[folder ${folder.name}: ${folder.filePath}]")
                    }

                    if (anchoredInsertions.isNotEmpty()) {
                        // Invariant: textParts[0] is always the user's own text — buildContentBlocks
                        // emits at most one TextContent and generated blocks (<attached-files>) are
                        // appended after it. Offsets are relative to that text.
                        var base = if (textParts.isNotEmpty()) textParts.removeAt(0) else ""
                        var delta = 0
                        for ((offset, snippet) in anchoredInsertions.sortedBy { it.first }) {
                            val at = (offset + delta).coerceIn(0, base.length)
                            base = base.substring(0, at) + snippet + base.substring(at)
                            delta += snippet.length
                        }
                        textParts.add(0, base)
                    }

                    if (folderRefs.isNotEmpty()) {
                        // Instruction stated once for all inline-anchored folders
                        textParts.add(
                            "(Folder paths marked inline above are user-referenced directories; their contents are not " +
                                "inlined here — use directory listing and file reading tools to explore them as needed.)"
                        )
                    }

                    val text = textParts.joinToString("\n\n")
                    if (text.isEmpty() && mediaList.isEmpty()) emptyList()
                    else {
                        if (mediaList.isEmpty()) {
                            listOf(SpringAiUserMessage(text))
                        } else {
                            listOf(
                                SpringAiUserMessage.builder()
                                    .text(text)
                                    .media(mediaList)
                                    .build()
                            )
                        }
                    }
                }
                is AssistantMessage -> {
                    val text = msg.content.filterIsInstance<TextContent>().joinToString("") { it.text }
                    val toolCalls = msg.content.filterIsInstance<ToolCallContent>()
                    val springAiToolCalls = toolCalls.map { tc ->
                        SpringAiAssistantMessage.ToolCall(tc.id, "function", tc.name, tc.arguments)
                    }
                    if (springAiToolCalls.isEmpty()) {
                        listOf(SpringAiAssistantMessage(text))
                    } else {
                        listOf(SpringAiAssistantMessage.builder().content(text).toolCalls(springAiToolCalls).build())
                    }
                    // Tool results are handled separately via ToolResultMessage
                }
                is ToolResultMessage -> {
                    // Tool results are guarded at generation time (AgentLoop / PendingToolCallExecutor):
                    // oversized results are spilled to the temp dir and replaced with a pointer notice,
                    // so no send-time re-processing is needed here.
                    val responses = msg.toolResults.map { entry ->
                        ToolResponseMessage.ToolResponse(entry.toolCallId, entry.toolName, entry.result)
                    }
                    listOf(ToolResponseMessage.builder().responses(responses).build())
                }
                is ErrorMessage -> {
                    // ErrorMessage is not sent to LLM - it's for UI display only
                    // Return empty list since LLM doesn't understand error messages
                    emptyList()
                }
                is SystemMessage -> {
                    // SystemMessage may appear mid-conversation (before the triggering UserMessage),
                    // not just at position 0. Most LLM providers handle this correctly.
                    listOf(SpringAiSystemMessage(msg.text))
                }
                // Future message types should return empty list rather than silently fail
                else -> emptyList()
            }
        }

    /** Escape XML special characters for safe use in attribute values. */
    private fun escapeXmlAttr(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    override fun fromSpringAiResponse(response: ChatResponse): AssistantMessage {
        val result = response.result
        val contentBlocks = mutableListOf<ContentBlock>()

        if (!result?.output?.text.isNullOrBlank()) {
            contentBlocks.add(TextContent(result.output.text!!))
        }

        for (tc in result?.output?.toolCalls.orEmpty()) {
            contentBlocks.add(ToolCallContent(
                id = tc.id,
                name = tc.name,
                arguments = tc.arguments
            ))
        }

        val finishReasonStr = result?.metadata?.finishReason
        return AssistantMessage(
            id = generateMessageId(),
            content = contentBlocks,
            stopReason = when (finishReasonStr) {
                "stop" -> StopReason.STOP
                "length" -> StopReason.LENGTH
                "tool_calls" -> StopReason.TOOL_USE
                else -> StopReason.STOP
            },
            usage = response.metadata.usage.let { u ->
                Usage(
                    inputTokens = u.promptTokens,
                    outputTokens = u.completionTokens,
                    cacheReadTokens = u.cacheReadInputTokens?.toInt() ?: 0,
                    cacheWriteTokens = u.cacheWriteInputTokens?.toInt() ?: 0
                )
            }
        )
    }

}
