package com.easy.easyai.api.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Hand-maintained speech facts for one TTS or ASR model, stored as JSON in
 * `model_catalog.speech_capabilities`.
 *
 * Kept out of [CatalogCapabilities] on purpose: that block is chat semantics and is overwritten
 * wholesale by every catalog refresh, while this one is maintained from the vendor's voice list and
 * must survive a refresh untouched.
 *
 * One row carries either block: a TTS model fills the synthesis fields, an ASR model fills [asr].
 * They share the column because both are the same kind of fact (published by hand, preserved by
 * refreshes), and a new column would need a migration plus a second copy of that preservation rule.
 *
 * Every flag defaults to `false` and every collection to empty so a partially filled row still
 * deserializes; unknown keys are ignored so adding a field to the seed cannot break an older build.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class SpeechCapabilities(
    val supportsInstruction: Boolean = false,
    val supportsEmotionTags: Boolean = false,
    val supportsSsml: Boolean = false,
    /** Contradictory across the vendor docs; false until a real synthetic probe confirms it. */
    val supportsLatex: Boolean = false,
    val supportsWordTimestamp: Boolean = false,
    val supportsHotFix: Boolean = false,
    /** Output formats the model accepts, e.g. `["mp3","pcm","wav","opus"]`. */
    val formats: List<String> = emptyList(),
    /** Discrete sample rates in Hz — the protocol has no continuous range. */
    val sampleRates: List<Int> = emptyList(),
    /** Protocol ceiling for one `continue-task` frame. */
    val maxCharsPerFrame: Int = 0,
    /** Protocol ceiling for one task across all frames. */
    val maxCharsPerTask: Int = 0,
    val docsUrl: String? = null,
    /**
     * Control tags: they switch the delivery of everything that follows, until the next control tag
     * or until the engine splits the sentence. Written inline as `[excited]`, and also usable as a
     * segment-level emotion (a control tag prepended to one segment is a whole-segment style).
     */
    val emotionTags: List<String> = emptyList(),
    /**
     * Paralinguistic tags: they insert one sound at their own position and leave the surrounding
     * style alone (`[laughing]`, `[clears throat]`). Kept apart from [emotionTags] because they are
     * position-sensitive — a paralinguistic tag prepended to a segment means "make that noise
     * before saying anything", which is almost never what a writer intended.
     */
    val paralinguisticTags: List<String> = emptyList(),
    /** Group order and labels as published, so a picker renders `optgroup`s without grouping itself. */
    val voiceGroups: List<VoiceGroup> = emptyList(),
    /** The voice a caller should use when it does not choose one. */
    val defaultVoice: String? = null,
    val voices: List<VoiceSpec> = emptyList(),
    /** Recognition facts for an ASR model; null on TTS rows and on rows seeded before it existed. */
    val asr: AsrCapabilities? = null
)

/** One `optgroup` of the vendor's voice table, with the published member count. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class VoiceGroup(
    val key: String,
    val label: String,
    val count: Int = 0
)

/**
 * One system voice.
 *
 * [traits] and [scenarios] are the vendor's own wording and are the only text a model can use to
 * pick a voice it cannot hear; [ssml] / [instruction] / [timestamp] are per-voice conclusions and must
 * not be inferred from the model-level flags.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class VoiceSpec(
    /** The exact `voice` parameter value. */
    val voice: String,
    val name: String,
    /** One of `flagship` / `multilingual` / `premium-zh` / `premium-en` / `other` / `minor` / `legacy`. */
    val group: String? = null,
    val groupLabel: String? = null,
    /** `female` / `male` / `child`; absent when the vendor does not say. */
    val gender: String? = null,
    /** Only the 3.0 family publishes an age. */
    val age: Int? = null,
    val traits: List<String> = emptyList(),
    val scenarios: List<String> = emptyList(),
    /** Accent label for English voices, e.g. 英式女声. */
    val accent: String? = null,
    /** BCP-47 language tags. */
    val languages: List<String> = emptyList(),
    /** Chinese name of a minority language, e.g. 保加利亚语. */
    val languageLabel: String? = null,
    /** The vendor did not state a language; the tag list is an inference, and the UI must say so. */
    val languageInferred: Boolean? = null,
    /** Native-script name of a minority-language voice. */
    val localName: String? = null,
    /** Dialects reachable through `instruction` for this voice. */
    val dialects: List<String> = emptyList(),
    val ssml: Boolean? = null,
    val instruction: Boolean? = null,
    val timestamp: Boolean? = null
)
