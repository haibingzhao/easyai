package com.easy.easyai.observability.observation

import io.micrometer.observation.Observation
import io.micrometer.tracing.Tracer
import io.micrometer.tracing.handler.DefaultTracingObservationHandler

/**
 * Tracing observation handler for standard (non-EasyAI) contexts.
 *
 * This handler explicitly excludes [EasyAiObservationContext] to prevent
 * conflicts with [EasyAiTracingObservationHandler].
 *
 * When both handlers are registered, this ensures:
 * - [EasyAiObservationContext] → handled by [EasyAiTracingObservationHandler]
 * - Standard [Observation.Context] → handled by this handler
 *
 * This prevents the default handler from overwriting spans in TracingContext
 * that were created by EasyAiTracingObservationHandler.
 *
 * @since 2026.0.1
 */
class NonEasyAiTracingObservationHandler(
    tracer: Tracer
) : DefaultTracingObservationHandler(tracer) {

    /**
     * Returns true for all contexts EXCEPT EasyAiObservationContext.
     * This allows EasyAiTracingObservationHandler to handle EasyAI contexts exclusively.
     */
    override fun supportsContext(context: Observation.Context): Boolean =
        context !is EasyAiObservationContext
}