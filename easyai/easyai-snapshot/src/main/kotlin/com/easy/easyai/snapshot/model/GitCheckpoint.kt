package com.easy.easyai.snapshot.model

/**
 * A checkpoint representing a snapshot taken during a conversation.
 *
 * @param commitHash Git commit hash (40-char hex, created via `commit-tree`)
 * @param sessionId Session that produced this checkpoint
 * @param messageId User message ID that triggered the snapshot
 * @param timestamp Checkpoint creation time (epoch millis)
 */
data class GitCheckpoint(
    val commitHash: String,
    val sessionId: String,
    val messageId: String,
    val timestamp: Long
)
