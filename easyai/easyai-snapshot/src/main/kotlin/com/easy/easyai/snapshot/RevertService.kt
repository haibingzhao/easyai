package com.easy.easyai.snapshot

import com.easy.easyai.common.util.ProcessExecutor
import com.easy.easyai.snapshot.model.FileChangeStatus
import com.easy.easyai.snapshot.model.FileDiff
import com.easy.easyai.snapshot.model.GitCheckpoint
import com.easy.easyai.snapshot.model.RevertState
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Service handling file revert/unrevert operations.
 *
 * Revert flow:
 * 1. Find target checkpoint by messageId
 * 2. Take a pre-revert snapshot (for unrevert)
 * 3. Restore files to the target checkpoint state
 * 4. Save revert state JSON
 *
 * Unrevert flow:
 * 1. Read pre-revert commit hash from revert state
 * 2. Restore files to pre-revert state
 * 3. Delete revert state JSON
 *
 * Cleanup: When user sends a new message while in reverted state, delete revert state.
 */
class RevertService(
    private val snapshotService: SnapshotService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Revert files to the state before the specified message's changes.
     *
     * @param projectPath Project root path
     * @param sessionId Session ID
     * @param messageId The message ID whose changes should be reverted
     * @return Revert result with diff stats
     */
    suspend fun revert(projectPath: Path, sessionId: String, messageId: String): RevertResult {
        requireEnabled(projectPath)
        // 1. List checkpoints for this session
        val checkpoints = snapshotService.listCheckpoints(projectPath, sessionId)
        if (checkpoints.isEmpty()) {
            throw IllegalStateException("No checkpoints found for session: $sessionId")
        }

        // 2. Find the target checkpoint (the one matching messageId)
        val targetCheckpoint = checkpoints.find { it.messageId == messageId }
            ?: throw IllegalArgumentException("Checkpoint not found for message: $messageId")

        // 3. Find the checkpoint BEFORE the target (to restore to that state)
        val targetIndex = checkpoints.indexOf(targetCheckpoint)
        val restoreToHash = if (targetIndex + 1 < checkpoints.size) {
            // Restore to the checkpoint just before the target
            checkpoints[targetIndex + 1].commitHash
        } else {
            // Target is the oldest checkpoint — restore to session baseline
            getSessionBaseline(projectPath, sessionId)
                ?: throw IllegalStateException("Cannot find session baseline for revert")
        }

        // 4. Take a pre-revert snapshot (current state before revert)
        val preRevertHash = try {
            snapshotService.commitAs(projectPath, sessionId, ChangeAuthor.USER, "pre-revert")
        } catch (e: Exception) {
            logger.warn("Failed to create pre-revert snapshot: {}", e.message)
            // Use the latest checkpoint's tree hash as fallback
            checkpoints.firstOrNull()?.commitHash
                ?: throw IllegalStateException("Cannot create pre-revert snapshot")
        }

        // 5. Compute diff between pre-revert and restore target
        val diffs = try {
            snapshotService.diff(projectPath, preRevertHash, restoreToHash)
        } catch (e: Exception) {
            logger.warn("Failed to compute revert diff: {}", e.message)
            emptyList()
        }

        // 6. Restore files to the target state
        snapshotService.restore(projectPath, restoreToHash, sessionId)

        // 7. Save revert state
        val revertState = RevertState(
            messageId = messageId,
            commitHash = restoreToHash,
            preRevertCommitHash = preRevertHash,
            timestamp = System.currentTimeMillis()
        )
        snapshotService.saveRevertState(projectPath, sessionId, revertState)

        logger.info("Reverted session {} to before message {} ({} files changed)",
            sessionId, messageId, diffs.size)

        return RevertResult(
            messageId = messageId,
            filesCount = diffs.size,
            additions = diffs.sumOf { it.additions },
            deletions = diffs.sumOf { it.deletions },
            diffs = diffs
        )
    }

    /**
     * Unrevert: restore files to the state before the revert.
     *
     * @param projectPath Project root path
     * @param sessionId Session ID
     * @return Unrevert result with diff stats
     */
    suspend fun unrevert(projectPath: Path, sessionId: String): UnrevertResult {
        requireEnabled(projectPath)
        val revertState = snapshotService.loadRevertState(projectPath, sessionId)
            ?: throw IllegalStateException("No active revert for session: $sessionId")

        // Restore to pre-revert state
        snapshotService.restore(projectPath, revertState.preRevertCommitHash, sessionId)

        // Compute diff
        val diffs = try {
            snapshotService.diff(projectPath, revertState.preRevertCommitHash, revertState.commitHash)
        } catch (e: Exception) {
            logger.warn("Failed to compute unrevert diff: {}", e.message)
            emptyList()
        }

        // Clear revert state
        snapshotService.clearRevertState(projectPath, sessionId)

        logger.info("Unreverted session {} (restored {} files)", sessionId, diffs.size)

        return UnrevertResult(
            messageId = revertState.messageId,
            filesCount = diffs.size,
            additions = diffs.sumOf { it.additions },
            deletions = diffs.sumOf { it.deletions }
        )
    }

    /**
     * Cleanup revert state when user sends a new message while in reverted state.
     * This confirms the revert and starts fresh.
     */
    suspend fun cleanup(projectPath: Path, sessionId: String) {
        if (!snapshotService.isEnabled(projectPath)) return
        val revertState = snapshotService.loadRevertState(projectPath, sessionId)
        if (revertState != null) {
            snapshotService.clearRevertState(projectPath, sessionId)
            logger.info("Cleaned up revert state for session {}", sessionId)
        }
    }

    /**
     * Get the current revert state for a session.
     */
    suspend fun getRevertState(projectPath: Path, sessionId: String): RevertState? {
        if (!snapshotService.isEnabled(projectPath)) return null
        return snapshotService.loadRevertState(projectPath, sessionId)
    }

    /**
     * List all checkpoints for a session.
     */
    suspend fun listCheckpoints(projectPath: Path, sessionId: String): List<GitCheckpoint> {
        if (!snapshotService.isEnabled(projectPath)) return emptyList()
        return snapshotService.listCheckpoints(projectPath, sessionId)
    }

    /**
     * Get diff between two commits.
     */
    suspend fun getDiff(projectPath: Path, from: String, to: String): List<FileDiff> {
        if (!snapshotService.isEnabled(projectPath)) return emptyList()
        return snapshotService.diff(projectPath, from, to)
    }

    /**
     * Get checkpoint diff (diff introduced by a specific checkpoint).
     */
    suspend fun getCheckpointDiff(projectPath: Path, sessionId: String, messageId: String): List<FileDiff> {
        requireEnabled(projectPath)
        val checkpoints = snapshotService.listCheckpoints(projectPath, sessionId)
        val targetIndex = checkpoints.indexOfFirst { it.messageId == messageId }
        if (targetIndex < 0) {
            throw IllegalArgumentException("Checkpoint not found for message: $messageId")
        }

        val target = checkpoints[targetIndex]
        val parent = if (targetIndex + 1 < checkpoints.size) {
            checkpoints[targetIndex + 1].commitHash
        } else {
            // Oldest checkpoint — use session baseline
            getSessionBaseline(projectPath, sessionId)
                ?: throw IllegalStateException("Cannot find session baseline for checkpoint diff")
        }

        return snapshotService.diff(projectPath, parent, target.commitHash)
    }

    /**
     * Get session-level diff (from baseline to latest checkpoint).
     */
    suspend fun getSessionDiff(projectPath: Path, sessionId: String): List<FileDiff> {
        if (!snapshotService.isEnabled(projectPath)) return emptyList()
        val checkpoints = snapshotService.listCheckpoints(projectPath, sessionId)
        if (checkpoints.isEmpty()) return emptyList()

        val newest = checkpoints.first()

        // Diff from session baseline to newest checkpoint
        val from = getSessionBaseline(projectPath, sessionId)
            ?: return emptyList()
        return snapshotService.diff(projectPath, from, newest.commitHash)
    }

    /**
     * Accept a file: persist review state (file remains unchanged).
     *
     * @param projectPath Project root path
     * @param sessionId Session ID
     * @param filePath Relative file path being accepted
     * @return File review result
     */
    suspend fun acceptFile(projectPath: Path, sessionId: String, filePath: String): FileReviewResult {
        requireEnabled(projectPath)
        snapshotService.atomicUpdateFileReviewState(projectPath, sessionId, mapOf(filePath to "accepted"))
        logger.info("Accepted file {} for session {}", filePath, sessionId)
        return FileReviewResult(path = filePath, action = "accepted")
    }

    /**
     * Batch accept multiple files atomically: all review states are updated in a single
     * read-modify-write operation, preventing lost updates from concurrent calls.
     *
     * @param projectPath Project root path
     * @param sessionId Session ID
     * @param filePaths Relative file paths being accepted
     * @return List of file review results
     */
    suspend fun batchAcceptFiles(projectPath: Path, sessionId: String, filePaths: List<String>): List<FileReviewResult> {
        requireEnabled(projectPath)
        val reviews = filePaths.associateWith { "accepted" }
        snapshotService.atomicUpdateFileReviewState(projectPath, sessionId, reviews)
        logger.info("Batch accepted {} files for session {}", filePaths.size, sessionId)
        return filePaths.map { FileReviewResult(path = it, action = "accepted") }
    }

    /**
     * Batch reject multiple files atomically: all review states are updated in a single
     * read-modify-write operation. File restoration is performed sequentially before the
     * atomic review state update.
     *
     * Performance: uses a single-pass checkpoint traversal via [buildFileRestoreMap]
     * to compute restore info for all files at once — O(checkpoints × diff + files × restore)
     * instead of O(files × checkpoints × diff).
     *
     * Cross-session safety: detects files externally modified by other sessions and
     * attempts a 3-way merge before restoring. If the merge produces conflicts, the
     * file is skipped with a `"skipped"` action.
     *
     * @param projectPath Project root path
     * @param sessionId Session ID
     * @param filePaths Relative file paths being rejected
     * @return List of file review results (action: "rejected" or "skipped")
     */
    suspend fun batchRejectFiles(projectPath: Path, sessionId: String, filePaths: List<String>): List<FileReviewResult> {
        requireEnabled(projectPath)

        // 1. Single-pass: build restore map for all files
        val restoreMap = buildFileRestoreMap(projectPath, sessionId, filePaths.toSet())

        // 2. Detect files modified externally (by other sessions)
        val externalMods = detectExternalModifications(projectPath, sessionId, filePaths.toSet())
        val baselineHash = getSessionBaseline(projectPath, sessionId)
        val latestCheckpointHash = snapshotService.listCheckpoints(projectPath, sessionId)
            .firstOrNull()?.commitHash

        // 3. Restore each file using pre-computed info
        val restoredPaths = mutableListOf<String>()
        val skippedPaths = mutableListOf<String>()
        try {
            for (filePath in filePaths) {
                val info = restoreMap[filePath] ?: continue

                if (filePath in externalMods && baselineHash != null && latestCheckpointHash != null) {
                    // Attempt 3-way merge to preserve other sessions' changes
                    val merged = tryMergeReject(projectPath, sessionId, filePath, baselineHash, latestCheckpointHash)
                    if (merged != null) {
                        Files.write(projectPath.resolve(filePath), merged)
                        restoredPaths.add(filePath)
                        logger.info("Rejected {} via 3-way merge for session {}", filePath, sessionId)
                        continue
                    }
                    // Merge conflict → skip
                    logger.warn("Skipped reject for {} — merge conflict with external modifications", filePath)
                    skippedPaths.add(filePath)
                    continue
                }

                // Normal restore (no external modifications)
                if (info.status == FileChangeStatus.ADDED) {
                    snapshotService.stageFileRemoval(projectPath, filePath, sessionId)
                } else {
                    snapshotService.restoreFile(projectPath, info.parentHash, filePath, sessionId)
                }
                restoredPaths.add(filePath)
            }
        } finally {
            // Update review state for successfully restored files even if later ones fail
            if (restoredPaths.isNotEmpty()) {
                val reviews = restoredPaths.associateWith { "rejected" }
                snapshotService.atomicUpdateFileReviewState(projectPath, sessionId, reviews)
            }
        }
        logger.info("Batch rejected {} files (skipped {}) for session {}",
            restoredPaths.size, skippedPaths.size, sessionId)
        return restoredPaths.map { FileReviewResult(path = it, action = "rejected") } +
            skippedPaths.map { FileReviewResult(path = it, action = "skipped") }
    }

    /**
     * Reject a file: persist review state + restore file from git history.
     *
     * Cross-session safety: if the file was externally modified by another session,
     * attempts a 3-way merge. Throws [IllegalStateException] if the merge produces
     * conflicts — the caller should surface this to the user.
     *
     * @param projectPath Project root path
     * @param sessionId Session ID
     * @param filePath Relative file path being rejected
     * @return File review result
     */
    suspend fun rejectFile(projectPath: Path, sessionId: String, filePath: String): FileReviewResult {
        requireEnabled(projectPath)

        val restoreMap = buildFileRestoreMap(projectPath, sessionId, setOf(filePath))
        val info = restoreMap[filePath]
            ?: throw IllegalArgumentException("File $filePath not found in any checkpoint for session: $sessionId")

        // Detect external modifications
        val externalMods = detectExternalModifications(projectPath, sessionId, setOf(filePath))
        if (filePath in externalMods) {
            val baselineHash = getSessionBaseline(projectPath, sessionId)
            val latestCheckpointHash = snapshotService.listCheckpoints(projectPath, sessionId)
                .firstOrNull()?.commitHash
            if (baselineHash != null && latestCheckpointHash != null) {
                val merged = tryMergeReject(projectPath, sessionId, filePath, baselineHash, latestCheckpointHash)
                if (merged != null) {
                    Files.write(projectPath.resolve(filePath), merged)
                    snapshotService.atomicUpdateFileReviewState(projectPath, sessionId, mapOf(filePath to "rejected"))
                    logger.info("Rejected {} via 3-way merge for session {}", filePath, sessionId)
                    return FileReviewResult(path = filePath, action = "rejected")
                }
            }
            throw IllegalStateException(
                "Cannot reject $filePath: merge conflict with changes from another session"
            )
        }

        // Normal restore
        if (info.status == FileChangeStatus.ADDED) {
            snapshotService.stageFileRemoval(projectPath, filePath, sessionId)
        } else {
            snapshotService.restoreFile(projectPath, info.parentHash, filePath, sessionId)
        }
        snapshotService.atomicUpdateFileReviewState(projectPath, sessionId, mapOf(filePath to "rejected"))
        return FileReviewResult(path = filePath, action = "rejected")
    }

    // ==================== Reject Helpers ====================

    /**
     * Result of mapping a file to its restore source during reject.
     */
    private data class FileRestoreInfo(
        val parentHash: String,
        val status: FileChangeStatus
    )

    /**
     * Single-pass traversal of the checkpoint chain to build a map of
     * filePath → FileRestoreInfo for all requested files.
     *
     * Iterates from oldest to newest checkpoint, recording the EARLIEST
     * checkpoint that touched each file (and its parent hash). Exits early
     * once all target files have been resolved.
     */
    private suspend fun buildFileRestoreMap(
        projectPath: Path,
        sessionId: String,
        targetFiles: Set<String>
    ): Map<String, FileRestoreInfo> {
        val checkpoints = snapshotService.listCheckpoints(projectPath, sessionId)
        if (checkpoints.isEmpty()) {
            throw IllegalStateException("No checkpoints found for session: $sessionId")
        }

        val baseline = getSessionBaseline(projectPath, sessionId)
        val result = mutableMapOf<String, FileRestoreInfo>()
        val remaining = targetFiles.toMutableSet()

        // Reverse traversal: oldest first (checkpoints are newest-first)
        for (i in checkpoints.indices.reversed()) {
            if (remaining.isEmpty()) break
            val cp = checkpoints[i]
            val parentHash = if (i + 1 < checkpoints.size) {
                checkpoints[i + 1].commitHash
            } else {
                baseline ?: continue
            }
            val cpDiff = snapshotService.diff(projectPath, parentHash, cp.commitHash)
            for (fd in cpDiff) {
                if (fd.path in remaining) {
                    result[fd.path] = FileRestoreInfo(parentHash, fd.status)
                    remaining.remove(fd.path)
                }
            }
        }

        if (remaining.isNotEmpty()) {
            logger.warn("Files not found in any checkpoint for session {}: {}", sessionId, remaining)
        }
        return result
    }

    /**
     * Detect files in [targetFiles] that have been externally modified
     * (by another session or the user) since this session's latest checkpoint.
     *
     * Compares the current working tree against the session's most recent
     * checkpoint; any divergence on a target file signals external modification.
     *
     * @return Subset of [targetFiles] that were modified outside this session
     */
    private suspend fun detectExternalModifications(
        projectPath: Path,
        sessionId: String,
        targetFiles: Set<String>
    ): Set<String> {
        val checkpoints = snapshotService.listCheckpoints(projectPath, sessionId)
        if (checkpoints.isEmpty()) return emptySet()

        val latestCheckpoint = checkpoints.first()
        val workingTreeDiffs = snapshotService.diffWithWorkTree(projectPath, latestCheckpoint.commitHash)
        return workingTreeDiffs
            .filter { it.path in targetFiles }
            .map { it.path }
            .toSet()
    }

    /**
     * Attempt a 3-way merge for rejecting a file that has external modifications.
     *
     * Merge strategy:
     *   base    = baseline version (target: what we want to revert to)
     *   ours    = current working tree (includes other sessions' changes)
     *   theirs  = latest checkpoint version (this session's full state)
     *
     * `git merge-file` exit codes:
     *   - 0: clean merge → use merged result (other sessions preserved, this session undone)
     *   - >0: conflict → return null, caller should skip the file
     */
    private suspend fun tryMergeReject(
        projectPath: Path,
        sessionId: String,
        filePath: String,
        baselineHash: String,
        latestCheckpointHash: String
    ): ByteArray? {
        // 1. Extract file content from baseline and latest checkpoint
        val baselineContent = snapshotService.extractFileContent(projectPath, baselineHash, filePath)
            ?: return null  // file didn't exist at baseline — can't merge, treat as ADDED
        val checkpointContent = snapshotService.extractFileContent(projectPath, latestCheckpointHash, filePath)
            ?: return null

        // 2. Read current working tree content
        val workingTreeFile = projectPath.resolve(filePath)
        if (!Files.exists(workingTreeFile)) return null
        val currentContent = Files.readAllBytes(workingTreeFile)

        // 3. Write 3 temp files for git merge-file
        val tempDir = Files.createTempDirectory("easyai-merge-")
        try {
            val baseFile = tempDir.resolve("base");   Files.write(baseFile, baselineContent)
            val oursFile = tempDir.resolve("ours");   Files.write(oursFile, currentContent)
            val theirsFile = tempDir.resolve("theirs"); Files.write(theirsFile, checkpointContent)

            // git merge-file -p ours theirs base → merged result on stdout
            // -p prints to stdout instead of modifying the file in-place
            val result = ProcessExecutor.execute(
                command = listOf(
                    "git", "merge-file", "-p",
                    oursFile.toAbsolutePath().toString(),
                    theirsFile.toAbsolutePath().toString(),
                    baseFile.toAbsolutePath().toString()
                ),
                workDir = tempDir,
                timeoutSeconds = 10L
            )

            if (result != null && result.exitCode == 0) {
                logger.info("3-way merge succeeded for {} during reject (session={})", filePath, sessionId)
                return result.stdout.toByteArray()
            }

            logger.warn("3-way merge conflict for {} during reject (exit={}, session={}), skipping",
                filePath, result?.exitCode, sessionId)
            return null
        } finally {
            Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    /**
     * Rollback files to the state before the target user message.
     * Finds the first checkpoint AFTER the target message (by timestamp) and restores to its parent.
     *
     * @param projectPath Project root path
     * @param sessionId Session ID
     * @param messageCreatedAt The createdAt timestamp of the user message being edited
     * @return Rollback result, or null if no checkpoints found after the message
     */
    suspend fun rollbackToBeforeMessage(projectPath: Path, sessionId: String, messageCreatedAt: Long): RollbackResult? {
        if (!snapshotService.isEnabled(projectPath)) return null
        // 1. List checkpoints (ordered newest first: index 0 = newest, index N = oldest)
        val checkpoints = snapshotService.listCheckpoints(projectPath, sessionId)
        if (checkpoints.isEmpty()) {
            return null
        }

        // 2. Reverse-traverse to find the EARLIEST checkpoint with timestamp > messageCreatedAt.
        // This is the first checkpoint triggered by the user message being edited.
        // Checkpoints are ordered newest first, so we iterate from the end (oldest).
        var targetCheckpoint: GitCheckpoint? = null
        var targetIndex = -1
        for (i in checkpoints.indices.reversed()) {
            val cp = checkpoints[i]
            if (cp.timestamp > messageCreatedAt) {
                targetCheckpoint = cp
                targetIndex = i
                // Continue searching backward to find the earliest one
            } else {
                // Once we find a checkpoint older than the message, stop
                break
            }
        }

        if (targetCheckpoint == null) {
            // No checkpoints after the message — nothing to rollback
            return null
        }

        // 3. Determine parent hash (state before the target checkpoint)
        val parentHash = if (targetIndex + 1 < checkpoints.size) {
            checkpoints[targetIndex + 1].commitHash
        } else {
            getSessionBaseline(projectPath, sessionId) ?: return null
        }

        // 4. Compute diff between parent and the latest checkpoint being rolled back
        // The latest checkpoint being rolled back is the one at the lowest index
        // that is still > messageCreatedAt. But for simplicity, we diff from
        // parent to the newest checkpoint (checkpoints[0]) to capture all changes
        // being rolled back.
        val newestCheckpoint = checkpoints.first()
        val diffs = try {
            snapshotService.diff(projectPath, parentHash, newestCheckpoint.commitHash)
        } catch (e: Exception) {
            logger.warn("Failed to compute rollback diff: {}", e.message)
            emptyList()
        }

        // No actual file changes — skip restore and report nothing to rollback
        if (diffs.isEmpty()) {
            logger.info("No file changes to roll back for session {} before messageCreatedAt {}", sessionId, messageCreatedAt)
            return null
        }

        // 5. Restore files to parent hash state
        snapshotService.restore(projectPath, parentHash, sessionId)

        logger.info("Rolled back session {} to before messageCreatedAt {} ({} files changed)",
            sessionId, messageCreatedAt, diffs.size)

        return RollbackResult(
            filesCount = diffs.size,
            additions = diffs.sumOf { it.additions },
            deletions = diffs.sumOf { it.deletions },
            diffs = diffs
        )
    }

    /**
     * Get the session baseline tree hash (the state before any tools ran).
     * This is used for reverting to the state before the oldest checkpoint.
     */
    private suspend fun getSessionBaseline(projectPath: Path, sessionId: String): String? {
        // The baseline is stored by ensureBaseline() in a per-session file
        // We can retrieve it by calling ensureBaseline() which returns the saved hash if it exists
        return try {
            snapshotService.ensureBaseline(projectPath, sessionId)
        } catch (e: Exception) {
            logger.warn("Failed to get session baseline for session {}: {}", sessionId, e.message)
            null
        }
    }

    /**
     * Assert that the snapshot system is enabled for the given project path.
     * @throws IllegalStateException if not enabled
     */
    private fun requireEnabled(projectPath: Path) {
        check(snapshotService.isEnabled(projectPath)) {
            "Snapshot system is not enabled for project: $projectPath"
        }
    }
}

/**
 * Result of a file review operation (accept/reject).
 */
data class FileReviewResult(
    val path: String,
    val action: String
)

/**
 * Result of a rollback operation.
 */
data class RollbackResult(
    val filesCount: Int,
    val additions: Int,
    val deletions: Int,
    val diffs: List<FileDiff>
)

/**
 * Result of a revert operation.
 */
data class RevertResult(
    val messageId: String,
    val filesCount: Int,
    val additions: Int,
    val deletions: Int,
    val diffs: List<FileDiff>
)

/**
 * Result of an unrevert operation.
 */
data class UnrevertResult(
    val messageId: String,
    val filesCount: Int,
    val additions: Int,
    val deletions: Int
)
