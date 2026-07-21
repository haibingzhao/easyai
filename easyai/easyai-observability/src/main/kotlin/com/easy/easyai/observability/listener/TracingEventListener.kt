package com.easy.easyai.observability.listener

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentEventListener
import com.easy.easyai.core.event.*
import com.easy.easyai.core.model.Usage
import com.easy.easyai.observability.config.ObservabilityProperties
import com.easy.easyai.observability.observation.ObservationKeys
import com.easy.easyai.observability.observation.ObservationUtils
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Core tracing listener that creates Observation spans for EasyAI agent events.
 *
 * This listener bridges EasyAI's EventStream-based event system with Spring Observation API,
 * creating proper parent-child span hierarchies for:
 * - Agent sessions (root spans)
 * - Turns (child of session)
 * - Messages (child of turn)
 * - Tool executions (child of turn)
 *
 * ## Parent Resolution Strategy
 *
 * All parent observations are resolved via **explicit lookup** from [activeObservations]
 * rather than `observationRegistry.currentObservation` (ThreadLocal). This is critical
 * for correctness when tools execute in parallel: parallel tool coroutines push events
 * into a shared Channel in interleaved order, and the sequential processing of those
 * events would cause ThreadLocal-based parent lookups to pick up a sibling tool span
 * instead of the correct turn span, producing an incorrect trace hierarchy.
 *
 * ```
 * Correct (explicit lookup):    turn → [tool_A, tool_B]   (siblings)
 * Broken (ThreadLocal):         turn → tool_A → tool_B    (wrong nesting)
 * ```
 *
 * @property observationRegistry Spring Observation registry for creating spans
 * @property properties observability configuration properties
 */
