package com.easy.easyai.core.message

import com.easy.easyai.core.model.*
import com.easy.easyai.core.storage.ObjectStorageException
import com.easy.easyai.core.storage.ObjectStorageResolver
import com.easy.easyai.core.storage.StoredFileReference
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.content.Media
import org.springframework.util.MimeType
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.IdentityHashMap
import org.springframework.ai.chat.messages.AssistantMessage as SpringAiAssistantMessage
import org.springframework.ai.chat.messages.SystemMessage as SpringAiSystemMessage
import org.springframework.ai.chat.messages.UserMessage as SpringAiUserMessage

interface MessageConverter {
    suspend fun toSpringAiMessages(messages: List<EasyAiMessage>, userId: String = "system"): List<Message>
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
    var maxTotalInlineFileBytes: Long = DEFAULT_MAX_TOTAL_INLINE_FILE_BYTES,
    private val objectStorageResolver: ObjectStorageResolver? = null
) : MessageConverter {
    companion object {
        const val DEFAULT_MAX_TOTAL_INLINE_FILE_BYTES: Long = 10L * 1024 * 1024 // 10 MB
        private val STORED_IMAGE_MIME_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
    }

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun toSpringAiMessages(messages: List<EasyAiMessage>, userId: String): List<Message> =
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
                    data class AnchoredInsertion(val offset: Int, val seq: Int, val snippet: String)
                    val resolvedRefs = mutableListOf<ResolvedRef>()
                    // Original block order breaks insertion ties when several refs share one
                    // displayOffset (uploaded attachments are all anchored at the text end).
                    val blockSeq = IdentityHashMap<ContentBlock, Int>()
                    msg.content.forEachIndexed { index, block -> blockSeq.putIfAbsent(block, index) }
                    val anchoredInsertions = mutableListOf<AnchoredInsertion>()
                    var hasImageMarkers = false
                    fun imageMarker(ref: FileRefContent): String = "[image ${mediaList.size}: ${ref.name}]"
                    for (img in images) {
                        mediaList.add(
                            Media.builder()
                                .mimeType(MimeType.valueOf(img.mimeType))
                                .data(img.data)
                                .build()
                        )
                    }
                    for (ref in fileRefs) {
                        if (StoredFileReference.isStored(ref.filePath)) {
                            mediaList.add(resolveStoredImage(ref, userId))
                            anchoredInsertions.add(
                                AnchoredInsertion(ref.displayOffset, blockSeq[ref] ?: 0, imageMarker(ref))
                            )
                            hasImageMarkers = true
                            continue
                        }
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
                    // AttachmentProcessor). Images get a positional [image N: name] marker
                    // tying each Media entry to the sentence it was placed in.

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
                            anchoredInsertions.add(
                                AnchoredInsertion(ref.displayOffset, blockSeq[ref] ?: 0, imageMarker(ref))
                            )
                            hasImageMarkers = true
                        } else if (!exceedsTotalLimit) {
                            // Text file → inline into message text (only when within total limit)
                            val fileText = try { Files.readString(path) } catch (e: Exception) {
                                logger.warn("FileRefContent: failed to read text file: {}", ref.filePath, e)
                                continue
                            }
                            // Wrap file content in CDATA to safely embed arbitrary text in XML
                            val snippet = "<file name=\"${escapeXmlAttr(ref.name)}\">\n<![CDATA[$fileText]]>\n</file>"
                            anchoredInsertions.add(AnchoredInsertion(ref.displayOffset, blockSeq[ref] ?: 0, snippet))
                        }
                    }

                    // Folder references → inline [folder name: path] markers at their recorded
                    // offsets (one marker per occurrence — sentence-to-folder mapping preserved).
                    // Directory contents are never inlined — explored via list/read tools.
                    val folderRefs = msg.content.filterIsInstance<FolderRefContent>()
                    for (folder in folderRefs) {
                        anchoredInsertions.add(
                            AnchoredInsertion(
                                folder.displayOffset,
                                blockSeq[folder] ?: 0,
                                "[folder ${folder.name}: ${folder.filePath}]"
                            )
                        )
                    }

                    if (anchoredInsertions.isNotEmpty()) {
                        // Invariant: textParts[0] is always the user's own text — buildContentBlocks
                        // emits at most one TextContent and generated blocks (<attached-files>) are
                        // appended after it. Offsets are relative to that text.
                        var base = if (textParts.isNotEmpty()) textParts.removeAt(0) else ""
                        var delta = 0
                        for (insertion in anchoredInsertions.sortedWith(compareBy({ it.offset }, { it.seq }))) {
                            val at = (insertion.offset + delta).coerceIn(0, base.length)
                            base = base.substring(0, at) + insertion.snippet + base.substring(at)
                            delta += insertion.snippet.length
                        }
                        textParts.add(0, base)
                    }

                    if (hasImageMarkers) {
                        // Stated once for all positional image markers
                        textParts.add(
                            "(Markers like [image N: name] in the text above show where each attached image " +
                                "appeared in the user's message; image N is the N-th image attached to it.)"
                        )
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

    private suspend fun resolveStoredImage(ref: FileRefContent, userId: String): Media {
        val stored = StoredFileReference.parse(ref.filePath, userId)
        require(ref.mimeType in STORED_IMAGE_MIME_TYPES) { "Unsupported stored chat image MIME type" }
        val storage = objectStorageResolver?.resolve(userId)
            ?: throw ObjectStorageException("Object storage is unavailable for the stored chat image")
        val meta = storage.head(stored.key)
            ?: throw ObjectStorageException("Stored chat image does not exist: ${stored.key}")
        validateStoredImageSize(meta.size)
        val signedUri = try {
            storage.presignedGetUrl(stored.key, StoredFileReference.URL_TTL_SECONDS)?.let { URI(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to sign stored chat image; reading object instead: {}", stored.key, e)
            null
        }
        val mimeType = MimeType.valueOf(ref.mimeType)
        // Anthropic only accepts HTTPS URL media. Do not rewrite a signature's scheme.
        if (signedUri?.scheme == "https" && !signedUri.host.isNullOrBlank()) {
            return Media.builder().mimeType(mimeType).data(signedUri).build()
        }
        val content = storage.get(stored.key)
            ?: throw ObjectStorageException("Stored chat image could not be read: ${stored.key}")
        validateStoredImageSize(content.meta.size)
        validateStoredImageSize(content.bytes.size.toLong())
        return Media.builder().mimeType(mimeType).data(content.bytes).build()
    }

    private fun validateStoredImageSize(size: Long) {
        if (size < 0 || size > StoredFileReference.MAX_IMAGE_BYTES) {
            throw ObjectStorageException("Stored chat image exceeds the 6 MB limit or has an invalid size")
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
