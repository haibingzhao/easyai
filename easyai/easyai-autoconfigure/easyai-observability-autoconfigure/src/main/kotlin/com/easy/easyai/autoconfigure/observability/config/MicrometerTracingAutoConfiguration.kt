package com.easy.easyai.autoconfigure.observability.config

import com.easy.easyai.observability.config.ObservabilityProperties
import com.easy.easyai.observability.observation.EasyAiObservationContext
import com.easy.easyai.observability.observation.EasyAiTracingObservationHandler
import com.easy.easyai.observability.observation.NonEasyAiTracingObservationHandler
import io.micrometer.observation.ObservationRegistry
import io.micrometer.tracing.Tracer
import io.micrometer.tracing.handler.DefaultTracingObservationHandler
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext
import io.micrometer.tracing.otel.bridge.OtelTracer
import io.opentelemetry.api.OpenTelemetry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationRegistryCustomizer
import org.springframework.context.annotation.Bean

/**
 * Auto-configuration for Micrometer Tracing bridge to OpenTelemetry.
 *
 * Provides OtelCurrentTraceContext for context propagation between Micrometer and OpenTelemetry,
 * and registers custom tracing observation handlers for EasyAI events.
 *
 * @see OpenTelemetrySdkAutoConfiguration
 * @since 2026.0.1
 */
@AutoConfiguration(after = [OpenTelemetrySdkAutoConfiguration::class])
@ConditionalOnClass(OtelTracer::class, OpenTelemetry::class, ObservationRegistry::class)
@ConditionalOnProperty(prefix = "easyai.observability", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class MicrometerTracingAutoConfiguration {

    private val log = LoggerFactory.getLogger(MicrometerTracingAutoConfiguration::class.java)

    companion object {
        private const val OBSERVABILITY_TOOL_CALLBACK_OBSERVATION_NAME = "tool call"
    }

    /**
     * Creates OtelCurrentTraceContext for parent-child span propagation.
     *
     * @return the OtelCurrentTraceContext instance
     */
    @Bean
    @ConditionalOnMissingBean(OtelCurrentTraceContext::class)
    fun otelCurrentTraceContext(): OtelCurrentTraceContext {
        log.debug("Creating OtelCurrentTraceContext for trace context propagation")
        return OtelCurrentTraceContext()
    }

    /**
     * Creates a Micrometer [Tracer] backed by OpenTelemetry.
     *
     * Bridges the [OpenTelemetry] instance to Micrometer Tracing so that
     * [DefaultTracingObservationHandler] and other Micrometer-based tracing
     * components have a [Tracer] bean available.
     *
     * @param openTelemetry the OpenTelemetry instance
     * @param otelCurrentTraceContext the current trace context bridge
     * @return the OtelTracer instance
     */
    @Bean
    @ConditionalOnMissingBean(Tracer::class)
    fun otelTracer(openTelemetry: OpenTelemetry, otelCurrentTraceContext: OtelCurrentTraceContext): Tracer {
        log.info("Creating OtelTracer bridge for Micrometer Tracing")
        return OtelTracer(
            openTelemetry.getTracer("easyai"),
            otelCurrentTraceContext
        ) { }
    }

    /**
     * Registers EasyAiTracingObservationHandler for root span creation and hierarchy management.
     *
     * @param tracerProvider the Micrometer Tracer provider
     * @param otelProvider the OpenTelemetry provider
     * @param properties the observability configuration properties
     * @return the customizer for the ObservationRegistry
     */
    @Bean
    @ConditionalOnProperty(prefix = "easyai.observability", name = ["implementation"], havingValue = "SPRING_OBSERVATION", matchIfMissing = true)
    fun easyAiTracingObservationCustomizer(
        tracerProvider: ObjectProvider<Tracer>,
        otelProvider: ObjectProvider<OpenTelemetry>,
        properties: ObservabilityProperties
    ): ObservationRegistryCustomizer<ObservationRegistry> {
        return ObservationRegistryCustomizer { registry ->
            val tracer = tracerProvider.ifAvailable
            val otel = otelProvider.ifAvailable

            if (tracer == null || otel == null) {
                log.warn("Cannot register EasyAiTracingObservationHandler: Tracer or OpenTelemetry not available")
                return@ObservationRegistryCustomizer
            }

            otel.getTracer(
                properties.tracerName,
                properties.tracerVersion
            )

            val handler = EasyAiTracingObservationHandler(tracer)
            registry.observationConfig().observationHandler(handler)

            log.info("Registered EasyAiTracingObservationHandler for Spring Observation API integration")
        }
    }

    /**
     * Replaces Spring Boot's DefaultTracingObservationHandler with NonEasyAiTracingObservationHandler.
     *
     * This prevents Spring Boot's default handler from processing [EasyAiObservationContext],
     * which should be handled exclusively by [EasyAiTracingObservationHandler].
     *
     * @param tracer the Micrometer Tracer
     * @return the configured observation handler
     */
    @Bean
    @ConditionalOnMissingBean(DefaultTracingObservationHandler::class)
    @ConditionalOnProperty(prefix = "easyai.observability", name = ["implementation"], havingValue = "SPRING_OBSERVATION", matchIfMissing = true)
    fun defaultTracingObservationHandler(tracerProvider: ObjectProvider<Tracer>): DefaultTracingObservationHandler? {
        val tracer = tracerProvider.ifAvailable ?: run {
            log.warn("Tracer bean not available, skipping NonEasyAiTracingObservationHandler registration")
            return null
        }
        log.info("Replacing Spring Boot's DefaultTracingObservationHandler with NonEasyAiTracingObservationHandler")
        return NonEasyAiTracingObservationHandler(tracer)
    }

    /**
     * Registers an ObservationPredicate to skip tool call observations from ObservabilityToolCallback
     * when EasyAI's own tool tracing is enabled.
     *
     * @return an ObservationRegistryCustomizer that registers the predicate
     */
    @Bean
    @ConditionalOnProperty(prefix = "easyai.observability", name = ["trace-tool-calls"], havingValue = "true", matchIfMissing = true)
    fun skipObservabilityToolCallbackCustomizer(): ObservationRegistryCustomizer<ObservationRegistry> {
        log.info(
            "Registering ObservationPredicate to skip ObservabilityToolCallback observations " +
                    "(trace-tool-calls=true, EasyAI will trace tools via events)"
        )
        return ObservationRegistryCustomizer { registry ->
            registry.observationConfig().observationPredicate { name, _ ->
                name != OBSERVABILITY_TOOL_CALLBACK_OBSERVATION_NAME
            }
        }
    }
}