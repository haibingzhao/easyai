package com.easy.easyai.speech

import com.easy.easyai.api.model.SpeechCapabilities
import com.easy.easyai.api.model.VoiceSpec

/**
 * Effective model facts with a normalized tag vocabulary and a deterministic fallback voice.
 *
 * Tags are stored bracket-free (`excited`, `clears throat`) because that is the form the plan uses
 * for a segment's `emotion`; the brackets are re-added by whoever builds the synthesis payload.
 */
data class ResolvedSpeechCapabilities(
    val voices: List<VoiceSpec>,
    val defaultVoice: String?,
    /** Control tags — safe both as a segment emotion and inline. */
    val emotionTags: List<String>,
    val supportsInstruction: Boolean,
    /** Paralinguistic tags — inline only; prepending one changes the meaning. */
    val paralinguisticTags: List<String> = emptyList()
) {
    /**
     * Every tag a spoken rewrite may carry inside its text.
     *
     * This is the whitelist the fidelity gate checks against: a bracketed word outside it is treated
     * as unknown markup and the segment falls back to reading the source, which is the safe failure.
     */
    val spokenTags: List<String> = (emotionTags + paralinguisticTags).distinct()

    /**
     * Resolve a plan-supplied voice to its catalog entry.
     *
     * The director is shown one line per voice — `id｜名称｜特质…｜场景…` — and echoes that first cell
     * back as the `voice` value often enough that a strict comparison would report a correct choice as
     * a fallback and silently swap in the default timbre. Everything from the first separator on is
     * display text, not identity, so it is cut away before matching; a human name on its own is
     * accepted only when it names exactly one voice, because an ambiguous name is not a choice.
     */
    fun voice(voiceId: String): VoiceSpec? {
        val id = canonicalVoiceId(voiceId)
        if (id.isEmpty()) return null
        return voices.firstOrNull { it.voice == id }
            ?: voices.firstOrNull { it.voice.equals(id, ignoreCase = true) }
            ?: voices.filter { it.name.trim().equals(id, ignoreCase = true) }.singleOrNull()
    }

    /** True for a tag a rewrite may write inline, with or without its brackets. */
    fun isSpokenTag(tag: String): Boolean = normalizeTag(tag) in spokenTags

    /** True for a control tag — the only kind a segment-level `emotion` may name. */
    fun isControlTag(tag: String): Boolean = normalizeTag(tag) in emotionTags

    /** Voice-specific conclusions win; a listed voice with no instruction support cannot receive one. */
    fun supportsInstruction(voiceId: String): Boolean {
        val voice = voice(voiceId) ?: return false
        return supportsInstruction && voice.instruction == true
    }

    companion object {
        /** `[clears throat]` and `clears throat` name the same tag; the model writes either. */
        @JvmStatic
        fun normalizeTag(raw: String): String =
            raw.trim().removePrefix("[").removeSuffix("]").trim()

        /**
         * The bare voice id inside [raw].
         *
         * A plan may carry `longanhuan_v3.1｜龙安欢_v3.1` where only `longanhuan_v3.1` is an id: the
         * name, the traits and the scenarios after it are labels written for a human reader. Both
         * separators are cut here because the prompt uses the full-width one while a model copying by
         * hand sometimes types the ASCII form.
         */
        @JvmStatic
        fun canonicalVoiceId(raw: String): String =
            raw.trim().substringBefore('｜').substringBefore('|').trim()
    }
}

/** Resolves the provider + model + voice boundary without depending on web configuration code. */
class SpeechCapabilityResolver {
    fun resolve(capabilities: SpeechCapabilities?): ResolvedSpeechCapabilities = ResolvedSpeechCapabilities(
        voices = capabilities?.voices.orEmpty(),
        defaultVoice = capabilities?.defaultVoice?.takeIf { default ->
            capabilities.voices.any { it.voice == default }
        } ?: capabilities?.voices?.firstOrNull()?.voice,
        emotionTags = normalizeTags(capabilities?.emotionTags),
        supportsInstruction = capabilities?.supportsInstruction == true,
        paralinguisticTags = normalizeTags(capabilities?.paralinguisticTags)
    )

    private fun normalizeTags(raw: List<String>?): List<String> =
        raw.orEmpty()
            .map { ResolvedSpeechCapabilities.normalizeTag(it) }
            .filter { it.isNotEmpty() }
            .distinct()
}
