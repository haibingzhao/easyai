package com.easy.easyai.web.service

import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.SessionManager
import com.easy.easyai.core.model.*
import com.easy.easyai.core.team.TeamExecutionStore
import com.easy.easyai.repository.session.AsyncSessionStore
import com.easy.easyai.repository.session.MessageWithTimestamp
import com.easy.easyai.skills.team.TeamCoordinationStateRegistry
import com.easy.easyai.snapshot.SnapshotService
import com.easy.easyai.snapshot.model.FileDiff
import com.easy.easyai.web.model.*
import org.slf4j.LoggerFactory
import tools.jackson.core.type.TypeReference

class SessionService(
    private val sessionManager: SessionManager,
    private val sessionStore: AsyncSessionStore,
    private val snapshotService: SnapshotService? = null,
    private val fileStorageService: FileStorageService? = null,
    /** Optional: cleans up Team Agent in-memory coordination state on session deletion. */
    private val teamStateRegistry: TeamCoordinationStateRegistry? = null,
    /** Optional: cascade-deletes Team Agent execution/round records + member sub-sessions. */
    private val teamExecutionStore: TeamExecutionStore? = null,
    /** Optional: resolves the last message's model config to report its context window. */
    private val configStore: ModelProviderConfigStore? = null
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

        // Resolve the last message's model context window so the frontend token bar
        // renders the real usage percentage instead of a hardcoded default (best-effort).
        val modelContextLength = lastConfig?.configId?.let { configId ->
            try {
                configStore?.getConfig(configId, userId)?.options?.contextToken
            } catch (e: Exception) {
                logger.warn("Failed to resolve model config {} for session {}: {}", configId, id, e.message)
                null
            }
        }

        // Load persisted session variables for frontend display (Summary -> References)
        val variables = loadSessionVariablesMap(id, userId)

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
            lastConfigId = lastConfig?.configId,
            modelContextLength = modelContextLength,
            variables = variables
        )
    }

    /**
     * Load persisted session variables as a key-value map for frontend display.
     * Source: compaction summary message metadata (single source of truth).
     * Returns null when absent or unparseable (best-effort, never fails the detail load).
     */
    private suspend fun loadSessionVariablesMap(id: String, userId: String): Map<String, String>? {
        return try {
            val json = sessionStore.loadVariablesFromCompactionSummary(id, userId)
            if (json.isNullOrBlank()) null
            else objectMapper.readValue(json, object : TypeReference<Map<String, String>>() {})
        } catch (e: Exception) {
            logger.warn("Failed to load session variables for {}: {}", id, e.message)
            null
        }
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

            // Filter out checkpoints whose messageId no longer exists in the message list
            // (e.g., removed by compaction or message editing/truncation)
            val messageIdSet = messages.map { it.id }.toHashSet()
            val validCheckpoints = checkpoints.filter { it.messageId in messageIdSet }
            if (validCheckpoints.size < checkpoints.size) {
                logger.debug("getCheckpoints: filtered {} stale checkpoints (messageId not in current messages), {} remaining for session {}",
                    checkpoints.size - validCheckpoints.size, validCheckpoints.size, sessionId)
            }
            logger.info("getCheckpoints: loaded {} checkpoints ({} valid), {} messages for session {}", checkpoints.size, validCheckpoints.size, messages.size, sessionId)

            // Pre-resolve session ref for fallback when checkpoint hashes are all identical
            // (due to tree-dedup in commitAs returning the same hash when no new tree is created).
            // The session ref always points to the latest real commit with actual changes.
            val sessionRefHash = try {
                snapshotService.resolveSessionRef(projectPath, sessionId)
            } catch (e: Exception) {
                logger.debug("Failed to resolve session ref for checkpoint fallback: {}", e.message)
                null
            }

            validCheckpoints.mapIndexed { index, checkpoint ->
                val assistantMessageId = findAssistantMessageIdAfter(messages, checkpoint.messageId)
                if (assistantMessageId == null) {
                    logger.debug("getCheckpoints: no assistant message found after user message {} for checkpoint {}", checkpoint.messageId, checkpoint.commitHash)
                }
                val baseline = if (index + 1 >= validCheckpoints.size) {
                    // Oldest checkpoint — use session baseline
                    try {
                        snapshotService.ensureBaseline(projectPath, sessionId)
                    } catch (e: Exception) {
                        logger.warn("Failed to get session baseline for checkpoint diff: {}", e.message)
                        null
                    }
                } else null
                val parent = if (index + 1 < validCheckpoints.size) {
                    validCheckpoints[index + 1].commitHash
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

        // Aggregate team member session checkpoints (members use isolated session IDs,
        // so their file changes are committed under separate session refs and never
        // appear in the parent session's checkpoint chain).
        if (teamExecutionStore != null) {
            try {
                val executions = teamExecutionStore.getExecutions(sessionId)
                val memberSessions = executions.mapNotNull { exec ->
                    exec.memberSessionId?.let { it to exec.memberId }
                }.distinctBy { it.first }

                for ((memberSessionId, memberId) in memberSessions) {
                    val memberCheckpoints = snapshotService.listCheckpoints(projectPath, memberSessionId)
                    if (memberCheckpoints.isEmpty()) continue
                    val latestHash = memberCheckpoints.first().commitHash // newest-first ordering
                    val memberBaseline = try {
                        snapshotService.ensureBaseline(projectPath, memberSessionId)
                    } catch (e: Exception) {
                        logger.debug("No baseline for member session {}: {}", memberSessionId, e.message)
                        continue
                    }
                    val memberDiffs = snapshotService.diff(projectPath, memberBaseline, latestHash)
                    if (memberDiffs.isEmpty()) continue
                    result.add(
                        CheckpointSummary(
                            messageId = "member:$memberSessionId",
                            assistantMessageId = null,
                            snapshotHash = latestHash,
                            filesChanged = memberDiffs.map {
                                FileChangeInfo(it.path, it.additions, it.deletions, it.status.name.lowercase(), "llm", memberId)
                            },
                            additions = memberDiffs.sumOf { it.additions },
                            deletions = memberDiffs.sumOf { it.deletions },
                            createdAt = memberCheckpoints.first().timestamp
                        )
                    )
                }
            } catch (e: Exception) {
                logger.warn("Failed to aggregate member checkpoints for session {}: {}", sessionId, e.message)
            }
        }

        return result
    }

    /**
     * Get session-level file diffs including team member changes.
     * Extends the parent session's baseline→latest diff with member-only file patches.
     */
    suspend fun getSessionDiffWithMembers(sessionId: String, userId: String = "system"): List<FileDiff> {
        if (snapshotService == null) return emptyList()
        val context = sessionManager.getSessionContext(sessionId, userId) ?: return emptyList()
        val projectPath = context.projectPath ?: return emptyList()
        if (!snapshotService.isEnabled(projectPath)) return emptyList()

        // Parent session diff: baseline → latest checkpoint
        val parentDiffs = try {
            val checkpoints = snapshotService.listCheckpoints(projectPath, sessionId)
            if (checkpoints.isEmpty()) {
                mutableListOf() // Parent has no checkpoints yet — still aggregate member diffs below
            } else {
                val baseline = snapshotService.ensureBaseline(projectPath, sessionId)
                snapshotService.diff(projectPath, baseline, checkpoints.first().commitHash).toMutableList()
            }
        } catch (e: Exception) {
            logger.warn("Failed to compute session diff for {}: {}", sessionId, e.message)
            mutableListOf()
        }

        // Aggregate member-only file diffs
        if (teamExecutionStore != null) {
            try {
                val memberSessions = teamExecutionStore.getExecutions(sessionId)
                    .mapNotNull { it.memberSessionId }.distinct()
                val existingPaths = parentDiffs.map { it.path }.toMutableSet()
                for (memberSessionId in memberSessions) {
                    val memberCheckpoints = snapshotService.listCheckpoints(projectPath, memberSessionId)
                    if (memberCheckpoints.isEmpty()) continue
                    val memberBaseline = try {
                        snapshotService.ensureBaseline(projectPath, memberSessionId)
                    } catch (_: Exception) { continue }
                    val memberDiffs = snapshotService.diff(projectPath, memberBaseline, memberCheckpoints.first().commitHash)
                    for (d in memberDiffs) {
                        if (existingPaths.add(d.path)) {
                            parentDiffs.add(d.copy(changedBy = "llm"))
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to aggregate member diffs for session {}: {}", sessionId, e.message)
            }
        }

        return parentDiffs
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
        logger.debug("findAssistantMessageIdAfter: no match found for userMessageId={}", userMessageId)
        return null
    }

    /**
     * Delete all messages with createdAt >= [fromTimestamp] (inclusive).
     * @return Number of messages deleted.
     */
    suspend fun deleteMessagesFromTimestamp(sessionId: String, fromTimestamp: Long, excludeIds: Set<String> = emptySet()): Int {
        return sessionStore.deleteMessagesFromTimestamp(sessionId, fromTimestamp, excludeIds)
    }

    /**
     * Clean up Team Agent records created at or after [fromTimestamp] for a session, plus their
     * member sub-sessions. Called when editing a historical message truncates the conversation, so
     * stale executions/rounds (and orphaned member sessions) from the removed portion don't linger.
     */
    suspend fun deleteTeamRecordsFrom(sessionId: String, fromTimestamp: Long, userId: String) {
        // Reset in-memory coordination state (cancel running members + clear stale round/member state)
        // so a re-run after editing starts fresh. Not gated on teamExecutionStore — the in-memory reset
        // is valuable even when DB persistence is unavailable. Mirrors the cleanup in deleteSession.
        try {
            teamStateRegistry?.remove(sessionId)
        } catch (e: Exception) {
            logger.warn("Failed to reset team coordination state for session {}: {}", sessionId, e.message)
        }

        val store = teamExecutionStore ?: return
        try {
            // Collect member sub-session IDs of the executions about to be removed (startedAt >= fromTimestamp),
            // mirroring the SQL boundary in deleteByTeamSessionFrom (null startedAt rows are left untouched).
            val memberSessionIds = store.getExecutions(sessionId)
                .filter { exec ->
                    val startedAt = exec.startedAt
                    startedAt != null && startedAt >= fromTimestamp
                }
                .mapNotNull { it.memberSessionId }
                .distinct()
            store.deleteByTeamSessionFrom(sessionId, fromTimestamp)
            memberSessionIds.forEach { memberSessionId ->
                sessionStore.delete(memberSessionId, userId)
            }
        } catch (e: Exception) {
            logger.warn("Failed to clean up team records from timestamp {} for session {}: {}",
                fromTimestamp, sessionId, e.message)
        }
    }

    /**
     * Get the createdAt timestamp of a specific message.
     * @return The timestamp, or null if the message is not found.
     */
    suspend fun getMessageCreatedAt(sessionId: String, messageId: String): Long? {
        return sessionStore.getMessageCreatedAt(sessionId, messageId)
    }

    /**
     * Check if any compaction indicator exists after the given message timestamp.
     * Returns the timestamp of the earliest compaction indicator, or null.
     *
     * Only checks compaction indicators (role = 'CUSTOM' with isCompactionIndicator metadata).
     */
    suspend fun getFirstCompactionAfter(sessionId: String, messageCreatedAt: Long): Long? {
        return sessionStore.getFirstCompactionAfter(sessionId, messageCreatedAt)
    }

    /**
     * Check if a compaction summary exists at/after the given timestamp.
     * Used to determine whether editing a message requires a full compaction undo.
     *
     * If false, the message is NOT in any compacted set and the expensive undo can be skipped.
     */
    suspend fun hasCompactionSummaryAtOrAfter(sessionId: String, messageCreatedAt: Long): Boolean {
        return sessionStore.hasCompactionSummaryAtOrAfter(sessionId, messageCreatedAt)
    }

    /**
     * Undo compaction that occurred at or after [messageCreatedAt].
     * Delegates to [AsyncSessionStore.undoCompactionAfter].
     * @return IDs of preserved indicators (should be excluded from subsequent deletion)
     */
    suspend fun undoCompactionAfter(sessionId: String, messageCreatedAt: Long): Set<String> {
        return sessionStore.undoCompactionAfter(sessionId, messageCreatedAt)
    }

    suspend fun createSession(userId: String = "system"): String {
        val session = sessionManager.getOrCreateSession(null, userId)
        logger.info("Created session: {}", session.id)
        return session.id
    }

    suspend fun deleteSession(id: String, userId: String = "system") {
        // Resolve project path once for all file-based cleanups
        val projectPath = try {
            sessionManager.getSessionContext(id, userId)?.projectPath
        } catch (e: Exception) {
            logger.warn("Failed to resolve session context for {}: {}", id, e.message)
            null
        }

        // Clean up snapshot session files before closing
        try {
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

        // Clean up session variable files (.easyai/vars/{sessionId}/)
        try {
            if (projectPath != null) {
                val varsDir = projectPath.resolve(".easyai/vars/$id")
                if (java.nio.file.Files.isDirectory(varsDir)) {
                    varsDir.toFile().deleteRecursively()
                    logger.info("Cleaned up session variable files for: {}", id)
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to clean up session variable files for {}: {}", id, e.message)
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
            metadata = when (msg) {
                is UserMessage -> msg.metadata.ifEmpty { null }
                is CustomMessage -> msg.metadata.ifEmpty { null }
                else -> null
            },
            usage = extractUsage(msg)?.let {
                UsageSnapshot(
                    inputTokens = it.inputTokens,
                    outputTokens = it.outputTokens,
                    totalTokens = it.inputTokens + it.outputTokens,
                    cacheReadTokens = it.cacheReadTokens,
                    cacheWriteTokens = it.cacheWriteTokens,
                    durationMs = it.durationMs,
                    modelName = it.modelName
                )
            },
            compactedAt = msgWithTs.compactedAt,
            parentMessageId = msgWithTs.parentMessageId,
            parentToolCallId = msgWithTs.parentToolCallId,
            references = refsSnapshot
        )
    }
}
