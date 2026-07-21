package com.easy.easyai.autoconfigure.observability.config

import com.easy.easyai.observability.config.ObservabilityProperties
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder
import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureOrder
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered

/**
 * Auto-configuration for OpenTelemetry SDK with multi-exporter support.
 *
 * Configures SdkTracerProvider and exports spans to backends (Langfuse, Zipkin, OTLP, etc.).
 * This configuration uses [AutoConfigureOrder] with low precedence to ensure
 * it runs AFTER all Spring Boot exporter auto-configurations have created their
 * [SpanExporter] beans.
 *
 * @since 2026.0.1
 */
@AutoConfiguration
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE - 10)
@EnableConfigurationProperties(ObservabilityProperties::class)
@ConditionalOnClass(SdkTracerProvider::class, OpenTelemetry::class)
@ConditionalOnProperty(prefix = "easyai.observability", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class OpenTelemetrySdkAutoConfiguration {

    private val log = LoggerFactory.getLogger(OpenTelemetrySdkAutoConfiguration::class.java)

    /**
     * Creates the OpenTelemetry Resource with service attributes.
     *
     * @param properties the observability configuration properties
     * @return the configured OpenTelemetry Resource
     */
    @Bean
    @ConditionalOnMissingBean(Resource::class)
    fun openTelemetryResource(properties: ObservabilityProperties): Resource {
        val serviceName = System.getProperty("otel.resource.attributes", "")
            .split(",")
            .firstOrNull { it.startsWith("service.name=") }
            ?.substringAfter("=")
            ?: properties.serviceName

        val serviceVersion = System.getProperty("otel.resource.attributes", "")
            .split(",")
            .firstOrNull { it.startsWith("service.version=") }
            ?.substringAfter("=")
            ?: properties.tracerVersion

        val deploymentEnvironment = System.getProperty("otel.resource.attributes", "")
            .split(",")
            .firstOrNull { it.startsWith("deployment.environment=") }
            ?.substringAfter("=")
            ?: "default"

        return Resource.getDefault()
            .merge(
                Resource.create(
                    Attributes.builder()
                        .put(io.opentelemetry.semconv.ServiceAttributes.SERVICE_NAME, serviceName)
                        .put(io.opentelemetry.semconv.ServiceAttributes.SERVICE_VERSION, serviceVersion)
                        .put(AttributeKey.stringKey("deployment.environment"), deploymentEnvironment)
                        .build()
                )
            )
    }

    /**
     * Creates OTLP HTTP SpanExporter from system properties.
     * This enables Alibaba Cloud Monitor 2.0 integration via -D properties.
     * Only creates exporter if no other SpanExporter beans are already configured.
     */
    @Bean
    @ConditionalOnMissingBean(SpanExporter::class)
    @ConditionalOnProperty(prefix = "easyai.observability", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun otlpHttpSpanExporter(): SpanExporter? {
        val endpoint = System.getProperty("otel.exporter.otlp.traces.endpoint")
            ?: System.getProperty("otel.exporter.otlp.endpoint")
        
        if (endpoint.isNullOrBlank()) {
            log.info("No OTLP endpoint configured via system properties. OTLP exporter will be disabled.")
            return null
        }

        val headers = System.getProperty("otel.exporter.otlp.headers", "")
        val headersMap = if (headers.isNotBlank()) {
            headers.split(",").map { it.split("=") }.filter { it.size == 2 }.associate { it[0] to it[1] }
        } else {
            emptyMap()
        }

        log.info("Configuring OTLP HTTP SpanExporter with endpoint: {}", endpoint)
        log.debug("OTLP headers configured: {}", headersMap.keys)

        return try {
            val builder = OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint)
                .addHeader("x-arms-license-key", headersMap["x-arms-license-key"] ?: "")
                .addHeader("x-arms-project", headersMap["x-arms-project"] ?: "")
                .addHeader("x-cms-workspace", headersMap["x-cms-workspace"] ?: "")
            
            builder.build()
        } catch (e: Exception) {
            log.error("Failed to create OtlpHttpSpanExporter", e)
            null
        }
    }

    /**
     * Creates the SdkTracerProvider with all configured SpanExporters and SpanProcessors.
     *
     * @param exportersProvider provider for the list of SpanExporter beans
     * @param processorsProvider provider for the list of SpanProcessor beans
     * @param resource the OpenTelemetry Resource to associate with traces
     * @return the configured SdkTracerProvider, or null if no exporters are available
     */
    @Bean
    @ConditionalOnMissingBean(SdkTracerProvider::class)
    fun sdkTracerProvider(
        exportersProvider: ObjectProvider<List<SpanExporter>>,
        processorsProvider: ObjectProvider<List<SpanProcessor>>,
        resource: Resource
    ): SdkTracerProvider? {
        val exporters = exportersProvider.ifAvailable
        val processors = processorsProvider.ifAvailable

        val validExporters = exporters?.filterNotNull() ?: emptyList()
        val validProcessors = processors?.filterNotNull() ?: emptyList()

        if (validExporters.isEmpty()) {
            log.warn(
                "No SpanExporter beans found. OpenTelemetry tracing will be disabled. " +
                        "To enable tracing, add an exporter dependency (e.g., opentelemetry-exporter-langfuse, " +
                        "opentelemetry-exporter-zipkin) and configure it properly."
            )
            return null
        }

        val tracerProviderBuilder: SdkTracerProviderBuilder = SdkTracerProvider.builder()
            .setResource(resource)

        for (processor in validProcessors) {
            tracerProviderBuilder.addSpanProcessor(processor)
            log.debug("Added SpanProcessor: {}", processor.javaClass.simpleName)
        }

        for (exporter in validExporters) {
            tracerProviderBuilder.addSpanProcessor(
                BatchSpanProcessor.builder(exporter).build()
            )
            log.debug("Added SpanExporter: {}", exporter.javaClass.simpleName)
        }

        val tracerProvider = tracerProviderBuilder.build()

        log.info(
            "SdkTracerProvider configured with {} processor(s) and {} exporter(s)",
            validProcessors.size, validExporters.size
        )

        return tracerProvider
    }

    /**
     * Creates the OpenTelemetry SDK instance and registers it globally.
     *
     * @param tracerProviderProvider provider for the SdkTracerProvider bean
     * @return the configured OpenTelemetry instance, or a noop instance if no tracer provider is available
     */
    @Bean
    @ConditionalOnMissingBean(OpenTelemetry::class)
    fun openTelemetry(tracerProviderProvider: ObjectProvider<SdkTracerProvider>): OpenTelemetry {
        val tracerProvider = tracerProviderProvider.ifAvailable

        if (tracerProvider == null) {
            log.warn("No SdkTracerProvider available. OpenTelemetry will be disabled.")
            return OpenTelemetry.noop()
        }

        val openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .buildAndRegisterGlobal()

        log.info("OpenTelemetry SDK configured and registered globally")

        return openTelemetry
    }
}