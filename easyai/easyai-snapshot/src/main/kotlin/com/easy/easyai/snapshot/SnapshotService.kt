package com.easy.easyai.snapshot

import com.easy.easyai.snapshot.model.CommitChangeInfo
import com.easy.easyai.snapshot.model.FileDiff
import com.easy.easyai.snapshot.model.FileReviewState
import com.easy.easyai.snapshot.model.GitCheckpoint
import com.easy.easyai.snapshot.model.RevertState
import java.nio.file.Path

/**
 * Identifies the author of a set of file changes for Git commit attribution.
 */
enum class ChangeAuthor(val gitAuthorName: String, val gitAuthorEmail: String) {
    LLM_AGENT("EasyAI Agent", "agent@easyai.dev"),
    USER("User", "user@local")
}

/**
 * Service for managing file snapshots via an independent Git repository.
 *
 * Each project gets its own snapshot repo (Shadow Git), isolated from the project's .git
 * via `--git-dir` and `--work-tree`. All sessions for a project share the same snapshot repo.
 *
 * Changes are tracked as Git commits (via `commit-tree`) with author attribution,
 * distinguishing LLM-agent changes from user changes.
 */
interface SnapshotService {

    /**
     * Check whether the snapshot system is enabled for the given project.
     * The shadow git implementation does not require the project to have its own `.git`,
     * so this returns true as long as the project path is a valid directory.
     *
     * @param projectPath Root path of the user's project
     * @return true if snapshot operations are supported for this project
     */
    fun isEnabled(projectPath: Path): Boolean

    /**
     * Stage working tree changes and create a commit with the specified author.
     *
     * @param projectPath Root path of the user's project
     * @param sessionId Session ID for per-session ref tracking
     * @param author The author to attribute this change to
     * @param message Commit message
     * @param agentId Optional agent identifier. Only effective when [author] is
     *   [ChangeAuthor.LLM_AGENT]; it is encoded into the git author name so that the
     *   per-commit view can attribute the commit to a specific agent. Ignored for
     *   [ChangeAuthor.USER] commits.
     * @return Commit hash (40-char hex)
     */
    suspend fun commitAs(
        projectPath: Path,
        sessionId: String,
        author: ChangeAuthor,
        message: String = "snapshot",
        agentId: String? = null
    ): String

    /**
     * Ensure the snapshot repo is initialized with a baseline commit of all existing files.
     * Called before file-modifying tools execute, so that subsequent commitAs() only
     * captures changes made by the tool, not pre-existing files.
     *
     * The baseline is committed as [ChangeAuthor.USER].
     * Idempotent — safe to call multiple times.
     *
     * @param projectPath Root path of the user's project
     * @param sessionId Session ID for per-session baseline tracking
     * @return The baseline commit hash
     */
    suspend fun ensureBaseline(projectPath: Path, sessionId: String): String

    /**
     * Compute the diff between two commits (or tree objects — `git diff` supports both).
     *
     * @param projectPath Root path of the user's project
     * @param fromCommitHash Source commit hash (exclusive)
     * @param toCommitHash Target commit hash (inclusive)
     * @return List of file diffs
     */
    suspend fun diff(projectPath: Path, fromCommitHash: String, toCommitHash: String): List<FileDiff>

    /**
     * Compute diff between a commit and the current working tree.
     *
     * @param projectPath Root path of the user's project
     * @param commitHash Base commit hash
     * @return List of file diffs with patches
     */
    suspend fun diffWithWorkTree(projectPath: Path, commitHash: String): List<FileDiff>

    /**
     * Restore the working tree to the state captured by a commit.
     *
     * @param projectPath Root path of the user's project
     * @param commitHash Commit hash to restore to
     */
    suspend fun restore(projectPath: Path, commitHash: String, sessionId: String? = null)

    /**
     * Restore a single file to the state at a given commit.
     *
     * @param projectPath Root path of the user's project
     * @param commitHash Commit hash to restore from
     * @param filePath Relative file path to restore
     */
    suspend fun restoreFile(projectPath: Path, commitHash: String, filePath: String, sessionId: String? = null)

    /**
     * Stage a file deletion in the session index and remove from working tree.
     * Used when rejecting ADDED files that need to be removed entirely.
     *
     * @param projectPath Root path of the user's project
     * @param filePath Relative file path to remove
     * @param sessionId Session ID for per-session index sync (optional)
     */
    suspend fun stageFileRemoval(projectPath: Path, filePath: String, sessionId: String? = null)

    // ==================== Checkpoint Persistence ====================

    /**
     * Save a checkpoint (commit hash + metadata) for a session.
     *
     * @param projectPath Root path of the user's project
     * @param sessionId Session ID
     * @param checkpoint Checkpoint to save
     */
    suspend fun saveCheckpoint(projectPath: Path, sessionId: String, checkpoint: GitCheckpoint)

    /**
     * List all checkpoints for a session, ordered by creation time (newest first).
     *
     * @param projectPath Root path of the user's project
     * @param sessionId Session ID to query checkpoints for
     * @return List of checkpoints ordered newest first
     */
    suspend fun listCheckpoints(projectPath: Path, sessionId: String): List<GitCheckpoint>

    // ==================== Revert State ====================

    /**
     * Save revert state for a session.
     * Stored as JSON in the snapshot repo directory (not in the project).
     */
    suspend fun saveRevertState(projectPath: Path, sessionId: String, state: RevertState)

    /**
     * Load revert state for a session.
     *
     * @return The revert state, or null if no revert is active
     */
    suspend fun loadRevertState(projectPath: Path, sessionId: String): RevertState?