class TracingEventListener(
    private val observationRegistry: ObservationRegistry,
    private val properties: ObservabilityProperties
) : AgentEventListener {
    private val log = LoggerFactory.getLogger(TracingEventListener::class.java)

    // Active observations keyed by type:id
    private val activeObservations = ConcurrentHashMap<String, ObservationContext>()

    /** Holds Observation and its Scope. */
    private data class ObservationContext(val observation: Observation, val scope: Observation.Scope?)

    /**
     * Records token usage information to the observation.
     */
    private fun recordTokenUsage(observation: Observation, usage: Usage) {
        if (usage.inputTokens != 0) {
            observation.highCardinalityKeyValue("gen_ai.usage.input_tokens", usage.inputTokens.toString())
        }
        if (usage.outputTokens != 0) {
            observation.highCardinalityKeyValue("gen_ai.usage.output_tokens", usage.outputTokens.toString())
        }
        if (usage.cacheReadTokens != 0) {
            observation.highCardinalityKeyValue("gen_ai.usage.cache_read_tokens", usage.cacheReadTokens.toString())
        }
        if (usage.cacheWriteTokens != 0) {
            observation.highCardinalityKeyValue("gen_ai.usage.cache_write_tokens", usage.cacheWriteTokens.toString())
        }
    }

    // --- Explicit parent observation lookup (avoids ThreadLocal) ---

    /**
     * Look up the agent session observation by sessionId.
     * Used as parent for turn-level spans.
     */
    private fun findAgentObservation(sessionId: String): Observation? =
        activeObservations[ObservationKeys.agentKey(sessionId)]?.observation

    /**
     * Look up the turn observation by turnId.
     * Used as parent for message and tool-level spans.
     * Tools are parented under the turn (not the message) because the message observation
     * is already closed by the time tool execution events arrive.
     */
    private fun findTurnObservation(turnId: Int): Observation? =
        activeObservations["turn:$turnId"]?.observation

    /**
     * Look up the most specific active observation for annotation (read-only access).
     * Tries turn → agent fallback. Does NOT create child spans.
     */
    private fun findAnnotateTarget(sessionId: String, turnId: Int = -1): Observation? {
        if (turnId >= 0) {
            findTurnObservation(turnId)?.let { return it }
        }
        return findAgentObservation(sessionId)
    }

    /**
     * Handles an agent event and creates/updates observations accordingly.
     */
    override suspend fun handle(agentContext: AgentContext, event: AgentEvent, push: suspend (AgentEvent) -> Unit) {
        if (!properties.enabled) return

        when (event) {
            is AgentStartEvent -> onAgentStart(agentContext, event)
            is AgentEndEvent -> onAgentEnd(event)
            is TurnStartEvent -> onTurnStart(agentContext, event)
            is TurnEndEvent -> onTurnEnd(event)
            is MessageStartEvent -> onMessageStart(agentContext, event)
            is MessageUpdateEvent -> onMessageUpdate(event)
            is ThinkingUpdateEvent -> onThinkingUpdate(event)
            is ThinkingEndEvent -> onThinkingEnd(event)
            is MessageEndEvent -> onMessageEnd(event)
            is ToolExecutionStartEvent -> {
                if (properties.traceToolCalls) onToolExecutionStart(agentContext, event)
            }
            is ToolExecutionUpdateEvent -> {
                if (properties.traceToolCalls) onToolExecutionUpdate(event)
            }
            is ToolExecutionEndEvent -> {
                if (properties.traceToolCalls) onToolExecutionEnd(event)
            }
            is ToolCallStatusUpdateEvent -> {
                if (properties.traceToolCalls) onToolCallStatusUpdate(event)
            }
            is CompactionStartEvent -> onCompactionStart(event)
            is CompactionEndEvent -> onCompactionEnd(event)
            is RetryEvent -> onRetry(event)
            is ErrorEvent -> onError(event)
            is PermissionRequestEvent -> onPermissionRequest(event)
            is UserMessageAddedEvent -> { /* informational only, no tracing needed */ }
            is CustomEvent -> { /* custom events are not traced */ }
            else -> { /* batch and future events are not traced */ }
        }
    }

    // --- Agent Session Lifecycle ---

    private fun onAgentStart(agentContext: AgentContext, event: AgentStartEvent) {
        val sessionId = event.sessionId

        val observation = Observation.createNotStarted("agent:$sessionId", observationRegistry)

        // OpenTelemetry GenAI semantic conventions
        observation.lowCardinalityKeyValue("gen_ai.operation.name", "agent_session")
        observation.lowCardinalityKeyValue("easyai.event.type", "agent_start")

        // Identity
        observation.lowCardinalityKeyValue("easyai.session.id", sessionId)
        observation.lowCardinalityKeyValue("easyai.agent.id", agentContext.agentId)

        // Model info
        val modelId = agentContext.modelId
        if (modelId.isNotEmpty()) {
            observation.lowCardinalityKeyValue("gen_ai.request.model", modelId)
        }
        val protocol = agentContext.protocol
        if (protocol != null) {
            observation.lowCardinalityKeyValue("easyai.model.protocol", protocol)
        }

        // Optional identity fields
        agentContext.projectId?.let {
            observation.lowCardinalityKeyValue("easyai.project.id", it)
        }
        agentContext.userId?.let {
            observation.lowCardinalityKeyValue("easyai.user.id", it)
        }
        agentContext.parentAgentId?.let {
            observation.lowCardinalityKeyValue("easyai.agent.parent_id", it)
        }

        // Behavior config
        observation.lowCardinalityKeyValue("easyai.agent.max_iterations", agentContext.maxIterations.toString())
        observation.lowCardinalityKeyValue("easyai.agent.max_retries", agentContext.maxRetries.toString())
        observation.lowCardinalityKeyValue("easyai.agent.tool_count", agentContext.tools.size.toString())

        // Start and open scope
        observation.start()
        val scope = observation.openScope()

        activeObservations[ObservationKeys.agentKey(sessionId)] = ObservationContext(observation, scope)
        log.debug("Started observation for agent session: {} (agent={}, model={})", sessionId, agentContext.agentId, modelId)
    }

    private fun onAgentEnd(event: AgentEndEvent) {
        val key = ObservationKeys.agentKey(event.sessionId)
        val ctx = activeObservations.remove(key)

        if (ctx != null) {
            ctx.observation.lowCardinalityKeyValue("easyai.agent.status", event.reason)

            // Close and stop
            ctx.scope?.close()
            ctx.observation.stop()

            log.debug("Completed observation for agent session: {} (reason: {})", event.sessionId, event.reason)
        }
    }

    // --- Turn Lifecycle ---

    private fun onTurnStart(agentContext: AgentContext, event: TurnStartEvent) {
        val turnId = event.turnId.toString()

        // Explicit parent: agent session observation (not ThreadLocal)
        val parentObservation = findAgentObservation(event.sessionId)

        val observation = Observation.createNotStarted("turn:$turnId", observationRegistry)

        if (parentObservation != null) {
            observation.parentObservation(parentObservation)
        }

        observation.lowCardinalityKeyValue("easyai.event.type", "turn_start")
        observation.highCardinalityKeyValue("easyai.turn.id", turnId)
        observation.lowCardinalityKeyValue("easyai.session.id", event.sessionId)
        observation.lowCardinalityKeyValue("easyai.agent.id", agentContext.agentId)

        observation.start()
        val scope = observation.openScope()

        activeObservations["turn:$turnId"] = ObservationContext(observation, scope)
        log.debug("Started observation for turn: {} (session={})", turnId, event.sessionId)
    }

    private fun onTurnEnd(event: TurnEndEvent) {
        val key = "turn:${event.turnId}"
        val ctx = activeObservations.remove(key)

        if (ctx != null) {
            ctx.scope?.close()
            ctx.observation.stop()
            log.debug("Completed observation for turn: {}", event.turnId)
        }
    }

    // --- Message Lifecycle ---

    private fun onMessageStart(agentContext: AgentContext, event: MessageStartEvent) {
        val messageId = event.messageId

        // Explicit parent: turn observation (not ThreadLocal)
        val parentObservation = findTurnObservation(event.turnId)

        val observation = Observation.createNotStarted("message:$messageId", observationRegistry)

        if (parentObservation != null) {
            observation.parentObservation(parentObservation)
        }

        observation.lowCardinalityKeyValue("easyai.event.type", "message_start")
        observation.highCardinalityKeyValue("easyai.message.id", messageId)
        observation.lowCardinalityKeyValue("easyai.session.id", event.sessionId)
        observation.lowCardinalityKeyValue("easyai.turn.id", event.turnId.toString())
        observation.lowCardinalityKeyValue("easyai.agent.id", agentContext.agentId)

        val modelId = agentContext.modelId
        if (modelId.isNotEmpty()) {
            observation.lowCardinalityKeyValue("gen_ai.request.model", modelId)
        }

        observation.start()
        val scope = observation.openScope()

        activeObservations["message:$messageId"] = ObservationContext(observation, scope)
        log.debug("Started observation for message: {} (session={}, turn={})", messageId, event.sessionId, event.turnId)
    }

    private fun onMessageUpdate(event: MessageUpdateEvent) {
        // Streaming updates don't create separate spans, just add to current message span
        val key = "message:${event.messageId}"
        val ctx = activeObservations[key]

        if (ctx != null && properties.traceLlmCalls) {
            // Could add high-cardinality attribute for streaming delta
            // But this might be too verbose, so we skip it by default
        }
    }

    private fun onThinkingUpdate(event: ThinkingUpdateEvent) {
        // Similar to message update, thinking deltas are part of the message span
        val key = "message:${event.messageId}"
        val ctx = activeObservations[key]

        if (ctx != null && properties.traceLlmCalls) {
            // Add thinking mode indicator
            ctx.observation.lowCardinalityKeyValue("easyai.message.mode", "thinking")
        }
    }

    private fun onThinkingEnd(event: ThinkingEndEvent) {
        // Thinking end is handled as part of the message span, similar to thinking updates
        val key = "message:${event.messageId}"
        val ctx = activeObservations[key]

        if (ctx != null && properties.traceLlmCalls) {
            // Could add thinking completion metadata if needed
        }
    }

    private fun onMessageEnd(event: MessageEndEvent) {
        val key = "message:${event.messageId}"
        val ctx = activeObservations.remove(key)

        if (ctx != null) {
            val message = event.message

            // Add message metadata
            ctx.observation.highCardinalityKeyValue("easyai.message.stop_reason", message.stopReason?.name ?: "unknown")
            ctx.observation.lowCardinalityKeyValue("easyai.session.id", event.sessionId)
            ctx.observation.lowCardinalityKeyValue("easyai.turn.id", event.turnId.toString())

            // Sub-agent context
            event.subAgentToolCallId?.let {
                ctx.observation.highCardinalityKeyValue("easyai.subagent.tool_call_id", it)
            }
            event.subAgentName?.let {
                ctx.observation.lowCardinalityKeyValue("easyai.subagent.name", it)
            }

            // Add token usage if available
            event.usage?.let { recordTokenUsage(ctx.observation, it) }
            ctx.scope?.close()
            ctx.observation.stop()

            log.debug("Completed observation for message: {} (session={}, turn={})", event.messageId, event.sessionId, event.turnId)
        }
    }

    // --- Tool Execution ---

    private fun onToolExecutionStart(agentContext: AgentContext, event: ToolExecutionStartEvent) {
        val toolCallId = event.toolCallId
        val toolName = event.toolName

        // Explicit parent: turn observation (not ThreadLocal).
        // Tools are parented under the turn because the message observation is already
        // closed by the time tool execution events arrive. Using ThreadLocal here would
        // cause incorrect nesting when multiple tools execute in parallel — a sibling
        // tool's scope could be picked up as the parent instead of the turn.
        val parentObservation = findTurnObservation(event.turnId)

        val observation = Observation.createNotStarted(ObservationKeys.toolSpanName(toolName), observationRegistry)

        if (parentObservation != null) {
            observation.parentObservation(parentObservation)
        }

        // OpenTelemetry GenAI semantic conventions for tool execution
        observation.lowCardinalityKeyValue("gen_ai.operation.name", "execute_tool")
        observation.lowCardinalityKeyValue("gen_ai.tool.name", toolName)
        observation.lowCardinalityKeyValue("gen_ai.tool.type", "function")

        observation.lowCardinalityKeyValue("easyai.event.type", "tool_execution_start")
        observation.highCardinalityKeyValue("easyai.tool.call_id", toolCallId)
        observation.lowCardinalityKeyValue("easyai.session.id", event.sessionId)
        observation.lowCardinalityKeyValue("easyai.turn.id", event.turnId.toString())
        observation.lowCardinalityKeyValue("easyai.agent.id", agentContext.agentId)

        // Sub-agent context
        event.subAgentToolCallId?.let {
            observation.highCardinalityKeyValue("easyai.subagent.tool_call_id", it)
        }
        event.subAgentName?.let {
            observation.lowCardinalityKeyValue("easyai.subagent.name", it)
        }

        // File tracking indicator
        if (event.tracksFileChanges) {
            observation.lowCardinalityKeyValue("easyai.tool.tracks_file_changes", "true")
        }

        // Add tool input (truncated)
        if (event.args.isNotEmpty()) {
            val argsStr = event.args.toString()
            observation.highCardinalityKeyValue("input.value", ObservationUtils.truncate(argsStr, properties.maxAttributeLength))
            observation.highCardinalityKeyValue("gen_ai.tool.call.arguments", ObservationUtils.truncate(argsStr, properties.maxAttributeLength))
        }

        observation.start()
        val scope = observation.openScope()

        activeObservations[ObservationKeys.toolKey(toolCallId, toolName)] = ObservationContext(observation, scope)
        log.debug("Started observation for tool: {} (callId={}, session={}, turn={})", toolName, toolCallId, event.sessionId, event.turnId)
    }

    private fun onToolExecutionUpdate(event: ToolExecutionUpdateEvent) {
        // Tool updates are intermediate states, no separate span needed
        val key = ObservationKeys.toolKey(event.toolCallId, "")
        // Find by callId prefix
        val ctx = activeObservations.entries.find { it.key.startsWith("tool:${event.toolCallId}:") }?.value

        if (ctx != null) {
            // Could add update info as attributes if needed
        }
    }

    private fun onToolExecutionEnd(event: ToolExecutionEndEvent) {
        val key = ObservationKeys.toolKey(event.toolCallId, event.toolName)
        val ctx = activeObservations.remove(key)

        if (ctx != null) {
            val result = event.result

            // Session/turn context
            ctx.observation.lowCardinalityKeyValue("easyai.session.id", event.sessionId)
            ctx.observation.lowCardinalityKeyValue("easyai.turn.id", event.turnId.toString())

            // Associated message ID (the assistant message that triggered this tool call)
            event.messageId?.let {
                ctx.observation.highCardinalityKeyValue("easyai.message.id", it)
            }

            // Sub-agent context
            event.subAgentToolCallId?.let {
                ctx.observation.highCardinalityKeyValue("easyai.subagent.tool_call_id", it)
            }
            event.subAgentName?.let {
                ctx.observation.lowCardinalityKeyValue("easyai.subagent.name", it)
            }

            // Add tool output
            if (result.content.isNotEmpty()) {
                val outputStr = result.content.joinToString("\n") { it.toString() }
                ctx.observation.highCardinalityKeyValue("output.value", ObservationUtils.truncate(outputStr, properties.maxAttributeLength))
                ctx.observation.highCardinalityKeyValue("gen_ai.tool.call.result", ObservationUtils.truncate(outputStr, properties.maxAttributeLength))
            }

            // Token usage from sub-agent tool execution
            event.usage?.let { recordTokenUsage(ctx.observation, it) }

            // Set status
            if (event.isError) {
                ctx.observation.lowCardinalityKeyValue("easyai.tool.status", "error")
                ctx.observation.error(RuntimeException("Tool execution failed: ${event.toolName}"))
            } else {
                ctx.observation.lowCardinalityKeyValue("easyai.tool.status", "success")
            }

            ctx.scope?.close()
            ctx.observation.stop()

            log.debug("Completed observation for tool: {} (callId={}, session={}, turn={}, error={})",
                event.toolName, event.toolCallId, event.sessionId, event.turnId, event.isError)
        }
    }

    // --- Tool Call Status ---

    private fun onToolCallStatusUpdate(event: ToolCallStatusUpdateEvent) {
        // Tool call status updates are tracked as metadata on existing tool execution spans
        val key = ObservationKeys.toolKey(event.toolCallId, event.toolName)
        val ctx = activeObservations[key]
        if (ctx != null) {
            ctx.observation.lowCardinalityKeyValue("easyai.tool.call.status", event.status.name)
        }
    }

    // --- Compaction ---

    private fun onCompactionStart(event: CompactionStartEvent) {
        // Explicit parent: turn observation (not ThreadLocal)
        val parentObservation = findTurnObservation(event.turnId)

        val observation = Observation.createNotStarted("compaction:${event.turnId}", observationRegistry)

        if (parentObservation != null) {
            observation.parentObservation(parentObservation)
        }

        observation.lowCardinalityKeyValue("easyai.event.type", "compaction_start")
        observation.lowCardinalityKeyValue("easyai.compaction.reason", event.reason)
        observation.lowCardinalityKeyValue("easyai.compaction.message_count", event.messageCount.toString())
        observation.lowCardinalityKeyValue("easyai.session.id", event.sessionId)
        observation.lowCardinalityKeyValue("easyai.turn.id", event.turnId.toString())

        observation.start()
        val scope = observation.openScope()

        activeObservations["compaction:${event.turnId}"] = ObservationContext(observation, scope)
        log.debug("Started observation for compaction: turn={}, session={}, reason={}, messageCount={}",
            event.turnId, event.sessionId, event.reason, event.messageCount)
    }

    private fun onCompactionEnd(event: CompactionEndEvent) {
        val key = "compaction:${event.turnId}"
        val ctx = activeObservations.remove(key)

        if (ctx != null) {
            ctx.observation.lowCardinalityKeyValue("easyai.event.type", "compaction_end")
            ctx.observation.lowCardinalityKeyValue("easyai.session.id", event.sessionId)
            ctx.observation.lowCardinalityKeyValue("easyai.compaction.compacted_count", event.compactedCount.toString())
            ctx.observation.lowCardinalityKeyValue("easyai.compaction.tokens_saved", event.tokensSaved.toString())
            if (event.tailStartMessageId != null) {
                ctx.observation.highCardinalityKeyValue("easyai.compaction.tail_start_message_id", event.tailStartMessageId!!)
            }

            // Add compaction LLM call usage if available
            recordTokenUsage(ctx.observation, event.usage)

            ctx.scope?.close()
            ctx.observation.stop()

            log.debug("Completed observation for compaction: turn={}, compacted={}, saved={} tokens",
                event.turnId, event.compactedCount, event.tokensSaved)
        }
    }

    // --- Retry ---

    private fun onRetry(event: RetryEvent) {
        // Explicit lookup: annotate turn observation (not ThreadLocal)
        val target = findAnnotateTarget(event.sessionId, event.turnId)
        if (target != null) {
            target.lowCardinalityKeyValue("easyai.retry.attempt", event.attempt.toString())
            target.lowCardinalityKeyValue("easyai.retry.max_retries", event.maxRetries.toString())
            target.lowCardinalityKeyValue("easyai.session.id", event.sessionId)
            target.lowCardinalityKeyValue("easyai.turn.id", event.turnId.toString())
            target.highCardinalityKeyValue("easyai.message.id", event.messageId)
        }

        log.debug("Retry attempt {}/{} for message: {} (session={}, backoff: {}ms)",
            event.attempt, event.maxRetries, event.messageId, event.sessionId, event.backoffMs)
    }

    // --- Permission Request ---

    private fun onPermissionRequest(event: PermissionRequestEvent) {
        // Explicit lookup: annotate agent session observation (no turnId available on this event)
        val target = findAnnotateTarget(event.sessionId)
        if (target != null) {
            target.lowCardinalityKeyValue("easyai.permission.tool", event.toolName)
            target.lowCardinalityKeyValue("easyai.permission.type", event.permission)
            target.lowCardinalityKeyValue("easyai.session.id", event.sessionId)
            target.highCardinalityKeyValue("easyai.tool.call_id", event.toolCallId)
        }

        log.debug("Permission requested for tool: {} (session={}, permission: {}, pattern: {})",
            event.toolName, event.sessionId, event.permission, event.pattern)
    }

    // --- Error Handling ---

    private fun onError(event: ErrorEvent) {
        // Explicit lookup: annotate the most specific active observation (not ThreadLocal)
        val target = findAnnotateTarget(event.sessionId, event.turnId)
        if (target != null) {
            target.error(event.error)
            target.lowCardinalityKeyValue("easyai.session.id", event.sessionId)
            if (event.turnId >= 0) {
                target.lowCardinalityKeyValue("easyai.turn.id", event.turnId.toString())
            }
            event.messageId?.let {
                target.highCardinalityKeyValue("easyai.message.id", it)
            }
        }

        log.warn("Agent error occurred: session={}, turn={}, error={}", event.sessionId, event.turnId, event.error.message, event.error)
    }

    /**
     * Cleans up any remaining active observations (should be called on session cleanup).
     */
    fun cleanup() {
        activeObservations.values.forEach { ctx ->
            try {
                ctx.scope?.close()
                ctx.observation.stop()
            } catch (e: Exception) {
                log.warn("Error cleaning up observation", e)
            }
        }
        activeObservations.clear()
        log.debug("Cleaned up all active observations")
    }
}
