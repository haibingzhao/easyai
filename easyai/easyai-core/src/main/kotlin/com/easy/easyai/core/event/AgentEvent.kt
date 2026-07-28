package com.easy.easyai.core.event

import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.ToolCallStatus
import com.easy.easyai.core.model.Usage
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlin.coroutines.CoroutineContext

sealed interface AgentEvent {
    val type: String
}

data class AgentStartEvent(val sessionId: String) : AgentEvent {
    override val type: String get() = "agent_start"
}
/**
 * @param messages The full transcript at the time of agent end.
 *   Not serialized to SSE — available for in-process listeners (e.g., SnapshotEventListener)
 *   to extract messageId, tool calls, etc. without maintaining per-event state.
 */
data class AgentEndEvent(
    val sessionId: String,
    val reason: String,
    val messages: List<EasyAiMessage> = emptyList(),
    /** Why the agent loop ended: "normal" | "max_iterations" */
    val endReason: String = "normal"
) : AgentEvent {
    override val type: String get() = "agent_end"
}
data class TurnStartEvent(val turnId: Int, val sessionId: String) : AgentEvent {
    override val type: String get() = "turn_start"
}
data class TurnEndEvent(val turnId: Int, val sessionId: String) : AgentEvent {
    override val type: String get() = "turn_end"
}
data class MessageStartEvent(val messageId: String, val turnId: Int, val sessionId: String) : AgentEvent {
    override val type: String get() = "message_start"
}
data class MessageUpdateEvent(
    val messageId: String,
    val delta: String,
    val turnId: Int,
    val sessionId: String,
    val subAgentToolCallId: String? = null,
    val subAgentName: String? = null
) : AgentEvent {
    override val type: String get() = "message_update"
}
data class ThinkingUpdateEvent(
    val messageId: String,
    val delta: String,
    val turnId: Int,
    val sessionId: String,
    val subAgentToolCallId: String? = null,
    val subAgentName: String? = null
) : AgentEvent {
    override val type: String get() = "thinking_update"
}
data class ThinkingEndEvent(
    val messageId: String,
    val turnId: Int,
    val sessionId: String,
    val durationMs: Long = 0,
    val subAgentToolCallId: String? = null,
    val subAgentName: String? = null
) : AgentEvent {
    override val type: String get() = "thinking_end"
}
data class MessageEndEvent(
    val messageId: String,
    val turnId: Int,
    val sessionId: String,
    val message: AssistantMessage,
    val usage: Usage? = null,
    val subAgentToolCallId: String? = null,
    val subAgentName: String? = null
) : AgentEvent {
    override val type: String get() = "message_end"
}
data class ToolExecutionStartEvent(
    val toolCallId: String,
    val toolName: String,
    val args: Map<String, Any?>,
    val turnId: Int,
    val sessionId: String,
    val tracksFileChanges: Boolean = false,
    val subAgentToolCallId: String? = null,
    val subAgentName: String? = null
) : AgentEvent {
    override val type: String get() = "tool_execution_start"
}
data class ToolExecutionUpdateEvent(
    val toolCallId: String,
    val update: com.easy.easyai.core.tool.ToolUpdate,
    val turnId: Int,
    val sessionId: String,
    val subAgentToolCallId: String? = null,
    val subAgentName: String? = null
) : AgentEvent {
    override val type: String get() = "tool_execution_update"
}
data class ToolExecutionEndEvent(
    val toolCallId: String,
    val toolName: String,
    val result: com.easy.easyai.core.tool.ToolResult,
    val isError: Boolean = false,
    val turnId: Int,
    val sessionId: String,
    val messageId: String? = null,
    val tracksFileChanges: Boolean = false,
    val subAgentToolCallId: String? = null,
    val subAgentName: String? = null,
    /** Token usage from this tool execution (e.g., sub-agent LLM calls). Null if not applicable. */
    val usage: Usage? = null
) : AgentEvent {
    override val type: String get() = "tool_execution_end"
}
/**
 * Event emitted when a tool call status changes.
 * Used to notify the UI about tool lifecycle: PENDING → RUNNING → COMPLETED/FAILED.
 */
data class ToolCallStatusUpdateEvent(
    val toolCallId: String,
    val toolName: String,
    val status: ToolCallStatus,
    val turnId: Int,
    val sessionId: String,
    val subAgentToolCallId: String? = null,
    val subAgentName: String? = null
) : AgentEvent {
    override val type: String get() = "toolcall_status"
}

data class ErrorEvent(
    val error: Throwable,
    val sessionId: String,
    val turnId: Int = -1,
    val isRetryable: Boolean = true,
    val messageId: String? = null
) : AgentEvent {
    override val type: String get() = "error"
}

data class RetryEvent(
    val messageId: String,
    val attempt: Int,
    val maxRetries: Int,
    val backoffMs: Long,
    val turnId: Int,
    val sessionId: String
) : AgentEvent {
    override val type: String get() = "retry"
}

/**
 * Event emitted when context compaction starts.
 */
data class CompactionStartEvent(
    val turnId: Int,
    val reason: String,           // "auto" | "manual" | "overflow"
    val messageCount: Int,
    val sessionId: String
) : AgentEvent {
    override val type: String get() = "compaction_start"
}

/**
 * Event emitted when context compaction completes.
 */
