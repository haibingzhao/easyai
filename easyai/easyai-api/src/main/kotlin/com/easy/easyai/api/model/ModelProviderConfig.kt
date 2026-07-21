package com.easy.easyai.api.model

import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.fasterxml.jackson.annotation.JsonInclude

/**
 * User's model provider configuration.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ModelProviderConfig(
    val id: String,
    val name: String,
    val protocol: Protocol,
    val isCustom: Boolean,
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val modelId: String,
    val modelName: String? = null,
    val isCustomModel: Boolean = false,
    val enabled: Boolean = true,
    val options: ModelOptions? = null,
    /** HTTP timeout in seconds for LLM API calls. Defaults to 600 (10 minutes). */
    val timeoutSeconds: Long = 600L,
    /** Model capabilities (e.g. vision support). */
    val capabilities: ModelCapabilities? = null
)

/**
 * Request to save a model provider configuration.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SaveModelProviderConfigRequest(
    val id: String? = null,
    val name: String,
    val protocol: Protocol,
    val isCustom: Boolean,
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val modelId: String,
    val modelName: String? = null,
    val isCustomModel: Boolean = false,
    val enabled: Boolean = true,
    val options: ModelOptions? = null,
    /** HTTP timeout in seconds for LLM API calls. Defaults to 600 (10 minutes). */
    val timeoutSeconds: Long = 600L,
    /** Model capabilities (e.g. vision support). */
    val capabilities: ModelCapabilities? = null
)

/**
 * Model generation options.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ModelOptions(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val thinking: Boolean? = null
)

/**
 * Declared capabilities of a model (e.g. vision/image support).
 * Used by the frontend to conditionally show/hide image upload UI.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ModelCapabilities(
    /** Whether the model supports vision/image input. */
    val vision: Boolean = false
)