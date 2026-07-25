package com.easy.easyai.web.model

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Request/response models for the internal LLM processing endpoint.
 * Used by scripts to call back to the EasyAI backend for LLM processing.
 */

@JsonInclude(JsonInclude.Include.NON_NULL)
data class InternalLlmRequest(
    val messages: List<LlmMessage>,
    /** Model config ID to use. Null = use the token's default modelConfigId. */
    val modelConfigId: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null
)

data class LlmMessage(
    val role: String,
    val content: String
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class InternalLlmResponse(
    val content: String,
    val model: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class BatchLlmRequest(
    val items: List<BatchItem>,
    /** System prompt applied to all items. */
    val instruction: String,
    /** Model config ID to use. Null = use the token's default modelConfigId. */
    val modelConfigId: String? = null,
    /** Max concurrent LLM calls. Default 5. */
    val concurrency: Int? = null
)

data class BatchItem(
    val id: String,
    val content: String
)

data class BatchLlmResponse(
    val results: List<BatchResult>,
    val totalDurationMs: Long
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class BatchResult(
    val id: String,
    val content: String? = null,
    val error: String? = null
)
