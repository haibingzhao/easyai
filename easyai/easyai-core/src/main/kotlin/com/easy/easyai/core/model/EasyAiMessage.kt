package com.easy.easyai.core.model

import com.easy.easyai.core.memory.MemoryRef
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.util.*

/**
 * Unified role enum for all message types.
 * Aligns with OpenAI/Anthropic message roles for API compatibility.
 */
enum class Role {
    SYSTEM, USER, ASSISTANT, TOOL, ERROR, CUSTOM
}

/**
 * Base interface for all content blocks in a message.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = TextContent::class, name = "text"),
    JsonSubTypes.Type(value = ImageContent::class, name = "image"),
    JsonSubTypes.Type(value = FileRefContent::class, name = "fileRef"),
    JsonSubTypes.Type(value = ThinkingContent::class, name = "thinking"),
    JsonSubTypes.Type(value = ToolCallContent::class, name = "toolCall"),
    JsonSubTypes.Type(value = ToolResultContent::class, name = "toolResult"),
    JsonSubTypes.Type(value = CustomContent::class, name = "custom"),
)
sealed interface ContentBlock {
    val type: String
}

data class TextContent(
    val text: String,
    val textSignature: String? = null,
    val durationMs: Long? = null
) : ContentBlock {
    @get:JsonIgnore
    override val type: String get() = "text"
}

data class ImageContent(
    val data: ByteArray,
    val mimeType: String
) : ContentBlock {
    @get:JsonIgnore
    override val type: String get() = "image"

    override fun equals(other: Any?): Boolean =
        this === other || (other is ImageContent && data.contentEquals(other.data) && mimeType == other.mimeType)
    override fun hashCode(): Int = data.contentHashCode() * 31 + mimeType.hashCode()
}

/**
 * Reference to a file stored on the local filesystem.
 * Replaces [ImageContent] for persistence — only the file path is stored in DB,
 * not the raw bytes. Bytes are read from disk when sending to the LLM.
 *
 * Also used for text file attachments: the path is referenced instead of inlining
 * the full file content into the message text.
 */
data class FileRefContent(
    /** Absolute path to the file on the local filesystem. */
    val filePath: String,
    /** Display name of the file. */
    val name: String,
    /** MIME type of the file (e.g. "image/png", "text/plain"). */
    val mimeType: String,
    /**
     * Origin of this file reference (persisted for DB round-trips).
     * - `"attachment"` (default): uploaded via paperclip attachment; path must be within [com.easy.easyai.core.message.DefaultMessageConverter.allowedBaseDir].
     * - `"inline"`: extracted from `@` mention in message text by AttachmentProcessor; already validated against project directory.
     */
    val source: String = "attachment"
) : ContentBlock {
    @get:JsonIgnore
    override val type: String get() = "fileRef"
}

data class ThinkingContent(
    val thinking: String,
    val thinkingSignature: String? = null,
    val redacted: Boolean = false,
    val durationMs: Long? = null
) : ContentBlock {
    @get:JsonIgnore
    override val type: String get() = "thinking"
}

/**
 * Content block representing a tool call in an assistant message.
 * This only captures the invocation (id, name, arguments) — not the result.
 * Tool results are represented separately via [ToolResultContent] in [ToolResultMessage].
 */
data class ToolCallContent(
    val id: String,
    val name: String,
    val arguments: String,
    val thoughtSignature: String? = null
) : ContentBlock {
    @get:JsonIgnore
    override val type: String get() = "toolCall"
}

/**
 * Content block representing the result of a tool execution.
 * Used in [ToolResultMessage] to pass tool results back to the LLM.
 * [toolCallId] and [toolName] are required — they identify which tool call this result belongs to.
 */
data class ToolResultContent(
    val toolCallId: String = "",
    val toolName: String = "",
    val output: String,
    val exitCode: Int? = null,
    val durationMs: Long? = null,
    val mimeType: String = "text/plain",
    val isError: Boolean = false,
    val truncated: Boolean = false,
    val isSkipped: Boolean = false,
    val usage: Usage? = null
) : ContentBlock {
    @get:JsonIgnore
    override val type: String get() = "toolResult"
}

/**
 * Custom content block for extensible message types.
 * Used for system-level metadata like compaction indicators.
 * Not sent to LLM - only used for frontend display or internal tracking.
 */
data class CustomContent(
    val customType: String,
    val metadata: Map<String, Any?> = emptyMap(),
    val durationMs: Long? = null
) : ContentBlock {
    @get:JsonIgnore
    override val type: String get() = "custom"
}

enum class StopReason {
    STOP,
    LENGTH,
    TOOL_USE,
    ERROR,
    ABORTED
}

data class Usage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    val cost: Double = 0.0,
    val durationMs: Long = 0
) {
    @get:JsonProperty("totalTokens")
    val totalTokens: Int get() = inputTokens + outputTokens
}