data class CompactionEndEvent(
    val turnId: Int,
    val summary: String,
    val compactedCount: Int,
    val tokensSaved: Int,
    val sessionId: String,
    val tailStartMessageId: String? = null,
    val currentTokens: Int = 0,
    val durationMs: Long = 0,
    val usage: Usage = Usage(),
    /** Session variables extracted during compaction (for real-time frontend update). */
    val variables: Map<String, String> = emptyMap()
) : AgentEvent {
    override val type: String get() = "compaction_end"
}

/**
 * Event emitted when a tool call requires user permission before execution.
 * The agent loop will pause after this event, waiting for user approval.
 */
data class PermissionRequestEvent(
    val toolCallId: String,
    val toolName: String,
    val permission: String,
    val pattern: String,
    val arguments: Map<String, Any?>,
    val sessionId: String,
    val subAgentToolCallId: String? = null,
    val subAgentName: String? = null
) : AgentEvent {
    override val type: String get() = "permission_request"
}

/**
 * Event emitted when a user message is dynamically added during the agent loop
 * (e.g., by completion checks injecting an auto-continue prompt).
 */
data class UserMessageAddedEvent(
    val messageId: String,
    val content: String,
    val sessionId: String,
    val metadata: Map<String, String> = emptyMap()
) : AgentEvent {
    override val type: String get() = "user_message_added"
}

/**
 * Custom event for extensible event types.
 * Analogous to [com.easy.easyai.core.model.CustomContent] for messages — allows pushing
 * domain-specific events through the standard AgentEvent pipeline without modifying
 * the sealed hierarchy.
 *
 * Listeners can push CustomEvent via the `push` lambda provided in
 * [com.easy.easyai.core.agent.AgentEventListener.handle], and the web layer converts
 * them to SSE events via registered [CustomEventConverter] implementations.
 */
data class CustomEvent(
    val customType: String,
    val sessionId: String,
    val metadata: Map<String, Any?> = emptyMap()
) : AgentEvent {
    override val type: String get() = "custom"
}

/**
 * Injects sub-agent context (parent toolCallId and agentName) into a copy of this event.
 * Used by ToolExecutionEngine when forwarding sub-agent events to the parent event stream.
 */
fun AgentEvent.withSubAgentContext(toolCallId: String, agentName: String): AgentEvent = when (this) {
    is MessageUpdateEvent -> copy(subAgentToolCallId = toolCallId, subAgentName = agentName)
    is ThinkingUpdateEvent -> copy(subAgentToolCallId = toolCallId, subAgentName = agentName)
    is ThinkingEndEvent -> copy(subAgentToolCallId = toolCallId, subAgentName = agentName)
    is MessageEndEvent -> copy(subAgentToolCallId = toolCallId, subAgentName = agentName)
    is ToolExecutionStartEvent -> copy(subAgentToolCallId = toolCallId, subAgentName = agentName)
    is ToolExecutionUpdateEvent -> copy(subAgentToolCallId = toolCallId, subAgentName = agentName)
    is ToolExecutionEndEvent -> copy(subAgentToolCallId = toolCallId, subAgentName = agentName)
    is ToolCallStatusUpdateEvent -> copy(subAgentToolCallId = toolCallId, subAgentName = agentName)
    else -> this
}

interface ProducerScope<TEvent : AgentEvent, TResult> : CoroutineScope {
    suspend fun push(event: TEvent)
    fun end(result: TResult)
    val isActive: Boolean
}

interface EventStream<TEvent : AgentEvent, TResult> {
    fun asFlow(): Flow<TEvent>
    suspend fun result(): TResult

    /**
     * Cancel the producer coroutine and close the channel.
     * Used to propagate cancellation from an outer scope to the inner EventStream.
     */
    fun cancel()

    companion object {
        fun <TEvent : AgentEvent, TResult> create(
            capacity: Int = Channel.UNLIMITED,
            producer: suspend ProducerScope<TEvent, TResult>.() -> Unit
        ): EventStream<TEvent, TResult> = ChannelEventStream(capacity, producer)
    }
}

private class ChannelEventStream<TEvent : AgentEvent, TResult>(
    capacity: Int,
    private val producer: suspend ProducerScope<TEvent, TResult>.() -> Unit
) : EventStream<TEvent, TResult> {

    private val channel = Channel<TEvent>(capacity)
    private val resultDeferred = CompletableDeferred<TResult>()
    @Volatile
    private var producerJob: Job? = null
    private var started = false

    private fun start() {
        if (started) return
        started = true
        val job = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            producerJob = coroutineContext[Job]
            val scope = DefaultProducerScope(channel, resultDeferred, coroutineContext)
            try {
                producer(scope)
            } catch (e: CancellationException) {
                resultDeferred.completeExceptionally(e)
            } catch (e: Throwable) {
                resultDeferred.completeExceptionally(e)
            } finally {
                channel.close()
            }
        }
        producerJob = job
    }

    override fun cancel() {
        producerJob?.cancel()
    }

    override fun asFlow(): Flow<TEvent> {
        start()
        return channel.consumeAsFlow()
    }

    override suspend fun result(): TResult {
        start()
        return resultDeferred.await()
    }
}

private class DefaultProducerScope<TEvent : AgentEvent, TResult>(
    private val channel: Channel<TEvent>,
    private val resultDeferred: CompletableDeferred<TResult>,
    parentContext: CoroutineContext
) : ProducerScope<TEvent, TResult>, CoroutineScope by CoroutineScope(parentContext + Job(parentContext[Job])) {

    override suspend fun push(event: TEvent) {
        channel.send(event)
    }

    override fun end(result: TResult) {
        resultDeferred.complete(result)
    }

    override val isActive: Boolean get() = !resultDeferred.isCompleted
}
