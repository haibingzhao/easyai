package com.easy.easyai.speech

/**
 * Validates a plan one segment at a time.
 *
 * Both kinds of plan go through this class, which is the point: a complete plan (`validateFull`)
 * and an incrementally streamed plan must be judged by exactly the same rules, or the fast path
 * would be the one that lets a bad segment through.
 *
 * Three gates, applied in the order that keeps a defect cheap to handle:
 *  1. **plan shape** — index continuity, unique ids, segment count, voice count. Cheap to check and
 *     fatal when wrong, because a mis-shaped plan cannot be attributed to one segment;
 *  2. **source coverage** — each segment must consume the next slice of the original text, so an
 *     omission is caught at the segment that caused it, before anything is synthesized for it;
 *  3. **segment content** — a bad voice, note, emotion, rate or pause is dropped (the segment is
 *     still speakable); a fidelity violation falls the segment back to its source sentence, because
 *     a rewrite that changes a number is worse than no rewrite at all.
 *
 * Not thread-safe: one assembler belongs to one plan.
 */
class SpeechPlanAssembler(
    private val sourceText: String,
    private val capabilities: ResolvedSpeechCapabilities,
    private val maxChars: Int
) {
    private val cursor = SourceCursor(sourceText)
    private val collected = mutableListOf<SpeechWarning>()
    private val ids = mutableSetOf<String>()
    private val voices = mutableSetOf<String>()
    private var expectedIndex = 0
    private var spokenChars = 0

    /** The first unrecoverable defect; once set, [accept] refuses everything else. */
    var fault: SpeechScriptFault? = null
        private set

    val warnings: List<SpeechWarning> get() = collected.toList()

    /** Segments accepted so far. */
    val accepted: Int get() = expectedIndex

    /**
     * Validate the next arriving segment and return the speakable version of it, or null when the
     * plan is now unusable — in which case [fault] says why and nothing may be synthesized further.
     */
    fun accept(segment: SpeechSegment): SpeechSegment? {
        if (fault != null) return null
        if (expectedIndex >= SpeechScriptValidator.MAX_SEGMENTS) {
            return fail(
                SpeechScriptValidator.CODE_SCHEMA,
                "the plan has more than ${SpeechScriptValidator.MAX_SEGMENTS} segments"
            )
        }
        if (segment.index != expectedIndex) {
            return fail(
                SpeechScriptValidator.CODE_SCHEMA,
                "segment ${segment.index} arrived where $expectedIndex was expected"
            )
        }
        if (segment.id.isBlank() || !ids.add(segment.id)) {
            return fail(SpeechScriptValidator.CODE_SCHEMA, "segment id '${segment.id}' is blank or repeated")
        }
        val canonical = cursor.next(segment.sourceText)
            ?: return fail(
                SpeechScriptValidator.CODE_COVERAGE,
                "segment ${segment.index} does not continue the source text at offset ${cursor.consumed}"
            )
        // A boundary that falls inside a number or an identifier clears every per-segment check: with
        // "上限是2" and "0个" each half looks faithful, while the audio says "two" then "zero". The
        // concatenation is the only place this is visible, and by then the clip is already paid for.
        if (splitsToken(canonical, cursor.peek())) {
            return fail(
                SpeechScriptValidator.CODE_COVERAGE,
                "segment ${segment.index} ends in the middle of a number or identifier"
            )
        }
        val fixed = repair(segment, canonical)
        spokenChars += fixed.spokenText.length
        if (spokenChars > maxChars) {
            return fail(
                SpeechScriptValidator.CODE_TOO_LONG,
                "the spoken plan is $spokenChars characters, over the $maxChars-character read-aloud limit"
            )
        }
        if (voices.add(fixed.voice) && voices.size > SpeechScriptValidator.MAX_VOICES_PER_SCRIPT) {
            return fail(
                SpeechScriptValidator.CODE_TOO_MANY_VOICES,
                "the plan uses ${voices.size} voices, over the ${SpeechScriptValidator.MAX_VOICES_PER_SCRIPT}-voice limit"
            )
        }
        expectedIndex++
        return fixed
    }

    /** End of plan: coverage and non-emptiness. Null means the plan is complete and renderable. */
    fun finish(): SpeechScriptFault? {
        fault?.let { return it }
        if (expectedIndex == 0) {
            return SpeechScriptFault(SpeechScriptValidator.CODE_SCHEMA, "the plan has no segments")
                .also { fault = it }
        }
        if (!cursor.complete()) {
            return SpeechScriptFault(
                SpeechScriptValidator.CODE_COVERAGE,
                "the plan stops at offset ${cursor.consumed} and leaves text unread"
            ).also { fault = it }
        }
        return null
    }

    private fun fail(code: String, message: String): SpeechSegment? {
        if (fault == null) fault = SpeechScriptFault(code, message)
        return null
    }

    /**
     * Fix one segment in place. [canonicalSource] is the authoritative slice: whatever the model
     * claimed its `sourceText` was, this is what the user actually wrote.
     */
    private fun repair(segment: SpeechSegment, canonicalSource: String): SpeechSegment {
        val defaultVoice = capabilities.defaultVoice!!
        // A voice the director named by its display label still resolves; the segment then carries the
        // catalog's own id, because everything downstream keys off that exact string.
        val requested = capabilities.voice(segment.voice)
        var voice = requested?.voice ?: segment.voice
        if (requested == null) {
            collected += SpeechWarning(
                SpeechScriptValidator.CODE_VOICE,
                segment.id,
                "voice '${segment.voice}' is not offered by this model; used $defaultVoice"
            )
            voice = defaultVoice
        }
        var note = segment.styleNote?.takeIf { it.isNotBlank() }
        if (note != null && (note.length > SpeechScriptValidator.MAX_STYLE_NOTE_CHARS || !capabilities.supportsInstruction(voice))) {
            collected += SpeechWarning(
                SpeechScriptValidator.CODE_INSTRUCTION,
                segment.id,
                "this voice cannot take a style note; it was dropped"
            )
            note = null
        }
        // A control tag at the very start of the rewrite is that segment's style, written by the
        // planner in the text instead of in this field. Two sources would fight, and the text is the
        // finer-grained one, so the field is dropped rather than prepended over it.
        val inlineControl = SpokenTextNormalizer.leadingTag(segment.spokenText)
            ?.takeIf { tag -> capabilities.isControlTag(tag) }
        var emotion = segment.emotion?.takeIf { tag -> capabilities.isControlTag(tag) }
        if (segment.emotion != null && emotion == null) {
            collected += SpeechWarning(
                SpeechScriptValidator.CODE_EMOTION,
                segment.id,
                "emotion '${segment.emotion}' is not supported; it was dropped"
            )
        }
        val rate = segment.rate?.takeIf { it.isFinite() && it in 0.5..2.0 }
        if (segment.rate != null && rate == null) {
            collected += SpeechWarning(
                SpeechScriptValidator.CODE_RATE,
                segment.id,
                "rate ${segment.rate} is outside 0.5–2.0; it was dropped"
            )
        }
        val pause = segment.pauseAfterMs.coerceIn(0, SpeechScriptValidator.MAX_PAUSE_MS)
        if (pause != segment.pauseAfterMs) {
            collected += SpeechWarning(
                SpeechScriptValidator.CODE_PAUSE,
                segment.id,
                "pause ${segment.pauseAfterMs} ms was clamped to $pause ms"
            )
        }

        val spoken = segment.spokenText.trim()
        val keepRewrite = spoken.isNotEmpty() &&
            spoken.length <= maxChars &&
            !SpokenTextNormalizer.containsUnexpectedMarkup(spoken, capabilities.spokenTags) &&
            keepsNumbers(canonicalSource, spoken) &&
            keepsTerms(canonicalSource, spoken)
        if (!keepRewrite) {
            collected += SpeechWarning(
                SpeechScriptValidator.CODE_SEGMENT_FALLBACK,
                segment.id,
                "this segment's rewrite changed or lost source content; it reads the original sentence instead"
            )
        }
        // Only a rewrite that is actually spoken can supersede the field: on a fallback the inline
        // tag is not spoken either, so the segment emotion still applies to the source sentence.
        if (keepRewrite && emotion != null && inlineControl != null) {
            collected += SpeechWarning(
                SpeechScriptValidator.CODE_EMOTION_SUPERSEDED,
                segment.id,
                "spokenText already opens with [$inlineControl]; emotion '${segment.emotion}' was dropped"
            )
            emotion = null
        }
        return segment.copy(
            sourceText = canonicalSource,
            spokenText = if (keepRewrite) spoken else canonicalSource,
            voice = voice,
            emotion = emotion,
            styleNote = note,
            rate = rate,
            pauseAfterMs = pause
        )
    }

    /** True when [slice] and the character after it are two halves of one ASCII token. */
    private fun splitsToken(slice: String, next: Char?): Boolean {
        val tail = slice.lastOrNull() ?: return false
        val head = next ?: return false
        return isTokenChar(tail) && isTokenChar(head)
    }

    /**
     * Only ASCII counts: a Chinese sentence routinely ends and starts on CJK characters, and treating
     * those as one token would reject every correctly split plan.
     */
    private fun isTokenChar(char: Char): Boolean =
        char in '0'..'9' || char in 'a'..'z' || char in 'A'..'Z'

    /** Every number, date and clock time in the source must survive, in order, and none invented. */
    private fun keepsNumbers(source: String, spoken: String): Boolean =
        SpokenTextNormalizer.numbersPreserved(source, spoken)

    /**
     * Identifiers and URL hosts must stay visible; a Chinese paraphrase of `user_id` is not one.
     * An IANA zone is the exception the normalizer owns, so `Asia/Shanghai` may be spoken as
     * "上海时区" without counting as a lost term.
     */
    private fun keepsTerms(source: String, spoken: String): Boolean =
        SpokenTextNormalizer.missingTermAnchors(source, spoken).isEmpty()
}
