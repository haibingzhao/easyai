package com.easy.easyai.snapshot.model

/**
 * Tracks the revert state for a session.
 * Stored as JSON in the snapshot repository directory (not in the project).
 *
 * @param messageId The message ID to revert to (revert to the state BEFORE this message's changes)
 * @param commitHash The commit hash to revert to (target checkpoint's parent state)
 * @param preRevertCommitHash The commit hash taken BEFORE revert (for unrevert/restore)
 * @param timestamp When the revert happened (epoch millis)
 */
data class RevertState(
    val messageId: String,
    val commitHash: String,
    val preRevertCommitHash: String,
    val timestamp: Long
)
