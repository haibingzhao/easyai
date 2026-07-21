package com.easy.easyai.snapshot

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentEventListener
import com.easy.easyai.core.event.*
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.UserMessage
import com.easy.easyai.snapshot.model.FileDiff
import com.easy.easyai.snapshot.model.GitCheckpoint
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * AgentEventListener that provides real-time file change visibility during agent execution.
 *
 * Uses synchronous batch hooks (called by AgentLoop) to attribute changes to either
 * the LLM agent or the user:
 *
 * - [beforeToolExecutionBatch]: Commits pending user changes as `ChangeAuthor.USER`,
 *   then establishes a baseline for LLM tracking.
 * - [afterToolExecutionBatch]: Commits tool-execution changes as `ChangeAuthor.LLM_AGENT`,
 *   computes diff, and pushes a checkpoint event for immediate UI feedback.
 * - On [AgentEndEvent]: Creates a final [GitCheckpoint] with the commit hash and
 *   pushes a checkpoint event for revert/rollback operations.
 *
 * @param snapshotService The snapshot service for Git operations
 */
class SnapshotEventListener(
    private val snapshotService: SnapshotService
) : AgentEventListener {

    private val logger = LoggerFactory.getLogger(javaClass)

    /** Tracks whether any file-modifying tool was used per session (thread-safe). */
    private val sessionFileModifications = ConcurrentHashMap<String, Boolean>()

    override suspend fun handle(agentContext: AgentContext, event: AgentEvent, push: suspend (AgentEvent) -> Unit) {
        // Guard: skip all snapshot operations if not enabled for this project
        val projectPath = agentContext.projectPath
        if (projectPath == null || !snapshotService.isEnabled(projectPath)) return

        when (event) {
            is AgentEndEvent -> handleAgentEnd(agentContext, event, push)
            else -> { /* ignore other events */ }
        }
    }

    /**
     * Synchronous pre-execution hook called by AgentLoop BEFORE tools run.
     * Commits pending user changes and establishes baseline for LLM tracking.
     *
     * This replaces the previous async handling of ToolBatchStartEvent, which had a
     * race condition: the event was pushed to an async Channel, so tools could modify
     * files before this listener processed the event. Now the work is done synchronously
     * in the agent loop, guaranteeing correct ordering.
     */
    override suspend fun beforeToolExecutionBatch(
        agentContext: AgentContext,
        toolCallIds: List<String>
    ) {
        val projectPath = agentContext.projectPath ?: return
        val sessionId = agentContext.sessionId ?: return

        try {
            // Commit user changes that happened since the last batch end
            snapshotService.commitAs(projectPath, sessionId, ChangeAuthor.USER, "user-changes")
        } catch (e: Exception) {
            logger.debug("No user changes to commit before batch: {}", e.message)
        }

        try {
            // Establish baseline for LLM change tracking
            snapshotService.ensureBaseline(projectPath, sessionId)
        } catch (e: Exception) {
            logger.warn("Failed to ensure baseline snapshot for project={}, session={}: {}",
                projectPath, sessionId, e.message)
        }
    }

    /**
     * Synchronous post-execution hook called by AgentLoop AFTER tools complete.
     * Commits LLM changes and pushes checkpoint event for UI feedback.
     *
     * When [hasFileChangingTools] is `false` (the batch contains only read-only tools),
     * all git operations are skipped to avoid unnecessary overhead.
     */
    override suspend fun afterToolExecutionBatch(
        agentContext: AgentContext,
        toolCallIds: List<String>,
        messageId: String?,
        push: suspend (AgentEvent) -> Unit,
        hasFileChangingTools: Boolean
    ) {
        val projectPath = agentContext.projectPath ?: return
        val sessionId = agentContext.sessionId ?: return

        // Fast-path: if no tool in this batch modifies files, skip all git operations
        if (!hasFileChangingTools) {
            logger.trace("Skipping afterToolExecutionBatch: no file-changing tools in batch for session {}", sessionId)
            return
        }

        // Mark that at least one file-modifying tool was used for this session
        sessionFileModifications[sessionId] = true

        try {
            // Get the previous tracked hash (or fall back to baseline)
            var previousHash = snapshotService.getLastTrackedHash(projectPath, sessionId)
            if (previousHash == null) {
                try {
                    previousHash = snapshotService.ensureBaseline(projectPath, sessionId)
                } catch (e: Exception) {
                    logger.debug("Failed to get baseline for first batch diff: {}", e.message)
                }
            }

            // Commit LLM agent changes
            val commitHash = snapshotService.commitAs(projectPath, sessionId, ChangeAuthor.LLM_AGENT, "llm-batch")

            // Save for next tool/agent-end
            snapshotService.saveLastTrackedHash(projectPath, sessionId, commitHash)

            // Compute diff against previous
            val diffs = if (previousHash != null && previousHash != commitHash) {
                try {
                    snapshotService.diff(projectPath, previousHash, commitHash)
                        .map { it.copy(changedBy = "llm") }
                } catch (e: Exception) {
                    logger.debug("Failed to compute batch diff: {}", e.message)
                    emptyList()
                }
            } else {
                emptyList()
            }

            if (diffs.isEmpty()) {
                logger.debug("No changes after tool batch for session {}", sessionId)
                return
            }

            // Invalidate stale review overrides for files that have been re-modified
            try {
                snapshotService.invalidateFileReviews(projectPath, sessionId, diffs.map { it.path }.toSet())
            } catch (e: Exception) {
                logger.debug("Failed to invalidate file review state: {}", e.message)
            }

            val additions = diffs.sumOf { it.additions }
            val deletions = diffs.sumOf { it.deletions }

            logger.info("Tool batch checkpoint tracked: session={}, files={}", sessionId, diffs.size)

            pushCheckpointEvent(
                push = push,
                sessionId = sessionId,
                messageId = null,
                assistantMessageId = messageId,
                snapshotHash = null,
                filesChanged = diffs,
                additions = additions,
                deletions = deletions
            )
        } catch (e: Exception) {
            logger.warn("Failed to track changes for session={} batch: {}", sessionId, e.message)
        }
    }

    /**
     * Handle agent end: create final checkpoint and push checkpoint event for revert operations.
     */
    private suspend fun handleAgentEnd(
        agentContext: AgentContext,
        event: AgentEndEvent,
        push: suspend (AgentEvent) -> Unit
    ) {
        val sessionId = event.sessionId
        val projectPath = agentContext.projectPath
        val messages = event.messages

        val userMessageId = messages.filterIsInstance<UserMessage>()
            .lastOrNull { UserMessage.SOURCE_KEY !in it.metadata }?.id
        if (userMessageId == null || projectPath == null) {
            logger.trace("Skipping final checkpoint: session={}, messageId={}, projectPath={}",
                sessionId, userMessageId, projectPath)
            return
        }

        val hadModifications = sessionFileModifications.remove(sessionId) ?: false
        if (!hadModifications) {
            logger.trace("Skipping final checkpoint: session={}, no file-modifying tools used", sessionId)
            return
        }

        val assistantMessageId = messages.filterIsInstance<AssistantMessage>().lastOrNull()?.id

        try {
            // Commit any remaining user changes before final snapshot
            val userCommitHash = try {
                snapshotService.commitAs(projectPath, sessionId, ChangeAuthor.USER, "user-changes-final")
            } catch (e: Exception) {
                logger.debug("No user changes to commit at agent end: {}", e.message)
                null
            }

            // Update lastTrackedHash after user commit so the final diff
            // only covers the LLM agent-end commit, matching per-commit view.
            if (userCommitHash != null) {
                snapshotService.saveLastTrackedHash(projectPath, sessionId, userCommitHash)
            }

            // Track final state via LLM commit
            val commitHash = snapshotService.commitAs(projectPath, sessionId, ChangeAuthor.LLM_AGENT, "agent-end")

            // Get the last tracked hash to compute final diff
            val lastTrackedHash = snapshotService.getLastTrackedHash(projectPath, sessionId)
            val comparisonBase = lastTrackedHash ?: try {
                snapshotService.ensureBaseline(projectPath, sessionId)
            } catch (e: Exception) {
                logger.debug("Failed to get baseline for final checkpoint diff: {}", e.message)
                null
            }

            val diffs = if (comparisonBase != null && comparisonBase != commitHash) {
                try {
                    val rawDiffs = snapshotService.diff(projectPath, comparisonBase, commitHash)
                    // Attribute each file change to its author from the commit chain
                    val authorMap = try {
                        snapshotService.determineFileAuthors(projectPath, comparisonBase, commitHash)
                    } catch (e: Exception) {
                        logger.debug("Failed to determine file authors at agent end: {}", e.message)
                        emptyMap()
                    }
                    rawDiffs.map { it.copy(changedBy = authorMap[it.path]) }
                } catch (e: Exception) {
                    logger.warn("Failed to compute checkpoint diff: {}", e.message)
                    emptyList()
                }
            } else {
                emptyList()
            }

            val additions = diffs.sumOf { it.additions }
            val deletions = diffs.sumOf { it.deletions }

            val checkpoint = GitCheckpoint(
                commitHash = commitHash,
                sessionId = sessionId,
                messageId = userMessageId,
                timestamp = System.currentTimeMillis()
            )
            snapshotService.saveCheckpoint(projectPath, sessionId, checkpoint)

            snapshotService.saveLastTrackedHash(projectPath, sessionId, commitHash)

            if (diffs.isNotEmpty()) {
                try {
                    snapshotService.invalidateFileReviews(projectPath, sessionId, diffs.map { it.path }.toSet())
                } catch (e: Exception) {
                    logger.debug("Failed to invalidate file review state at agent end: {}", e.message)
                }
            }

            logger.info("Final checkpoint created: session={}, message={}, commitHash={}, files={}",
                sessionId, userMessageId, commitHash, diffs.size)

            // Only push checkpoint SSE event when there are actual file changes.
            // When diffs is empty (e.g., commitAs returned the same tree hash due to
            // tree-dedup), pushing would overwrite the valid checkpoint data already
            // sent by afterToolExecutionBatch with an empty filesChanged list.
            if (diffs.isNotEmpty()) {
                pushCheckpointEvent(
                    push = push,
                    sessionId = sessionId,
                    messageId = userMessageId,
                    assistantMessageId = assistantMessageId,
                    snapshotHash = commitHash,
                    filesChanged = diffs,
                    additions = additions,
                    deletions = deletions
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to create final checkpoint for session={}, message={}: {}",
                sessionId, userMessageId, e.message)
        }
    }

    /**
     * Push a checkpoint custom event to the event stream.
     */
    private suspend fun pushCheckpointEvent(
        push: suspend (AgentEvent) -> Unit,
        sessionId: String,
        messageId: String?,
        assistantMessageId: String?,
        snapshotHash: String?,
        filesChanged: List<FileDiff>,
        additions: Int,
        deletions: Int
    ) {
        val metadata = buildMap<String, Any?> {
            put("messageId", messageId)
            put("assistantMessageId", assistantMessageId)
            if (snapshotHash != null) {
                put("snapshotHash", snapshotHash)
            }
            put("filesChanged", filesChanged)
            put("additions", additions)
            put("deletions", deletions)
        }

        push(CustomEvent(
            customType = "checkpoint",
            sessionId = sessionId,
            metadata = metadata
        ))
    }
}
