package com.easy.easyai.speech

/**
 * One recognition update, normalized away from the vendor's frame shapes.
 *
 * [Sentence] carries the *whole* text of its sentence, not an incremental delta: the service
 * revises earlier words, punctuation and even dialect wording, so a consumer must replace the
 * sentence rather than append. `sentenceId` is the stable key inside one recording; it restarts at
 * 1 for the next one, which is why consumers pair it with their own round id.
 */
sealed interface SpeechRecognitionEvent {

    /** The task is open and the service accepts audio. Nothing may be sent before this arrives. */
    data class Started(val modelId: String) : SpeechRecognitionEvent

    data class Sentence(
        val sentenceId: Int,
        val text: String,
        val final: Boolean,
        val beginMs: Int? = null,
        val endMs: Int? = null
    ) : SpeechRecognitionEvent

    /** The task ended normally; [usage] is the last cumulative report the service sent. */
    data class Finished(val usage: SpeechRecognitionUsage) : SpeechRecognitionEvent
}

/**
 * Cumulative usage of one recognition task.
 *
 * Which fields exist depends on the model generation, so every one is nullable and a consumer must
 * not assume a shape: 3.0 reports `duration`, 3.1 reports tokens, and a value that never arrived
 * stays null rather than becoming 0 — "no data" and "zero" are not the same answer.
 */
data class SpeechRecognitionUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val durationSeconds: Double? = null
) {
    val isEmpty: Boolean
        get() = inputTokens == null && outputTokens == null && totalTokens == null && durationSeconds == null
}

/**
 * A recognition failure with a stable code.
 *
 * The web layer maps [code] onto the frame it sends the browser; the vendor's own error text is
 * kept in the message for logs only, because it is not something to show a user or to key logic on.
 */
class SpeechRecognitionException(
    val code: String,
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause) {

    companion object {
        /** The service answered `task-failed`. */
        const val TASK_FAILED = "TASK_FAILED"

        /** A frame the protocol does not define, or one belonging to another task. */
        const val PROTOCOL_ERROR = "PROTOCOL_ERROR"

        /** The socket closed before `task-finished`; the transcript is partial by definition. */
        const val UNREACHABLE = "UNREACHABLE"

        /** Nothing arrived from upstream for too long. */
        const val IDLE_TIMEOUT = "IDLE_TIMEOUT"
    }
}
