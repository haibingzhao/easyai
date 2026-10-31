package com.easy.easyai.web.service

import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.speech.ResolvedSpeechCapabilities
import com.easy.easyai.speech.SpeechScript
import com.easy.easyai.speech.SpeechSegment
import com.easy.easyai.speech.SpeechStyle
import com.easy.easyai.speech.SpeechTone
import com.easy.easyai.speech.SpeechWarning

/**
 * The planning port read-aloud depends on.
 *
 * Kept separate from [SpeechDirector] because the application layer has no business knowing that a
 * plan is produced by an Aux LLM: a rule-based planner, a cached plan or a test double all satisfy
 * this contract, and none of them should drag a chat model into a rendering test.
 *
 * Every implementation must return **validated** segments: the renderer trusts `sourceText`,
 * `spokenText` and `voice` and will not re-derive them.
 */
interface SpeechPlanner {

    /** What a successful plan produced, for the meta event and the history log. */
    data class PlanSummary(
        val scriptId: String,
        val revision: Int = 1,
        val mood: String,
        val style: SpeechStyle,
        val segments: Int,
        val warnings: List<SpeechWarning> = emptyList()
    )

    /** Effective voices/emotions for a target TTS row; null when the catalog has no usable voice. */
    suspend fun capabilitiesFor(ttsConfig: ModelProviderConfig): ResolvedSpeechCapabilities?

    /**
     * Build and validate one single-task rewrite, or say why there is none.
     * Used by the `refine` mode, which must never fail a request that direct read-aloud could serve.
     *
     * [tone] only shapes the wording; fidelity gates still decide whether the rewrite is speakable.
     */
    suspend fun quickRefine(
        owners: Collection<String>,
        text: String,
        ttsConfig: ModelProviderConfig,
        maxChars: Int,
        tone: SpeechTone = SpeechTone.DEFAULT
    ): RefineOutcome

    /**
     * Produce a multi-segment plan, handing each validated segment to [onSegment] in order.
     *
     * @throws SpeechPlanException when no usable plan was produced. Segments already delivered stay
     * delivered; the exception means "the rest is unavailable", not "nothing played".
     */
    suspend fun produce(
        owners: Collection<String>,
        text: String,
        ttsConfig: ModelProviderConfig,
        maxChars: Int,
        incremental: Boolean,
        firstSegmentBudgetMs: Long? = null,
        tone: SpeechTone = SpeechTone.DEFAULT,
        onSegment: suspend (SpeechSegment) -> Unit
    ): PlanSummary

    companion object {
        /**
         * How long a streamed plan may go without producing its first segment before the caller gives
         * up and reads the original text instead.
         *
         * It started at 1.7 s (a 2.5 s first-audio target minus one TTS round-trip), which assumed a
         * near-instant model. Measured against the real director the first segment lands at 1.2–1.8 s,
         * so the old value was a coin flip: a miss cancels the model stream, the listener silently
         * gets a plain read, and — because no plan exists — nothing is archived for Read Aloud. 5 s
         * keeps the fast path for a healthy director while making that miss rare.
         */
        const val FIRST_SEGMENT_BUDGET_MS = 5_000L

        const val CODE_NOT_CONFIGURED = "NOT_CONFIGURED"
        const val CODE_TIMEOUT = "DIRECTOR_TIMEOUT"
        const val CODE_INVALID_PLAN = "INVALID_PLAN"

        // Refine fallback reasons. These are wire values — the `X-Speech-Fallback` header and the
        // warning code stored on a fallback script — so they are lower-kebab and never localized:
        // the client owns the sentence the listener reads.
        const val REFINE_LENGTH_LIMIT = "length-limit"
        const val REFINE_TIMEOUT = "refine-timeout"
        const val REFINE_NOT_CONFIGURED = "refine-not-configured"
        const val REFINE_NO_VOICE = "refine-no-voice"
        const val REFINE_REJECTED = "refine-rejected"

        /** The reason reported when the attempt failed for none of the specific causes above. */
        const val REFINE_UNAVAILABLE = "unavailable"

        /**
         * Whether [code] — possibly `reason:seconds` — is a refine fallback reason.
         *
         * Used when a stored fallback script is replayed: its warnings carry the reason, and only a
         * known one may be turned back into a notice. A blank or stale code must not become one.
         */
        fun isRefineReason(code: String): Boolean = code.substringBefore(':') in REFINE_REASONS

        private val REFINE_REASONS = setOf(
            REFINE_LENGTH_LIMIT,
            REFINE_TIMEOUT,
            REFINE_NOT_CONFIGURED,
            REFINE_NO_VOICE,
            REFINE_REJECTED,
            REFINE_UNAVAILABLE
        )
    }
}

/**
 * What one refine attempt produced.
 *
 * The plan is either usable or absent — and an absent plan is not a diagnosis. "Reading the original"
 * is the same sentence whether the director timed out, was never configured, or wrote a rewrite the
 * fidelity gate threw away; those have different fixes, so the reason travels to the client and is
 * shown next to the message. It stays a *notice*, never an error: the audio still plays.
 */
sealed interface RefineOutcome {
    data class Planned(val script: SpeechScript) : RefineOutcome

    /**
     * No plan, because of [reason] (one of the `SpeechPlanner.REFINE_*` codes).
     *
     * [budgetSeconds] is set only for [SpeechPlanner.REFINE_TIMEOUT]: "it was given 6 s for 679
     * characters" is what turns a timeout from mysterious into actionable.
     */
    data class Unavailable(
        val reason: String,
        val budgetSeconds: Long? = null
    ) : RefineOutcome {
        /** Wire form: `reason`, or `reason:seconds` when a budget explains it. */
        fun code(): String = budgetSeconds?.let { "$reason:$it" } ?: reason
    }
}
