package com.easy.easyai.snapshot.model

/**
 * Represents a single commit in the session's commit history with per-file diffs.
 * Used by the per-commit view in the frontend Review panel.
 *
 * @param commitHash Full commit hash (40-char hex)
 * @param author Change author: "llm" or "user"
 * @param message Commit message
 * @param timestamp Commit timestamp (epoch millis)
 * @param files Per-file diffs against the parent commit
 */
data class CommitChangeInfo(
    val commitHash: String,
    val author: String,
    val message: String,
    val timestamp: Long,
    val files: List<FileDiff>
)
