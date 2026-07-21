package com.easy.easyai.autoconfigure.observability.config

import com.easy.easyai.core.agent.AgentEventListener
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.observability.annotation.TrackedAspect
import com.easy.easyai.observability.config.ObservabilityProperties
import com.easy.easyai.observability.listener.MdcPropagationListener
import com.easy.easyai.observability.listener.MetricsEventListener
import com.easy.easyai.observability.listener.TracingEventListener
import com.easy.easyai.observability.observation.ChatModelObservationFilter
import com.easy.easyai.observability.servlet.HttpBodyCachingFilter
import com.easy.easyai.observability.servlet.HttpRequestObservationFilter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Auto-configuration for EasyAI observability.
 *
 * Configures tracing infrastructure using Spring Observation API,
 * metrics collection via Micrometer, and MDC propagation for log correlation.
 *
 * @see ObservabilityProperties
 * @since 2026.0.1
 */
@AutoConfiguration(
    afterName = [
        "com.easy.easyai.autoconfigure.observability.config.MicrometerTracingAutoConfiguration",
        // Spring Boot 4.x (current)
        "org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration",
        "org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration",
        // Spring Boot 3.x (backward compat)
        "org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration"
    ]
)
@EnableConfigurationProperties(ObservabilityProperties::class)
@ConditionalOnProperty(prefix = "easyai.observability", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class ObservabilityAutoConfiguration {

    private val log = LoggerFactory.getLogger(ObservabilityAutoConfiguration::class.java)

    /**
     * Creates the core tracing event listener.
     *
     * @param observationRegistry the Spring Observation registry for creating spans
     * @param properties the observability configuration properties
     * @return the configured tracing event listener
     */
    @Bean
    @ConditionalOnBean(ObservationRegistry::class)
    @ConditionalOnProperty(prefix = "easyai.observability", name = ["trace-agent-events"], havingValue = "true", matchIfMissing = true)
    fun tracingEventListener(
        observationRegistry: ObservationRegistry,
        properties: ObservabilityProperties
    ): TracingEventListener {
        log.info("Configuring EasyAI tracing with Spring Observation API")
        return TracingEventListener(observationRegistry, properties)
    }

    /**
     * Creates the Micrometer business metrics event listener.
     *
     * @param meterRegistry the Micrometer meter registry to publish metrics to
     * @param properties the observability configuration properties
     * @return the configured metrics event listener
     */
    @Bean
    @ConditionalOnBean(MeterRegistry::class)
    @ConditionalOnProperty(prefix = "easyai.observability", name = ["metrics-enabled"], havingValue = "true", matchIfMissing = true)
    fun metricsEventListener(
        meterRegistry: MeterRegistry,
        properties: ObservabilityProperties
    ): MetricsEventListener {
        log.info("Configuring EasyAI Micrometer metrics listener")
        return MetricsEventListener(meterRegistry, properties)
    }

    /**
     * Creates the MDC propagation listener for log correlation.
     *
     * @param properties the observability configuration properties
     * @return the MDC propagation event listener
     */
    @Bean
    @ConditionalOnProperty(prefix = "easyai.observability", name = ["mdc-propagation"], havingValue = "true", matchIfMissing = true)
    fun mdcPropagationListener(properties: ObservabilityProperties): MdcPropagationListener {
        log.info("Configuring EasyAI MDC propagation for log correlation")
        return MdcPropagationListener(properties)
    }

    /**
     * Configuration class for HTTP servlet request/response tracing.
     *
     * Defines a body-caching filter and an observation filter that enriches
     * HTTP server observations with request/response details (headers, query params, bodies).
     *
     * Isolated into its own @Configuration class to avoid pre-loading Servlet API classes
     * when they are not on the classpath. Spring's @ConditionalOnClass on a @Bean method
     * guards both classpath-dependent beans.
     *
     * Only active when `easyai.observability.trace-http-details=true`.
     *
     * @since 2026.0.1
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["jakarta.servlet.Filter"])
    @ConditionalOnProperty(prefix = "easyai.observability", name = ["trace-http-details"], havingValue = "true")
    class ServletObservabilityConfiguration {

        private val log = LoggerFactory.getLogger(ServletObservabilityConfiguration::class.java)

        /**
         * Creates a servlet filter that wraps request/response for body caching.
         */
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnClass(name = ["org.springframework.web.util.ContentCachingRequestWrapper"])
        fun httpBodyCachingFilter(): HttpBodyCachingFilter {
            log.debug("Configuring HTTP body caching filter for request/response tracing")
            return HttpBodyCachingFilter()
        }

        /**
         * Creates observation filter to enrich HTTP server observations with request/response details.
         */
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnClass(name = ["org.springframework.http.server.observation.ServerRequestObservationContext"])
        fun httpRequestObservationFilter(properties: ObservabilityProperties): HttpRequestObservationFilter {
            log.debug("Configuring HTTP request observation filter for request/response tracing")
            return HttpRequestObservationFilter(properties.maxAttributeLength)
        }
    }

    /**
     * Creates filter to enrich Spring AI LLM observations with prompt/completion.
     *
     * @param properties the observability properties
     * @return the configured observation filter
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = ["org.springframework.ai.chat.observation.ChatModelObservationContext"])
    @ConditionalOnProperty(prefix = "easyai.observability", name = ["trace-llm-calls"], havingValue = "true", matchIfMissing = true)
    fun chatModelObservationFilter(properties: ObservabilityProperties): ChatModelObservationFilter {
        log.debug("Configuring ChatModel observation filter for LLM call tracing")
        return ChatModelObservationFilter(properties.maxAttributeLength)
    }

    /**
     * Configuration class for @Tracked annotation support.
     * Isolated to avoid AspectJ dependency issues when not needed.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["org.aspectj.lang.ProceedingJoinPoint"])
    @ConditionalOnProperty(prefix = "easyai.observability", name = ["trace-tracked-operations"], havingValue = "true", matchIfMissing = true)
     class TrackedAnnotationConfiguration {

        private val log = LoggerFactory.getLogger(TrackedAnnotationConfiguration::class.java)

        /**
         * Creates the AOP aspect for @Tracked annotation support.
         *
         * @param observationRegistry the Spring Observation registry
         * @param properties the observability configuration properties
         * @return the configured tracked aspect
         */
        @Bean
        @ConditionalOnBean(ObservationRegistry::class)
        fun trackedAspect(
            observationRegistry: ObservationRegistry,
            properties: ObservabilityProperties
        ): TrackedAspect {
            log.info("Configuring @Tracked annotation aspect for custom operation tracking")
            return TrackedAspect(observationRegistry, properties)
        }
    }

    /**
     * Wraps AgentService beans to inject observability event listeners.
     *
     * Observability listeners now implement [AgentEventListener] directly,
     * so they are auto-collected by Spring into [AgentService.eventListeners].
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(AgentService::class)
    class AgentServiceObservabilityConfiguration {

        private val log = LoggerFactory.getLogger(AgentServiceObservabilityConfiguration::class.java)

        /**
         * Register each observability listener as an [AgentEventListener] bean.
         * Spring collects all AgentEventListener beans when injecting
         * into DefaultAgentService.eventListeners.
         *
         * These beans are conditional on the corresponding listener beans being available.
         */
        @Bean
        @ConditionalOnProperty(prefix = "easyai.observability", name = ["trace-agent-events"], havingValue = "true", matchIfMissing = true)
        fun tracingAgentEventListener(
            tracingEventListener: ObjectProvider<TracingEventListener>
        ): AgentEventListener? {
            val tracing = tracingEventListener.ifAvailable ?: return null
            log.debug("Registered TracingEventListener as AgentService eventListener")
            return tracing
        }

        @Bean
        @ConditionalOnProperty(prefix = "easyai.observability", name = ["metrics-enabled"], havingValue = "true")
        fun metricsAgentEventListener(
            metricsEventListener: ObjectProvider<MetricsEventListener>
        ): AgentEventListener? {
            val metrics = metricsEventListener.ifAvailable ?: return null
            log.debug("Registered MetricsEventListener as AgentService eventListener")
            return metrics
        }

        @Bean
        @ConditionalOnProperty(prefix = "easyai.observability", name = ["mdc-propagation"], havingValue = "true", matchIfMissing = true)
        fun mdcAgentEventListener(
            mdcPropagationListener: ObjectProvider<MdcPropagationListener>
        ): AgentEventListener? {
            val mdc = mdcPropagationListener.ifAvailable ?: return null
            log.debug("Registered MdcPropagationListener as AgentService eventListener")
            return mdc
        }
    }
}
