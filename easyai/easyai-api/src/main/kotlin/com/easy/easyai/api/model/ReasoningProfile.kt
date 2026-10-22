package com.easy.easyai.api.model

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * How a model exposes extended thinking and reasoning effort.
 *
 * The catalog's boolean `thinking` flag cannot express whether thinking may be turned off or which
 * effort levels a model accepts, so this profile carries those facts explicitly. [source] records
 * where the profile came from, and a catalog refresh keeps any `manual` profile in place.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ReasoningProfile(
    val mode: ReasoningMode = ReasoningMode.UNKNOWN,
    /** Canonical order low→max. Empty means the model exposes no effort knob. */
    val effortLevels: List<String> = emptyList(),
    val defaultThinking: Boolean = false,
    /** Null means "automatic" — the provider/request default applies. */
    val defaultEffort: String? = null,
    val source: String = SOURCE_UNKNOWN
) {
    companion object {
        const val SOURCE_VENDOR_DOC = "vendor-doc"
        const val SOURCE_CATALOG = "catalog"
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_UNKNOWN = "unknown"

        /** Every effort name the adapters understand, in ascending order. */
        val EFFORT_LEVELS: List<String> = listOf("low", "medium", "high", "xhigh", "max")

        val UNKNOWN: ReasoningProfile = ReasoningProfile()
    }
}

/**
 * How the thinking switch behaves for a model.
 *
 * @property NONE No extended thinking at all.
 * @property TOGGLEABLE The model can run with or without thinking.
 * @property ALWAYS_ON Thinking cannot be turned off; the switch renders locked on.
 * @property UNKNOWN Not declared yet — keep the controls editable and let adapters use legacy
 *   behavior. Profiles are resolved from the model id, so this only remains for custom endpoints.
 */
enum class ReasoningMode { NONE, TOGGLEABLE, ALWAYS_ON, UNKNOWN }
