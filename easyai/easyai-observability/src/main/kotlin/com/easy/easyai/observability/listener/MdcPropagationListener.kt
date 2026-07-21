package com.easy.easyai.observability.listener

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentEventListener
import com.easy.easyai.core.event.*
import com.easy.easyai.observability.config.ObservabilityProperties
import org.slf4j.MDC

/**
 * Propagates EasyAI Agent context into SLF4J MDC for log correlation.
 *
 * Sets the following MDC keys:
 * - `easyai.session.id` — the agent session ID
 * - `easyai.message.id` — the current message ID (during message processing)
 * - `easyai.tool.call_id` — the current tool call ID (during tool execution)
 *
 * This allows filtering and correlating application logs by agent session or message
 * without any manual MDC configuration.
 *
 * @property properties observability configuration properties controlling MDC propagation
 */
class MdcPropagationListener(
    private val properties: ObservabilityProperties
) : AgentEventListener {
    companion object {
        const val MDC_SESSION_ID = "easyai.session.id"
        const val MDC_MESSAGE_ID = "easyai.message.id"
        const val MDC_TOOL_CALL_ID = "easyai.tool.call_id"
    }

    /**
     * Handles an agent event by setting or clearing MDC keys based on the event type.
     *
     * On [AgentStartEvent], sets `session.id`.
     * On [MessageStartEvent], adds the `message.id` key.
     * On [MessageEndEvent], removes the `message.id` key.
     * On [ToolExecutionStartEvent], adds the `tool.call_id` key.
     * On [ToolExecutionEndEvent], removes the `tool.call_id` key.
     * On terminal events (AgentEndEvent), clears all MDC keys.
     */
    override suspend fun handle(agentContext: AgentContext, event: AgentEvent, push: suspend (AgentEvent) -> Unit) {
        if (!properties.mdcPropagation) return

        when (event) {
            is AgentStartEvent -> {
                MDC.put(MDC_SESSION_ID, event.sessionId)
            }
            is MessageStartEvent -> {
                MDC.put(MDC_MESSAGE_ID, event.messageId)
            }
            is MessageEndEvent -> {
                MDC.remove(MDC_MESSAGE_ID)
            }
            is ToolExecutionStartEvent -> {
                MDC.put(MDC_TOOL_CALL_ID, event.toolCallId)
            }
            is ToolExecutionEndEvent -> {
                MDC.remove(MDC_TOOL_CALL_ID)
            }
            is AgentEndEvent -> {
                clearAll()
            }
            is ErrorEvent -> {
                // Keep MDC context for error logging
            }
            else -> {}
        }
    }

    /**
     * Removes all EasyAI MDC keys (`session.id`, `message.id`, `tool.call_id`).
     * Called on terminal session events to prevent MDC leaking into unrelated log statements.
     */
    private fun clearAll() {
        MDC.remove(MDC_SESSION_ID)
        MDC.remove(MDC_MESSAGE_ID)
        MDC.remove(MDC_TOOL_CALL_ID)
    }
}
