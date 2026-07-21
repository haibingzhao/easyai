package com.easy.easyai.web.model

import com.easy.easyai.core.model.ContextReferences
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonIgnore

/**
 * SSE event types for chat streaming, inspired by pi-mono's proxy protocol.
 * Events are designed to be minimal - partial fields stripped to reduce bandwidth.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(value = [
    JsonSubTypes.Type(value = ChatStreamEvent.Start::class, name = "start"),
    JsonSubTypes.Type(value = ChatStreamEvent.TextStart::class, name = "text_start"),
    JsonSubTypes.Type(value = ChatStreamEvent.TextDelta::class, name = "text_delta"),
    JsonSubTypes.Type(value = ChatStreamEvent.TextEnd::class, name = "text_end"),
    JsonSubTypes.Type(value = ChatStreamEvent.ThinkingStart::class, name = "thinking_start"),
    JsonSubTypes.Type(value = ChatStreamEvent.ThinkingDelta::class, name = "thinking_delta"),
    JsonSubTypes.Type(value = ChatStreamEvent.ThinkingEnd::class, name = "thinking_end"),
    JsonSubTypes.Type(value = ChatStreamEvent.ToolCallStart::class, name = "toolcall_start"),
    JsonSubTypes.Type(value = ChatStreamEvent.ToolCallDelta::class, name = "toolcall_delta"),
    JsonSubTypes.Type(value = ChatStreamEvent.ToolCallEnd::class, name = "toolcall_end"),
    JsonSubTypes.Type(value = ChatStreamEvent.ToolCallStatus::class, name = "toolcall_status"),
    JsonSubTypes.Type(value = ChatStreamEvent.ToolExecutionStart::class, name = "tool_execution_start"),
    JsonSubTypes.Type(value = ChatStreamEvent.ToolExecutionUpdate::class, name = "tool_execution_update"),
    JsonSubTypes.Type(value = ChatStreamEvent.ToolExecutionEnd::class, name = "tool_execution_end"),
    JsonSubTypes.Type(value = ChatStreamEvent.Done::class, name = "done"),
    JsonSubTypes.Type(value = ChatStreamEvent.Error::class, name = "error"),
    JsonSubTypes.Type(value = ChatStreamEvent.Cancelled::class, name = "cancelled"),
    JsonSubTypes.Type(value = ChatStreamEvent.Retry::class, name = "retry"),
    JsonSubTypes.Type(value = ChatStreamEvent.CompactionStart::class, name = "compaction_start"),
    JsonSubTypes.Type(value = ChatStreamEvent.CompactionEnd::class, name = "compaction_end"),
    JsonSubTypes.Type(value = ChatStreamEvent.PermissionRequest::class, name = "permission_request"),
    JsonSubTypes.Type(value = ChatStreamEvent.MessageEnd::class, name = "message_end"),
    JsonSubTypes.Type(value = ChatStreamEvent.UserMessageAdded::class, name = "user_message_added"),
    JsonSubTypes.Type(value = ChatStreamEvent.Checkpoint::class, name = "checkpoint"),
    JsonSubTypes.Type(value = ChatStreamEvent.Revert::class, name = "revert"),
    JsonSubTypes.Type(value = ChatStreamEvent.GoalStatus::class, name = "goal_status"),
    JsonSubTypes.Type(value = ChatStreamEvent.UserMessageAck::class, name = "user_message_ack")
])
sealed interface ChatStreamEvent {
    @get:JsonIgnore
    val type: String

    /** Stream started */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class Start(
        val sessionId: String? = null
    ) : ChatStreamEvent {
        override val type: String get() = "start"
    }

    /** Text content block started */
    data class TextStart(
        val contentIndex: Int
    ) : ChatStreamEvent {
        override val type: String get() = "text_start"
    }

    /** Text content delta */
    data class TextDelta(
        val contentIndex: Int,
        val delta: String,
        val turnId: Int? = null,
        val messageId: String? = null,
        val subAgentToolCallId: String? = null,
        val subAgentName: String? = null
    ) : ChatStreamEvent {
        override val type: String get() = "text_delta"
    }

    /** Text content block completed */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class TextEnd(
        val contentIndex: Int,
        val contentSignature: String? = null,
        val turnId: Int? = null,
        val durationMs: Long? = null,
        val subAgentToolCallId: String? = null,
        val subAgentName: String? = null
    ) : ChatStreamEvent {
        override val type: String get() = "text_end"
    }

    /** Thinking content block started */
    data class ThinkingStart(
        val contentIndex: Int
    ) : ChatStreamEvent {
        override val type: String get() = "thinking_start"
    }

    /** Thinking content delta */
    data class ThinkingDelta(
        val contentIndex: Int,
        val delta: String,
        val turnId: Int? = null,
        val messageId: String? = null,
        val subAgentToolCallId: String? = null,
        val subAgentName: String? = null
    ) : ChatStreamEvent {
        override val type: String get() = "thinking_delta"
    }

    /** Thinking content block completed */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class ThinkingEnd(
        val contentIndex: Int,
        val contentSignature: String? = null,
        val turnId: Int? = null,
        val durationMs: Long? = null,
        val subAgentToolCallId: String? = null,
        val subAgentName: String? = null
    ) : ChatStreamEvent {
        override val type: String get() = "thinking_end"
    }

    /** Tool call started with ID and name */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class ToolCallStart(
        val contentIndex: Int,
        val id: String,
        val toolName: String,
        val subAgentToolCallId: String? = null,
        val subAgentName: String? = null
    ) : ChatStreamEvent {
        override val type: String get() = "toolcall_start"
    }

    /** Tool call arguments delta */
    data class ToolCallDelta(
        val contentIndex: Int,
        val id: String,
        val delta: String
    ) : ChatStreamEvent {
        override val type: String get() = "toolcall_delta"
    }

    /** Tool call completed */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class ToolCallEnd(
        val contentIndex: Int,
        val id: String,
        val subAgentToolCallId: String? = null,
        val subAgentName: String? = null
    ) : ChatStreamEvent {
        override val type: String get() = "toolcall_end"
    }

    /** Tool call status changed */
    data class ToolCallStatus(
        val toolCallId: String,
        val toolName: String,
        val status: String,  // "PENDING" | "RUNNING" | "COMPLETED" | "FAILED"
        val turnId: Int? = null,
        val subAgentToolCallId: String? = null,
        val subAgentName: String? = null
    ) : ChatStreamEvent {
        override val type: String get() = "toolcall_status"
    }

    /** Tool execution started */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class ToolExecutionStart(
        val toolCallId: String,
        val toolName: String,
        val args: Map<String, Any?> = emptyMap(),
        val turnId: Int? = null,
        val subAgentToolCallId: String? = null,
        val subAgentName: String? = null,
        /** Whether this tool modifies files on disk (triggers snapshot tracking). */
        val tracksFileChanges: Boolean = false
    ) : ChatStreamEvent {
        override val type: String get() = "tool_execution_start"
    }

    /** Tool execution output */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class ToolExecutionUpdate(
        val toolCallId: String,
        val output: String,
        val turnId: Int? = null,
        val subAgentToolCallId: String? = null,
        val subAgentName: String? = null
    ) : ChatStreamEvent {
        override val type: String get() = "tool_execution_update"
    }

    /** Tool execution completed */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class ToolExecutionEnd(
        val toolCallId: String,
        val toolName: String,
        val result: String? = null,
        val isError: Boolean = false,
        val exitCode: Int? = null,
        val mimeType: String? = null,
        val truncated: Boolean = false,
        val turnId: Int? = null,
        val subAgentToolCallId: String? = null,
        val subAgentName: String? = null,
        /** Token usage from this tool execution (e.g., sub-agent LLM calls) */
        val toolUsage: UsageInfo? = null,
        /** Whether this tool modifies files on disk (triggers snapshot tracking). */
        val tracksFileChanges: Boolean = false
    ) : ChatStreamEvent {
        override val type: String get() = "tool_execution_end"
    }

    /** Stream completed */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class Done(
        val reason: String = "stop",
        val usage: UsageInfo? = null,
        /** Why the agent loop ended: "normal" | "max_iterations" */
        val endReason: String? = null
    ) : ChatStreamEvent {
        override val type: String get() = "done"
    }

    /** Error occurred */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class Error(
        val reason: String = "error",
        val errorMessage: String? = null,
        val isRetryable: Boolean = true,
        val messageId: String? = null,
        val usage: UsageInfo? = null,
        val turnId: Int? = null
    ) : ChatStreamEvent {
        override val type: String get() = "error"
    }

    /** Stream cancelled by user */
    data class Cancelled(
        val reason: String = "user_cancelled"
    ) : ChatStreamEvent {
        override val type: String get() = "cancelled"
    }

    /** LLM call is being retried after timeout */
    data class Retry(
        val attempt: Int,
        val maxRetries: Int,
        val backoffMs: Long,
        val turnId: Int? = null
    ) : ChatStreamEvent {
        override val type: String get() = "retry"
    }

    /** Context compaction started */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class CompactionStart(
        val turnId: Int,
        val reason: String,           // "auto" | "manual" | "overflow"
        val messageCount: Int
    ) : ChatStreamEvent {
        override val type: String get() = "compaction_start"
    }

    /** Context compaction completed */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class CompactionEnd(
        val turnId: Int,
        val summary: String,
        val compactedCount: Int,
        val tokensSaved: Int,
        val tailStartMessageId: String? = null,
        val currentTokens: Int = 0,
        val durationMs: Long = 0,
        val usage: UsageInfo? = null
    ) : ChatStreamEvent {
        override val type: String get() = "compaction_end"
    }

    /** Permission request for tool execution - SSE stream will pause until user responds */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class PermissionRequest(
        val toolCallId: String,
        val toolName: String,
        val permission: String,
        val pattern: String,
        val arguments: Map<String, Any?> = emptyMap(),
        val subAgentToolCallId: String? = null,
        val subAgentName: String? = null
    ) : ChatStreamEvent {
        override val type: String get() = "permission_request"
    }

    /** Message completed — carries usage info and context references for the individual assistant message */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class MessageEnd(
        val messageId: String,
        val turnId: Int? = null,
        val usage: UsageInfo? = null,
        val subAgentToolCallId: String? = null,
        val subAgentName: String? = null,
        val references: ReferencesSnapshot? = null
    ) : ChatStreamEvent {
        override val type: String get() = "message_end"
    }

    /** A user message was dynamically added during the agent loop (e.g., auto-continue prompt) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class UserMessageAdded(
        val messageId: String,
        val content: String,
        val metadata: Map<String, String> = emptyMap()
    ) : ChatStreamEvent {
        override val type: String get() = "user_message_added"
    }

    /** Checkpoint created after a turn with file changes */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class Checkpoint(
        val messageId: String? = null,
        val assistantMessageId: String? = null,
        val snapshotHash: String? = null,
        val filesChanged: List<FileChangeInfo> = emptyList(),
        val additions: Int = 0,
        val deletions: Int = 0
    ) : ChatStreamEvent {
        override val type: String get() = "checkpoint"
    }

    /** Revert operation completed */
    data class Revert(
        val messageId: String,
        val additions: Int,
        val deletions: Int,
        val filesCount: Int
    ) : ChatStreamEvent {
        override val type: String get() = "revert"
    }

    /** Acknowledgment of the user's original message — carries the persisted messageId so the frontend can enable editing */
    data class UserMessageAck(
        val messageId: String
    ) : ChatStreamEvent {
        override val type: String get() = "user_message_ack"
    }

    /** Goal status change notification */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class GoalStatus(
        val sessionId: String,
        val objective: String,
        val status: String,
        val turnCount: Int = 0,
        val maxTurns: Int = 0,
        val elapsedSeconds: Long = 0,
        val evidence: String? = null,
        val blockedReason: String? = null
    ) : ChatStreamEvent {
        override val type: String get() = "goal_status"
    }

    /** Todo item for SSE serialization */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class TodoItem(
        val id: String,
        val content: String,
        val status: String,
        val priority: String,
        val position: Int,
        val createdAt: Long
    ) {
        companion object {
            fun from(info: com.easy.easyai.core.model.TodoInfo): TodoItem = TodoItem(
                id = info.id,
                content = info.content,
                status = info.status.name.lowercase(),
                priority = info.priority.name.lowercase(),
                position = info.position,
                createdAt = info.createdAt
            )
        }
    }

    /** Usage information */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class UsageInfo(
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val totalTokens: Int = 0,
        val cacheReadTokens: Int = 0,
        val cacheWriteTokens: Int = 0,
        val durationMs: Long = 0
    )

    /** Snapshot of context references (memories and rules) injected into the system prompt */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class ReferencesSnapshot(
        val memories: List<MemoryRefDto> = emptyList(),
        val rules: List<RuleRefDto> = emptyList()
    ) {
        companion object {
            /** Convert domain [ContextReferences] to SSE snapshot DTO. */
            @JvmStatic
            fun from(refs: ContextReferences): ReferencesSnapshot = ReferencesSnapshot(
                memories = refs.memories.map { mem ->
                    MemoryRefDto(
                        name = mem.name,
                        description = mem.description,
                        type = mem.type.dirName,
                        scope = mem.scope.name.lowercase()
                    )
                },
                rules = refs.rules.map { rule ->
                    RuleRefDto(name = rule.name, source = rule.source)
                }
            )
        }
    }

    /** DTO for a memory reference in SSE events */
    data class MemoryRefDto(
        val name: String,
        val description: String,
        val type: String,
        val scope: String
    )

    /** DTO for a rule reference in SSE events */
    data class RuleRefDto(
        val name: String,
        val source: String
    )
}