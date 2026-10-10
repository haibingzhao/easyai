package com.easy.easyai.api.llm

import java.net.URI

/**
 * Role of a [Message] in a conversation, mirroring the wire roles every supported
 * provider protocol understands.
 */
enum class MessageType {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL
}

/**
 * Protocol-neutral chat message. This is easyai's own replacement for Spring AI's
 * `chat.messages.Message`: [DefaultMessageConverter] produces these from
 * the persisted `EasyAiMessage` domain model, and each protocol adapter translates them into the
 * provider SDK's request shape. Kept deliberately minimal — only what the adapters and the
 * observation layer read.
 */
sealed interface Message {
    val messageType: MessageType

    /** Best-effort plain-text view used by tracing/observation; may be null for tool responses. */
    val text: String?
}

/** A user turn: text plus optional inline/remote [Media] attachments. */
data class UserMessage(
    val content: String,
    val media: List<Media> = emptyList()
) : Message {
    override val messageType: MessageType get() = MessageType.USER
    override val text: String get() = content
}

/** A system/instruction turn. */
data class SystemMessage(
    val content: String
) : Message {
    override val messageType: MessageType get() = MessageType.SYSTEM
    override val text: String get() = content
}

/**
 * An assistant turn. [text] is the visible content (empty/null for tool-call-only or
 * thinking-only chunks); [toolCalls] are the requested tool invocations; [metadata] carries
 * out-of-band signals the agent loop reads — notably `"thinking" = true` on reasoning chunks and
 * `"signature"` on Anthropic thinking-signature chunks.
 *
 * [thinkingBlocks] is the send-direction only: reasoning persisted for this turn, replayed only
 * by Anthropic (signed thinking blocks). OpenAI chat-completions has no replay field, and
 * DashScope deliberately never replays reasoning (preserve_thinking requires it verbatim while
 * compaction truncates) — both drop it. Presence is decided by the agent-level
 * `thinkingHistoryEnabled` projection upstream.
 */
data class AssistantMessage(
    val content: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val thinkingBlocks: List<ThinkingBlock> = emptyList(),
    val metadata: Map<String, Any> = emptyMap()
) : Message {
    override val messageType: MessageType get() = MessageType.ASSISTANT
    override val text: String? get() = content

    /** A single tool invocation requested by the model. [type] is always `"function"` today. */
    data class ToolCall(
        val id: String,
        val type: String,
        val name: String,
        val arguments: String
    )

    /**
     * One persisted reasoning block for replay. [signature] is Anthropic's integrity signature;
     * a block without one is skipped by the Anthropic adapter (the API rejects unsigned thinking
     * blocks). [redacted] marks a security-redacted block whose opaque payload lives in
     * [signature] rather than [text].
     */
    data class ThinkingBlock(
        val text: String,
        val signature: String? = null,
        val redacted: Boolean = false
    )
}

/** A turn carrying the results of previously requested tool calls. */
data class ToolResponseMessage(
    val responses: List<ToolResponse>
) : Message {
    override val messageType: MessageType get() = MessageType.TOOL
    override val text: String? get() = responses.joinToString("\n") { it.responseData }

    /** One tool result, keyed by the id of the [AssistantMessage.ToolCall] it answers. */
    data class ToolResponse(
        val id: String,
        val name: String,
        val responseData: String
    )
}

/**
 * A media attachment. [mimeType] is a plain string (e.g. `image/png`) so this type stays free of
 * any Spring dependency; [source] distinguishes inline bytes from a remote URL reference.
 */
data class Media(
    val mimeType: String,
    val source: MediaSource
)

/** The payload of a [Media]: either inline [Bytes] or a remote [Url]. */
sealed interface MediaSource {
    class Bytes(val data: ByteArray) : MediaSource
    data class Url(val uri: URI) : MediaSource
}
