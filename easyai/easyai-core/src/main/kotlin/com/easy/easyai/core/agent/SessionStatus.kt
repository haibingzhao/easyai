package com.easy.easyai.core.agent

/**
 * Lifecycle status of a chat session.
 * Used to track whether a session is active, cancelled, or completed.
 */
enum class SessionStatus {
    /** Session is actively processing or idle (ready for new messages). */
    ACTIVE,

    /** Session was cancelled by the user; can be resumed. */
    CANCELLED,

    /** Session completed with an error; can be retried or resumed. */
    ERROR,

    /** Session finished normally (no more work to do). */
    COMPLETED
}