    /**
     * Clear revert state for a session (delete the JSON file).
     */
    suspend fun clearRevertState(projectPath: Path, sessionId: String)

    // ==================== File Review State ====================

    /**
     * Save file review states for a session.
     * Stored as JSON in the snapshot repo directory.
     */
    suspend fun saveFileReviewState(projectPath: Path, sessionId: String, state: FileReviewState)

    /**
     * Load file review states for a session.
     */
    suspend fun loadFileReviewState(projectPath: Path, sessionId: String): FileReviewState?

    /**
     * Clear file review state for a session (delete the JSON file).
     */
    suspend fun clearFileReviewState(projectPath: Path, sessionId: String)

    /**
     * Atomically update file review state for multiple files.
     * Read-modify-write is performed inside the project mutex to prevent lost updates
     * when concurrent accept/reject calls target the same session.
     *
     * @param projectPath Root path of the user's project
     * @param sessionId Session ID
     * @param reviews Map of filePath to review status ("accepted" or "rejected")
     * @return The updated FileReviewState
     */
    suspend fun atomicUpdateFileReviewState(
        projectPath: Path,
        sessionId: String,
        reviews: Map<String, String>
    ): FileReviewState

    /**
     * Remove review state entries for files that have been re-modified.
     * Called when new checkpoints introduce changes to previously reviewed files,
     * so that the UI resets from "accepted"/"rejected" back to "applied".
     *
     * @param projectPath Root path of the user's project
     * @param sessionId Session ID
     * @param filePaths Paths of files whose review state should be cleared
     * @return The updated FileReviewState, or null if no state file exists
     */
    suspend fun invalidateFileReviews(
        projectPath: Path,
        sessionId: String,
        filePaths: Set<String>
    ): FileReviewState?

    // ==================== Crash Recovery ====================

    /**
     * Get currently staged (indexed) changes without committing.
     * Used to recover file change info when AgentEnd was not reached
     * (e.g., session crashed or interrupted mid-execution).
     *
     * Uses a session-specific GIT_INDEX_FILE to isolate staged state per session,
     * preventing cross-session data leakage. The session index reflects the state
     * from the most recent commitAs() call for this session.
     *
     * @param projectPath Root path of the user's project
     * @param sessionId Session ID to scope staged changes lookup
     * @return List of staged file diffs, empty if nothing staged or no baseline exists
     */
    suspend fun getStagedChanges(projectPath: Path, sessionId: String): List<FileDiff>

    // ==================== Session Lifecycle ====================

    /**
     * Clean up all per-session snapshot files when a session is deleted.
     * Removes: index-{sessionId}, baseline-{sessionId}.tree, checkpoints-{sessionId}.json,
     * revert-{sessionId}.json, file-review-{sessionId}.json, last-track-{sessionId}.hash,
     * and the refs/easyai/session-{sessionId} ref.
     *
     * @param projectPath Root path of the user's project
     * @param sessionId Session ID being deleted
     */
    suspend fun cleanupSession(projectPath: Path, sessionId: String)

    // ==================== Session Tracking State ====================

    /**
     * Get the last tracked commit hash for a session.
     * Used to compute diffs between consecutive tool executions.
     *
     * @param projectPath Root path of the user's project
     * @param sessionId Session ID
     * @return The last tracked commit hash, or null if none exists
     */
    suspend fun getLastTrackedHash(projectPath: Path, sessionId: String): String?

    /**
     * Save the last tracked commit hash for a session.
     * Called after each commitAs() to enable incremental diffs.
     *
     * @param projectPath Root path of the user's project
     * @param sessionId Session ID
     * @param commitHash Commit hash to save
     */
    suspend fun saveLastTrackedHash(projectPath: Path, sessionId: String, commitHash: String)

    // ==================== Commit History ====================

    /**
     * List all commits for a session with per-commit diffs.
     * Walks the commit chain from refs/easyai/session-{sessionId} back to root.
     *
     * @return Commits ordered oldest-first, each with author and file diffs
     */
    suspend fun listCommitsWithDiffs(
        projectPath: Path,
        sessionId: String
    ): List<CommitChangeInfo>

    /**
     * Determine the author of each file change within a commit range.
     *
     * Walks the commit chain from [fromCommitHash] (exclusive) to [toCommitHash] (inclusive)
     * and maps each changed file to its most recent modifying author.
     *
     * @param projectPath Root path of the user's project
     * @param fromCommitHash Start of range (exclusive), or session baseline
     * @param toCommitHash End of range (inclusive), typically a checkpoint commit
     * @return Map of file path to author identifier ("llm" or "user")
     */
    suspend fun determineFileAuthors(
        projectPath: Path,
        fromCommitHash: String,
        toCommitHash: String
    ): Map<String, String>

    /**
     * Extract the content of a single file at a given commit.
     * Used for 3-way merge during cross-session reject.
     *
     * @param projectPath Root path of the user's project
     * @param commitHash Commit hash to read from
     * @param filePath Relative file path within the project
     * @return File content as ByteArray, or null if the file did not exist at that commit
     */
    suspend fun extractFileContent(projectPath: Path, commitHash: String, filePath: String): ByteArray?

    // ==================== Session Ref ====================

    /**
     * Resolve the session ref (`refs/easyai/session-{sessionId}`) to its current commit hash.
     * The session ref always points to the latest real commit in the session's commit chain,
     * which may differ from checkpoint commit hashes when tree-dedup returns an existing hash.
     *
     * @param projectPath Root path of the user's project
     * @param sessionId Session ID
     * @return The commit hash the session ref points to, or null if the ref does not exist
     */
    suspend fun resolveSessionRef(projectPath: Path, sessionId: String): String?
}
