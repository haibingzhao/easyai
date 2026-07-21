package com.easy.easyai.observability.listener

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentEventListener
import com.easy.easyai.core.event.*
import com.easy.easyai.observability.config.ObservabilityProperties
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Emits Micrometer business metrics for EasyAI agent processes.
 *
 * Metrics produced:
 * - `easyai.agent.active` (gauge) — number of agents currently running
 * - `easyai.agent.sessions.total` (counter) — total agent sessions started
 * - `easyai.agent.errors.total` (counter) — agent failures
 * - `easyai.turns.total` (counter) — total turns executed
 * - `easyai.messages.total` (counter) — total messages processed
 * - `easyai.tool.calls.total` (counter) — total tool calls, tagged by `tool`
 * - `easyai.tool.errors.total` (counter) — tool failures, tagged by `tool`
 * - `easyai.llm.tokens.total` (counter) — LLM tokens, tagged by `direction` (input/output)
 *
 * @property registry the Micrometer meter registry to publish metrics to
 * @property properties observability configuration properties controlling metrics emission
 */
class MetricsEventListener(
    private val registry: MeterRegistry,
    private val properties: ObservabilityProperties
) : AgentEventListener {
    private val log = LoggerFactory.getLogger(MetricsEventListener::class.java)

    private val activeSessions = AtomicInteger(0)
    private val sessionStartTimes = ConcurrentHashMap<String, Instant>()

    init {
        // Register active sessions gauge
        io.micrometer.core.instrument.Gauge.builder("easyai.agent.active", activeSessions) { it.get().toDouble() }
            .description("Number of agent sessions currently running")
            .register(registry)

        log.info("MetricsEventListener initialized with Micrometer registry")
    }

    /**
     * Handles an agent event and records metrics accordingly.
     */
    override suspend fun handle(agentContext: AgentContext, event: AgentEvent, push: suspend (AgentEvent) -> Unit) {
        if (!properties.metricsEnabled) return

        when (event) {
            is AgentStartEvent -> onAgentStart(event)
            is AgentEndEvent -> onAgentEnd(event)
            is TurnStartEvent -> onTurnStart(event)
            is MessageEndEvent -> onMessageEnd(event)
            is ToolExecutionStartEvent -> onToolExecutionStart(event)
            is ToolExecutionEndEvent -> onToolExecutionEnd(event)
            is ErrorEvent -> onError(event)
            else -> {} // Ignore other events for metrics
        }
    }

    private fun onAgentStart(event: AgentStartEvent) {
        activeSessions.incrementAndGet()
        sessionStartTimes[event.sessionId] = Instant.now()

        Counter.builder("easyai.agent.sessions.total")
            .description("Total agent sessions started")
            .register(registry)
            .increment()
    }

    private fun onAgentEnd(event: AgentEndEvent) {
        activeSessions.decrementAndGet()

        // Record session duration
        sessionStartTimes.remove(event.sessionId)?.let { startTime ->
            val duration = java.time.Duration.between(startTime, Instant.now())
            Timer.builder("easyai.agent.duration")
                .description("Agent session duration")
                .tag("session_id", event.sessionId)
                .tag("reason", event.reason)
                .register(registry)
                .record(duration)
        }
    }

    private fun onTurnStart(event: TurnStartEvent) {
        Counter.builder("easyai.turns.total")
            .description("Total turns executed")
            .register(registry)
            .increment()
    }

    private fun onMessageEnd(event: MessageEndEvent) {
        Counter.builder("easyai.messages.total")
            .description("Total messages processed")
            .register(registry)
            .increment()

        // Record token usage
        event.message.usage.let { usage ->
            if (usage.inputTokens > 0) {
                Counter.builder("easyai.llm.tokens.total")
                    .description("Total LLM tokens consumed")
                    .tag("direction", "input")
                    .register(registry)
                    .increment(usage.inputTokens.toDouble())
            }
            if (usage.outputTokens > 0) {
                Counter.builder("easyai.llm.tokens.total")
                    .description("Total LLM tokens consumed")
                    .tag("direction", "output")
                    .register(registry)
                    .increment(usage.outputTokens.toDouble())
            }
        }
    }

    private fun onToolExecutionStart(event: ToolExecutionStartEvent) {
        Counter.builder("easyai.tool.calls.total")
            .description("Total tool calls")
            .tag("tool", event.toolName)
            .register(registry)
            .increment()
    }

    private fun onToolExecutionEnd(event: ToolExecutionEndEvent) {
        if (event.isError) {
            Counter.builder("easyai.tool.errors.total")
                .description("Total tool call failures")
                .tag("tool", event.toolName)
                .register(registry)
                .increment()
        }
    }

    private fun onError(event: ErrorEvent) {
        Counter.builder("easyai.agent.errors.total")
            .description("Total agent errors")
            .register(registry)
            .increment()
    }
}
