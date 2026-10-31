package com.easy.easyai.speech

/** A complete, immutable read-aloud plan. The server owns identity and revision fields. */
data class SpeechScript(
    val schemaVersion: Int = 1,
    val scriptId: String,
    val revision: Int = 1,
    val sourceDigest: String,
    val mood: String,
    val style: SpeechStyle,
    val segments: List<SpeechSegment>,
    val warnings: List<SpeechWarning> = emptyList()
)

/** One source fragment and the text that should actually be synthesized. */
data class SpeechSegment(
    val id: String,
    val index: Int,
    val sourceText: String,
    val spokenText: String,
    val voice: String,
    val emotion: String? = null,
    val styleNote: String? = null,
    val rate: Double? = null,
    val role: SpeechRole = SpeechRole.NARRATION,
    val pauseAfterMs: Int = 0
)

/** Audio rendering is a server decision, never an LLM-controlled field. */
data class RenderProfile(
    val provider: String,
    val modelId: String,
    val format: String = "mp3",
    val sampleRate: Int = 24_000,
    val bitRate: Int? = null
)

data class SpeechWarning(
    val code: String,
    val segmentId: String? = null,
    val message: String
)

/** Request-level synthesis controls. Null values preserve the model row's defaults. */
data class SpeechOverrides(
    val voice: String? = null,
    val instruction: String? = null,
    /** A validated provider tag such as `[serious]`, never arbitrary markup from a client. */
    val emotionTag: String? = null,
    val rate: Double? = null,
    val pitch: Double? = null,
    val inputMode: SpeechInputMode = SpeechInputMode.PLAIN_TEXT
)

enum class SpeechInputMode {
    PLAIN_TEXT,
    /** Reserved for markup generated and sanitized inside a provider adapter. */
    PROVIDER_GENERATED_MARKUP
}

enum class SpeechRole { OPENING, NARRATION, EMPHASIS, QUOTE, DIALOGUE, LIST_ITEM, WARNING, CLOSING }
enum class SpeechStyle { NARRATOR, TEACHER, PODCAST, NEWS, STORYTELLER }

/**
 * One word's position inside the clip it belongs to.
 *
 * Only populated when the configured row asks the provider for word timestamps; an empty list means
 * "this voice or row does not report them", which the client renders as "no highlighting".
 */
data class TimedWord(
    val text: String,
    /** Offset from the start of this clip, milliseconds. */
    val beginMs: Int,
    val endMs: Int
)

enum class SpeechMode(val key: String) {
    /** Plain text, default voice, one task. */
    DIRECT("direct"),

    /** One validated rewrite, still one task: the low-latency middle ground. */
    REFINE("refine"),

    /** A fully checked multi-segment plan, rendered and played segment by segment. */
    DIRECTOR("director");

    companion object {
        @JvmStatic
        fun fromKey(key: String): SpeechMode? = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}
