package com.easy.easyai.core.validation

import com.easy.easyai.core.agent.AgentCompletionCheck
import com.easy.easyai.core.agent.CompletionCheckInput
import com.easy.easyai.core.agent.CompletionCheckResult
import com.easy.easyai.core.model.AssistantMessage
import org.slf4j.LoggerFactory

/**
 * Completion check that validates the final assistant output against a JSON Schema.
 * Acts as a fallback when StructuredOutputChatOptions is not supported by the model.
 *
 * When validation fails, injects a retry prompt (up to [maxRetries] times) to guide
 * the LLM to produce valid JSON output.
 *
 * In multi-turn mode ([com.easy.easyai.core.agent.AgentContext.outputSchemaMultiTurn]) the
 * structured-output iteration is skipped when the tool-calling phase already emitted
 * schema-conforming JSON — see [check].
 *
 * Stateless: the loop's per-run nudge ledger supplies [CompletionCheckInput.nudgeAttempt],
 * which doubles as "the structured-output phase has already been forced" in multi-turn mode.
 * Being run-scoped is also a fix over the previous session-keyed counter map, whose markers
 * survived an abnormal termination and leaked into the next run of the same session.
 */
class OutputSchemaCompletionCheck(
    private val validator: OutputSchemaValidator,
    private val maxRetries: Int = 2
) : AgentCompletionCheck {

    private val logger = LoggerFactory.getLogger(javaClass)

    /** One nudge for the forced structured-output phase, plus the validation retries. */
    override fun maxNudges(): Int = maxRetries + 1

    override suspend fun check(input: CompletionCheckInput): CompletionCheckResult {
        val schema = input.agentContext.outputSchema
        val sessionKey = input.agentContext.sessionId

        if (schema == null || sessionKey == null) {
            return CompletionCheckResult.Done
        }

        // Last assistant text, resolved once: shared by the multi-turn fast path below
        // and the regular validation at the end of this function.
        val text = (input.transcript.lastOrNull { it is AssistantMessage } as? AssistantMessage)?.text().orEmpty()

        // Multi-turn mode: the tool-calling phase is free-form, so a dedicated structured-output
        // iteration is normally forced afterwards. Skip that iteration when the phase already
        // produced schema-conforming output: it saves a full LLM round-trip, and it keeps a usable
        // result on gateways whose model rejects API-level structured output (HTTP 400), which
        // would otherwise abort the run and discard the valid output.
        if (input.agentContext.outputSchemaMultiTurn && input.nudgeAttempt == 0) {
            if (text.isNotBlank() && validator.validateOutput(schema, text) is ValidationResult.Valid) {
                logger.info(
                    "Multi-turn output schema: tool-phase output already conforms, " +
                        "skipping structured output phase for session {}",
                    sessionKey
                )
                return CompletionCheckResult.Done
            }
            logger.info("Multi-turn output schema: triggering structured output phase for session {}", sessionKey)
            return CompletionCheckResult.Continue(prompt = buildFinalOutputPrompt(schema))
        }
        // nudgeAttempt > 0 in multi-turn mode: already forced — fall through to validation

        if (text.isBlank()) return CompletionCheckResult.Done

        val result = validator.validateOutput(schema, text)

        return when {
            result is ValidationResult.Valid -> {
                logger.debug("Output schema validation passed for session {}", sessionKey)
                CompletionCheckResult.Done
            }
            input.nudgeAttempt >= maxRetries -> {
                logger.warn(
                    "Output schema validation failed after {} retries for session {}, returning original result",
                    maxRetries, sessionKey
                )
                CompletionCheckResult.Done  // Exceeded retries, return original
            }
            else -> {
                val attempt = input.nudgeAttempt + 1
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
