package com.easy.easyai.observability.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for EasyAI observability.
 *
 * Works with any OpenTelemetry-compatible exporter (Zipkin, OTLP, Langfuse, etc.).
 *
 * @since 2026.0.1
 */
@ConfigurationProperties(prefix = "easyai.observability")
data class ObservabilityProperties(
    /** Enable/disable observability. */
    var enabled: Boolean = true,

    /** Service name for traces. */
    var serviceName: String = "easyai",

    /** Tracer instrumentation name. */
    var tracerName: String = "easyai",

    /** Tracer version. */
    var tracerVersion: String = "2026.0.1",

    /** Max attribute length before truncation. */
    var maxAttributeLength: Int = 4000,

    /** Trace agent events (agents, actions, goals). */
    var traceAgentEvents: Boolean = true,

    /** Trace tool calls. */
    var traceToolCalls: Boolean = true,

    /** Trace tool loop execution. */
    var traceToolLoop: Boolean = true,

    /** Trace LLM calls. */
    var traceLlmCalls: Boolean = true,

    /** Trace planning events. */
    var tracePlanning: Boolean = true,

    /** Trace state transitions. */
    var traceStateTransitions: Boolean = true,

    /** Trace lifecycle states. */
    var traceLifecycleStates: Boolean = true,

    /** Trace RAG events (request, response, pipeline). */
    var traceRag: Boolean = true,

    /** Trace ranking/selection events (agent routing decisions). */
    var traceRanking: Boolean = true,

    /** Trace dynamic agent creation events. */
    var traceDynamicAgentCreation: Boolean = true,

    /** Trace HTTP request/response details including bodies, headers and params. */
    var traceHttpDetails: Boolean = true,

    /** Enable @Tracked annotation aspect for custom operation tracking. */
    var traceTrackedOperations: Boolean = true,

    /** Propagate EasyAI context (run_id, agent name, action name) into SLF4J MDC for log correlation. */
    var mdcPropagation: Boolean = true,

    /** Enable/disable Micrometer business metrics (counters, gauges). */
    var metricsEnabled: Boolean = true
)