/**
 * Generate a unique message ID using nanoTime for better precision in high-concurrency scenarios.
 */
fun generateMessageId(): String = "msg_${System.nanoTime()}_${UUID.randomUUID().toString().take(8)}"

sealed interface EasyAiMessage {
    val id: String
    val role: Role
    val content: List<ContentBlock>
}

/**
 * Message that holds tool execution results.
 * Used to pass tool results to the next LLM iteration.
 */
data class ToolResultMessage(
    override val id: String = generateMessageId(),
    val toolResults: List<ToolResultEntry> = emptyList()
) : EasyAiMessage {
    override val role: Role get() = Role.TOOL
    override val content: List<ContentBlock> get() = toolResults.map { entry ->
        ToolResultContent(
            toolCallId = entry.toolCallId,
            toolName = entry.toolName,
            output = entry.result,
            exitCode = entry.exitCode,
            durationMs = entry.durationMs,
            mimeType = entry.mimeType,
            isError = entry.isError,
            truncated = entry.truncated,
            isSkipped = entry.isSkipped,
            usage = entry.usage
        )
    }
}

data class ToolResultEntry(
    val toolCallId: String,
    val toolName: String,
    val result: String,
    val exitCode: Int? = null,
    val durationMs: Long? = null,
    val mimeType: String = "text/plain",
    val isError: Boolean = false,
    val truncated: Boolean = false,
    val isSkipped: Boolean = false,
    val usage: Usage? = null
)

data class UserMessage(
    override val id: String = generateMessageId(),
    override val content: List<ContentBlock>,
    val metadata: Map<String, String> = emptyMap(),
    val usage: Usage? = null
) : EasyAiMessage {
    override val role: Role get() = Role.USER

    constructor(text: String) : this(generateMessageId(), listOf(TextContent(text)))
    
    constructor(id: String, text: String) : this(id, listOf(TextContent(text)))

    companion object {
        const val SOURCE_KEY = "source"
        const val SOURCE_STEERING = "steering"
        const val SOURCE_FOLLOW_UP = "follow_up"
        const val SOURCE_COMPLETION_CHECK = "completion_check"
        const val COMMAND_EXPANSION = "commandExpansion"
        const val COMMAND_NAME = "commandName"
    }
}

/**
 * System-level message injected into the conversation context.
 * Not persisted to DB — only visible to the LLM for the current turn.
 * Used for command template expansion and other ephemeral instructions.
 *
 * Position convention: SystemMessage is inserted immediately before the UserMessage
 * that triggered the command, resulting in mid-conversation system role messages.
 * Downstream consumers (custom ChatModel adapters, compaction listeners, etc.)
 * must not assume system messages only appear at position 0.
 */
data class SystemMessage(
    override val id: String = generateMessageId(),
    val text: String,
) : EasyAiMessage {
    override val role: Role get() = Role.SYSTEM
    override val content: List<ContentBlock> get() = listOf(TextContent(text))
}
data class CustomMessage(
    override val id: String = generateMessageId(),
    override val content: List<ContentBlock>,
    val metadata: Map<String, String> = emptyMap()
) : EasyAiMessage {
    override val role: Role get() = Role.CUSTOM
}

/**
 * Reference to a rule file (e.g., AGENTS.md) that was injected into the system prompt.
 */
data class RuleRef(
    val name: String,
    val source: String
)

/**
 * Context references that were injected into an assistant message's system prompt.
 * Tracks which memory entries and rule files contributed to the response.
 */
data class ContextReferences(
    val memories: List<MemoryRef> = emptyList(),
    val rules: List<RuleRef> = emptyList()
)

data class AssistantMessage(
    override val id: String = generateMessageId(),
    override val content: List<ContentBlock>,
    val stopReason: StopReason? = null,
    val usage: Usage = Usage(),
    val references: ContextReferences? = null
) : EasyAiMessage {
    override val role: Role get() = Role.ASSISTANT

    fun text(): String = content.filterIsInstance<TextContent>().joinToString("") { it.text }

    fun toolCalls(): List<ToolCallContent> = content.filterIsInstance<ToolCallContent>()

    /** Build the metadata map for persistence from this message's fields. */
    fun toMetadataMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        if (references != null) map["references"] = references
        return map
    }
}

/**
 * Message that represents an error that occurred during LLM processing.
 * Stored in the message table with role=ERROR so it can be displayed in history.
 * When retry succeeds, this message is deleted and replaced with a normal assistant response.
 */
data class ErrorMessage(
    override val id: String = generateMessageId(),
    val error: String,
    val isRetryable: Boolean = true,
    val attempt: Int = 0,
    val maxRetries: Int = 0
) : EasyAiMessage {
    override val role: Role get() = Role.ERROR
    override val content: List<ContentBlock> get() = listOf(TextContent(error))
}

