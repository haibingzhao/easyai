package com.easy.easyai.web.model

import com.fasterxml.jackson.annotation.JsonInclude
import tools.jackson.databind.JsonNode

/**
 * Request body for AI-powered config generation.
 * Uses AgentLoop with validate_config/list_resources tools for multi-step generation.
 */
data class AiConfigGenerateRequest(
    val description: String,
    val configType: String, // "agent" | "swarm"
    val modelConfigId: String? = null,
    val existingConfig: JsonNode? = null,
)

/**
 * Response body for AI config generation.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AiConfigGenerateResponse(
    val generatedConfig: JsonNode,
    val validation: ConfigValidationResult,
    val explanation: String,
    val retryCount: Int = 0,
)

/**
 * Result of validating a generated (or user-supplied) config.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ConfigValidationResult(
    val valid: Boolean,
    val errors: List<ConfigValidationError> = emptyList(),
)

/**
 * A single validation error/warning.
 */
data class ConfigValidationError(
    val field: String,
    val message: String,
    val severity: String = "error", // "error" | "warning"
)

/**
 * Request body for standalone config validation.
 */
data class AiConfigValidateRequest(
    val configType: String, // "agent" | "swarm"
    val config: JsonNode,
)
