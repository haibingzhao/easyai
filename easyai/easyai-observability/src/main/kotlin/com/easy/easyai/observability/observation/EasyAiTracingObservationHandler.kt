package com.easy.easyai.observability.observation

import io.micrometer.observation.Observation
import io.micrometer.tracing.Span
import io.micrometer.tracing.Tracer
import io.micrometer.tracing.handler.TracingObservationHandler
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Custom TracingObservationHandler for EasyAI observations.
 *
 * Creates root spans for agents and manages parent-child hierarchy for actions, tools, and LLM calls.
 * Integrates with Spring Observation API to provide distributed tracing for agent execution.
 *
 * @since 2026.0.1
 */
class EasyAiTracingObservationHandler(
    private val tracer: Tracer
) : TracingObservationHandler<EasyAiObservationContext> {

    private val log = LoggerFactory.getLogger(EasyAiTracingObservationHandler::class.java)

    // Active spans keyed by runId for parent resolution
    private val activeAgentSpans: MutableMap<String, Span> = ConcurrentHashMap()
    private val activeActionSpans: MutableMap<String, Span> = ConcurrentHashMap()
    private val activeLlmSpans: MutableMap<String, Span> = ConcurrentHashMap()
    private val activeToolLoopSpans: MutableMap<String, Span> = ConcurrentHashMap()

    // Active scopes for span-in-scope management
    private val activeScopes: MutableMap<Int, Tracer.SpanInScope> = ConcurrentHashMap()

    init {
        log.info("EasyAiTracingObservationHandler initialized")
    }

    override fun getTracer(): Tracer = tracer

    override fun supportsContext(context: Observation.Context): Boolean =
        context is EasyAiObservationContext

    override fun onStart(context: EasyAiObservationContext) {
        val span: Span

        if (isRoot(context)) {
            val currentSpan = tracer.currentSpan()
            if (currentSpan != null) {
                span = tracer.nextSpan(currentSpan)!!.name(context.contextualName ?: "")
                span.start()
                log.debug(
                    "Created agent span as child of existing trace for: {} (runId: {})",
                    context.contextualName, context.runId
                )
            } else {
                span = createRootSpan(context.contextualName ?: "")
                log.debug(
                    "Created root span for agent: {} (runId: {})",
                    context.contextualName, context.runId
                )
            }
        } else {
            val parentSpan = resolveParentSpan(context)
            if (parentSpan != null) {
                span = tracer.nextSpan(parentSpan)!!.name(context.contextualName ?: "")
            } else {
                log.warn(
                    "No parent span found for {} '{}' (runId: {}), span will use thread-local context. " +
                            "Active maps: agents={}, actions={}, llms={}, toolLoops={}",
                    context.eventType, context.contextualName, context.runId,
                    activeAgentSpans.keys, activeActionSpans.keys,
                    activeLlmSpans.keys, activeToolLoopSpans.keys
                )
                span = tracer.nextSpan().name(context.contextualName ?: "")
            }
            span.start()
            log.debug(
                "Created child span for {}: {} (runId: {})",
                context.eventType, context.contextualName, context.runId
            )
        }

        span.tag("easyai.event.type", context.eventType.name.lowercase())
        span.tag("easyai.run_id", context.runId)
        getTracingContext(context).span = span
        trackActiveSpan(context, span)
    }

    override fun onScopeOpened(context: EasyAiObservationContext) {
        val span = getTracingContext(context).span
        if (span != null) {
            val scope = tracer.withSpan(span)
            activeScopes[System.identityHashCode(context)] = scope
        }
    }

    override fun onScopeClosed(context: EasyAiObservationContext) {
        val scope = activeScopes.remove(System.identityHashCode(context))
        scope?.close()
    }

    override fun onStop(context: EasyAiObservationContext) {
        val span = getRequiredSpan(context)

        for (keyValue in context.lowCardinalityKeyValues) {
            span.tag(keyValue.key, keyValue.value)
        }
        for (keyValue in context.highCardinalityKeyValues) {
            span.tag(keyValue.key, keyValue.value)
        }
        if (context.contextualName != null) {
            span.name(context.contextualName!!)
        }

        val error = context.error
        if (error != null) {
            span.error(error)
        }

        untrackActiveSpan(context)
        span.end()
        log.debug(
            "Ended span for {}: {} (runId: {})",
            context.eventType, context.name, context.runId
        )
    }

    override fun onError(context: EasyAiObservationContext) {
        val error = context.error
        if (error != null) {
            val span = getTracingContext(context).span
            span?.error(error)
        }
    }

    /**
     * Creates a root span with no parent by clearing context.
     */
    private fun createRootSpan(name: String): Span {
        val oldScope = tracer.withSpan(null)
        try {
            val rootSpan = tracer.nextSpan().name(name)
            rootSpan.start()
            return rootSpan
        } finally {
            oldScope.close()
        }
    }

    /**
     * Resolves the parent span from EasyAI hierarchy or current tracer context.
     */
    @Suppress("UNCHECKED_CAST")
    private fun resolveParentSpan(context: EasyAiObservationContext): Span? {
        // Check if the listener has explicitly set a parent observation.
        val parentObs = context.parentObservation
        if (parentObs != null) {
            val parentCtx = parentObs.context
            val tracingContextClass = Class.forName("io.micrometer.tracing.handler.TracingObservationHandler\$TracingContext")
            val parentTracingCtx = parentCtx.get(tracingContextClass) as Any?
            if (parentTracingCtx != null) {
                val spanMethod = tracingContextClass.getMethod("getSpan")
                val span = spanMethod.invoke(parentTracingCtx) as? Span
                if (span != null) {
                    log.debug(
                        "Parent span resolved from parentObservation for {} '{}' (runId: {})",
                        context.eventType, context.name, context.runId
                    )
                    return span
                }
            }
        }

        val runId = context.runId

        return when (context.eventType) {
            EasyAiObservationContext.EventType.ACTION -> activeAgentSpans[runId]

            EasyAiObservationContext.EventType.AGENT_PROCESS -> {
                if (context.parentRunId != null) {
                    val parentActionSpan = activeActionSpans[context.parentRunId]
                    if (parentActionSpan != null) {
                        return parentActionSpan
                    }
                    activeAgentSpans[context.parentRunId]
                } else {
                    null
                }
            }

            EasyAiObservationContext.EventType.LLM_CALL -> {
                activeActionSpans[runId] ?: activeAgentSpans[runId]
            }

            EasyAiObservationContext.EventType.TOOL_LOOP -> {
                val currentSpan = tracer.currentSpan()
                if (currentSpan != null) {
                    log.debug("TOOL_LOOP parent resolved to tracer.currentSpan() (runId: {})", runId)
                    return currentSpan
                }
                val toolLoopActionSpan = activeActionSpans[runId]
                if (toolLoopActionSpan != null) {
                    log.debug("TOOL_LOOP parent resolved to ACTION span (runId: {}, no currentSpan)", runId)
                    return toolLoopActionSpan
                }
                log.debug("TOOL_LOOP parent resolved to AGENT span (runId: {}, no currentSpan or ACTION)", runId)
                activeAgentSpans[runId]
            }

            EasyAiObservationContext.EventType.TOOL_CALL -> {
                val currentSpan = tracer.currentSpan()
                if (currentSpan != null) {
                    return currentSpan
                }
                activeActionSpans[runId] ?: activeAgentSpans[runId]
            }

            EasyAiObservationContext.EventType.GOAL,
            EasyAiObservationContext.EventType.PLANNING,
            EasyAiObservationContext.EventType.STATE_TRANSITION,
            EasyAiObservationContext.EventType.LIFECYCLE,
            EasyAiObservationContext.EventType.CUSTOM -> {
                activeActionSpans[runId] ?: activeAgentSpans[runId]
            }

            EasyAiObservationContext.EventType.RAG,
            EasyAiObservationContext.EventType.RANKING,
            EasyAiObservationContext.EventType.DYNAMIC_AGENT_CREATION -> null
        }
    }

    /**
     * Tracks active span for parent resolution.
     */
    private fun trackActiveSpan(context: EasyAiObservationContext, span: Span) {
        when (context.eventType) {
            EasyAiObservationContext.EventType.AGENT_PROCESS -> activeAgentSpans[context.runId] = span
            EasyAiObservationContext.EventType.ACTION -> activeActionSpans[context.runId] = span
            EasyAiObservationContext.EventType.LLM_CALL ->
                activeLlmSpans[System.identityHashCode(context).toString()] = span

            EasyAiObservationContext.EventType.TOOL_LOOP ->
                activeToolLoopSpans[System.identityHashCode(context).toString()] = span

            else -> {}
        }
    }

    /**
     * Removes span from active tracking when observation stops.
     */
    private fun untrackActiveSpan(context: EasyAiObservationContext) {
        when (context.eventType) {
            EasyAiObservationContext.EventType.AGENT_PROCESS -> activeAgentSpans.remove(context.runId)
            EasyAiObservationContext.EventType.ACTION -> activeActionSpans.remove(context.runId)
            EasyAiObservationContext.EventType.LLM_CALL ->
                activeLlmSpans.remove(System.identityHashCode(context).toString())

            EasyAiObservationContext.EventType.TOOL_LOOP ->
                activeToolLoopSpans.remove(System.identityHashCode(context).toString())

            else -> {}
        }
    }

    private fun isRoot(context: EasyAiObservationContext): Boolean =
        context.eventType == EasyAiObservationContext.EventType.AGENT_PROCESS && context.parentRunId == null
}