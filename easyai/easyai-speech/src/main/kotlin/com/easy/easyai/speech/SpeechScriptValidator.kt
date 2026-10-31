package com.easy.easyai.speech

/** One plan-level defect that makes the whole script unrenderable. */
data class SpeechScriptFault(val code: String, val message: String)

/**
 * Outcome of [SpeechScriptValidator.validateFull].
 *
 * [script] is the **repaired** plan: source slices are authoritative and any per-segment defect has
 * been replaced deterministically. It is null when [fault] is set — then nothing may be synthesized
 * from this plan and the caller must speak the original text instead.
 */
data class SpeechValidation(
    val script: SpeechScript?,
    val warnings: List<SpeechWarning> = emptyList(),
    val fault: SpeechScriptFault? = null
) {
    val usable: Boolean get() = script != null
}

/** Business validation that remains necessary even when a provider enforces JSON schema output. */
object SpeechScriptValidator {

    const val MAX_SEGMENTS: Int = 40
    const val MAX_STYLE_NOTE_CHARS: Int = 40
    const val MAX_PAUSE_MS: Int = 5_000
    const val MAX_VOICES_PER_SCRIPT: Int = 2

    /**
     * P1 accepts exactly one segment. This prevents an accidental multi-segment answer from being
     * silently truncated into the existing single-task renderer.
     */
    @JvmStatic
    fun validateRefine(
        script: SpeechScript,
        sourceText: String,
        capabilities: ResolvedSpeechCapabilities,
        maxChars: Int
    ): List<String> {
        val errors = mutableListOf<String>()
        if (script.schemaVersion != 1) errors += "unsupported schema version"
        if (script.revision < 1) errors += "invalid revision"
        if (script.sourceDigest != SpokenTextNormalizer.sourceDigest(sourceText)) errors += "source digest mismatch"
        if (script.segments.size != 1) {
            errors += "refine requires exactly one segment"
            return errors
        }
        val segment = script.segments.single()
        if (segment.id.isBlank() || segment.index != 0) errors += "invalid segment identity"
        // Whitespace is not content: the director routinely re-wraps the sentence it echoed back,
        // and the multi-segment path compares the same way through `SourceCursor`.
        if (SourceCursor.normalize(segment.sourceText) != SourceCursor.normalize(sourceText)) {
            errors += "source text mismatch"
        }
        if (segment.spokenText.isBlank()) errors += "blank spoken text"
        if (segment.spokenText.length > maxChars) errors += "spoken text exceeds $maxChars characters"
        val unknownMarkup = SpokenTextNormalizer.unexpectedMarkup(segment.spokenText, capabilities.spokenTags)
        if (unknownMarkup.isNotEmpty()) {
            errors += "unknown markup in spoken text: " + unknownMarkup.joinToString(" ")
        }
        if (capabilities.voices.isEmpty() || capabilities.voice(segment.voice) == null) errors += "voice is not allowed"
        segment.styleNote?.let { note ->
            if (note.isBlank() || note.length > MAX_STYLE_NOTE_CHARS) errors += "invalid style note"
            if (!capabilities.supportsInstruction(segment.voice)) errors += "voice does not support instruction"
        }
        segment.emotion?.let { emotion ->
            if (emotion !in capabilities.emotionTags) errors += "emotion is not allowed"
        }
        segment.rate?.let { rate ->
            if (!rate.isFinite() || rate !in 0.5..2.0) errors += "invalid rate"
        }
        if (segment.pauseAfterMs !in 0..MAX_PAUSE_MS) errors += "invalid pause"
        if (!SpokenTextNormalizer.numbersPreserved(sourceText, segment.spokenText)) {
            errors += "numeric fidelity mismatch"
        }
        if (SpokenTextNormalizer.missingTermAnchors(sourceText, segment.spokenText).isNotEmpty()) {
            errors += "term fidelity mismatch"
        }
        return errors
    }

    /**
     * Validate a complete multi-segment plan before anything is synthesized.
     *
     * Segment-level rules live in [SpeechPlanAssembler] so that an incrementally planned script is
     * judged by exactly the same rules as one delivered whole; this method adds only the checks that
     * need the plan header (schema version, revision, source digest).
     */
    @JvmStatic
    fun validateFull(
        script: SpeechScript,
        sourceText: String,
        capabilities: ResolvedSpeechCapabilities,
        maxChars: Int
    ): SpeechValidation {
        planFault(script, sourceText, capabilities)?.let { return SpeechValidation(null, fault = it) }

        val assembler = SpeechPlanAssembler(sourceText, capabilities, maxChars)
        val repaired = ArrayList<SpeechSegment>(script.segments.size)
        for (segment in script.segments.sortedBy { it.index }) {
            val fixed = assembler.accept(segment)
                ?: return SpeechValidation(null, assembler.warnings, assembler.fault)
            repaired += fixed
        }
        assembler.finish()?.let { return SpeechValidation(null, assembler.warnings, it) }

        return SpeechValidation(
            script = script.copy(segments = repaired, warnings = script.warnings + assembler.warnings),
            warnings = assembler.warnings
        )
    }

    /** Plan-header defects only; everything a segment can be blamed for is handled by the assembler. */
    private fun planFault(
        script: SpeechScript,
        sourceText: String,
        capabilities: ResolvedSpeechCapabilities
    ): SpeechScriptFault? {
        if (script.schemaVersion != 1) return SpeechScriptFault(CODE_SCHEMA, "unsupported schema version")
        if (script.revision < 1) return SpeechScriptFault(CODE_SCHEMA, "invalid revision")
        if (script.sourceDigest != SpokenTextNormalizer.sourceDigest(sourceText)) {
            return SpeechScriptFault(CODE_STALE_SOURCE, "the plan was built for a different text")
        }
        if (capabilities.voices.isEmpty() || capabilities.defaultVoice == null) {
            return SpeechScriptFault(CODE_NO_VOICE, "the target model exposes no usable voice")
        }
        if (script.segments.isEmpty()) return SpeechScriptFault(CODE_SCHEMA, "the plan has no segments")
        return null
    }

    const val CODE_SCHEMA = "SCHEMA"
    const val CODE_STALE_SOURCE = "STALE_SOURCE"
    const val CODE_COVERAGE = "COVERAGE"
    const val CODE_TOO_LONG = "TOO_LONG"
    const val CODE_TOO_MANY_VOICES = "TOO_MANY_VOICES"
    const val CODE_NO_VOICE = "NO_VOICE"
    const val CODE_VOICE = "VOICE_FALLBACK"
    const val CODE_INSTRUCTION = "INSTRUCTION_DROPPED"
    const val CODE_EMOTION = "EMOTION_DROPPED"

    /** A control tag inside the text already sets the style, so the segment emotion was ignored. */
    const val CODE_EMOTION_SUPERSEDED = "EMOTION_SUPERSEDED"
    const val CODE_RATE = "RATE_DROPPED"
    const val CODE_PAUSE = "PAUSE_CLAMPED"
    const val CODE_SEGMENT_FALLBACK = "SEGMENT_READS_SOURCE"
}
