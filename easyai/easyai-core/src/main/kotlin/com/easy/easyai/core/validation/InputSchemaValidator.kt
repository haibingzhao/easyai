package com.easy.easyai.core.validation

import com.easy.easyai.common.util.SharedObjectMapper
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper

/**
 * Validates structured input data against JSON Schema.
 * Used by AgentLoop to validate structured input data against the agent's input schema
 * at the Agent Run stage.
 *
 * Thread-safe, stateless service with schema compilation caching.
 */
class InputSchemaValidator : AbstractSchemaValidator() {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper: ObjectMapper = SharedObjectMapper.instance

    /**
     * Validate that input data conforms to the given JSON Schema.
     *
     * @param schema JSON Schema string
     * @param inputData Map of input variables from API request
     * @return Valid if input conforms, Invalid with error list otherwise
     */
    fun validateInput(schema: String, inputData: Map<String, Any?>): ValidationResult {
        return try {
            val jsonText = objectMapper.writeValueAsString(inputData)
            validateJson(schema, jsonText)
        } catch (e: Exception) {
            logger.warn("Input schema validation failed: {}", e.message)
            ValidationResult.Invalid(listOf("Validation error: ${e.message}"))
        }
    }
}
