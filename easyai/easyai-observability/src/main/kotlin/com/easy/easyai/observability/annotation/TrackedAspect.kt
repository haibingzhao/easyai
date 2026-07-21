package com.easy.easyai.observability.annotation

import com.easy.easyai.observability.config.ObservabilityProperties
import com.easy.easyai.observability.observation.ObservationUtils
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.slf4j.MDC
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order

/**
 * AOP aspect that intercepts [@Tracked] annotated methods and creates observation spans.
 *
 * Captures:
 * - Method arguments with parameter names (truncated to maxAttributeLength)
 * - Return value (truncated to 256 chars)
 * - Duration (automatic)
 * - Errors (automatic, with stack trace)
 * - Session context from MDC (if inside an agent session)
 *
 * **Important:** Spring AOP is proxy-based, so internal method calls within the same class
 * are NOT intercepted. Use one of these workarounds:
 * 1. Extract tracked methods to a separate bean (recommended)
 * 2. Self-injection: `@Autowired private lateinit var self: MyService` and call `self.trackedMethod()`
 *
 * @property observationRegistry Spring Observation registry for creating spans
 * @property properties observability configuration properties
 */
@Aspect
@Order(1) // Ensure this runs before other aspects
class TrackedAspect(
    private val observationRegistry: ObservationRegistry,
    private val properties: ObservabilityProperties
) {
    private val log = LoggerFactory.getLogger(TrackedAspect::class.java)

    companion object {
        private const val MAX_RETURN_VALUE_LENGTH = 256
    }

    /**
     * Intercepts methods annotated with [@Tracked] and creates an observation span.
     *
     * @param joinPoint the proceeding join point
     * @return the method's return value
     * @throws Throwable if the method throws an exception
     */
    @Around("@annotation(tracked)")
    fun trackOperation(joinPoint: ProceedingJoinPoint, tracked: Tracked): Any? {
        if (!properties.enabled || !properties.traceTrackedOperations) {
            return joinPoint.proceed()
        }

        val signature = joinPoint.signature as MethodSignature
        val methodName = signature.name
        val operationName = if (tracked.value.isNotBlank()) tracked.value else methodName

        val observation = Observation.createNotStarted(operationName, observationRegistry)

        // Add session context from MDC if available
        MDC.get("easyai.session.id")?.let { sessionId ->
            observation.highCardinalityKeyValue("easyai.session.id", sessionId)
        }

        // Add operation metadata
        observation.lowCardinalityKeyValue("easyai.operation.type", tracked.type.name)
        observation.lowCardinalityKeyValue("easyai.event.type", "tracked_operation")

        if (tracked.description.isNotBlank()) {
            observation.highCardinalityKeyValue("easyai.operation.description", tracked.description)
        }

        // Add method arguments
        val args = signature.parameterNames.zip(joinPoint.args) { name, value ->
            name to ObservationUtils.safeToString(value, properties.maxAttributeLength)
        }.toMap()

        if (args.isNotEmpty()) {
            observation.highCardinalityKeyValue("input.value", args.toString())
        }

        // Start observation
        observation.start()

        return try {
            val result = joinPoint.proceed()

            // Add return value
            if (result != null) {
                val resultStr = ObservationUtils.truncate(result.toString(), MAX_RETURN_VALUE_LENGTH)
                observation.highCardinalityKeyValue("output.value", resultStr)
            }

            observation.stop()
            result
        } catch (e: Throwable) {
            observation.error(e)
            observation.stop()
            throw e
        }
    }
}
