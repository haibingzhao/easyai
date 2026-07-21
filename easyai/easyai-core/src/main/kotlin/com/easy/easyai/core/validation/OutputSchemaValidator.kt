package com.easy.easyai.core.validation

import org.slf4j.LoggerFactory

/**
 * Sealed class representing validation results.
 */
sealed class ValidationResult {
    data object Valid : ValidationResult()
    data class Invalid(val errors: List<String>) : ValidationResult()
}

/**
 * Validates LLM output against JSON Schema.
 * Used by OutputSchemaCompletionCheck as a fallback when
 * StructuredOutputChatOptions is not supported by the model.
 *
 * Thread-safe, stateless service with schema compilation caching.
 */
class OutputSchemaValidator : AbstractSchemaValidator() {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Validate that assistant text conforms to the given JSON Schema.
     *
     * @param schema JSON Schema string
     * @param assistantText Raw LLM response text
     * @return Valid if output conforms, Invalid with error list otherwise
     */
    fun validateOutput(schema: String, assistantText: String): ValidationResult {
        val jsonText = JsonExtractor.extract(assistantText)
            ?: return ValidationResult.Invalid(listOf("No valid JSON found in response"))

        return try {
            validateJson(schema, jsonText)
        } catch (e: Exception) {
            logger.warn("Output schema validation failed: {}", e.message)
            ValidationResult.Invalid(listOf("Validation error: ${e.message}"))
        }
    }
}
