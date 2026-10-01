package com.easy.easyai.core.model.aux

/**
 * A purpose that can be backed by a dedicated, per-user configured model, independent of the
 * model selected for the chat session.
 *
 * Adding a new purpose is a one-line change here plus an i18n label in the frontend — the
 * `aux_model_settings` table, the REST surface and the settings UI are all keyed by [key] and
 * need no structural change. The persisted form is the string [key], so renaming an enum constant
 * is safe as long as its key stays stable.
 */
enum class AuxModelTask(val key: String) {
    /** Context-compaction summary generation. The first purpose with a wired consumer. */
    COMPACTION("compaction"),

    /**
     * Session title summarization. Declared so it is configurable today; its consumer lands with
     * the title-generation feature, which only has to resolve this task and fall back when unset.
     */
    SESSION_TITLE("session_title"),

    /**
     * Per-turn skill routing over the Bailian System One decision endpoint. Consumed through
     * [AuxModelResolver.resolveConfig] — the referenced row supplies endpoint material
     * (apiKey/baseUrl/modelId) only; no ChatModel is built for it.
     */
    SKILL_SELECTION("skill_selection");

    companion object {
        /** The task for a persisted key; null when the key is unknown (e.g. from an older/newer build). */
        @JvmStatic
        fun fromKey(key: String): AuxModelTask? = entries.firstOrNull { it.key == key }
    }
}
