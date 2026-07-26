package com.easy.easyai.web.service

import com.easy.easyai.core.agent.SessionManager
import com.easy.easyai.core.model.*
import com.easy.easyai.core.team.TeamExecutionStore
import com.easy.easyai.repository.session.AsyncSessionStore
import com.easy.easyai.repository.session.MessageWithTimestamp
import com.easy.easyai.skills.team.TeamCoordinationStateRegistry
import com.easy.easyai.snapshot.SnapshotService
import com.easy.easyai.web.model.*
import org.slf4j.LoggerFactory
import tools.jackson.core.type.TypeReference
import com.easy.easyai.common.util.SharedObjectMapper

class SessionService(
    private val sessionManager: SessionManager,
    private val sessionStore: AsyncSessionStore,
    private val snapshotService: SnapshotService? = null,
    private val fileStorageService: FileStorageService? = null,
    /** Optional: cleans up Team Agent in-memory coordination state on session deletion. */
    private val teamStateRegistry: TeamCoordinationStateRegistry? = null,
    /** Optional: cascade-deletes Team Agent execution/round records + member sub-sessions. */
    private val teamExecutionStore: TeamExecutionStore? = null
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper = SharedObjectMapper.instance

    data class SessionListResponse(
        val sessions: List<SessionListItem>,
        val hasMore: Boolean
    )

    suspend fun listSessions(limit: Int, offset: Int, projectId: String? = null, userId: String = "system"): SessionListResponse {
        val (metadataList, hasMore) = sessionStore.findMetadataByLimit(limit, offset, projectId, userId)
        val items = metadataList.map { meta ->
            SessionListItem(
                id = meta.id,
                title = meta.firstUserMessageText,
                createdAt = meta.createdAt,
                updatedAt = meta.updatedAt,
                messageCount = meta.messageCount,
                streaming = meta.streaming
            )
        }
        return SessionListResponse(
            sessions = items,
            hasMore = hasMore
        )
    }

    suspend fun getSessionDetail(id: String, userId: String = "system"): SessionDetail? {
        val session = sessionManager.getSessionDetail(id, userId) ?: return null

        // Load messages with their original timestamps from the database
        val messagesWithTimestamps = sessionStore.loadMessagesWithTimestamps(id)

        val pendingPermission = loadPendingPermission(id)

        // Load end reason from DB
        val endReason = sessionStore.findEndReason(id, userId)

        // Get last message config (agentId, configId) to allow frontend to restore selection
        val lastConfig = sessionStore.getLastMessageConfig(id)

        return SessionDetail(
            id = session.id,
            title = extractTitle(session.messages),
            createdAt = session.createdAt.toEpochMilli(),
            updatedAt = session.updatedAt.toEpochMilli(),
            messageCount = session.messages.size,
            messages = messagesWithTimestamps.map { toMessageSnapshot(it) },
            pendingPermission = pendingPermission,
            endReason = endReason?.takeIf { it != "normal" },
            lastAgentId = lastConfig?.agentId,
            lastConfigId = lastConfig?.configId
        )
    }

    /**
     * Get messages created after [afterTimestamp] for incremental recovery.
     * Returns metadata that allows the frontend to decide whether to fall back to full reload:
     * - [compactionOccurredAfter]: true if compaction happened after [afterTimestamp]
     * - [sessionUpdatedAt]: dirty marker bumped by [updateMessage], used to detect in-place updates
     */
    suspend fun getSessionMessagesAfter(
        id: String,
        afterTimestamp: Long,
        userId: String = "system"
    ): SessionMessagesAfterResponse? {
        // Lightweight existence check + contentUpdatedAt in one query (avoids loading all messages)
        val contentUpdatedAt = sessionStore.findContentUpdatedAt(id, userId) ?: return null

        val messagesWithTimestamps = sessionStore.loadMessagesWithTimestampsAfter(id, afterTimestamp)
        val compactionAt = sessionStore.getFirstCompactionAfter(id, afterTimestamp)
        val sessionStatus = sessionStore.findStatus(id, userId)

        val pendingPermission = loadPendingPermission(id)

        // Load endReason so the frontend's incremental poll has the same data as getSessionDetail.
        // This prevents detectInterruptedSession from using stale/missing endReason during polling.
        val endReason = sessionStore.findEndReason(id, userId)?.takeIf { it != "normal" }

        return SessionMessagesAfterResponse(
            sessionId = id,
            messages = messagesWithTimestamps.map { toMessageSnapshot(it) },
            compactionOccurredAfter = compactionAt != null,
            contentUpdatedAt = contentUpdatedAt,
            streaming = sessionStatus == "streaming",
            pendingPermission = pendingPermission,
            endReason = endReason
        )
    }

    /**
     * Get checkpoint summaries for a session.
     * Used to restore file change information when loading a historical session.
     */
    suspend fun getCheckpoints(sessionId: String, userId: String = "system"): List<CheckpointSummary> {
        if (snapshotService == null) return emptyList()
        val context = sessionManager.getSessionContext(sessionId, userId) ?: return emptyList()
        val projectPath = context.projectPath ?: return emptyList()
        if (!snapshotService.isEnabled(projectPath)) return emptyList()

        val checkpoints = try {
            snapshotService.listCheckpoints(projectPath, sessionId)
        } catch (e: Exception) {
            logger.warn("Failed to list checkpoints for session {}: {}", sessionId, e.message)
            emptyList()
        }

        val result = if (checkpoints.isNotEmpty()) {
            // Load messages to build userMessageId → assistantMessageId mapping
            val messagesWithTs = sessionStore.loadMessagesWithTimestamps(sessionId)
            val messages = messagesWithTs.map { it.message }
            logger.info("getCheckpoints: loaded {} checkpoints, {} messages for session {}", checkpoints.size, messages.size, sessionId)

            // Pre-resolve session ref for fallback when checkpoint hashes are all identical
            // (due to tree-dedup in commitAs returning the same hash when no new tree is created).
            // The session ref always points to the latest real commit with actual changes.
            val sessionRefHash = try {
                snapshotService.resolveSessionRef(projectPath, sessionId)
            } catch (e: Exception) {
                logger.debug("Failed to resolve session ref for checkpoint fallback: {}", e.message)
                null
            }

            checkpoints.mapIndexed { index, checkpoint ->
                val assistantMessageId = findAssistantMessageIdAfter(messages, checkpoint.messageId)
                if (assistantMessageId == null) {
                    logger.warn("getCheckpoints: no assistant message found after user message {} for checkpoint {}", checkpoint.messageId, checkpoint.commitHash)
                }
                val baseline = if (index + 1 >= checkpoints.size) {
                    // Oldest checkpoint — use session baseline
                    try {
                        snapshotService.ensureBaseline(projectPath, sessionId)
                    } catch (e: Exception) {
                        logger.warn("Failed to get session baseline for checkpoint diff: {}", e.message)
                        null
                    }
                } else null
                val parent = if (index + 1 < checkpoints.size) {
                    checkpoints[index + 1].commitHash
                } else {
                    baseline
                }

                // When parent == commitHash (tree-dedup produced identical hashes),
                // fall back to diff(baseline, sessionRef) to recover actual file changes.
                val needsFallback = parent != null && parent == checkpoint.commitHash
                        && sessionRefHash != null && sessionRefHash != parent
                val effectiveParent = if (needsFallback) baseline else parent
                val effectiveTarget = if (needsFallback) sessionRefHash else checkpoint.commitHash

                val diffs = try {
                    if (effectiveParent != null) {
                        snapshotService.diff(projectPath, effectiveParent, effectiveTarget)
                    } else {
                        emptyList()
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to compute checkpoint diff for message {}: {}", checkpoint.messageId, e.message)
                    emptyList()
                }
                // Determine per-file author attribution from the commit chain
                val authorMap = if (effectiveParent != null && diffs.isNotEmpty()) {
                    try {
                        snapshotService.determineFileAuthors(projectPath, effectiveParent, effectiveTarget)
                    } catch (e: Exception) {
                        logger.warn("Failed to determine file authors for checkpoint {}: {}", checkpoint.messageId, e.message)
                        emptyMap()
                    }
                } else {
                    emptyMap()
                }
                CheckpointSummary(
                    messageId = checkpoint.messageId,
                    assistantMessageId = assistantMessageId,
                    snapshotHash = checkpoint.commitHash,
                    filesChanged = diffs.map { FileChangeInfo(it.path, it.additions, it.deletions, it.status.name.lowercase(), authorMap[it.path]) },
                    additions = diffs.sumOf { it.additions },
                    deletions = diffs.sumOf { it.deletions },
                    createdAt = checkpoint.timestamp
                )
            }.toMutableList()
        } else {
            mutableListOf()
        }

        // Check for staged but untracked changes (e.g., AgentEnd was not reached
        // due to session crash or interruption). These changes exist in the shadow
        // git index but have no corresponding checkpoint tree hash.
        // getStagedChanges validates that the baseline belongs to this session to prevent
        // cross-session data leakage via the shared shadow git index.
        try {
            val stagedDiffs = snapshotService.getStagedChanges(projectPath, sessionId)
            if (stagedDiffs.isNotEmpty()) {
                logger.info("getCheckpoints: found {} staged but uncommitted changes for session {}", stagedDiffs.size, sessionId)
                // Use a unique synthetic messageId so the frontend won't collide with
                // any real checkpoint key (frontend uses `assistantMessageId || messageId` as key).
                result.add(
                    0, // prepend as newest
                    CheckpointSummary(
                        messageId = "staged:$sessionId",
                        assistantMessageId = null,
                        snapshotHash = null,
                        filesChanged = stagedDiffs.map { FileChangeInfo(it.path, it.additions, it.deletions, it.status.name.lowercase(), "llm") },
                        additions = stagedDiffs.sumOf { it.additions },
                        deletions = stagedDiffs.sumOf { it.deletions },
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to check staged changes for session {}: {}", sessionId, e.message)
        }

        return result
    }

    /**
     * Find the assistant message ID that follows a given user message ID in the transcript.
     */
    private fun findAssistantMessageIdAfter(messages: List<EasyAiMessage>, userMessageId: String): String? {
        logger.trace("findAssistantMessageIdAfter: searching for userMessageId={}, total messages={}", userMessageId, messages.size)
        var foundUser = false
        for (msg in messages) {
            if (msg.id == userMessageId) {
                foundUser = true
                logger.trace("findAssistantMessageIdAfter: found user message id={}, role={}", msg.id, msg.role)
                continue
            }
            if (foundUser && msg is AssistantMessage) {
                logger.trace("findAssistantMessageIdAfter: found assistant message id={}", msg.id)
                return msg.id
            }
        }
        logger.warn("findAssistantMessageIdAfter: no match found for userMessageId={}", userMessageId)
        return null
    }

    /**
     * Delete all messages from the given messageId onwards (by createdAt timestamp).
     * @return Number of messages deleted.
     */
    suspend fun deleteMessagesFrom(sessionId: String, messageId: String): Int {
        return sessionStore.deleteMessagesFrom(sessionId, messageId)
    }

    /**
     * Get the createdAt timestamp of a specific message.
     * @return The timestamp, or null if the message is not found.
     */
    suspend fun getMessageCreatedAt(sessionId: String, messageId: String): Long? {
        return sessionStore.getMessageCreatedAt(sessionId, messageId)
    }

    /**
     * Check if any compaction occurred after the given message timestamp.
     * Returns the timestamp of the earliest compaction after the message, or null.
     *
     * Checks both:
     * 1. Compaction indicators (role = 'CUSTOM' with isCompactionIndicator metadata)
     * 2. Compaction summaries (role = 'USER' with isCompactionSummary metadata)
     */
    suspend fun getFirstCompactionAfter(sessionId: String, messageCreatedAt: Long): Long? {
        return sessionStore.getFirstCompactionAfter(sessionId, messageCreatedAt)
    }

    /**
     * Undo compaction that occurred at or after [messageCreatedAt].
     * Delegates to [AsyncSessionStore.undoCompactionAfter].
     */
    suspend fun undoCompactionAfter(sessionId: String, messageCreatedAt: Long) {
        sessionStore.undoCompactionAfter(sessionId, messageCreatedAt)
    }

    suspend fun createSession(userId: String = "system"): String {
        val session = sessionManager.getOrCreateSession(null, userId)
        logger.info("Created session: {}", session.id)
        return session.id
    }

    suspend fun deleteSession(id: String, userId: String = "system") {
        // Clean up snapshot session files before closing (needs projectPath from session context)
        try {
            val context = sessionManager.getSessionContext(id, userId)
            val projectPath = context?.projectPath
            if (projectPath != null && snapshotService != null && snapshotService.isEnabled(projectPath)) {
                snapshotService.cleanupSession(projectPath, id)
            }
        } catch (e: Exception) {
            logger.warn("Failed to clean up snapshot session files for {}: {}", id, e.message)
        }

        // Clean up stored images for this session
        try {
            fileStorageService?.cleanupSession(id)
        } catch (e: Exception) {
            logger.warn("Failed to clean up images for session {}: {}", id, e.message)
        }

        // Delete from DB with proper userId scoping
        sessionStore.delete(id, userId)
        // Close in-memory session
        sessionManager.closeSession(id)
        // Clean up Team Agent in-memory coordination state (coroutine scope + channel)
        try {
            teamStateRegistry?.remove(id)
        } catch (e: Exception) {
            logger.warn("Failed to clean up team coordination state for session {}: {}", id, e.message)
        }
        // Cascade-delete Team Agent persistence: member execution/round records + member sub-sessions.
        // Without this, deleting a team session orphans rows in team_member_execution / team_round_record
        // and leaves member sub-sessions visible in the history list.
        try {
            if (teamExecutionStore != null) {
                val memberSessionIds = teamExecutionStore.getExecutions(id)
                    .mapNotNull { it.memberSessionId }
                    .distinct()
                teamExecutionStore.deleteByTeamSession(id)
                memberSessionIds.forEach { memberSessionId ->
                    sessionStore.delete(memberSessionId, userId)
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to clean up team records for session {}: {}", id, e.message)
        }
        logger.info("Deleted session: {}", id)
    }

    private suspend fun loadPendingPermission(sessionId: String): Map<String, Any?>? {
        val json = sessionStore.findPendingPermission(sessionId)
        if (json.isNullOrBlank()) return null
        return try {
            objectMapper.readValue(json, object : TypeReference<Map<String, Any?>>() {})
        } catch (e: Exception) {
            logger.warn("Failed to parse pending permission for session {}: {}", sessionId, e.message)
            null
        }
    }

    private fun extractUsage(msg: EasyAiMessage): Usage? = when (msg) {
        is AssistantMessage -> msg.usage
        is UserMessage -> msg.usage
        else -> null
    }

    private fun extractTitle(messages: List<EasyAiMessage>): String? {
        val firstUserMsg = messages.firstOrNull { it.role == Role.USER }
        val text = firstUserMsg?.content?.filterIsInstance<TextContent>()?.joinToString("") { it.text }
        return if (text.isNullOrBlank()) null else text.take(50)
    }

    /**
     * Convert a [MessageWithTimestamp] to a [MessageSnapshot] DTO.
     * Shared between [getSessionDetail] and [getSessionMessagesAfter].
     */
    private fun toMessageSnapshot(msgWithTs: MessageWithTimestamp): MessageSnapshot {
        val msg = msgWithTs.message
        val assistantMsg = msg as? AssistantMessage
        val refsSnapshot = assistantMsg?.references?.let { ChatStreamEvent.ReferencesSnapshot.from(it) }
        return MessageSnapshot(
            id = msg.id,
            role = msg.role.name,
            content = msg.content,
            timestamp = msgWithTs.timestamp,
            stopReason = assistantMsg?.stopReason?.name,
            metadata = (msg as? UserMessage)?.metadata?.ifEmpty { null },
            usage = extractUsage(msg)?.let {
                UsageSnapshot(
                    inputTokens = it.inputTokens,
                    outputTokens = it.outputTokens,
                    totalTokens = it.inputTokens + it.outputTokens,
                    cacheReadTokens = it.cacheReadTokens,
                    cacheWriteTokens = it.cacheWriteTokens,
                    durationMs = it.durationMs
                )
            },
            compactedAt = msgWithTs.compactedAt,
            parentMessageId = msgWithTs.parentMessageId,
            parentToolCallId = msgWithTs.parentToolCallId,
            references = refsSnapshot
        )
    }
}
