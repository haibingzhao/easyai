package com.easy.easyai.core.validation

import com.easy.easyai.core.agent.AgentCompletionCheck
import com.easy.easyai.core.agent.CompletionCheckInput
import com.easy.easyai.core.agent.CompletionCheckResult
import com.easy.easyai.core.model.AssistantMessage
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Completion check that validates the final assistant output against a JSON Schema.
 * Acts as a fallback when StructuredOutputChatOptions is not supported by the model.
 *
 * When validation fails, injects a retry prompt (up to [maxRetries] times) to guide
 * the LLM to produce valid JSON output.
 */
class OutputSchemaCompletionCheck(
    private val validator: OutputSchemaValidator,
    private val maxRetries: Int = 2
) : AgentCompletionCheck {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val retryCounters = ConcurrentHashMap<String, Int>()

    /**
     * Clears stale state for the given session.
     * Called at the start of each agent loop run to prevent leaked state
     * from a previous abnormal termination (abort, cancellation, etc.).
     */
    fun resetSession(sessionId: String) {
        retryCounters.remove(sessionId)
        retryCounters.remove("$sessionId-forced")
    }

    override suspend fun check(input: CompletionCheckInput): CompletionCheckResult {
        val schema = input.agentContext.outputSchema
        val sessionKey = input.agentContext.sessionId

        if (schema == null || sessionKey == null) {
            // Clean up any stale counters
            if (sessionKey != null) retryCounters.remove(sessionKey)
            return CompletionCheckResult.Done
        }

        // Multi-turn mode: first trigger → force a structured output iteration
        if (input.agentContext.outputSchemaMultiTurn) {
            val forcedKey = "$sessionKey-forced"
            if (retryCounters[forcedKey] == null) {
                retryCounters[forcedKey] = 1
                logger.info("Multi-turn output schema: triggering structured output phase for session {}", sessionKey)
                return CompletionCheckResult.Continue(prompt = buildFinalOutputPrompt(schema))
            }
            // Already in structured phase: fall through to normal validation (keep marker for cleanup)
        }

        // Find the last AssistantMessage in transcript
        val lastAssistant = input.transcript.lastOrNull { it is AssistantMessage } as? AssistantMessage
            ?: return CompletionCheckResult.Done

        val text = lastAssistant.text()
        if (text.isBlank()) return CompletionCheckResult.Done

        val result = validator.validateOutput(schema, text)

        return when {
            result is ValidationResult.Valid -> {
                logger.debug("Output schema validation passed for session {}", sessionKey)
                retryCounters.remove(sessionKey)
                retryCounters.remove("$sessionKey-forced")
                CompletionCheckResult.Done
            }
            (retryCounters[sessionKey] ?: 0) >= maxRetries -> {
                logger.warn(
                    "Output schema validation failed after {} retries for session {}, returning original result",
                    maxRetries, sessionKey
                )
                retryCounters.remove(sessionKey)
                retryCounters.remove("$sessionKey-forced")
                CompletionCheckResult.Done  // Exceeded retries, return original
            }
            else -> {
                val attempt = (retryCounters[sessionKey] ?: 0) + 1
                retryCounters[sessionKey] = attempt
                val errors = (result as ValidationResult.Invalid).errors
                logger.info(
                    "Output schema validation failed (attempt {}/{}) for session {}: {}",
                    attempt, maxRetries, sessionKey, errors
                )
                CompletionCheckResult.Continue(prompt = buildRetryPrompt(errors, schema, attempt, maxRetries))
            }
        }
    }

    private fun buildFinalOutputPrompt(schema: String): String = buildString {
        appendLine("You have completed all necessary tool calls and gathered sufficient information.")
        appendLine("Now produce your final result as a valid JSON object matching this schema:")
        appendLine("```json")
        appendLine(schema)
        appendLine("```")
        appendLine()
        appendLine("Output ONLY the JSON object, no additional text.")
    }

    private fun buildRetryPrompt(
        errors: List<String>,
        schema: String,
        attempt: Int,
        maxAttempts: Int
    ): String = buildString {
        appendLine("Your previous response did not match the required output format (attempt $attempt/$maxAttempts).")
        appendLine()
        appendLine("Validation errors:")
        errors.forEach { appendLine("- $it") }
        appendLine()
        appendLine("Please reformat your response as a valid JSON object matching this schema:")
        appendLine("```json")
        appendLine(schema)
        appendLine("```")
        appendLine()
        appendLine("Output ONLY the JSON object, no additional text.")
    }
}
