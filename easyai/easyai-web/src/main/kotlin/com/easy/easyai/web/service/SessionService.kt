package com.easy.easyai.web.service

import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.model.ModelOverridePolicy
import com.easy.easyai.api.model.ModelOverrides
import com.easy.easyai.auth.AuthConstants
import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.SessionManager
import com.easy.easyai.core.model.*
import com.easy.easyai.core.team.TeamExecutionStore
import com.easy.easyai.repository.session.AsyncSessionStore
import com.easy.easyai.repository.session.MessageRef
import com.easy.easyai.repository.session.MessageWithTimestamp
import com.easy.easyai.skills.team.TeamCoordinationStateRegistry
import com.easy.easyai.snapshot.SnapshotService
import com.easy.easyai.snapshot.model.FileDiff
import com.easy.easyai.tools.background.BackgroundTaskManagerRegistry
import com.easy.easyai.web.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private val configStore: ModelProviderConfigStore? = null,
    /** Optional: cleans up background task manager on session deletion. */
    private val backgroundTaskManagerRegistry: BackgroundTaskManagerRegistry? = null,
    /** Optional: releases the temporary workspace a project-less session was given. */
    private val workspaceService: DefaultWorkspaceService? = null
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper = SharedObjectMapper.instance

    companion object {
        /**
         * How many checkpoint diffs may run at once.
         *
         * Each one is two `git diff` processes against the shared shadow repo, so this trades
         * process latency for disk contention. Four keeps a long session's checkpoint list from
         * taking seconds without turning it into a process storm.
         */
        private const val DIFF_CONCURRENCY = 4
    }

    data class SessionListResponse(
        val sessions: List<SessionListItem>,
        val hasMore: Boolean
    )

    /**
     * @param tempWorkspace Only list sessions that run in a temporary workspace (or carry no
     *   project) — the History list of the project-less "No Project" mode.
     */
    suspend fun listSessions(limit: Int, offset: Int, projectId: String? = null, userId: String = "system", tags: List<String>? = null, tempWorkspace: Boolean = false): SessionListResponse {
        val (metadataList, hasMore) = sessionStore.findMetadataByLimit(limit, offset, projectId, userId, tags = tags, tempWorkspaceOnly = tempWorkspace)
        val items = metadataList.map { meta ->
            SessionListItem(
                id = meta.id,
                title = meta.firstUserMessageText,
                createdAt = meta.createdAt,
                updatedAt = meta.updatedAt,
                messageCount = meta.messageCount,
                streaming = meta.streaming,
                tags = meta.tags
            )
        }
        return SessionListResponse(
            sessions = items,
            hasMore = hasMore
        )
    }

    /**
     * Overwrite the tag set of a session. Tags are normalized in the store layer.
     */
    suspend fun updateSessionTags(id: String, tags: List<String>, userId: String = "system") {
        sessionStore.updateTags(id, tags, userId)
    }

    /**
     * Distinct tags in use, for the History panel autocomplete + filter chip row.
     * [tempWorkspace] scopes the aggregation to project-less sessions, matching [listSessions].
     */
    suspend fun listSessionTags(projectId: String? = null, userId: String = "system", tempWorkspace: Boolean = false): List<String> =
        sessionStore.findAllTags(projectId, userId, tempWorkspaceOnly = tempWorkspace)

    /**
     * Session detail: the whole transcript in one response.
     */
    suspend fun getSessionDetail(
        id: String,
        userId: String = "system",
        owners: Collection<String>,
    ): SessionDetail? {
        // Same fallback as DatabaseSessionManager: an empty visibility set still sees the caller's
        // own rows plus the shared system layer, so the token bar never silently loses its window.
        val readOwners = owners.ifEmpty { setOf(userId, AuthConstants.SYSTEM_USER_ID) }
        // Metadata + one message load. Previously this method resolved the session twice
        // (findById loads and deserializes the whole transcript, loadMessagesWithTimestamps
        // does it again) plus a third time inside getSessionContext, which only needs projectId.
        val meta = sessionStore.findSessionMeta(id, userId) ?: return null

        // Read the cursor BEFORE the rows: a rewrite landing mid-read then leaves the client with
        // a revision older than its data, which invalidates rather than silently caches stale text.
        // `newestSeq` comes from the same counter, not from the last loaded row: a backdated
        // compaction summary has the highest seq but does not sort last.
        val cursor = runCatching { sessionStore.findSessionCursor(id, userId) }.getOrNull()

        val messagesWithTimestamps = sessionStore.loadMessagesWithTimestamps(id)

        val pendingPermission = loadPendingPermission(id)

        // Load end reason from DB
        val endReason = sessionStore.findEndReason(id, userId)

        // Get last message config (agentId, configId) to allow frontend to restore selection
        val lastConfig = sessionStore.getLastMessageConfig(id)

        // Resolve the last message's model context window so the frontend token bar
        // renders the real usage percentage instead of a hardcoded default (best-effort).
        // Composer overrides recorded on the session take precedence over the model config.
        val modelContextLength = lastConfig?.configId?.let { configId ->
            try {
                // Group-aware: a session on a family-owned chat model still reports its real window.
                val config = configStore?.getConfig(configId, readOwners)
                val overrides = sessionStore.findModelOverrides(id)?.let {
                    objectMapper.readValue(it, ModelOverrides::class.java)
                }
                config?.let {
                    ModelOverridePolicy.applyRecorded(it, overrides).options?.contextToken
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Failed to resolve model config {} for session {}: {}", configId, id, e.message)
                null
            }
        }

        // Load persisted session variables for frontend display (Summary -> References)
        val variables = loadSessionVariablesMap(id, userId)

        // Fork lineage so the frontend can locate this session in the branch list
        val forkInfo = sessionStore.findForkInfo(id, userId)

        // Tags attached to this session (empty when none)
        val tags = sessionStore.findTagsMap(listOf(id), userId)[id] ?: emptyList()

        // Agent context carries the project — or temporary workspace — this session runs in.
        val context = resolveSessionContext(id, userId, readOwners)

        return SessionDetail(
            id = meta.id,
            title = extractTitle(messagesWithTimestamps),
            createdAt = meta.createdAt,
            updatedAt = meta.updatedAt,
            messageCount = messagesWithTimestamps.size,
            messages = toMessageSnapshots(messagesWithTimestamps, userId, readOwners),
            pendingPermission = pendingPermission,
            endReason = endReason?.takeIf { it != "normal" },
            lastAgentId = lastConfig?.agentId,
            lastConfigId = lastConfig?.configId,
            modelContextLength = modelContextLength,
            variables = variables,
            forkedFromSessionId = forkInfo?.forkedFromSessionId,
            forkRootSessionId = forkInfo?.forkRootSessionId,
            snapshotEnabled = resolveSnapshotEnabled(context),
            workspaceProjectId = context?.projectId,
            workspacePath = context?.projectPath?.toString(),
            workspaceKind = context?.projectKind?.value,
            tags = tags,
            newestMessageSeq = cursor?.newestSeq ?: messagesWithTimestamps.maxOfOrNull { it.seq },
            contentRevision = cursor?.contentRevision
        )
    }

    /**
     * Best-effort agent context of a session: carries the project (or temporary workspace)
     * the session runs in. Never fails the detail load.
     */
    private suspend fun resolveSessionContext(
        sessionId: String,
        userId: String,
        owners: Collection<String>
    ): AgentContext? = try {
        sessionManager.getSessionContext(sessionId, userId, owners)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("Failed to resolve agent context for session {}: {}", sessionId, e.message)
        null
    }

    /**
     * Resolve whether the snapshot/checkpoint system is active for this session's project.
     * Mirrors the runtime gating in [getCheckpoints] and SnapshotEventListener, so a
     * no-op SnapshotService override (or a session without a project) reports false and
     * the frontend can hide the file-review UI. Best-effort: never fails the detail load.
     */
    private fun resolveSnapshotEnabled(context: AgentContext?): Boolean {
        val service = snapshotService ?: return false
        val projectPath = context?.projectPath ?: return false
        return runCatching { service.isEnabled(projectPath) }
            .onFailure { logger.warn("Failed to resolve snapshot capability for session {}: {}", context.sessionId, it.message) }
            .getOrDefault(false)
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
     * - [SessionMessagesAfterResponse.compactionOccurredAfter]: true if compaction happened after
     * - [SessionMessagesAfterResponse.contentUpdatedAt]: dirty marker bumped by updateMessage()
     *
     * Kept for callers that still poll by timestamp (swarm task transcript). New clients use
     * [getSessionMessages] with the seq cursors.
     */
    suspend fun getSessionMessagesAfter(
        id: String,
        afterTimestamp: Long,
        userId: String = "system",
        owners: Collection<String>
    ): SessionMessagesAfterResponse? =
        getSessionMessages(id, userId, owners, afterTimestamp = afterTimestamp)

    /**
     * Incremental load of a session's messages.
     *
     * Exactly one cursor may be supplied (the controller enforces this):
     * - [afterSeq] — everything newer than the client's cursor (the only loss-free incremental
     *   cursor; a millisecond timestamp silently skips same-ms rows).
     * - [afterTimestamp] — legacy cursor, strictly `>` unchanged.
     */
    suspend fun getSessionMessages(
        id: String,
        userId: String = "system",
        owners: Collection<String>,
        afterTimestamp: Long? = null,
        afterSeq: Long? = null,
    ): SessionMessagesAfterResponse? {
        // Same fallback as getSessionDetail: an empty visibility set still sees the caller's
        // own rows plus the shared system layer.
        val readOwners = owners.ifEmpty { setOf(userId, AuthConstants.SYSTEM_USER_ID) }
        // Lightweight existence check + contentUpdatedAt in one query (avoids loading all messages)
        val contentUpdatedAt = sessionStore.findContentUpdatedAt(id, userId) ?: return null

        // Cursor read before the rows so a concurrent rewrite can only make the reported revision
        // *older* than the data — which invalidates a cache instead of freezing stale content.
        val cursor = runCatching { sessionStore.findSessionCursor(id, userId) }.getOrNull()

        val messagesWithTimestamps = when {
            afterSeq != null -> sessionStore.loadMessagesAfter(id, afterSeq)
            afterTimestamp != null -> sessionStore.loadMessagesWithTimestampsAfter(id, afterTimestamp)
            else -> emptyList()
        }
        val compactionAt = afterTimestamp?.let { sessionStore.getFirstCompactionAfter(id, it) }
        val sessionStatus = sessionStore.findStatus(id, userId)

        val pendingPermission = loadPendingPermission(id)

        // Load endReason so the frontend's incremental poll has the same data as getSessionDetail.
        // This prevents detectInterruptedSession from using stale/missing endReason during polling.
        val endReason = sessionStore.findEndReason(id, userId)?.takeIf { it != "normal" }

        return SessionMessagesAfterResponse(
            sessionId = id,
            messages = toMessageSnapshots(messagesWithTimestamps, userId, readOwners),
            compactionOccurredAfter = compactionAt != null,
            contentUpdatedAt = contentUpdatedAt,
            streaming = sessionStatus == "streaming",
            pendingPermission = pendingPermission,
            endReason = endReason,
            // An empty delta still advances the cursor: the client must not re-ask the same range.
            newestSeq = messagesWithTimestamps.lastOrNull()?.seq ?: cursor?.newestSeq,
            revision = cursor?.contentRevision
        )
    }

    /**
     * Get checkpoint summaries for a session.
     * Used to restore file change information when loading a historical session.
     *
     * Returns an explicit [CheckpointListResponse.status]: this endpoint is the input to the
     * edit-time rollback confirmation, so "the query failed" must never look like
     * "there is nothing to roll back".
     */
    suspend fun getCheckpoints(
        sessionId: String,
        userId: String = "system",
        owners: Collection<String>
    ): CheckpointListResponse {
        // No snapshot service, no project, or snapshots switched off for this project: there is
        // genuinely no rollback semantics here, and editing without a file check is correct.
        val service = snapshotService ?: return CheckpointListResponse.disabled()
        val context = try {
            sessionManager.getSessionContext(sessionId, userId, owners)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to resolve context for checkpoints of session {}: {}", sessionId, e.message)
            return CheckpointListResponse.failed(e.message)
        } ?: return CheckpointListResponse.disabled()
        val projectPath = context.projectPath ?: return CheckpointListResponse.disabled()
        val enabled = try {
            service.isEnabled(projectPath)
        } catch (e: Exception) {
            logger.warn("Failed to read snapshot capability for session {}: {}", sessionId, e.message)
            return CheckpointListResponse.failed(e.message)
        }
        if (!enabled) return CheckpointListResponse.disabled()

        val checkpoints = try {
            service.listCheckpoints(projectPath, sessionId)
        } catch (e: Exception) {
            logger.warn("Failed to list checkpoints for session {}: {}", sessionId, e.message)
            return CheckpointListResponse.failed(e.message)
        }

        val result = if (checkpoints.isNotEmpty()) {
            // Only ids + roles are needed here (existence check + locating the assistant reply).
            // Loading the transcript just for this cost a full deserialization per request.
            val messageRefs = sessionStore.findMessageRefs(sessionId)

            // Filter out checkpoints whose messageId no longer exists in the message list
            // (e.g., removed by compaction or message editing/truncation)
            val messageIdSet = messageRefs.mapTo(HashSet()) { it.id }
            val validCheckpoints = checkpoints.filter { it.messageId in messageIdSet }
            if (validCheckpoints.size < checkpoints.size) {
                logger.debug("getCheckpoints: filtered {} stale checkpoints (messageId not in current messages), {} remaining for session {}",
                    checkpoints.size - validCheckpoints.size, validCheckpoints.size, sessionId)
            }
            logger.info("getCheckpoints: loaded {} checkpoints ({} valid), {} messages for session {}", checkpoints.size, validCheckpoints.size, messageRefs.size, sessionId)

            // Pre-resolve session ref for fallback when checkpoint hashes are all identical
            // (due to tree-dedup in commitAs returning the same hash when no new tree is created).
            // The session ref always points to the latest real commit with actual changes.
            val sessionRefHash = try {
                service.resolveSessionRef(projectPath, sessionId)
            } catch (e: Exception) {
                logger.debug("Failed to resolve session ref for checkpoint fallback: {}", e.message)
                null
            }

            // Endpoint pairs, computed once so the diff and the author attribution always
            // describe the SAME commit range (§9 A4: authors must use effectiveTarget).
            val plans = validCheckpoints.mapIndexed { index, checkpoint ->
                val assistantMessageId = findAssistantMessageIdAfter(messageRefs, checkpoint.messageId)
                if (assistantMessageId == null) {
                    logger.debug("getCheckpoints: no assistant message found after user message {} for checkpoint {}", checkpoint.messageId, checkpoint.commitHash)
                }
                val baseline = if (index + 1 >= validCheckpoints.size) {
                    // Oldest checkpoint — use session baseline
                    try {
                        service.ensureBaseline(projectPath, sessionId)
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
                CheckpointPlan(
                    checkpoint = checkpoint,
                    assistantMessageId = assistantMessageId,
                    effectiveParent = if (needsFallback) baseline else parent,
                    effectiveTarget = if (needsFallback) sessionRefHash else checkpoint.commitHash,
                )
            }

            // Diffs: numstat + name-status only — the patch body is never read by this endpoint.
            // A failure marks just this checkpoint as incomplete instead of silently claiming
            // "no files changed" (which used to let an edit skip its rollback confirmation).
            //
            // Run with bounded parallelism: each entry is two `git diff` processes, so a 30-turn
            // session was 60 processes back to back. The cap keeps the shadow repo's object reads
            // from storming the disk while still overlapping the process latency.
            val diffPermits = Semaphore(DIFF_CONCURRENCY)
            val diffsByPlan = coroutineScope {
                plans.map { plan ->
                    async {
                        diffPermits.withPermit {
                            if (plan.effectiveParent == null) {
                                // A null parent here means the diff could not be computed at all —
                                // either the oldest checkpoint's baseline failed to resolve, or the
                                // tree-dedup fallback had no session ref to fall back to. Marking
                                // this `complete = true` would report "this turn changed no files"
                                // and let the edit skip its rollback confirmation, which is the one
                                // case the whole status contract exists for.
                                logger.warn(
                                    "No diff baseline for checkpoint {} of session {} — marking it incomplete",
                                    plan.checkpoint.messageId, sessionId
                                )
                                DiffOutcome(emptyList(), complete = false)
                            } else {
                                try {
                                    DiffOutcome(
                                        service.diffStats(projectPath, plan.effectiveParent, plan.effectiveTarget),
                                        complete = true
                                    )
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    logger.warn(
                                        "Failed to compute checkpoint diff for message {}: {}",
                                        plan.checkpoint.messageId, e.message
                                    )
                                    DiffOutcome(emptyList(), complete = false)
                                }
                            }
                        }
                    }
                }.awaitAll()
            }

            // Authors: one `git log` for the whole chain instead of one per checkpoint.
            val authorRanges = plans.indices
                .filter { plans[it].effectiveParent != null && diffsByPlan[it].files.isNotEmpty() }
                .map { plans[it].effectiveParent!! to plans[it].effectiveTarget }
            val chainAuthors: List<Map<String, String>> = if (authorRanges.isEmpty()) {
                emptyList()
            } else {
                try {
                    service.fileAuthorsInChain(projectPath, authorRanges)
                } catch (e: Exception) {
                    logger.warn("Failed to determine file authors for session {}: {}", sessionId, e.message)
                    emptyList()
                }
            }

            var authorCursor = 0
            plans.mapIndexed { index, plan ->
                val outcome = diffsByPlan[index]
                val diffs = outcome.files
                val authorMap = if (plan.effectiveParent != null && diffs.isNotEmpty()) {
                    // A missing entry means the batch could not resolve this range; fall back to
                    // "unknown author" for the files, but the diff itself stays trustworthy.
                    val authors = chainAuthors.getOrNull(authorCursor) ?: emptyMap()
                    authorCursor++
                    authors
                } else {
                    emptyMap()
                }
                CheckpointSummary(
                    messageId = plan.checkpoint.messageId,
                    assistantMessageId = plan.assistantMessageId,
                    snapshotHash = plan.checkpoint.commitHash,
                    filesChanged = diffs.map { FileChangeInfo(it.path, it.additions, it.deletions, it.status.name.lowercase(), authorMap[it.path]) },
                    additions = diffs.sumOf { it.additions },
                    deletions = diffs.sumOf { it.deletions },
                    createdAt = plan.checkpoint.timestamp,
                    // false = this entry's file list cannot be trusted. The frontend must block
                    // edits that depend on it rather than treat it as "no files changed".
                    diffsComplete = outcome.complete,
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
            val stagedDiffs = service.getStagedChanges(projectPath, sessionId)
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
                    val memberCheckpoints = service.listCheckpoints(projectPath, memberSessionId)
                    if (memberCheckpoints.isEmpty()) continue
                    val latestHash = memberCheckpoints.first().commitHash // newest-first ordering
                    val memberBaseline = try {
                        service.ensureBaseline(projectPath, memberSessionId)
                    } catch (e: Exception) {
                        logger.debug("No baseline for member session {}: {}", memberSessionId, e.message)
                        continue
                    }
                    val memberDiffs = service.diff(projectPath, memberBaseline, latestHash)
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

        return CheckpointListResponse.ok(result)
    }

    /**
     * Get session-level file diffs including team member changes.
     * Extends the parent session's baseline→latest diff with member-only file patches.
     */
    suspend fun getSessionDiffWithMembers(
        sessionId: String,
        userId: String = "system",
        owners: Collection<String>
    ): List<FileDiff> {
        if (snapshotService == null) return emptyList()
        val context = sessionManager.getSessionContext(sessionId, userId, owners) ?: return emptyList()
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
     * Works on [AsyncSessionStore.findMessageRefs] rows — the content is irrelevant here.
     */
    private fun findAssistantMessageIdAfter(messageRefs: List<MessageRef>, userMessageId: String): String? {
        logger.trace("findAssistantMessageIdAfter: searching for userMessageId={}, total messages={}", userMessageId, messageRefs.size)
        var foundUser = false
        for (ref in messageRefs) {
            if (ref.id == userMessageId) {
                foundUser = true
                logger.trace("findAssistantMessageIdAfter: found user message id={}, role={}", ref.id, ref.role)
                continue
            }
            if (foundUser && ref.role == Role.ASSISTANT.name) {
                logger.trace("findAssistantMessageIdAfter: found assistant message id={}", ref.id)
                return ref.id
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

    /**
     * Fork [sourceSessionId] up to and including [messageId] into a new branch session.
     * @throws IllegalArgumentException when the source session or anchor message is inaccessible.
     */
    suspend fun forkSession(sourceSessionId: String, messageId: String, userId: String = "system"): String {
        val newSessionId = sessionStore.createFork(sourceSessionId, messageId, userId)
            ?: throw IllegalArgumentException("Session or message not found: $sourceSessionId/$messageId")
        logger.info("Forked session {} at message {} into {}", sourceSessionId, messageId, newSessionId)
        return newSessionId
    }

    suspend fun listForks(rootSessionId: String, userId: String = "system"): List<ForkBranchInfo> {
        return sessionStore.listForks(rootSessionId, userId).map {
            ForkBranchInfo(
                id = it.id,
                title = it.title,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt,
                messageCount = it.messageCount,
                forkedFromSessionId = it.forkedFromSessionId
            )
        }
    }

    suspend fun deleteSession(id: String, userId: String = "system", owners: Collection<String>) {
        // Resolve project context once for all file-based cleanups
        val context = resolveSessionContext(id, userId, owners)
        val projectPath = context?.projectPath

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
        // Clean up background task manager (cancels any running background tasks)
        try {
            backgroundTaskManagerRegistry?.remove(id)
        } catch (e: Exception) {
            logger.warn("Failed to clean up background task manager for session {}: {}", id, e.message)
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
        // Release the temporary workspace last: forks and team member sessions deleted above
        // may still share it, and the sibling check needs their rows to be gone. The id prefix is
        // the marker here because a scratch directory already deleted out from under the DB leaves
        // the resolved context without a kind; releaseWorkspace still verifies the row is temporary.
        val workspaceProjectId = context?.projectId
        if (workspaceProjectId != null && workspaceProjectId.startsWith(DefaultWorkspaceService.TEMP_ID_PREFIX)) {
            try {
                workspaceService?.releaseForSession(id, workspaceProjectId, userId)
            } catch (e: Exception) {
                logger.warn("Failed to release temporary workspace of session {}: {}", id, e.message)
            }
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

    /** One checkpoint's resolved commit endpoints, before any git work is done. */
    private data class CheckpointPlan(
        val checkpoint: com.easy.easyai.snapshot.model.GitCheckpoint,
        val assistantMessageId: String?,
        val effectiveParent: String?,
        val effectiveTarget: String,
    )

    /** Result of diffing one plan: the files, plus whether the computation actually succeeded. */
    private data class DiffOutcome(val files: List<FileDiff>, val complete: Boolean)

    private fun extractTitle(messages: List<MessageWithTimestamp>): String? {
        val firstUserMsg = messages.firstOrNull { it.message.role == Role.USER }?.message
        val text = firstUserMsg?.content?.filterIsInstance<TextContent>()?.joinToString("") { it.text }
        return if (text.isNullOrBlank()) null else text.take(50)
    }

    private suspend fun toMessageSnapshots(
        messages: List<MessageWithTimestamp>,
        userId: String,
        owners: Collection<String>,
    ): List<MessageSnapshot> {
        val urls = mutableMapOf<String, String?>()
        return messages.map { message ->
            val fileUrls = mutableMapOf<String, String>()
            if (fileStorageService != null) {
                for (ref in message.message.content.filterIsInstance<FileRefContent>()) {
                    if (!ref.mimeType.startsWith("image/")) continue
                    if (!urls.containsKey(ref.filePath)) {
                        urls[ref.filePath] = try {
                            fileStorageService.resolveImageUrl(ref.filePath, userId, owners)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.warn("Failed to resolve session image {}: {}", ref.name, e.message)
                            null
                        }
                    }
                    urls[ref.filePath]?.let { fileUrls[ref.filePath] = it }
                }
            }
            toMessageSnapshot(message).copy(fileUrls = fileUrls.takeIf { it.isNotEmpty() })
        }
    }

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
