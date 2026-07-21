package com.easy.easyai.core.agent

import com.easy.easyai.core.event.AgentEvent
import com.easy.easyai.core.model.ContentBlock
import java.nio.file.Path

// ==================== Context & Result Types ====================

data class BeforeToolCallContext(
    val toolCallId: String,
    val toolName: String,
    val arguments: Map<String, Any?>,
    val projectId: String? = null,
    val projectPath: Path? = null,
    val parentAgentId: String? = null
)

sealed interface BeforeToolCallResult {
    /** Tool execution is allowed to proceed. */
    data object Allow : BeforeToolCallResult

    /** Tool execution is blocked with a reason. */
    data class Block(val reason: String) : BeforeToolCallResult

    /**
     * Permission is required before the tool can proceed.
     * The agent loop will pause and wait for user approval via SSE.
     */
    data class PermissionRequest(
        val permission: String,
        val pattern: String,
        val toolCallId: String,
        val toolName: String,
        val arguments: Map<String, Any?>
    ) : BeforeToolCallResult
}

data class AfterToolCallContext(
    val toolCallId: String,
    val toolName: String,
    val result: com.easy.easyai.core.tool.ToolResult
)

data class AfterToolCallResult(
    val contentOverride: List<ContentBlock>? = null,
    val detailsOverride: Map<String, Any>? = null,
    val isErrorOverride: Boolean? = null,
    val terminate: Boolean = false
) {
    companion object {
        val Default = AfterToolCallResult()
    }
}

// ==================== Hook Interfaces ====================

/**
 * Hook invoked before each tool call.
 * Determines whether the tool call should proceed, be blocked, or require user permission.
 *
 * Can be implemented as a Spring Bean or provided as a lambda via SAM conversion.
 */
fun interface BeforeToolCallHook {
    suspend operator fun invoke(context: BeforeToolCallContext): BeforeToolCallResult
}

/**
 * Hook invoked after each tool call completes.
 * Allows post-processing of tool results (e.g., content override, termination signal).
 *
 * Can be implemented as a Spring Bean or provided as a lambda via SAM conversion.
 */
fun interface AfterToolCallHook {
    suspend operator fun invoke(context: AfterToolCallContext): AfterToolCallResult
}

// ==================== Event Listener Interface ====================

/**
 * Listener for agent lifecycle events.
 * Used for observability (tracing, metrics, MDC propagation) and snapshot checkpoint creation.
 *
 * All implementations receive [AgentContext] for session/identity information, the [AgentEvent],
 * and a [push] lambda for pushing [com.easy.easyai.core.event.CustomEvent] back into the EventStream.
 *
 * Registered globally via [AgentService.eventListeners] and invoked by [AgentRunner] on every event.
 *
 * Note: This is a regular interface (not `fun interface`), so Kotlin SAM conversion is not available.
 * Use `object : AgentEventListener { ... }` to create instances.
 */
interface AgentEventListener {
    /**
     * Handle an agent event with full context and push capability.
     *
     * This is a suspend function so that implementations can perform asynchronous work
     * (e.g., git operations) and push events before the EventStream channel closes.
     *
     * @param agentContext The agent context carrying session identity, project path, etc.
     * @param event The agent event
     * @param push Suspend function to push events into the EventStream
     */
    suspend fun handle(agentContext: AgentContext, event: AgentEvent, push: suspend (AgentEvent) -> Unit)

    /**
     * Called synchronously by [com.easy.easyai.core.agent.AgentLoop] before a batch of
     * tool calls begins execution. Used to perform pre-flight work (e.g., snapshot
     * user-change commits) that must complete *before* tools modify the working tree.
     *
     * Default: no-op. Override in listeners that need pre-execution hooks.
     *
     * @param agentContext The agent context carrying session identity, project path, etc.
     * @param toolCallIds IDs of the tool calls about to be executed
     */
    suspend fun beforeToolExecutionBatch(
        agentContext: AgentContext,
        toolCallIds: List<String>
    ) {
        // default: no-op
    }

    /**
     * Called synchronously by [com.easy.easyai.core.agent.AgentLoop] after a batch of
     * tool calls completes execution. Used for post-flight work (e.g., snapshot
     * LLM-change commits and checkpoint events) that must run after tools finish
     * but before the next agent turn begins.
     *
     * Default: no-op. Override in listeners that need post-execution hooks.
     *
     * @param agentContext The agent context carrying session identity, project path, etc.
     * @param toolCallIds IDs of the tool calls that were executed
     * @param messageId The assistant message ID associated with this tool batch (for checkpoint correlation)
     * @param push Suspend function to push events (e.g., checkpoint CustomEvent) into the EventStream
     * @param hasFileChangingTools `true` if at least one tool in this batch has
     *   `tracksFileChanges = true`. Listeners that only care about file modifications
     *   (e.g., snapshot checkpoint) can use this to skip expensive git operations
     *   when the batch contains only read-only tools.
     */
    suspend fun afterToolExecutionBatch(
        agentContext: AgentContext,
        toolCallIds: List<String>,
        messageId: String?,
        push: suspend (AgentEvent) -> Unit,
        hasFileChangingTools: Boolean
    ) {
        // default: no-op
    }
}
