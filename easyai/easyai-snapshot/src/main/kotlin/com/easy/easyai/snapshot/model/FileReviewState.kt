package com.easy.easyai.snapshot.model

/**
 * Persisted file review state for a session.
 * Tracks which files have been accepted or rejected by the user.
 *
 * @param reviews Map of filePath to review status ("accepted" or "rejected")
 * @param updatedAt Timestamp when the state was last modified
 */
data class FileReviewState(
    val reviews: Map<String, String>,
    val updatedAt: Long = System.currentTimeMillis()
)
