package com.easy.easyai.snapshot.model

/**
 * Represents a single file's diff between two checkpoints.
 *
 * @param path Relative file path within the project
 * @param patch Unified diff patch content (null if only stats are available)
 * @param additions Number of added lines
 * @param deletions Number of deleted lines
 * @param status File change status: added, modified, deleted, or renamed
 */
data class FileDiff(
    val path: String,
    val patch: String? = null,
    val additions: Int = 0,
    val deletions: Int = 0,
    val status: FileChangeStatus = FileChangeStatus.MODIFIED,
    val changedBy: String? = null
)

enum class FileChangeStatus {
    ADDED, MODIFIED, DELETED, RENAMED
}
