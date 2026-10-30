package com.easy.easyai.api.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Vendor- and project-declared recognition facts for one ASR model, stored as the `asr` block of
 * `model_catalog.speech_capabilities`.
 *
 * The block is the single authority for three things at once: which transport a model can be
 * driven over ([channel]), which `run-task` parameters a row may set ([params]), and which
 * higher-level abilities the model has ([features]). The Models form renders controls from it and
 * the availability check validates a saved row against it, so "the console offers it" and "the
 * request accepts it" cannot drift apart.
 *
 * Every collection defaults to empty and [channel] to [CHANNEL_NONE] so a partially filled row
 * still deserializes, and unknown keys are ignored so a newer seed cannot break an older build.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class AsrCapabilities(
    /** `websocket` / `http` / `none` — see [CHANNEL_*]; a model may be recognized but unusable for mic input. */
    val channel: String = CHANNEL_NONE,
    val docsUrl: String? = null,
    /** What the capture chain must produce for this model; the browser is fixed to 16 kHz mono PCM16. */
    val audio: AsrAudioSpec? = null,
    /** `run-task.payload.parameters` whitelist, keyed by the exact request field name. */
    val params: Map<String, AsrParamSpec> = emptyMap(),
    val features: AsrFeatures? = null
) {
    companion object {
        const val CHANNEL_WEBSOCKET = "websocket"
        const val CHANNEL_HTTP = "http"
        const val CHANNEL_NONE = "none"
    }
}

/** Accepted input formats and rates; the UI shows these read-only because the capture chain is fixed. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class AsrAudioSpec(
    val formats: List<String> = emptyList(),
    val sampleRates: List<Int> = emptyList()
)

/**
 * One settable request parameter.
 *
 * [default] is the value this project sends when the row does not set one, which is not always the
 * vendor's own default: [vendorDefault] keeps the published value for the form to display, and
 * [note] explains a deliberate difference. Parameters that are [locked] are always sent as their
 * default and are not written into `media_options`.
 *
 * `default` is typed as `Any?` on purpose — it holds a string, number, boolean, list or object
 * depending on [type], and Jackson already produces the right Kotlin/Java type for each.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class AsrParamSpec(
    /** `enum` / `int` / `bool` / `string` / `string[]` / `map` — selects the form control. */
    val type: String,
    val options: List<String> = emptyList(),
    val min: Int? = null,
    val max: Int? = null,
    val unit: String? = null,
    val default: Any? = null,
    val vendorDefault: Any? = null,
    val note: String? = null,
    /** Fixed by the product for chat dictation; the form shows it read-only and never stores it. */
    val locked: Boolean = false,
    /** Documented as affecting the final result only — a hint in the form, never a data-flow branch. */
    val finalOnly: Boolean = false,
    val maxItems: Int? = null,
    /** Instant-vocabulary ceilings: super terms (weight 50) and merged instant + precompiled terms. */
    val maxSuper: Int? = null,
    val maxMerged: Int? = null
) {
    companion object {
        const val TYPE_ENUM = "enum"
        const val TYPE_INT = "int"
        const val TYPE_BOOL = "bool"
        const val TYPE_STRING = "string"
        const val TYPE_STRING_ARRAY = "string[]"
        const val TYPE_MAP = "map"

        val SUPPORTED_TYPES = setOf(TYPE_ENUM, TYPE_INT, TYPE_BOOL, TYPE_STRING, TYPE_STRING_ARRAY, TYPE_MAP)
    }
}

/**
 * Capabilities that are not request parameters. Recorded so the UI can state what a model can do
 * without inventing a control for a feature this project does not implement yet (dynamic context).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class AsrFeatures(
    /** `initial` when `run-task.payload.input.context` is accepted; `none` when the model has no context. */
    val context: String? = null,
    /** The model accepts `continue-task` to update context mid-task. */
    val continueTask: Boolean = false,
    val speakerDiarization: Boolean = false,
    val emotion: Boolean = false
)
