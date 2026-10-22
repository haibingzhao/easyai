package com.easy.easyai.api.model

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * One row of the global model catalog: vendor-published facts about one API model id.
 *
 * Feeds the add-model form prefill (capabilities + token limits) and, later, LLM-driven
 * model selection (pricing + description). Rows are written only by catalog refreshes —
 * [rawPayload] keeps the vendor item JSON verbatim so mapping evolution can replay
 * stored data without another network round-trip.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ModelCatalogEntry(
    val modelId: String,
    val vendor: String,
    val displayName: String,
    val description: String? = null,
    val capabilities: CatalogCapabilities? = null,
    val defaultOptions: CatalogOptions? = null,
    /** Thinking/effort matrix resolved from vendor documentation; null on rows written before it existed. */
    val reasoningProfile: ReasoningProfile? = null,
    val pricing: ModelPricing? = null,
    /** Which pipeline wrote the row: `bailian-refresh` today; `manual`/`probed` are reserved. */
    val source: String,
    val rawPayload: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0
)

/**
 * Vendor-declared capability flags, mirroring [ModelCapabilities] plus the thinking flag so
 * the prefill can seed `ModelOptions.thinking` too.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class CatalogCapabilities(
    val vision: Boolean = false,
    val structuredOutput: StructuredOutputSupport? = null,
    val supportsToolCalling: Boolean? = null,
    val thinking: Boolean = false
)

/** Suggested generation options for a fresh model config built from this catalog row. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class CatalogOptions(
    /** Max output tokens per reply, in raw tokens. */
    val maxTokens: Int? = null,
    /** Model window hard upper bound, in raw tokens. */
    val maxContextTokens: Int? = null
)

/** Vendor pricing for cost-aware selection; amounts are per one million tokens in [currency]. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ModelPricing(
    val currency: String,
    val inputPerMTok: Double? = null,
    val outputPerMTok: Double? = null,
    val cachedInputPerMTok: Double? = null
)
