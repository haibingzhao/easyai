package com.easy.easyai.core.resilience

import com.easy.easyai.core.agent.AgentContext
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide registry of [LlmCircuitBreaker] instances, one per LLM endpoint.
 *
 * The failure domain is the endpoint itself (`protocol + baseUrl`), matching
 * ModelConfigGroup semantics where multiple models share the same baseUrl/apiKey:
 * when a gateway goes down, all models behind it fail together and share one
 * breaker, while a different baseUrl is unaffected.
 *
 * Configuration via system properties (overridable with `-D` / desktop jvmArgs):
 * - `easyai.llm.circuit-breaker.enabled` (default `true`, kill switch)
 * - `easyai.llm.circuit-breaker.failure-threshold` (default `3`)
 * - `easyai.llm.circuit-breaker.initial-cooldown-seconds` (default `30`)
 * - `easyai.llm.circuit-breaker.max-cooldown-seconds` (default `600`)
 *
 * Known coverage gaps (v1, by design): compaction (`session.getChatModel()`)
 * and InternalLlmService call paths do not go through AgentLoopRunner and are
 * therefore not protected by this breaker.
 */
internal object LlmCircuitBreakerRegistry {

    private const val PREFIX = "easyai.llm.circuit-breaker"

    private val logger = LoggerFactory.getLogger(javaClass)
    private val breakers = ConcurrentHashMap<String, LlmCircuitBreaker>()

    /**
     * Resolve the breaker for the context's model endpoint, or null when the
     * breaker is disabled or the context carries no model config (callers
     * treat null as a no-op).
     */
    fun forContext(context: AgentContext): LlmCircuitBreaker? {
        if (!isEnabled()) return null
        val config = context.modelConfig ?: return null
        val key = keyFor(config.protocol.name, config.baseUrl)
        return breakers.computeIfAbsent(key) {
            LlmCircuitBreaker(key, loadSettings())
        }
    }

    /** Reset all breaker state (test isolation). */
    internal fun reset() {
        breakers.clear()
    }

    /** Build the normalized endpoint key: `protocol:baseUrl` (or `protocol:default`). */
    internal fun keyFor(protocol: String, baseUrl: String?): String {
        val normalized = baseUrl?.trim()?.trimEnd('/')?.lowercase()
        return "$protocol:${normalized?.ifEmpty { null } ?: "default"}"
    }

    private fun isEnabled(): Boolean =
        System.getProperty("$PREFIX.enabled")?.toBooleanStrictOrNull() ?: true

    private fun loadSettings(): CircuitBreakerSettings {
        val settings = CircuitBreakerSettings(
            failureThreshold = intProperty("failure-threshold", 3),
            initialCooldownMs = intProperty("initial-cooldown-seconds", 30) * 1000L,
            maxCooldownMs = intProperty("max-cooldown-seconds", 600) * 1000L
        )
        logger.debug("Circuit breaker settings for next endpoint: {}", settings)
        return settings
    }

    private fun intProperty(name: String, default: Int): Int =
        System.getProperty("$PREFIX.$name")?.toIntOrNull()?.takeIf { it > 0 } ?: default
}
