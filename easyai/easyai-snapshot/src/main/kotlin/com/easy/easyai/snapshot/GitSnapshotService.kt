package com.easy.easyai.snapshot

import com.easy.easyai.common.util.ProcessExecutor
import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.snapshot.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Git-based implementation of [SnapshotService].
 *
 * Uses an independent Git repository (Shadow Git) per project, with `--git-dir` pointing
 * to the snapshot repo and `--work-tree` pointing to the user's project directory.
 * This isolates snapshot operations from the project's own .git history.
 *
 * Checkpoints are stored as commit object hashes (via `commit-tree`) with author attribution,
 * distinguishing LLM-agent changes from user changes.
 *
 * Repository layout:
 * ```
 * {dataDir}/snapshots/{projectHash}/
 * ├── .git/                          ← independent Git database
 * ├── checkpoints-{sessionId}.json   ← checkpoint list per session
 * ├── revert-{sessionId}.json        ← revert state (only during active revert)
 * └── (--work-tree → projectPath)
 * ```
 *
 * @param dataDir Base directory for storing snapshot repos (e.g., ~/.easyai)
 */
class GitSnapshotService(
    private val dataDir: Path
) : SnapshotService {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper: ObjectMapper = SharedObjectMapper.instance

    /** Per-repo mutexes to prevent concurrent git write operations on the same snapshot repo. */
    private val repoMutexes = ConcurrentHashMap<String, Mutex>()

    private fun mutexFor(projectPath: Path): Mutex {
        val key = projectPath.toAbsolutePath().toString()
        return repoMutexes.getOrPut(key) { Mutex() }
    }

    companion object {
        private const val GIT_TIMEOUT_SECONDS = 30L

        /** Git's well-known empty tree hash (git hash-object -t tree /dev/null) */
        private const val EMPTY_TREE_HASH = "4b825dc642cb6eb9a060e54bf8d69288fbee4904"

        /** Directories to always exclude from snapshot tracking */
        val EXCLUDED_DIRS = setOf(
            "node_modules", "dist", "build", ".idea", "target",
            ".gradle", "__pycache__", ".next", ".nuxt", "vendor",
            ".git", ".svn", ".hg", "coverage", ".cache", ".qoder",
            ".kilo", ".vscode", ".mypy_cache", ".pytest_cache", ".qwen",
            ".sisyphus", ".venv", ".env", ".virtualenv", ".trae", ".claude"
        )
    }

    // ==================== Public API ====================

    override fun isEnabled(projectPath: Path): Boolean {
        // Shadow git does not require the project to have its own .git.
        // We only need the project path to be a valid directory.
        val valid = Files.isDirectory(projectPath)
        if (valid) {
            val hasOwnGit = Files.exists(projectPath.resolve(".git"))
            logger.trace("Snapshot enabled for project={} (hasOwnGit={})", projectPath, hasOwnGit)
        } else {
            logger.warn("Snapshot disabled: project path is not a valid directory: {}", projectPath)
        }
        return valid
    }

    override suspend fun commitAs(
        projectPath: Path,
        sessionId: String,
        author: ChangeAuthor,
        message: String
    ): String =
        mutexFor(projectPath).withLock {
            withContext(Dispatchers.IO) {
                val snapshotRepoDir = getSnapshotRepoDir(projectPath)
                commitAsInternal(snapshotRepoDir, projectPath, sessionId, author, message)
            }
        }

    override suspend fun ensureBaseline(projectPath: Path, sessionId: String): String =
        mutexFor(projectPath).withLock {
            withContext(Dispatchers.IO) {
                val snapshotRepoDir = getSnapshotRepoDir(projectPath)

                // Check if baseline already exists for this session
                val baselineFile = getBaselineFile(snapshotRepoDir, sessionId)
                if (Files.exists(baselineFile)) {
                    val storedHash = Files.readString(baselineFile).trim()
                    if (storedHash.isNotBlank()) {
                        logger.trace("Baseline already exists for project: {}, session: {} (hash={})", projectPath, sessionId, storedHash)
                        return@withContext storedHash
                    }
                    // Stored hash is blank/corrupt — fall through to recreate
                    logger.warn("Baseline file exists but is blank for session {}, recreating", sessionId)
                }

                // Stage all existing files and create baseline commit (author = USER)
                val commitHash = commitAsInternal(snapshotRepoDir, projectPath, sessionId, ChangeAuthor.USER, "baseline")

                // Persist baseline hash for revert operations
                Files.createDirectories(snapshotRepoDir)
                Files.writeString(baselineFile, commitHash)

                logger.info("Created baseline snapshot for project: {}, session: {} (commitHash={})", projectPath, sessionId, commitHash)
                commitHash
            }
        }

    override suspend fun diff(projectPath: Path, fromCommitHash: String, toCommitHash: String): List<FileDiff> =
        withContext(Dispatchers.IO) {
            val snapshotRepoDir = getSnapshotRepoDir(projectPath)
            if (!isRepoInitialized(snapshotRepoDir)) return@withContext emptyList()

            diffWithPatches(snapshotRepoDir, projectPath, fromCommitHash, toCommitHash)
        }

    override suspend fun diffWithWorkTree(projectPath: Path, commitHash: String): List<FileDiff> =
        withContext(Dispatchers.IO) {
            val snapshotRepoDir = getSnapshotRepoDir(projectPath)
            if (!isRepoInitialized(snapshotRepoDir)) return@withContext emptyList()

            diffWithPatches(snapshotRepoDir, projectPath, commitHash)
        }

    override suspend fun restore(projectPath: Path, commitHash: String, sessionId: String?) =
        mutexFor(projectPath).withLock {
            withContext(Dispatchers.IO) {
                val snapshotRepoDir = getSnapshotRepoDir(projectPath)
                if (!isRepoInitialized(snapshotRepoDir)) {
                    throw IllegalStateException("Snapshot repo not initialized for: $projectPath")
                }

                // Step 1: Capture current working tree files BEFORE read-tree changes the index.
                // We walk the filesystem because ls-files only shows tracked files,
                // but we need to find files that should be deleted (they may be untracked).
                val currentFiles = listWorkingTreeFiles(projectPath)

                // Step 2: Restore tracked files to the target tree state
                // read-tree sets the index to match the target tree
                runGit(snapshotRepoDir, projectPath, "read-tree", commitHash)
                // checkout-index writes all index entries to the working tree
                runGit(snapshotRepoDir, projectPath, "checkout-index", "-a", "-f")

                // Step 3: Delete files that exist in working tree but not in target tree
                val targetFiles = runGitQuiet(snapshotRepoDir, projectPath, "ls-tree", "-r", "--name-only", commitHash)
                    .lines().filter { it.isNotBlank() }.toSet()

                val filesToDelete = currentFiles - targetFiles
                for (file in filesToDelete) {
                    val filePath = projectPath.resolve(file)
                    if (Files.exists(filePath)) {
                        Files.delete(filePath)
                        logger.debug("Deleted file {} (not in target tree)", file)
                    }
                }

                // Clean up empty directories left after file deletion
                cleanEmptyDirectories(projectPath, filesToDelete)

                // Sync the session-specific index to match the restored tree
                if (sessionId != null) {
                    val env = sessionGitEnv(snapshotRepoDir, sessionId)
                    runGit(snapshotRepoDir, projectPath, env, "read-tree", commitHash)
                }

                logger.info("Restored project to commit hash {} (deleted {} files)", commitHash, filesToDelete.size)
            }
        }

    override suspend fun restoreFile(projectPath: Path, commitHash: String, filePath: String, sessionId: String?) =
        mutexFor(projectPath).withLock {
            withContext(Dispatchers.IO) {
                val snapshotRepoDir = getSnapshotRepoDir(projectPath)
                if (!isRepoInitialized(snapshotRepoDir)) {
                    throw IllegalStateException("Snapshot repo not initialized for: $projectPath")
                }

                // git checkout <commitHash> -- <filePath>
                runGit(snapshotRepoDir, projectPath, "checkout", commitHash, "--", filePath)

                // Sync the session-specific index to reflect the restored file
                if (sessionId != null) {
                    val env = sessionGitEnv(snapshotRepoDir, sessionId)
                    runGit(snapshotRepoDir, projectPath, env, "add", "--", filePath)
                }

                logger.info("Restored file {} to commit hash {}", filePath, commitHash)
            }
        }

    override suspend fun stageFileRemoval(projectPath: Path, filePath: String, sessionId: String?) =
        mutexFor(projectPath).withLock {
            withContext(Dispatchers.IO) {
                val snapshotRepoDir = getSnapshotRepoDir(projectPath)

                // Remove file from session index (safe even if file is not tracked)
                if (sessionId != null) {
                    val env = sessionGitEnv(snapshotRepoDir, sessionId)
                    runGit(snapshotRepoDir, projectPath, env, "rm", "--cached", "--force", "--", filePath)
                } else {
                    runGit(snapshotRepoDir, projectPath, "rm", "--cached", "--force", "--", filePath)
                }

                // Remove from working tree
                val fileToDelete = projectPath.resolve(filePath)
                java.nio.file.Files.deleteIfExists(fileToDelete)

                logger.info("Staged file removal {} (sessionId={})", filePath, sessionId)
            }
        }

    // ==================== Checkpoint Persistence ====================

    override suspend fun saveCheckpoint(projectPath: Path, sessionId: String, checkpoint: GitCheckpoint) =
        mutexFor(projectPath).withLock {
            withContext(Dispatchers.IO) {
                val snapshotRepoDir = getSnapshotRepoDir(projectPath)
                Files.createDirectories(snapshotRepoDir)
                val checkpointFile = snapshotRepoDir.resolve("checkpoints-$sessionId.json")

                // Load existing checkpoints
                val existing = if (Files.exists(checkpointFile)) {
                    try {
                        objectMapper.readValue<List<GitCheckpoint>>(checkpointFile.toFile())
                    } catch (e: Exception) {
                        logger.warn("Failed to read checkpoint file, starting fresh: {}", e.message)
                        emptyList()
                    }
                } else {
                    emptyList()
                }

                // Prepend new checkpoint (newest first)
                val updated = listOf(checkpoint) + existing
                objectMapper.writeValue(checkpointFile.toFile(), updated)
                logger.trace("Saved checkpoint commitHash={} for session={}", checkpoint.commitHash, sessionId)
            }
        }

    override suspend fun listCheckpoints(projectPath: Path, sessionId: String): List<GitCheckpoint> =
        withContext(Dispatchers.IO) {
            val snapshotRepoDir = getSnapshotRepoDir(projectPath)
            if (!isRepoInitialized(snapshotRepoDir)) return@withContext emptyList()

            val checkpointFile = snapshotRepoDir.resolve("checkpoints-$sessionId.json")
            if (!Files.exists(checkpointFile)) return@withContext emptyList()

            try {
                objectMapper.readValue<List<GitCheckpoint>>(checkpointFile.toFile())
            } catch (e: Exception) {
                logger.warn("Failed to read checkpoints for session {}: {}", sessionId, e.message)
                emptyList()
            }
        }

    // ==================== Revert State ====================

    override suspend fun saveRevertState(projectPath: Path, sessionId: String, state: RevertState) =
        withContext(Dispatchers.IO) {
            val snapshotRepoDir = getSnapshotRepoDir(projectPath)
            Files.createDirectories(snapshotRepoDir)
            val stateFile = snapshotRepoDir.resolve("revert-$sessionId.json")
            objectMapper.writeValue(stateFile.toFile(), state)
            logger.info("Saved revert state for session {}", sessionId)
        }

    override suspend fun loadRevertState(projectPath: Path, sessionId: String): RevertState? =
        withContext(Dispatchers.IO) {
            val snapshotRepoDir = getSnapshotRepoDir(projectPath)
            val stateFile = snapshotRepoDir.resolve("revert-$sessionId.json")
            if (Files.exists(stateFile)) {
                objectMapper.readValue<RevertState>(stateFile.toFile())
            } else {
                null
            }
        }

    override suspend fun clearRevertState(projectPath: Path, sessionId: String) =
        withContext(Dispatchers.IO) {
            val snapshotRepoDir = getSnapshotRepoDir(projectPath)
            val stateFile = snapshotRepoDir.resolve("revert-$sessionId.json")
            Files.deleteIfExists(stateFile)
            logger.info("Cleared revert state for session {}", sessionId)
        }

    // ==================== File Review State ====================

    override suspend fun saveFileReviewState(projectPath: Path, sessionId: String, state: FileReviewState) =
        mutexFor(projectPath).withLock {
            withContext(Dispatchers.IO) {
                val snapshotRepoDir = getSnapshotRepoDir(projectPath)
                Files.createDirectories(snapshotRepoDir)
                val stateFile = snapshotRepoDir.resolve("file-review-$sessionId.json")
                objectMapper.writeValue(stateFile.toFile(), state)
                logger.info("Saved file review state for session {}", sessionId)
            }
        }

    override suspend fun loadFileReviewState(projectPath: Path, sessionId: String): FileReviewState? =
        mutexFor(projectPath).withLock {
            withContext(Dispatchers.IO) {
                val snapshotRepoDir = getSnapshotRepoDir(projectPath)
                val stateFile = snapshotRepoDir.resolve("file-review-$sessionId.json")
                if (Files.exists(stateFile)) {
                    objectMapper.readValue<FileReviewState>(stateFile.toFile())
                } else {
                    null
                }
            }
        }

    override suspend fun clearFileReviewState(projectPath: Path, sessionId: String) =
        mutexFor(projectPath).withLock {
            withContext(Dispatchers.IO) {
                val snapshotRepoDir = getSnapshotRepoDir(projectPath)
                val stateFile = snapshotRepoDir.resolve("file-review-$sessionId.json")
                Files.deleteIfExists(stateFile)
                logger.info("Cleared file review state for session {}", sessionId)
            }
        }

    override suspend fun atomicUpdateFileReviewState(
        projectPath: Path,
        sessionId: String,
        reviews: Map<String, String>
    ): FileReviewState =
        mutexFor(projectPath).withLock {
            withContext(Dispatchers.IO) {
                val snapshotRepoDir = getSnapshotRepoDir(projectPath)
                Files.createDirectories(snapshotRepoDir)
                val stateFile = snapshotRepoDir.resolve("file-review-$sessionId.json")

                // Load current state inside mutex (prevents lost updates)
                val currentState = if (Files.exists(stateFile)) {
                    objectMapper.readValue<FileReviewState>(stateFile.toFile())
                } else {
                    FileReviewState(reviews = emptyMap())
                }

                // Merge new reviews into existing state
                val updatedReviews = currentState.reviews + reviews
                val newState = currentState.copy(
                    reviews = updatedReviews,
                    updatedAt = System.currentTimeMillis()
                )

                objectMapper.writeValue(stateFile.toFile(), newState)
                logger.info("Atomically updated file review state for session {} ({} files)", sessionId, reviews.size)
                newState
            }
        }

    override suspend fun invalidateFileReviews(
        projectPath: Path,
        sessionId: String,
        filePaths: Set<String>
    ): FileReviewState? =
        mutexFor(projectPath).withLock {
            withContext(Dispatchers.IO) {
                val snapshotRepoDir = getSnapshotRepoDir(projectPath)
                val stateFile = snapshotRepoDir.resolve("file-review-$sessionId.json")
                if (!Files.exists(stateFile)) return@withContext null

                val currentState = objectMapper.readValue<FileReviewState>(stateFile.toFile())
                val keysToRemove = filePaths.intersect(currentState.reviews.keys)
                if (keysToRemove.isEmpty()) return@withContext currentState

                val newState = currentState.copy(
                    reviews = currentState.reviews - keysToRemove,
                    updatedAt = System.currentTimeMillis()
                )
                objectMapper.writeValue(stateFile.toFile(), newState)
                logger.info("Invalidated {} file review entries for session {}", keysToRemove.size, sessionId)
                newState
            }
        }

    // ==================== Crash Recovery ====================

    override suspend fun getStagedChanges(projectPath: Path, sessionId: String): List<FileDiff> =
        withContext(Dispatchers.IO) {
            val snapshotRepoDir = getSnapshotRepoDir(projectPath)
            if (!isRepoInitialized(snapshotRepoDir)) return@withContext emptyList()

            // Check if there are staged changes (index differs from baseline commit)
            // We use the baseline commit hash stored for this session as the comparison base
            val baselineFile = getBaselineFile(snapshotRepoDir, sessionId)
            if (!Files.exists(baselineFile)) {
                logger.debug("No baseline found for session {}, skipping staged changes", sessionId)
                return@withContext emptyList()
            }

            val baselineCommitHash = Files.readString(baselineFile).trim()
            if (baselineCommitHash.isBlank()) return@withContext emptyList()

            // Stage current changes and compare using session-specific index
            val env = sessionGitEnv(snapshotRepoDir, sessionId)
            runGitQuiet(snapshotRepoDir, projectPath, env, "add", "-A")
            val currentTreeHash = try {
                runGitQuiet(snapshotRepoDir, projectPath, env, "write-tree").trim()
            } catch (_: Exception) {
                return@withContext emptyList()
            }

            if (currentTreeHash.isBlank()) {
                return@withContext emptyList()
            }

            // Skip diff if working tree is identical to baseline
            val baselineTreeHash = try {
                runGitQuiet(snapshotRepoDir, projectPath, "rev-parse", "$baselineCommitHash^{tree}").trim()
            } catch (_: Exception) { null }
            if (currentTreeHash == baselineTreeHash) {
                logger.trace("No working tree changes from baseline for session={}", sessionId)
                return@withContext emptyList()
            }

            // git diff handles mixed commit/tree hashes natively
            diffWithPatches(snapshotRepoDir, projectPath, baselineCommitHash, currentTreeHash)
        }

    // ==================== Session Lifecycle ====================

    override suspend fun cleanupSession(projectPath: Path, sessionId: String) =
        withContext(Dispatchers.IO) {
            val snapshotRepoDir = getSnapshotRepoDir(projectPath)
            if (!Files.exists(snapshotRepoDir)) return@withContext

            val sessionFiles = listOf(
                getSessionIndexFile(snapshotRepoDir, sessionId),
                getBaselineFile(snapshotRepoDir, sessionId),
                snapshotRepoDir.resolve("checkpoints-$sessionId.json"),
                snapshotRepoDir.resolve("revert-$sessionId.json"),
                snapshotRepoDir.resolve("file-review-$sessionId.json"),
                snapshotRepoDir.resolve("last-track-$sessionId.hash")
            )

            var deletedCount = 0
            for (file in sessionFiles) {
                if (Files.deleteIfExists(file)) {
                    deletedCount++
                    logger.debug("Cleaned up session file: {}", file.fileName)
                }
            }

            // Clean up session ref
            try {
                if (isRepoInitialized(snapshotRepoDir)) {
                    runGitQuiet(snapshotRepoDir, projectPath, "update-ref", "-d", "refs/easyai/session-$sessionId")
                    logger.debug("Deleted session ref refs/easyai/session-{}", sessionId)
                }
            } catch (e: Exception) {
                logger.debug("Failed to delete session ref: {}", e.message)
            }

            if (deletedCount > 0) {
                logger.info("Cleaned up {} session files for session {}", deletedCount, sessionId)
            }
        }

    // ==================== Session Tracking State ====================

    override suspend fun getLastTrackedHash(projectPath: Path, sessionId: String): String? =
        withContext(Dispatchers.IO) {
            val snapshotRepoDir = getSnapshotRepoDir(projectPath)
            val hashFile = snapshotRepoDir.resolve("last-track-$sessionId.hash")
            if (Files.exists(hashFile)) {
                Files.readString(hashFile).trim().ifBlank { null }
            } else {
                null
            }
        }

    override suspend fun saveLastTrackedHash(projectPath: Path, sessionId: String, commitHash: String) =
        withContext(Dispatchers.IO) {
            val snapshotRepoDir = getSnapshotRepoDir(projectPath)
            Files.createDirectories(snapshotRepoDir)
            val hashFile = snapshotRepoDir.resolve("last-track-$sessionId.hash")
            Files.writeString(hashFile, commitHash)
            logger.debug("Saved last tracked commitHash={} for session={}", commitHash, sessionId)
        }

    override suspend fun resolveSessionRef(projectPath: Path, sessionId: String): String? =
        withContext(Dispatchers.IO) {
            val snapshotRepoDir = getSnapshotRepoDir(projectPath)
            if (!isRepoInitialized(snapshotRepoDir)) return@withContext null
            try {
                val hash = runGitQuiet(snapshotRepoDir, projectPath, "rev-parse", "refs/easyai/session-$sessionId").trim()
                hash.ifBlank { null }
            } catch (_: Exception) {
                null
            }
        }

    // ==================== Commit History ====================

    override suspend fun listCommitsWithDiffs(
        projectPath: Path,
        sessionId: String
    ): List<CommitChangeInfo> =
        withContext(Dispatchers.IO) {
            val snapshotRepoDir = getSnapshotRepoDir(projectPath)
            if (!isRepoInitialized(snapshotRepoDir)) return@withContext emptyList()

            val sessionRef = "refs/easyai/session-$sessionId"

            // Check if the ref exists
            val refExists = try {
                val hash = runGitQuiet(snapshotRepoDir, projectPath, "rev-parse", sessionRef).trim()
                hash.isNotBlank()
            } catch (_: Exception) {
                false
            }
            if (!refExists) return@withContext emptyList()

            // Walk the commit chain: format = hash|authorEmail|subject|timestamp
            val logOutput = runGitQuiet(
                snapshotRepoDir, projectPath,
                "log", "--format=%H|%ae|%s|%at", "--reverse", sessionRef
            )
            if (logOutput.isBlank()) return@withContext emptyList()

            val commits = mutableListOf<CommitChangeInfo>()
            val lines = logOutput.lines().filter { it.isNotBlank() }

            for (line in lines) {
                val parts = line.split("|", limit = 4)
                if (parts.size < 4) continue

                val commitHash = parts[0]
                val authorEmail = parts[1]
                val message = parts[2]
                val timestampSec = parts[3].toLongOrNull() ?: 0L

                val author = if (authorEmail == ChangeAuthor.LLM_AGENT.gitAuthorEmail) "llm" else "user"

                // Get per-file diff for this commit (against its parent)
                val files = try {
                    diffWithPatches(snapshotRepoDir, projectPath, "$commitHash~1", commitHash)
                } catch (_: Exception) {
                    // Root commit (no parent) — diff against the well-known empty tree
                    try {
                        diffWithPatches(snapshotRepoDir, projectPath, EMPTY_TREE_HASH, commitHash)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }

                commits.add(
                    CommitChangeInfo(
                        commitHash = commitHash,
                        author = author,
                        message = message,
                        timestamp = timestampSec * 1000,
                        files = files.map { it.copy(changedBy = author) }
                    )
                )
            }

            commits
        }

    override suspend fun determineFileAuthors(
        projectPath: Path,
        fromCommitHash: String,
        toCommitHash: String
    ): Map<String, String> =
        withContext(Dispatchers.IO) {
            val snapshotRepoDir = getSnapshotRepoDir(projectPath)
            if (!isRepoInitialized(snapshotRepoDir)) return@withContext emptyMap()

            // Walk commits in the range (from, to], ordered oldest-first.
            // Format: COMMIT_START:<author-email> marks each commit boundary,
            // followed by --name-only file paths.
            val output = runGitQuiet(
                snapshotRepoDir, projectPath,
                "log", "--format=COMMIT_START:%ae", "--name-only", "-M", "--reverse",
                "$fromCommitHash..$toCommitHash"
            )
            if (output.isBlank()) return@withContext emptyMap()

            val result = mutableMapOf<String, String>()
            var currentAuthor: String? = null

            for (line in output.lines()) {
                if (line.startsWith("COMMIT_START:")) {
                    val email = line.substring("COMMIT_START:".length).trim()
                    currentAuthor = if (email == ChangeAuthor.LLM_AGENT.gitAuthorEmail) "llm" else "user"
                } else if (line.isNotBlank() && currentAuthor != null) {
                    result[decodeGitPath(line)] = currentAuthor
                }
            }

            result
        }

    override suspend fun extractFileContent(
        projectPath: Path,
        commitHash: String,
        filePath: String
    ): ByteArray? =
        withContext(Dispatchers.IO) {
            val snapshotRepoDir = getSnapshotRepoDir(projectPath)
            try {
                val gitDir = snapshotRepoDir.resolve(".git").toAbsolutePath()
                val result = ProcessExecutor.execute(
                    command = listOf(
                        "git", "--git-dir=$gitDir",
                        "show", "$commitHash:$filePath"
                    ),
                    workDir = projectPath.toAbsolutePath(),
                    timeoutSeconds = GIT_TIMEOUT_SECONDS
                )
                if (result != null && result.exitCode == 0) result.stdout.toByteArray() else null
            } catch (_: Exception) {
                null
            }
        }

    // ==================== Internal Helpers ====================

    /**
     * Get the snapshot repo directory for a project.
     * Uses the project folder name + last 6 hex chars of SHA-256 for readability and uniqueness.
     */
    internal fun getSnapshotRepoDir(projectPath: Path): Path {
        val folderName = projectPath.toAbsolutePath().let { it.fileName?.toString() ?: it.toString() }
        val sanitized = folderName.replace(Regex("[^a-zA-Z0-9]"), "-")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(projectPath.toAbsolutePath().toString().toByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }
        val hashSuffix = hex.substring(hex.length - 6)
        return dataDir.resolve("snapshots").resolve("$sanitized-$hashSuffix")
    }

    /**
     * Get the per-session index file path.
     * Each session uses an independent Git index to prevent cross-session data leakage.
     */
    private fun getSessionIndexFile(snapshotRepoDir: Path, sessionId: String): Path {
        return snapshotRepoDir.resolve("index-$sessionId")
    }

    /**
     * Build the environment map for git commands using a session-specific index.
     */
    private fun sessionGitEnv(snapshotRepoDir: Path, sessionId: String): Map<String, String> {
        return mapOf("GIT_INDEX_FILE" to getSessionIndexFile(snapshotRepoDir, sessionId).toAbsolutePath().toString())
    }

    /**
     * Internal commit helper that does NOT acquire the repo mutex.
     * Used by [ensureBaseline] which already holds the lock.
     */
    private suspend fun commitAsInternal(
        snapshotRepoDir: Path,
        projectPath: Path,
        sessionId: String,
        author: ChangeAuthor,
        message: String
    ): String {
        ensureRepoInitialized(snapshotRepoDir, projectPath)
        ensureGitIgnore(snapshotRepoDir, projectPath)

        val env = sessionGitEnv(snapshotRepoDir, sessionId)

        // Stage all working tree changes
        runGit(snapshotRepoDir, projectPath, env, "add", "-A")

        // Write tree object
        val treeHash = runGitQuiet(snapshotRepoDir, projectPath, env, "write-tree").trim()
        if (treeHash.isBlank()) {
            throw RuntimeException("git write-tree returned empty hash")
        }

        // Get parent commit for this session (if any)
        val parentRef = "refs/easyai/session-$sessionId"
        val parentHash = try {
            runGitQuiet(snapshotRepoDir, projectPath, "rev-parse", parentRef).trim()
                .takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }

        // Skip creating a redundant commit if tree is identical to parent
        if (parentHash != null) {
            val parentTreeHash = try {
                runGitQuiet(snapshotRepoDir, projectPath, "rev-parse", "$parentHash^{tree}").trim()
            } catch (_: Exception) { null }
            if (treeHash == parentTreeHash) {
                logger.trace("No working tree changes for session={}, skipping commit (internal)", sessionId)
                return parentHash
            }
        }

        val parentArgs = if (parentHash != null) arrayOf("-p", parentRef) else emptyArray()

        // Create commit with specified author
        val commitEnv = env + mapOf(
            "GIT_AUTHOR_NAME" to author.gitAuthorName,
            "GIT_AUTHOR_EMAIL" to author.gitAuthorEmail,
            "GIT_COMMITTER_NAME" to author.gitAuthorName,
            "GIT_COMMITTER_EMAIL" to author.gitAuthorEmail
        )
        val commitHash = runGitQuiet(
            snapshotRepoDir, projectPath, commitEnv,
            "commit-tree", treeHash, *parentArgs, "-m", message
        ).trim()

        if (commitHash.isBlank()) {
            throw RuntimeException("git commit-tree returned empty hash")
        }

        // Update session ref
        runGit(snapshotRepoDir, projectPath, "update-ref", parentRef, commitHash)

        logger.debug("Committed as {}: commitHash={}", author, commitHash)
        return commitHash
    }

    private fun getBaselineFile(snapshotRepoDir: Path, sessionId: String): Path {
        return snapshotRepoDir.resolve("baseline-$sessionId.tree")
    }

    private suspend fun isRepoInitialized(snapshotRepoDir: Path): Boolean {
        val gitDir = snapshotRepoDir.resolve(".git")
        if (!Files.exists(gitDir)) return false
        return try {
            val result = ProcessExecutor.execute(
                command = listOf("git", "--git-dir=${gitDir.toAbsolutePath()}", "rev-parse", "--git-dir"),
                workDir = snapshotRepoDir,
                timeoutSeconds = 5L
            )
            result != null && result.exitCode == 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Initialize the independent Git repo if not already done.
     */
    private suspend fun ensureRepoInitialized(snapshotRepoDir: Path, projectPath: Path) {
        if (isRepoInitialized(snapshotRepoDir)) return

        val gitDir = snapshotRepoDir.resolve(".git")
        if (Files.exists(gitDir)) {
            logger.warn("Found broken snapshot repo at {}, re-initializing", snapshotRepoDir)
            deleteRecursively(gitDir)
        }

        Files.createDirectories(snapshotRepoDir)
        runGitRaw(snapshotRepoDir, "git", "init")

        val gitConfig = listOf(
            "core.worktree" to projectPath.toAbsolutePath().toString(),
            "core.bare" to "false",
            "user.name" to "EasyAI Snapshot",
            "user.email" to "snapshot@easyai.dev"
        )
        for ((key, value) in gitConfig) {
            runGitRaw(snapshotRepoDir, "git", "config", "-f", "${gitDir.toAbsolutePath()}/config", key, value)
        }

        // Create initial empty commit so HEAD exists for diff operations
        runGitRaw(snapshotRepoDir, "git", "--git-dir=${gitDir.toAbsolutePath()}",
            "--work-tree=${projectPath.toAbsolutePath()}",
            "commit", "--allow-empty", "-m", "init: snapshot repo")

        logger.info("Initialized snapshot repo for project: {}", projectPath)
    }

    /**
     * Create or update the .gitignore in the snapshot repo to exclude common build/dependency dirs.
     * If the exclude file already exists but its content is outdated (e.g. EXCLUDED_DIRS was extended),
     * rewrite it and untrack any already-indexed files that should now be excluded.
     */
    private suspend fun ensureGitIgnore(snapshotRepoDir: Path, projectPath: Path) {
        val gitDir = snapshotRepoDir.resolve(".git")
        val infoDir = gitDir.resolve("info")
        Files.createDirectories(infoDir)
        val excludeFile = infoDir.resolve("exclude")

        val content = buildString {
            appendLine("# Auto-generated by EasyAI Snapshot")
            for (dir in EXCLUDED_DIRS) {
                appendLine(dir)
            }
            appendLine("*.class")
            appendLine("*.jar")
            appendLine("*.war")
            appendLine("*.ear")
            appendLine("*.pyc")
            appendLine("*.pyo")
        }

        val needsUpdate = if (!Files.exists(excludeFile)) {
            true
        } else {
            Files.readString(excludeFile) != content
        }

        if (needsUpdate) {
            Files.writeString(excludeFile, content)
            // Untrack already-indexed files that should now be excluded.
            // git rm --cached removes files from the index but keeps them on disk.
            for (dir in EXCLUDED_DIRS) {
                runGitQuiet(snapshotRepoDir, projectPath, "rm", "--cached", "-r", "--ignore-unmatch", dir)
            }
            logger.info("Updated snapshot exclude file and untracked excluded dirs for project: {}", projectPath)
        }
    }

    private suspend fun runGit(snapshotRepoDir: Path, workTree: Path, vararg args: String): String =
        runGit(snapshotRepoDir, workTree, emptyMap(), *args)

    private suspend fun runGit(
        snapshotRepoDir: Path, workTree: Path,
        env: Map<String, String>,
        vararg args: String
    ): String {
        val gitDir = snapshotRepoDir.resolve(".git").toAbsolutePath().toString()
        val command = listOf("git", "--git-dir=$gitDir", "--work-tree=${workTree.toAbsolutePath()}") + args
        return runGitRaw(snapshotRepoDir, env, *command.toTypedArray())
    }

    private suspend fun runGitQuiet(snapshotRepoDir: Path, workTree: Path, vararg args: String): String =
        runGitQuiet(snapshotRepoDir, workTree, emptyMap(), *args)

    private suspend fun runGitQuiet(
        snapshotRepoDir: Path, workTree: Path,
        env: Map<String, String>,
        vararg args: String
    ): String {
        return try {
            runGit(snapshotRepoDir, workTree, env, *args)
        } catch (e: Exception) {
            logger.debug("Git command returned error: {}", e.message)
            ""
        }
    }

    private suspend fun runGitRaw(workDir: Path, vararg command: String): String =
        runGitRaw(workDir, emptyMap(), *command)

    private suspend fun runGitRaw(
        workDir: Path,
        env: Map<String, String>,
        vararg command: String
    ): String {
        logger.trace("Executing git command: {} (workDir={})", command.joinToString(" "), workDir)
        val result = ProcessExecutor.execute(
            command = command.toList(),
            workDir = workDir,
            timeoutSeconds = GIT_TIMEOUT_SECONDS,
            env = env
        )

        if (result == null) {
            throw RuntimeException("Git command timed out: ${command.joinToString(" ")}")
        }

        if (result.exitCode != 0) {
            logger.trace("Git command failed (exit={}): {} | stderr: {}", result.exitCode, command.joinToString(" "), result.stderr)
            throw RuntimeException("Git command failed (exit=${result.exitCode}): ${result.stderr}")
        }

        return result.stdout
    }

    /**
     * Decode a git C-quoted path, handling:
     * - Rename format: `"old" => "new"` → extract new path
     * - Surrounding double quotes: `"path"` → `path`
     * - Octal escapes: `\NNN` → UTF-8 bytes → string (e.g. Chinese characters)
     * - Standard escapes: `\\`, `\"`, `\n`, `\t`
     */
    private fun decodeGitPath(raw: String): String {
        var path = raw

        // Handle rename format: "old" => "new" or old => new
        val renameIdx = path.indexOf(" => ")
        if (renameIdx >= 0) {
            path = path.substring(renameIdx + 4)
        }

        // Strip surrounding quotes
        if (path.length >= 2 && path.startsWith("\"") && path.endsWith("\"")) {
            path = path.substring(1, path.length - 1)
        }

        // Decode escape sequences
        if ('\\' !in path) return path

        val bytes = mutableListOf<Byte>()
        var i = 0
        while (i < path.length) {
            if (path[i] == '\\' && i + 1 < path.length) {
                val next = path[i + 1]
                when {
                    next in '0'..'7' && i + 3 < path.length
                            && path[i + 2] in '0'..'7'
                            && path[i + 3] in '0'..'7' -> {
                        // Octal escape: \NNN → single byte
                        val octal = path.substring(i + 1, i + 4)
                        bytes.add(octal.toInt(8).toByte())
                        i += 4
                    }
                    next == '\\' -> { bytes.add('\\'.code.toByte()); i += 2 }
                    next == '"'  -> { bytes.add('"'.code.toByte());  i += 2 }
                    next == 'n'  -> { bytes.add('\n'.code.toByte()); i += 2 }
                    next == 't'  -> { bytes.add('\t'.code.toByte()); i += 2 }
                    else -> { bytes.add(path[i].code.toByte()); i++ }
                }
            } else {
                bytes.add(path[i].code.toByte())
                i++
            }
        }

        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        if (Files.isDirectory(path)) {
            Files.list(path).use { stream ->
                stream.forEach { deleteRecursively(it) }
            }
        }
        Files.delete(path)
    }

    /**
     * List all files in the working tree as relative paths, excluding snapshot-internal dirs
     * and common build/dependency directories.
     */
    private fun listWorkingTreeFiles(projectPath: Path): Set<String> {
        if (!Files.isDirectory(projectPath)) return emptySet()
        val result = mutableSetOf<String>()
        Files.walk(projectPath).use { stream ->
            for (path in stream) {
                if (path == projectPath) continue
                val relative = projectPath.relativize(path).toString()
                // Skip excluded top-level directories
                val firstSegment = relative.split('/', java.io.File.separatorChar).first()
                if (firstSegment in EXCLUDED_DIRS) continue
                if (Files.isRegularFile(path)) {
                    result.add(relative)
                }
            }
        }
        return result
    }

    /**
     * Remove empty directories left after file deletion.
     * Walks up from each deleted file's parent directory.
     */
    private fun cleanEmptyDirectories(projectPath: Path, deletedFiles: Set<String>) {
        val parentDirs = deletedFiles.mapNotNull { file ->
            val parent = Path.of(file).parent?.toString()
            parent
        }.distinct().sortedByDescending { it.length }

        for (dir in parentDirs) {
            val dirPath = projectPath.resolve(dir)
            if (Files.isDirectory(dirPath) && Files.list(dirPath).use { it.findAny().isEmpty }) {
                Files.delete(dirPath)
                logger.debug("Deleted empty directory: {}", dir)
            }
        }
    }

    /**
     * Get diff between two tree objects with patches.
     */
    private suspend fun diffWithPatches(snapshotRepoDir: Path, projectPath: Path, vararg diffArgs: String): List<FileDiff> {
        val statOutput = runGitQuiet(
            snapshotRepoDir, projectPath,
            *arrayOf("diff", "-M", "--numstat") + diffArgs
        )
        if (statOutput.isBlank()) return emptyList()
        val nameStatusOutput = runGitQuiet(
            snapshotRepoDir, projectPath,
            *arrayOf("diff", "-M", "--name-status") + diffArgs
        )
        val diffs = parseNumstat(statOutput, nameStatusOutput)
        val patchOutput = runGitQuiet(
            snapshotRepoDir, projectPath,
            *arrayOf("diff", "--no-color") + diffArgs
        )
        val patchesByFile = parsePatchByFile(patchOutput)
        return diffs
            .filter { it.path.substringBefore('/') !in EXCLUDED_DIRS }
            .map { it.copy(patch = patchesByFile[it.path]) }
    }

    /**
     * Parse `git diff --numstat` output into FileDiff list.
     */
    private fun parseNumstat(output: String, nameStatusOutput: String = ""): List<FileDiff> {
        val statusMap = if (nameStatusOutput.isNotBlank()) {
            nameStatusOutput.lines()
                .filter { it.isNotBlank() }
                .associate { line ->
                    val parts = line.split("\t")
                    val statusChar = parts[0].firstOrNull() ?: 'M'
                    val path = decodeGitPath(when (statusChar) {
                        'D' -> parts.getOrElse(1) { "" }
                        'R' -> parts.getOrElse(3) { parts.getOrElse(1) { "" } }
                        else -> parts.getOrElse(1) { "" }
                    })
                    path to statusChar
                }
        } else {
            emptyMap()
        }

        return output.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("\t")
                if (parts.size < 3) return@mapNotNull null
                val additions = parts[0].toIntOrNull() ?: 0
                val deletions = parts[1].toIntOrNull() ?: 0
                val path = decodeGitPath(parts[2])

                val status = when (statusMap[path]) {
                    'A' -> FileChangeStatus.ADDED
                    'D' -> FileChangeStatus.DELETED
                    'R' -> FileChangeStatus.RENAMED
                    else -> FileChangeStatus.MODIFIED
                }

                FileDiff(path = path, additions = additions, deletions = deletions, status = status)
            }
    }

    /**
     * Parse `git diff` output into per-file patches.
     *
     * Handles both normal headers (`diff --git a/path b/path`) and
     * combined-rename headers (`diff --git a/dir/{old => new}/file b/dir/new/file`)
     * by extracting the actual new-path from the `b/` side.
     * 
     * Git quotes paths with non-ASCII characters:
     * - Unquoted: diff --git a/path b/path
     * - Quoted:   diff --git "a/path" "b/path"  (quotes wrap entire a/... and b/...)
     */
    private fun parsePatchByFile(output: String): Map<String, String> {
        if (output.isBlank()) return emptyMap()

        val result = mutableMapOf<String, String>()
        val chunks = output.split(Regex("(?=^diff --git )", RegexOption.MULTILINE))

        for (chunk in chunks) {
            if (chunk.isBlank()) continue
            // Match diff header - handle both quoted and unquoted paths
            // Unquoted: diff --git a/path b/path
            // Quoted:   diff --git "a/path" "b/path"
            // The regex handles both formats with optional quotes around each path
            val headerMatch = Regex("^diff --git \"?a/(.+?)\"? \"?b/(.+?)\"?(?:\r?\n|$)", RegexOption.MULTILINE)
                .find(chunk)
            if (headerMatch != null) {
                var rawBPath = headerMatch.groupValues[2].trim()
                // Handle quoted paths: strip surrounding quotes if present
                if (rawBPath.startsWith("\"") && rawBPath.endsWith("\"")) {
                    rawBPath = rawBPath.substring(1, rawBPath.length - 1)
                }
                val filePath = decodeGitPath(extractNewPath(rawBPath))
                result[filePath] = chunk.trim()
            }
        }

        return result
    }

    /**
     * Extract the actual new file path from a git diff `b/` side.
     *
     * Git uses `{old => new}` curly-brace notation for combined rename diffs,
     * e.g. `dir/{old => new}/file.txt` should yield `dir/new/file.txt`.
     * Non-rename paths are returned unchanged.
     */
    private fun extractNewPath(bPath: String): String {
        val braceStart = bPath.indexOf('{')
        val arrowIdx = bPath.indexOf(" => ")
        if (braceStart < 0 || arrowIdx < 0 || arrowIdx < braceStart) return bPath

        val braceEnd = bPath.indexOf('}', arrowIdx)
        if (braceEnd < 0) return bPath

        // Extract the "new" portion from "{old => new}"
        val newPart = bPath.substring(arrowIdx + 4, braceEnd).trim()
        return bPath.substring(0, braceStart) + newPart + bPath.substring(braceEnd + 1)
    }
}
