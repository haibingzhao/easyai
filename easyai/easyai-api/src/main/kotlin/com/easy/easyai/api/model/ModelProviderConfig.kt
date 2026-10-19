package com.easy.easyai.api.model

import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.fasterxml.jackson.annotation.JsonInclude

/**
 * User's model provider configuration.
 *
 * [modelType] splits the table in two populations: `CHAT` rows feed the ReAct ChatModel and every
 * model picker; generation rows (`IMAGE`/`VIDEO`/`SPEECH`/`MUSIC`/`ASR`) are reached through tools
 * and never listed in chat selectors. [isDefault] marks the fallback entry within one user's
 * (modelType) population for generation tools that accept no explicit model.
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
    val capabilities: ModelCapabilities? = null,
    /** Group ID this config belongs to. Null for ungrouped configs. */
    val groupId: String? = null,
    /** What this row can do. See [ModelType]. */
    val modelType: ModelType = ModelType.CHAT,
    /** Provider-specific generation parameters as a raw JSON object string (size / voice / etc.). */
    val mediaOptions: String? = null,
    /** Within one user's (modelType) population, at most one row carries true. */
    val isDefault: Boolean = false,
    /** Row owner (`system` rows are shared fallbacks); server-side metadata, never edited. */
    val userId: String = "system"
)

/**
 * Purpose of a model configuration row.
 *
 * CHAT rows feed the ChatModel loop; the rest are media-generation / understanding backends
 * consumed by tools (generate_image, generate_video, generate_speech, generate_music,
 * transcribe_audio).
 */
enum class ModelType { CHAT, IMAGE, VIDEO, SPEECH, MUSIC, ASR }

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
    val capabilities: ModelCapabilities? = null,
    /** Group ID to associate this config with. */
    val groupId: String? = null,
    /** Defaults to CHAT; generation rows are managed from the Models page and consumed by tools. */
    val modelType: ModelType = ModelType.CHAT,
    /** Raw JSON object string of provider-specific generation parameters (generation rows only). */
    val mediaOptions: String? = null,
    /** Mark this row as the fallback entry for its modelType; the store clears sibling flags. */
    val isDefault: Boolean = false
)

/**
 * Model generation options.
 *
 * @property temperature Sampling temperature. Null — the default — means the field is not sent at
 *   all and the provider's own per-model default applies (see the protocol adapters'
 *   `temperature?.let` guards). Range and default are both model-specific: vendor defaults span
 *   0 to 1.0 and Anthropic caps the range at 1.0, so a number stored here is only what was
 *   *requested* — it is never proof of what the gateway applied.
 *   Rows saved before this field became nullable carry an explicit 0.7 and keep sending it.
 * @property maxTokens Maximum output tokens in raw token count (e.g. 16384 = 16K). Default 16384.
 * @property thinking Whether to enable extended thinking / reasoning. Default false.
 * @property effort Controls overall token budget across all response types (text, tools, thinking).
 *   Values: "low", "medium", "high", "xhigh", "max". Independent of [thinking]. Default null (not set).
 * @property maxContextTokens Model's maximum supported context window in raw tokens. Hard upper bound. Default 204800 (200K).
 * @property contextToken Active working context window in raw tokens. Compaction triggers when this is reached.
 *   Must be <= maxContextTokens. Default 204800 (200K).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ModelOptions(
    val temperature: Double? = null,
    val maxTokens: Int = 16_384,
    val thinking: Boolean = false,
    val effort: String? = null,
    val maxContextTokens: Int = 204_800,
    val contextToken: Int = 204_800
)

/**
 * Declared capabilities of a model (e.g. vision/image support).
 * Used by the frontend to conditionally show/hide image upload UI,
 * and by the agent loop to decide whether API-level structured output is enforceable.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ModelCapabilities(
    /** Whether the model supports vision/image input. */
    val vision: Boolean = false,
    /**
     * API-level structured output support. Null means undeclared — treated as
     * [StructuredOutputSupport.NONE], so nothing goes on the wire and the schema is conveyed by the
     * prompt (see [StructuredOutputPolicy]). Mark models explicitly when their gateway accepts
     * `response_format`/`output_config`, and equally explicitly when it answers HTTP 400.
     */
    val structuredOutput: StructuredOutputSupport? = null,
    /**
     * Whether sampling parameters may be sent to this model at all. Null means undeclared — treated
     * as [SamplingSupport.SETTABLE] (current behavior). Mark a model `NOT_SETTABLE` when its gateway
     * rejects the field outright, so the adapter omits it instead of failing the call.
     */
    val sampling: SamplingSupport? = null,
    /**
     * Whether the model reasons and accepts tool calls. Null/true means capable (legacy rows
     * predate the flag); explicit false marks single-forward decision models (e.g. Bailian
     * decision-model-preview) that answer structured classification queries instead of chat.
     * Metadata only — surfaced in the model pickers, it never gates tool registration.
     */
    val supportsToolCalling: Boolean? = null
)

/**
 * Structured output enforcement capability of a model's API.
 *
 * @property JSON_SCHEMA The gateway accepts schema-constrained output
 *   (Anthropic `output_config.format=json_schema` / OpenAI `response_format` json_schema).
 * @property JSON_OBJECT The gateway only accepts plain-JSON enforcement
 *   (OpenAI `response_format` json_object). Not expressible on the Anthropic protocol —
 *   falls back to prompt-based enforcement there.
 * @property NONE No API-level enforcement; rely on prompt + OutputSchemaValidator fallback.
 */
enum class StructuredOutputSupport { JSON_SCHEMA, JSON_OBJECT, NONE }

/**
 * Whether a model accepts sampling parameters (temperature and friends) on the request.
 *
 * @property SETTABLE The value may be sent; this is what an undeclared capability means.
 * @property NOT_SETTABLE A request carrying sampling parameters fails. Current-generation reasoning
 *   endpoints on both major vendors do this — some reject any value including the model's own
 *   default, others reject only non-default ones. easyai cannot tell those two apart (it does not
 *   know the vendor's default), so both are handled the same way: send nothing.
 */
enum class SamplingSupport { SETTABLE, NOT_SETTABLE }

/**
 * A group of model configurations sharing the same connection settings.
 * Member configs denormalize connection fields for zero-JOIN runtime reads.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ModelConfigGroup(
    val id: String,
    val name: String,
    val protocol: Protocol,
    val isCustom: Boolean,
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val timeoutSeconds: Long = 600L,
    val models: List<ModelProviderConfig> = emptyList()
)

/**
 * Request to create or update a model config group.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SaveModelConfigGroupRequest(
    val id: String? = null,
    val name: String,
    val protocol: Protocol,
    val isCustom: Boolean,
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val timeoutSeconds: Long = 600L
)