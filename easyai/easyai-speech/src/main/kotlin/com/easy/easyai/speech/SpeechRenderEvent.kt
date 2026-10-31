package com.easy.easyai.speech

/**
 * What a multi-segment read-aloud reports while it runs.
 *
 * These are domain events, not a wire format: the transport decides whether they become NDJSON,
 * SSE or WebSocket frames. Audio is delivered one segment at a time on purpose — the plan's
 * `pauseAfterMs` between sentences belongs to the player, and joining MP3 frames across tasks is
 * not safe without a container rewrite, so the player gets separate clips instead of a fake stream.
 */
sealed interface SpeechRenderEvent {

    /**
     * Sent once, before the first clip. [planned] is false when the plan was unusable and the
     * original text is being read instead — the caller can then say so rather than pretending the
     * director ran. [segmentCount] is -1 for an incrementally planned script, whose length is not
     * known when the first segment is ready; [Completed] reports the final count.
     */
    data class Meta(
        val scriptId: String,
        val revision: Int,
        val segmentCount: Int,
        val planned: Boolean,
        val warnings: List<SpeechWarning> = emptyList()
    ) : SpeechRenderEvent

    /** One segment's complete clip, in plan order. [words] is empty unless the row enables timestamps. */
    data class SegmentAudio(
        val segmentId: String,
        val index: Int,
        val spokenText: String,
        val voice: String,
        val pauseAfterMs: Int,
        val bytes: ByteArray,
        val mimeType: String,
        val words: List<TimedWord> = emptyList()
    ) : SpeechRenderEvent

    /** A defect repaired while rendering, e.g. a segment that fell back to its source sentence. */
    data class Warning(val warning: SpeechWarning) : SpeechRenderEvent

    data class Completed(val segments: Int, val warnings: List<SpeechWarning> = emptyList()) : SpeechRenderEvent

    /**
     * [playedSegments] is how many clips were already handed to the caller: audio that has left the
     * server cannot be withdrawn, so a failure after the first clip is a partial read, never a
     * "nothing happened".
     */
    data class Failed(
        val code: String,
        val message: String,
        val playedSegments: Int
    ) : SpeechRenderEvent

    companion object {
        /** The stored plan itself is gone (expired, another owner) or the row lost its voice. */
        const val CODE_PLAN_UNAVAILABLE = "PLAN_UNAVAILABLE"

        /** The submitted edit did not survive validation; the user's drafts are what to retry. */
        const val CODE_EDIT_REJECTED = "EDIT_REJECTED"

        /** Another tab saved a newer revision than the edit was based on. */
        const val CODE_REVISION_CONFLICT = "REVISION_CONFLICT"

        const val CODE_SEGMENT_FAILED = "SEGMENT_FAILED"
        const val CODE_TIMEOUT = "TIMEOUT"
        const val CODE_CANCELLED = "CANCELLED"
    }
}
