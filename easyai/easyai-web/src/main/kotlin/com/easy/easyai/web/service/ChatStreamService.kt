package com.easy.easyai.web.service

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.model.ModelOverridePolicy
import com.easy.easyai.api.model.ModelOverrides
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.auth.AuthConstants
import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.compaction.CompactionTransformContextService
import com.easy.easyai.compaction.ContextCompactionOrchestrator
import com.easy.easyai.core.agent.*
import com.easy.easyai.core.event.*
import com.easy.easyai.core.goal.GoalStatusListener
import com.easy.easyai.core.goal.GoalStatusNotifier
import com.easy.easyai.core.goal.GoalStore
import com.easy.easyai.core.model.*
import com.easy.easyai.core.permission.PermissionScope
import com.easy.easyai.core.permission.PermissionService
import com.easy.easyai.core.tool.ScriptEnvProvider
import com.easy.easyai.repository.project.AsyncProjectStore
import com.easy.easyai.repository.session.AsyncSessionStore
import com.easy.easyai.repository.session.CancelVerdict
import com.easy.easyai.repository.session.SessionExecutionService
import com.easy.easyai.skills.command.CommandCategory
import com.easy.easyai.skills.command.CommandReferenceException
import com.easy.easyai.skills.command.CommandService
import com.easy.easyai.skills.selection.SkillTurnRouter
import com.easy.easyai.snapshot.SnapshotService
import com.easy.easyai.tools.background.BackgroundTaskManagerRegistry
import com.easy.easyai.tools.background.BackgroundTaskStatus
import com.easy.easyai.web.context.ChatContextContributor
import com.easy.easyai.web.context.ChatContextRejectedException
import com.easy.easyai.web.context.ChatSessionTagProvider
import com.easy.easyai.web.context.ChatTurnContext
import com.easy.easyai.web.handler.ChatEventConverter
import com.easy.easyai.web.handler.CustomEventConverter
import com.easy.easyai.web.model.*
import com.easy.easyai.web.util.AttachmentProcessor
import com.easy.easyai.web.util.AttachmentValidationException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.http.HttpStatus
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.DateTimeException
import java.time.ZoneId
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Coroutine-based service that bridges EasyAI Agent with SSE streaming.
 * Converts Agent events to ServerSentEvent<ChatStreamEvent> for reactive streaming.
 * All public streaming methods return Kotlin Flow; Reactor Flux conversion happens at the Controller layer.
 */
@Service
class ChatStreamService(
    private val sessionManager: SessionManager,
    private val configStore: ModelProviderConfigStore,
    private val modelFactories: List<ChatModelFactory>,
    private val transformContextService: TransformContextService? = null,
    private val permissionService: PermissionService? = null,
    private val sessionStore: AsyncSessionStore? = null,
    private val projectStore: AsyncProjectStore? = null,
    private val snapshotService: SnapshotService? = null,
    private val customEventConverters: List<CustomEventConverter> = emptyList(),
    private val commandService: CommandService? = null,
    private val goalStatusNotifier: GoalStatusNotifier? = null,
    private val goalStore: GoalStore? = null,
    private val fileStorageService: FileStorageService? = null,
    private val scriptEnvProvider: ScriptEnvProvider? = null,
    private val executionService: SessionExecutionService? = null,
    /**
     * Per-message decision-model skill routing (v1): fires only for fresh user messages on the
     * chat and resume entry points. Steer/followUp queue messages and swarm tasks do not route;
     * sub-agents keep the whitelist + suppression baseline with `skill_search` as the escape hatch.
     */
    private val skillTurnRouter: SkillTurnRouter? = null,
    private val backgroundTaskManagerRegistry: BackgroundTaskManagerRegistry? = null,
    /** Allocates the per-session scratch workspace for chats that come in without a project. */
    private val workspaceService: DefaultWorkspaceService? = null,
    /** Guards the fresh-chat entry against an agentId with no row; see [streamChat]. */
    private val agentStore: AsyncAgentStore? = null,
    /**
     * Server-trusted session-variable contributors, invoked on every run entry (fresh chat,
     * resume, question/permission answer, manual compaction). Empty keeps the pre-existing
     * behaviour: no variables are injected and no host identity is resolved.
     */
    private val contextContributors: List<ChatContextContributor> = emptyList(),
    /**
     * Host tags for a freshly opened session. Applied once, on the first user turn, so History
     * can group sessions by business scenario. Empty keeps sessions untagged.
     */
    private val sessionTagProviders: List<ChatSessionTagProvider> = emptyList()
) : DisposableBean {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper: ObjectMapper = SharedObjectMapper.instance

    /**
     * Resume the goal timer when the user responds to a permission request or question.
     * Accumulates the paused duration into [com.easy.easyai.core.goal.GoalState.totalPausedMs].
     */
    private suspend fun resumeGoalTimer(sessionId: String, userId: String) {
        if (goalStore == null) return
        try {
            val goal = goalStore.getGoal(sessionId, userId) ?: return
            if (goal.pausedAt == null) return
            val pausedAt = goal.pausedAt!! // guaranteed non-null by early return above
            val resumed = goal.resumeTimer()
            goalStore.saveGoal(resumed, userId)
            goalStatusNotifier?.notifyGoalChanged(resumed)
            val pausedSec = (System.currentTimeMillis() - pausedAt) / 1000
            logger.info("Goal timer resumed for session {} (paused {}s)", sessionId, pausedSec)
        } catch (e: Exception) {
            logger.warn("Failed to resume goal timer for session {}: {}", sessionId, e.message)
        }
    }

    override fun destroy() {
        backgroundScope.cancel()
        sessionTaps.clear()
        sessionBridges.clear()
    }

    /**
     * Scope for detached agent execution pumps (see [buildSseFlow]). Independent of any HTTP
     * connection so a client disconnect (page refresh / navigation) cannot cancel the agent.
     */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Per-session broadcast tap for secondary SSE subscribers (e.g., historical session viewer).
     * Primary SSE consumer (buildSseFlow) emits via non-blocking tryEmit;
     * the watch endpoint collects from this flow.
     * Config mirrors SwarmEventBridge: replay=0, buffer=256, DROP_OLDEST.
     */
    private val sessionTaps = ConcurrentHashMap<String, MutableSharedFlow<ServerSentEvent<ChatStreamEvent>>>()

    /**
     * Per-session primary SSE bridge (the current execution's HTTP consumer channel).
     * Background task events are published by a per-session manager that outlives any
     * single SSE flow, so they must route to whichever bridge is current at publish
     * time instead of the bridge captured when the manager was created.
     */
    private val sessionBridges = ConcurrentHashMap<String, Channel<ServerSentEvent<ChatStreamEvent>>>()

    /**
     * Check if a session has an active SSE stream.
     * Returns dual-dimension status: local (in-memory) + remote (DB).
     */
    suspend fun isSessionStreaming(sessionId: String, userId: String = "system"): StreamingStatus {
        val local = executionService?.isLocallyExecuting(sessionId) == true
        val dbStatus = sessionStore?.findStatus(sessionId, userId)
        val remote = dbStatus == "streaming"
        return StreamingStatus(local = local, remote = remote)
    }

    /**
     * Attach to an active session's SSE broadcast as a read-only observer.
     * Terminates after done/error event (same transformWhile pattern as SwarmController.streamRunEvents).
     * Returns immediate "not_streaming" done if session is not active on this server.
     *
     * The liveness check is performed at collection time (inside the flow) to eliminate
     * the check-then-subscribe race where the session could end between check and subscription.
     */
    fun watchSession(sessionId: String): Flow<ServerSentEvent<ChatStreamEvent>> = flow {
        val tap = sessionTaps[sessionId]
        if (tap == null || executionService?.isLocallyExecuting(sessionId) != true) {
            emit(ChatStreamEvent.Done(reason = "not_streaming").toSse("done"))
            return@flow
        }
        // Attach-time context push: the tap has replay=0, so a watch subscriber that
        // connects after buildSseFlow started would otherwise miss the handshake
        // session_context event. Re-emit it from the live session on every attach.
        executionService.getActiveSession(sessionId)?.let { session ->
            emit(sessionContextSse(session))
        }
        // Attach-time replay of settled background tasks: their lifecycle events were
        // emitted before this subscriber existed (tap replay=0), so without the replay
        // the watcher would render already-finished tasks as still running.
        backgroundTaskReplaySses(sessionId).forEach { emit(it) }
        tap.transformWhile { sse ->
            emit(sse)
            sse.event() != "done" && sse.event() != "error"
        }.collect { emit(it) }
    }

    /**
     * Snapshot the session's settled background tasks as SSE lifecycle events.
     * Background task events are transient (tap replay=0, in-memory manager), so a
     * client attaching after a task settled — new turn flow, watch attach, page
     * refresh — would otherwise keep rendering it as running.
     */
    private fun backgroundTaskReplaySses(sessionId: String): List<ServerSentEvent<ChatStreamEvent>> {
        val manager = backgroundTaskManagerRegistry?.get(sessionId) ?: return emptyList()
        return manager.listTasks().mapNotNull { task ->
            val eventType = when (task.status) {
                BackgroundTaskStatus.COMPLETED -> "completed"
                BackgroundTaskStatus.FAILED -> "failed"
                BackgroundTaskStatus.CANCELLED -> "cancelled"
                BackgroundTaskStatus.RUNNING -> null
            }
            if (eventType == null) return@mapNotNull null
            ChatStreamEvent.BackgroundTaskEvent(
                event = eventType,
                taskId = task.taskId,
                toolName = task.toolName,
                result = task.result?.take(500),
                error = task.error?.take(500),
                durationMs = task.completedAt?.let { it - task.startedAt }
            ).toSse()
        }
    }

    /**
     * Return the number of sessions with active SSE streams ON THIS SERVER.
     * Replaces the old SessionManager.getActiveSessionCount() which was based on a global cache.
     */
    fun getActiveSessionCount(): Int = executionService?.getActiveSessionCount() ?: 0

    /**
     * Auto-resume a session when a background task completes and the agent loop is idle.
     * Loads the session's message history and triggers a new agent loop to process
     * the steering message injected by [com.easy.easyai.tools.background.BackgroundTaskManager].
     *
     * SSE events are broadcast via the sessionTaps mechanism so any connected clients receive them.
     *
     * @param sessionId Session to resume
     * @param userId User who owns the session
     */
    suspend fun autoResumeForBackgroundTask(sessionId: String, userId: String) {
        val session = executionService?.getActiveSession(sessionId) ?: run {
            logger.warn("Cannot auto-resume: session not found or inactive: {}", sessionId)
            return
        }

        try {
            val messages = sessionManager.loadMessages(sessionId)
            if (messages.isEmpty()) {
                logger.warn("Cannot auto-resume: no messages found for session {}", sessionId)
                return
            }

            logger.info("Auto-resuming session {} for background task completion", sessionId)

            // Create per-session broadcast tap BEFORE starting the stream to avoid missing early events
            val tap = MutableSharedFlow<ServerSentEvent<ChatStreamEvent>>(
                replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
            sessionTaps[sessionId] = tap

            val stream = session.promptWithHistory(messages)

            // Launch detached pump to process the steering message
            backgroundScope.launch {
                val handle = executionService.beginExecution(sessionId, userId, session)
                var endReason = "normal"
                try {
                    stream.asFlow().collect { event ->
                        if (event is AgentEndEvent) {
                            endReason = event.endReason
                            session.lastEndReason = event.endReason
                        }
                        val chatEvents = ChatEventConverter.convert(event, customEventConverters)
                        chatEvents.forEach { chatEvent ->
                            tap.tryEmit(chatEvent.toSse())
                        }
                    }
                } catch (e: CancellationException) {
                    logger.info("Auto-resume cancelled for session {}", sessionId)
                } catch (e: Exception) {
                    logger.error("Error in auto-resume for session {}", sessionId, e)
                } finally {
                    sessionTaps.remove(sessionId)
                    handle.let { executionService.endExecution(it, endReason) }
                    logger.debug("Auto-resume terminated for session {}", sessionId)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to auto-resume session {} for background task", sessionId, e)
        }
    }

    // ==================== Helper functions ====================

    private fun ChatStreamEvent.toSse(eventType: String = this.type): ServerSentEvent<ChatStreamEvent> =
        ServerSentEvent.builder<ChatStreamEvent>().event(eventType).data(this).build()

    private fun errorSse(message: String?, isRetryable: Boolean = false): ServerSentEvent<ChatStreamEvent> =
        ChatStreamEvent.Error(errorMessage = message, isRetryable = isRetryable).toSse("error")

    /**
     * Handshake event carrying the session's active model context window, so the
     * frontend token bar renders the real usage percentage instead of a hardcoded default.
     */
    private fun sessionContextSse(session: ChatSession): ServerSentEvent<ChatStreamEvent> =
        ChatStreamEvent.SessionContext(
            sessionId = session.id,
            modelContextLength = session.agentContext.modelContextLength,
            modelId = session.agentContext.modelId,
            projectId = session.agentContext.projectId,
            projectPath = session.agentContext.projectPath?.toString(),
            projectKind = session.agentContext.projectKind.value
        ).toSse()

    // ==================== Public API ====================

    /**
     * Stream chat events as a Kotlin Flow.
     */
    suspend fun streamChat(
        request: ChatRequest,
        userId: String = "system",
        owners: Set<String> = emptySet(),
        turn: ChatTurnContext? = null
    ): Flow<ServerSentEvent<ChatStreamEvent>> {
        val configId = request.modelProviderConfigId
            ?: return flowOf(errorSse("modelProviderConfigId is required"))

        // Group sharing: resolve the model against the caller's full visibility set (self + group +
        // system) so a member can chat with a group-owned config. Empty owners (internal/test callers)
        // fall back to the pre-group {userId, system}.
        val readOwners = owners.ifEmpty { setOf(userId, AuthConstants.SYSTEM_USER_ID) }
        val storedConfig = try {
            configStore.getConfig(configId, readOwners)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return flowOf(errorSse(e.message))
        } ?: return flowOf(errorSse("Config not found: $configId"))

        // Composer overrides are validated against the stored row's declared profile and then
        // folded into a per-run copy — the saved config is never touched.
        val overrides = request.modelOverrides
        if (overrides != null) {
            try {
                ModelOverridePolicy.validate(storedConfig, overrides)
            } catch (e: IllegalArgumentException) {
                return flowOf(errorSse(e.message ?: "Invalid model overrides"))
            }
        }
        val config = ModelOverridePolicy.apply(storedConfig, overrides)

        val factory = modelFactories.firstOrNull { it.supports(config.protocol) }
            ?: return flowOf(errorSse("No ChatModelFactory for protocol: ${config.protocol}"))

        val agentId = request.agentId
        // A bad zone is a client bug, not a reason to guess: a watch scheduled in the wrong zone
        // would fire at a time the user never asked for.
        val requestTimeZone = try {
            request.timezone?.trim()?.takeIf { it.isNotEmpty() }?.let { ZoneId.of(it) }
        } catch (e: DateTimeException) {
            return flowOf(
                errorSse(
                    "Invalid timezone '${request.timezone}'; use an IANA zone such as Asia/Shanghai"
                )
            )
        }

        return try {
            // Reject an agentId nobody owns before any session state is minted: the session manager takes
            // an empty tool list as final, so a row-less agent would open a session that can do nothing.
            // Same owner set the manager resolves against. No store means nothing to check against.
            if (agentStore != null && agentStore.findById(agentId, readOwners) == null) {
                return flowOf(errorSse(
                    "Agent not found: '$agentId'. Select an existing agent — a session cannot resolve tools without one."
                ))
            }
            // Mint the session id up front: a project-less chat resolves its temporary workspace
            // by session, so the very first turn must already run inside that directory.
            val sessionId = request.sessionId ?: UUID.randomUUID().toString()
            // Resolve the request identity before anything per-session exists: a rejected
            // contributor must not leave a workspace directory and temp project row behind.
            val contributed = resolveContributions(turn?.copy(sessionId = sessionId))
            val agentContext = createAgentContext(
                agentId, config, sessionId, request.projectId, userId, readOwners, requestTimeZone
            ).let { ctx ->
                if (request.inputData != null) ctx.copy(inputVariables = request.inputData) else ctx
            }
            // Trusted values survive the manager's restoreSessionVariables() later in this call.
            applyContributions(agentContext, contributed)

            val session = sessionManager.getOrCreateSession(
                agentContext = agentContext,
                config = config,
                chatOptionsFactory = factory
            )
            // Recorded after the row exists: resume/answer paths reload the config from DB and must
            // replay the same window/effort. A `{}` payload clears a previous selection.
            if (overrides != null) persistModelOverrides(sessionId, overrides)
            chatFlow(agentContext, session, request.message, request.attachments)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChatContextRejectedException) {
            throw e
        } catch (e: Exception) {
            flowOf(errorSse(e.message))
        }
    }

    /**
     * Persist the applied overrides on the session row. Best-effort: the current run already uses
     * them in memory, and losing this record only affects a later resume/history view.
     */
    private suspend fun persistModelOverrides(sessionId: String, overrides: ModelOverrides) {
        val store = sessionStore ?: return
        try {
            val json = if (overrides.isEmpty()) null else objectMapper.writeValueAsString(overrides)
            store.saveModelOverrides(sessionId, json)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to persist model overrides for session {}: {}", sessionId, e.message)
        }
    }

    /**
     * Cancel an ongoing chat session.
     * Delegates to [SessionExecutionService.cancelExecution] which handles:
     * handle removal → session.abort() → DB status update (synchronous).
     *
     * @return the [CancelVerdict]: whether a live run on this instance was actually aborted, plus
     *   the session row's state before this call downgraded it. The controller reports both to the
     *   client instead of claiming unconditional success — the downgrade erases the evidence, so a
     *   client that re-reads `streaming-status` afterwards can never see the run this verdict is
     *   about.
     */
    suspend fun cancelChat(sessionId: String, userId: String = "system"): CancelVerdict {
        val verdict = try {
            if (executionService != null) {
                executionService.cancelExecution(sessionId, userId)
            } else {
                // Fallback: direct DB write when no execution service. Runs are unregistered in this
                // deployment (the chat flow's beginExecution is a no-op too), so there is nothing
                // here that could be aborted — the write is bookkeeping, not a confirmed stop, and no
                // run can be live behind it.
                sessionStore?.let { store ->
                    try {
                        store.updateStatus(sessionId, "active", userId, expectedStatus = "streaming")
                        store.saveEndReason(sessionId, "cancelled", userId)
                    } catch (e: Exception) {
                        logger.warn("Failed to update status after cancel for {}", sessionId, e)
                    }
                }
                CancelVerdict(aborted = false, rowWasStreaming = false)
            }
        } catch (e: Exception) {
            logger.warn("Failed to cancel session: {}", sessionId, e)
            // The row may still say `streaming` when the failure happened before the downgrade, so it
            // is still worth reading here — unlike the delegate path, this one erased nothing.
            CancelVerdict(
                aborted = false,
                rowWasStreaming = runCatching { isSessionStreaming(sessionId, userId).remote }.getOrDefault(false)
            )
        }
        // Kept out of the try above: a failure to clear the pending permission must not report the
        // cancel itself as unconfirmed, or the client would keep showing a running agent.
        try {
            sessionStore?.savePendingPermission(sessionId, null)
        } catch (e: Exception) {
            logger.warn("Failed to clear pending permission for session {}: {}", sessionId, e.message)
        }
        return verdict
    }

    // ==================== Queue management API ====================

    /**
     * Add a queued message (steer or followUp) to a session.
     * Returns the generated queue ID.
     */
    suspend fun addQueuedMessage(
        sessionId: String,
        userId: String,
        content: String,
        type: String,
        attachments: List<ChatAttachment>? = null
    ): QueuedMessageResponse {
        require(type == "steer" || type == "followUp") { "Invalid queue type: $type" }
        val session = executionService?.getActiveSession(sessionId)
            ?: throw IllegalStateException("No active session for queue operation: $sessionId")
        require(session.agentContext.userId == userId) { "Session is not available to this user" }

        // Validate attachments and extract inline @ references BEFORE command expansion
        // (which has side effects: goal creation, notification) so an invalid attachment
        // fails fast without leaving partial state.
        // Attachments have no inline position — anchor them at the end of the cleaned text.
        val projectDir = session.agentContext.projectPath?.toAbsolutePath()?.normalize()
        val owners = session.agentContext.effectiveOwners
        val contentBlocks = AttachmentProcessor.buildContentBlocks(content, projectDir, userId, owners, fileStorageService).toMutableList()
        if (fileStorageService != null) {
            // New path: process all attachments via FileRefContent (no base64 in DB).
            // Skip attachments already covered by an inline ref so the same image is not sent twice.
            val inlinePaths = contentBlocks.filterIsInstance<FileRefContent>().mapTo(mutableSetOf()) { it.filePath }
            val textEnd = contentBlocks.filterIsInstance<TextContent>().firstOrNull()?.text?.length ?: 0
            val pendingAttachments = attachments?.filterNot { it.filePath != null && it.filePath in inlinePaths }
            contentBlocks.addAll(
                AttachmentProcessor.processAttachments(
                    pendingAttachments, fileStorageService, sessionId,
                    anchorOffset = textEnd, projectDir = projectDir, userId = userId, owners = owners
                )
            )
        } else {
            // Legacy path: base64 ImageContent (backward compatibility)
            val decodedImages = AttachmentProcessor.decodeImageAttachments(attachments)
            for ((img, bytes) in decodedImages) {
                contentBlocks.add(ImageContent(bytes, img.mimeType))
            }
        }
        val userMessage = prepareCommandMessage(content, UserMessage(content = contentBlocks), session)

        val queueId = when (type) {
            "steer" -> session.steerWithId(userMessage)
            "followUp" -> session.followUpWithId(userMessage)
            else -> throw IllegalArgumentException("Invalid queue type: $type (must be 'steer' or 'followUp')")
        }
        logger.info("Queued {} message {} for session {}", type, queueId, sessionId)
        return QueuedMessageResponse(id = queueId, content = content, type = type)
    }

    /**
     * Remove a queued message by ID from a session.
     * Returns true if the message was found and removed.
     */
    suspend fun removeQueuedMessage(
        sessionId: String,
        queueId: String
    ): Boolean {
        val session = executionService?.getActiveSession(sessionId)
            ?: throw IllegalStateException("No active session for queue removal: $sessionId")
        val removed = session.removeQueuedMessage(queueId)
        if (removed) {
            logger.info("Removed queued message {} from session {}", queueId, sessionId)
        } else {
            logger.warn("Queued message {} not found in session {}", queueId, sessionId)
        }
        return removed
    }

    /**
     * Update the content of a queued message by ID.
     * Returns true if the message was found and updated.
     */
    suspend fun updateQueuedMessage(
        sessionId: String,
        queueId: String,
        newContent: String
    ): Boolean {
        val session = executionService?.getActiveSession(sessionId)
            ?: throw IllegalStateException("No active session for queue update: $sessionId")
        val previous = session.getQueuedMessage(queueId) ?: return false
        if (previous.metadata[UserMessage.COMMAND_CATEGORY] == CommandCategory.BUILTIN.name) {
            throw CommandReferenceException("Queued built-in commands cannot be edited; use Goal management instead")
        }
        val blocks = AttachmentProcessor.buildContentBlocks(
            newContent, session.agentContext.projectPath,
            session.agentContext.userId ?: "system", session.agentContext.effectiveOwners, fileStorageService
        ).toMutableList()
        blocks.addAll(previous.content.filter { it !is TextContent })
        val replacement = prepareCommandMessage(
            newContent, previous.copy(content = blocks), session, allowSideEffects = false
        )
        val updated = session.updateQueuedMessage(queueId, previous, replacement)
        if (updated) {
            logger.info("Updated queued message {} in session {}", queueId, sessionId)
        } else {
            logger.warn("Queued message {} not found in session {} for update", queueId, sessionId)
        }
        return updated
    }

    /**
     * Reorder queued messages by the given ID list.
     */
    suspend fun reorderQueuedMessages(
        sessionId: String,
        ids: List<String>
    ) {
        val session = executionService?.getActiveSession(sessionId)
            ?: throw IllegalStateException("No active session for queue reorder: $sessionId")
        session.reorderQueuedMessages(ids)
        logger.info("Reordered {} queued messages in session {}", ids.size, sessionId)
    }

    /**
     * Return a snapshot of all queued messages (steering + followUp) for a session.
     */
    suspend fun getQueuedMessages(
        sessionId: String
    ): List<QueuedMessageResponse> {
        val session = executionService?.getActiveSession(sessionId)
        // Return empty list if session is not actively streaming (queue is ephemeral)
            ?: return emptyList()
        return session.getQueuedMessages().map {
            QueuedMessageResponse(id = it.id, content = it.content, type = it.type)
        }
    }

    /**
     * Resume a cancelled or errored chat session.
     * Optionally accepts a user message to add before resuming.
     * Injects resumption context and streams the continuation as SSE.
     */
    suspend fun resumeChat(sessionId: String, userId: String = "system", message: String? = null, owners: Collection<String> = emptyList(), turn: ChatTurnContext? = null): Flow<ServerSentEvent<ChatStreamEvent>> {
        return try {
            val session = sessionManager.getSession(sessionId, userId, owners)
                ?: throw IllegalStateException("Session not found: $sessionId")
            // A resumed session restores only compaction-persisted variables; re-resolve the
            // request's current identity instead of trusting a possibly stale snapshot.
            applyContextContributors(session.agentContext, turn?.copy(sessionId = sessionId))

            // Resume goal timer if it was paused waiting for user input
            resumeGoalTimer(sessionId, userId)

            // Load fresh messages from DB (single source of truth)
            val messages = sessionManager.loadMessages(sessionId)

            commandService?.validateReplay(messages, userId, session.agentContext.effectiveOwners)
            val stream = if (message.isNullOrBlank()) {
                session.resume(messages = messages)
            } else {
                val content = AttachmentProcessor.buildContentBlocks(
                    message, session.agentContext.projectPath, userId, session.agentContext.effectiveOwners, fileStorageService
                )
                val prepared = prepareCommandMessage(message, UserMessage(content = content), session)
                sessionManager.saveSessionMessages(session.agentContext, listOf(prepared))
                skillTurnRouter?.routeOwners(session.agentContext.effectiveOwners, session.agentContext.allowedSkillNames, message)
                    ?.let { session.updateTurnSkills(it) }
                session.promptWithHistory(messages + prepared)
            }

            buildSseFlow(session, stream, context = "resume", isRetryable = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChatContextRejectedException) {
            throw e
        } catch (e: Exception) {
            flowOf(errorSse(e.message?.removePrefix("Session not found: ") ?: e.message))
        }
    }

    private suspend fun chatFlow(
        agentContext: AgentContext,
        session: ChatSession,
        message: String?,
        attachments: List<ChatAttachment>? = null
    ): Flow<ServerSentEvent<ChatStreamEvent>> {
        if (message.isNullOrBlank() && attachments.isNullOrEmpty()) {
            return flowOf(errorSse("Message is required"))
        }

        // Text attachments are inlined by the frontend; backend only handles images.
        val messageText = message.orEmpty()

        logger.info("Processing chat for session: {} with agentId={}, modelId={}",
            session.id, agentContext.agentId, agentContext.modelId)

        // Generate a messageId for this user turn
        val userMessageId = UUID.randomUUID().toString()
        val projectPath = agentContext.projectPath
        val projectDir = projectPath?.toAbsolutePath()?.normalize()

        // Build content blocks (text + file refs / images) up front, before any side-effecting
        // step below (revert-state cleanup, command expansion which creates goals), so that an
        // attachment validation failure returns early without leaving partial state.
        // Inline @ references (‛[name](path)‛) become FileRefContent/FolderRefContent blocks
        // anchored at their sentence position; attachments have no inline position and are
        // anchored at the end of the cleaned text.
        val chatUserId = agentContext.userId ?: "system"
        val owners = agentContext.effectiveOwners
        val contentBlocks = AttachmentProcessor.buildContentBlocks(messageText, projectDir, chatUserId, owners, fileStorageService).toMutableList()
        if (fileStorageService != null) {
            // New path: attachments → FileRefContent/FolderRefContent (no base64 in DB).
            // Skip attachments already covered by an inline ref so the same image is not sent twice.
            try {
                val inlinePaths = contentBlocks.filterIsInstance<FileRefContent>().mapTo(mutableSetOf()) { it.filePath }
                val pendingAttachments = attachments?.filterNot { it.filePath != null && it.filePath in inlinePaths }
                val textEnd = contentBlocks.filterIsInstance<TextContent>().firstOrNull()?.text?.length ?: 0
                contentBlocks.addAll(
                    AttachmentProcessor.processAttachments(
                        pendingAttachments, fileStorageService, session.id,
                        anchorOffset = textEnd, projectDir = projectDir, userId = chatUserId, owners = owners
                    )
                )
            } catch (e: AttachmentValidationException) {
                return flowOf(errorSse(e.message))
            }
        } else {
            // Legacy path: base64 ImageContent (backward compatibility)
            try {
                val decodedImages = AttachmentProcessor.decodeImageAttachments(attachments)
                decodedImages.forEach { (img, bytes) -> contentBlocks.add(ImageContent(bytes, img.mimeType)) }
            } catch (e: AttachmentValidationException) {
                return flowOf(errorSse(e.message))
            }
        }

        // Cleanup revert state when user sends a new message
        if (projectPath != null) {
            try {
                val revertState = snapshotService?.loadRevertState(projectPath, session.id)
                if (revertState != null) {
                    snapshotService.clearRevertState(projectPath, session.id)
                    logger.info("Cleaned up revert state for session {} on new message", session.id)
                }
            } catch (e: Exception) {
                logger.warn("Failed to cleanup revert state for session {}: {}", session.id, e.message)
            }
        }

        val history = sessionManager.loadMessages(session.id)
        // A session row can be pre-created by POST /api/chat/session, so "no history" is the
        // reliable first-turn signal. Resumed turns keep whatever tags the user has edited.
        if (history.isEmpty()) applySessionTags(agentContext, session.id)
        commandService?.validateReplay(history, agentContext.userId ?: "system", agentContext.effectiveOwners)

        // Resume goal timer if paused (defensive: user may send a new message instead of using dedicated resume endpoints)
        resumeGoalTimer(session.id, agentContext.userId ?: "system")

        // Pre-register goal listener BEFORE command expansion so the initial goal_status
        // event from GoalCommandHandler is captured (UNLIMITED channel buffers it until
        // the flow is collected).
        val preGoalChannel = Channel<AgentEvent>(Channel.UNLIMITED)
        val preGoalListener = createGoalListener(session.id, preGoalChannel)
        preGoalListener?.let { goalStatusNotifier?.addListener(it) }

        val userMessage = try {
            prepareCommandMessage(messageText, UserMessage(id = userMessageId, content = contentBlocks), session)
        } catch (e: Exception) {
            preGoalListener?.let { goalStatusNotifier?.removeListener(it) }
            preGoalChannel.close()
            throw e
        }
        val messages = history + userMessage
        sessionManager.saveSessionMessages(session.agentContext, listOf(userMessage))
        // Update session agent with fresh inputVariables from current request
        session.updateInputVariables(agentContext.inputVariables)
        skillTurnRouter?.routeOwners(session.agentContext.effectiveOwners, session.agentContext.allowedSkillNames, messageText)
            ?.let { session.updateTurnSkills(it) }
        val stream = session.promptWithHistory(messages)

        // Wrap: emit user_message_ack first so the frontend can associate the persisted
        // messageId with the optimistically-added user message (enables edit capability),
        // then delegate to buildSseFlow for the agent event stream.
        // Pass the pre-registered goal channel so buildSseFlow reuses it (no duplicate listener).
        val sseFlow = buildSseFlow(session, stream, context = "chat", isRetryable = false,
            preGoalChannel = preGoalChannel, preGoalListener = preGoalListener)
        return flow {
            emit(ChatStreamEvent.UserMessageAck(messageId = userMessageId).toSse())
            emitAll(sseFlow)
        }
    }

    /**
     * Best-effort initial tags for a fresh session. Tagging is metadata only: a failing host
     * provider must never block the chat turn, and a cancellation still propagates.
     */
    private suspend fun applySessionTags(agentContext: AgentContext, sessionId: String) {
        val store = sessionStore ?: return
        if (sessionTagProviders.isEmpty()) return
        try {
            val tags = sessionTagProviders
                .flatMap { it.tags(agentContext) }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            if (tags.isNotEmpty()) {
                store.updateTags(sessionId, tags, agentContext.userId ?: "system")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to apply session tags for {}: {}", sessionId, e.message)
        }
    }

    private suspend fun prepareCommandMessage(
        rawText: String,
        message: UserMessage,
        session: ChatSession,
        allowSideEffects: Boolean = true
    ): UserMessage {
        val context = session.agentContext
        val userId = context.userId ?: "system"
        val expansion = commandService?.resolveAndExpand(rawText, userId, session.id, allowSideEffects, context.effectiveOwners)
        val commandKeys = setOf(
            UserMessage.COMMAND_NAME, UserMessage.COMMAND_EXPANSION, UserMessage.COMMAND_SOURCE,
            UserMessage.COMMAND_CATEGORY, UserMessage.COMMAND_USER_ID
        )
        val metadata = message.metadata - commandKeys + commandService?.metadata(expansion, userId).orEmpty()
        return message.copy(metadata = metadata)
    }

    /**
     * Resolve the project backing one chat turn.
     *
     * A turn carries either the project the user picked (or the one already bound to the session)
     * or nothing at all. "Nothing at all" is not an error: the session gets its own temporary
     * workspace so tools run inside a contained directory instead of the backend process directory.
     */
    private suspend fun resolveTurnProject(
        requestedProjectId: String?,
        sessionId: String?,
        userId: String
    ): TurnProject? {
        if (requestedProjectId != null) {
            val found = projectStore?.findById(requestedProjectId, userId)
            if (found != null) return TurnProject(found.id, found)
            // A temporary workspace row can be gone while its session outlives it (partial
            // cleanup, resumed stream): re-allocate rather than failing the turn.
            if (sessionId != null && requestedProjectId.startsWith(DefaultWorkspaceService.TEMP_ID_PREFIX)
                && workspaceService != null
            ) {
                val workspace = workspaceService.resolveOrCreate(sessionId, userId)
                logger.info("Re-allocated temporary workspace {} for session {}", workspace.projectId, sessionId)
                return TurnProject(workspace.projectId, toProjectInfo(workspace))
            }
            throw IllegalArgumentException("Project is not available to this user")
        }
        if (sessionId != null && workspaceService != null) {
            val workspace = workspaceService.resolveOrCreate(sessionId, userId)
            return TurnProject(workspace.projectId, toProjectInfo(workspace))
        }
        return null
    }

    private fun toProjectInfo(workspace: Workspace): ProjectInfo = ProjectInfo(
        id = workspace.projectId,
        name = DefaultWorkspaceService.TEMP_PROJECT_NAME,
        path = workspace.path.toString(),
        kind = workspace.kind
    )

    /** The project a turn runs against: which row to bind and how to resolve its directory. */
    private data class TurnProject(
        val projectId: String,
        val project: ProjectInfo
    )

    private suspend fun createAgentContext(
        agentId: String,
        config: ModelProviderConfig,
        sessionId: String? = null,
        projectId: String? = null,
        userId: String = "system",
        owners: Set<String> = emptySet(),
        timeZone: ZoneId? = null
    ): AgentContext {
        if (sessionId != null && sessionStore?.isSessionOwnedByUser(sessionId, userId) == false) {
            throw IllegalArgumentException("Session is not available to this user")
        }
        val persisted = sessionId?.let { sessionStore?.findById(it, userId) }
        // A brand-new session must never adopt a client-supplied temporary workspace id: that
        // scratch dir belongs to another session (stale frontend state). Nulling it lets
        // resolveTurnProject mint this session's own workspace, while an existing session keeps
        // its persisted binding (forks and resumed streams share workspaces by design).
        val requestedProjectId = when {
            persisted != null -> persisted.projectId
            projectId != null && projectId.startsWith(DefaultWorkspaceService.TEMP_ID_PREFIX) -> null
            else -> projectId
        }
        val turn = resolveTurnProject(requestedProjectId, sessionId, userId)
        val verifiedProjectId = turn?.projectId
        val project = turn?.project
        val projectPath = project?.let {
            require(it.path.isNotBlank()) { "Project directory is unavailable" }
            val path = Path.of(it.path).toAbsolutePath().normalize()
            require(withContext(Dispatchers.IO) { Files.isDirectory(path) }) { "Project directory is unavailable" }
            path
        }

        // Build a partial context for script env generation
        val partialContext = AgentContext(
            agentId = agentId,
            modelConfig = config,
            sessionId = sessionId,
            userId = userId,
            owners = owners
        )
        val scriptEnv = scriptEnvProvider?.getScriptEnv(partialContext) ?: emptyMap()

        return AgentContext(
            agentId = agentId,
            modelConfig = config,
            sessionId = sessionId,
            userId = userId,
            owners = owners,
            projectId = verifiedProjectId,
            projectPath = projectPath,
            projectKind = project?.kind ?: ProjectKind.USER,
            timeZone = timeZone,
            memoryAutoGeneration = project?.memoryAutoGeneration ?: true,
            modelContextLength = config.options?.contextToken ?: 204_800,
            scriptEnv = scriptEnv,
            sessionLookup = { sessionId?.let { executionService?.getActiveSession(it) } },
            isSessionExecuting = { sessionId?.let { executionService?.isLocallyExecuting(it) } ?: false },
            backgroundEventPublisher = { event ->
                val chatEvents = ChatEventConverter.convert(event, customEventConverters)
                chatEvents.forEach { chatEvent ->
                    sessionId?.let { sessionTaps[it]?.tryEmit(chatEvent.toSse()) }
                }
            },
            backgroundAutoResume = { sid, uid -> autoResumeForBackgroundTask(sid, uid) }
        )
    }

    /**
     * Merge every contributor's values into [context] as trusted session variables.
     *
     * Run entry points whose session-row validation already happened call this; [streamChat]
     * resolves the contributions first so a rejection cannot leave per-session state behind.
     */
    private suspend fun applyContextContributors(context: AgentContext, turn: ChatTurnContext?) {
        applyContributions(context, resolveContributions(turn))
    }

    /** Run every contributor for this request; throws when a host refuses the run. */
    private suspend fun resolveContributions(turn: ChatTurnContext?): Map<String, String> {
        if (turn == null || contextContributors.isEmpty()) return emptyMap()
        val contributed = LinkedHashMap<String, String>()
        for (contributor in contextContributors) {
            contributed.putAll(contributor.contribute(turn))
        }
        return contributed
    }

    private fun applyContributions(context: AgentContext, contributed: Map<String, String>) {
        contributed.forEach { (key, value) -> context.sessionVariables.putTrusted(key, value) }
    }

    /**
     * Validate a permission/compaction continuation before it produces side effects, and return
     * the restored session so the caller does not have to restore it a second time.
     * The resumed flow validates again only when no session is handed over.
     */
    private suspend fun validateTurnIdentity(
        sessionId: String,
        userId: String,
        owners: Collection<String>,
        turn: ChatTurnContext?,
    ): ChatSession? {
        if (turn == null || contextContributors.isEmpty()) return null
        val session = sessionManager.getSession(sessionId, userId, owners)
            // This runs outside the SSE flow, so it must use the same HTTP mapping as compact:
            // an IllegalStateException here would surface as an opaque 500.
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found: $sessionId")
        applyContextContributors(session.agentContext, turn.copy(sessionId = sessionId))
        return session
    }

    /**
     * Resume after answering a question.
     * Constructs a ToolResultMessage (role=TOOL) and resumes the session.
     */
    suspend fun resumeAfterAnswer(sessionId: String, userId: String = "system", toolCallId: String, answers: List<List<String>>, owners: Collection<String> = emptyList(), turn: ChatTurnContext? = null): Flow<ServerSentEvent<ChatStreamEvent>> {
        val answerText = buildAnswerText(answers)
        val toolResultEntry = ToolResultEntry(
            toolCallId = toolCallId,
            toolName = "", // will be resolved in resumeWithToolResult
            result = answerText,
            isError = false
        )
        return resumeWithToolResult(
            sessionId = sessionId,
            userId = userId,
            toolCallId = toolCallId,
            context = "answer",
            owners = owners,
            turn = turn
        ) { messages, resolvedToolCallId ->
            // toolName will be resolved from messages
            val toolName = findToolNameById(messages, resolvedToolCallId)
            toolResultEntry.copy(toolName = toolName)
        }
    }

    /**
     * Reject a pending question and resume the session.
     * Constructs a ToolResultMessage (role=TOOL) with rejection info and resumes the session.
     */
    suspend fun rejectAndResume(sessionId: String, userId: String = "system", toolCallId: String, owners: Collection<String> = emptyList(), turn: ChatTurnContext? = null): Flow<ServerSentEvent<ChatStreamEvent>> {
        val toolResultEntry = ToolResultEntry(
            toolCallId = toolCallId,
            toolName = "", // will be resolved in resumeWithToolResult
            result = "[User dismissed the question]",
            isError = true
        )
        return resumeWithToolResult(
            sessionId = sessionId,
            userId = userId,
            toolCallId = toolCallId,
            context = "resume",
            owners = owners,
            turn = turn
        ) { messages, resolvedToolCallId ->
            val toolName = findToolNameById(messages, resolvedToolCallId)
            toolResultEntry.copy(toolName = toolName)
        }
    }

    /**
     * Allow a pending permission request and resume the session.
     * If remember=true, saves an "always allow" rule to the database.
     *
     * @param permission Permission type echoed back from the frontend (originally sent in PermissionRequestEvent)
     * @param pattern Pattern echoed back from the frontend
     */
    suspend fun allowPermissionAndResume(sessionId: String, userId: String = "system", toolCallId: String, remember: Boolean, permission: String?, pattern: String?, owners: Collection<String> = emptyList(), turn: ChatTurnContext? = null): Flow<ServerSentEvent<ChatStreamEvent>> {
        // If remember=true, save the allow rule before resuming
        var validated: ChatSession? = null
        if (remember && permissionService != null && permission != null) {
            // Validate the request identity before persisting anything: a rejected turn must not
            // leave a durable rule behind.
            validated = validateTurnIdentity(sessionId, userId, owners, turn)
            val scopeContext = validated?.agentContext
                ?: sessionManager.getSessionContext(sessionId, userId, owners)
            scopeContext?.projectId?.let { projectId ->
                // Temporary workspaces keep no durable rules; their "always allow" lands in the
                // default bucket (the family's shared one when the login is group-backed), which
                // every project-less session of that scope reads.
                val ruleScope = PermissionScope.forProject(projectId, scopeContext.projectKind, userId, owners)
                permissionService.addAllowRule(ruleScope, permission, pattern ?: "*")
                logger.info("Saved allow rule for {} {} in scope {}", permission, pattern, ruleScope)
            }
        }

        // Permission allow: do NOT save a ToolResultMessage.
        // executePendingToolCallsIfNeeded will execute the tool directly (allow rule already saved),
        // producing the real result.
        return resumeWithToolResult(
            sessionId = sessionId,
            userId = userId,
            toolCallId = toolCallId,
            context = "permission_allow",
            saveToolResult = false,
            owners = owners,
            turn = turn,
            preloadedSession = validated
        ) { messages, resolvedToolCallId ->
            val toolName = findToolNameById(messages, resolvedToolCallId)
            ToolResultEntry(toolCallId = resolvedToolCallId, toolName = toolName, result = "", isError = false)
        }
    }

    /**
     * Deny a pending permission request and resume the session.
     * If remember=true, saves a "deny" rule to the database.
     *
     * @param permission Permission type echoed back from the frontend (originally sent in PermissionRequestEvent)
     * @param pattern Pattern echoed back from the frontend
     */
    suspend fun denyPermissionAndResume(sessionId: String, userId: String = "system", toolCallId: String, remember: Boolean, reason: String?, permission: String?, pattern: String?, owners: Collection<String> = emptyList(), turn: ChatTurnContext? = null): Flow<ServerSentEvent<ChatStreamEvent>> {
        // If remember=true, save the deny rule before resuming
        var validated: ChatSession? = null
        if (remember && permissionService != null && permission != null) {
            // Validate the request identity before persisting anything: a rejected turn must not
            // leave a durable rule behind.
            validated = validateTurnIdentity(sessionId, userId, owners, turn)
            val scopeContext = validated?.agentContext
                ?: sessionManager.getSessionContext(sessionId, userId, owners)
            scopeContext?.projectId?.let { projectId ->
                val ruleScope = PermissionScope.forProject(projectId, scopeContext.projectKind, userId, owners)
                permissionService.addDenyRule(ruleScope, permission, pattern ?: "*")
                logger.info("Saved deny rule for {} {} in scope {}", permission, pattern, ruleScope)
            }
        }

        val denyReason = reason ?: "Permission denied by user"

        val entry = ToolResultEntry(
            toolCallId = toolCallId,
            toolName = "",
            result = "[Permission denied: $denyReason]",
            isError = true
        )
        return resumeWithToolResult(
            sessionId = sessionId,
            userId = userId,
            toolCallId = toolCallId,
            context = "permission_deny",
            owners = owners,
            turn = turn,
            preloadedSession = validated
        ) { messages, resolvedToolCallId ->
            val toolName = findToolNameById(messages, resolvedToolCallId)
            entry.copy(toolName = toolName)
        }
    }

    /**
     * Common logic for resuming a session with a tool result.
     * Sends completion events and continues the stream.
     *
     * @param sessionId The session ID
     * @param toolCallId The tool call ID
     * @param context Context label for logging
     * @param saveToolResult Whether to save the tool result message to DB.
     *   - true: for ask_question / permission deny — the tool result is final
     *   - false: for permission allow — executePendingToolCallsIfNeeded will execute the tool directly
     * @param resolveToolName Lambda to resolve the tool name from messages before building the ToolResultEntry
     */
    private suspend fun resumeWithToolResult(
        sessionId: String,
        userId: String = "system",
        toolCallId: String,
        context: String,
        saveToolResult: Boolean = true,
        owners: Collection<String> = emptyList(),
        turn: ChatTurnContext? = null,
        /** Already restored and contributor-validated by the caller; skips both steps here. */
        preloadedSession: ChatSession? = null,
        resolveToolName: (messages: List<EasyAiMessage>, toolCallId: String) -> ToolResultEntry
    ): Flow<ServerSentEvent<ChatStreamEvent>> {
        return try {
            val session = preloadedSession ?: sessionManager.getSession(sessionId, userId, owners)
                ?: throw IllegalStateException("Session not found: $sessionId")
            // Continuations re-resolve the request identity; the restored context only carries
            // whatever a previous compaction happened to persist. A preloaded session already has
            // this request's contributions applied to the same AgentContext instance.
            if (preloadedSession == null) {
                applyContextContributors(session.agentContext, turn?.copy(sessionId = sessionId))
            }

            // Clear pending permission from DB (permission has been answered)
            if (context.startsWith("permission_") && sessionStore != null) {
                sessionStore.savePendingPermission(sessionId, null)
                logger.info("Cleared pending permission for session {}", sessionId)
            }

            // Resume goal timer if it was paused waiting for user input
            resumeGoalTimer(sessionId, userId)

            // Load fresh messages from DB
            val messages = sessionManager.loadMessages(session.id)
            commandService?.validateReplay(messages, userId, session.agentContext.effectiveOwners)

            val chatContext = session.agentContext
            val messagesWithResult: List<EasyAiMessage>
            val toolResultMessage: ToolResultMessage?

            if (saveToolResult) {
                // Resolve tool name from messages
                val resolvedEntry = resolveToolName(messages, toolCallId)

                // Check if the last message is already a ToolResultMessage (permission pause case).
                // If so, merge the new result into the existing message instead of creating a new one.
                // This avoids consecutive ToolResultMessages which would confuse the LLM.
                val lastMessage = messages.lastOrNull()

                if (lastMessage is ToolResultMessage) {
                    // Permission pause case: merge into existing ToolResultMessage.
                    // Filter out skipped placeholder entries — these were generated for tool calls
                    // that were not executed because the loop paused on a permission request.
                    val nonSkippedResults = lastMessage.toolResults.filter {
                        !it.isSkipped
                    }
                    val updatedResults = nonSkippedResults + resolvedEntry
                    toolResultMessage = ToolResultMessage(id = lastMessage.id, toolResults = updatedResults)
                    // Replace the last message in the list
                    messagesWithResult = messages.dropLast(1) + toolResultMessage
                    // Persist: update the existing message in DB (not insertIgnore)
                    sessionStore?.updateMessage(sessionId, lastMessage.id, toolResultMessage)
                    val skippedCount = lastMessage.toolResults.size - nonSkippedResults.size
                    logger.info("Merged permission result into ToolResultMessage {} ({} kept, {} skipped removed, 1 new)",
                        lastMessage.id, nonSkippedResults.size, skippedCount)
                } else {
                    // Normal resume case: create new ToolResultMessage
                    toolResultMessage = ToolResultMessage(toolResults = listOf(resolvedEntry))
                    messagesWithResult = messages + toolResultMessage
                    sessionManager.saveSessionMessages(chatContext, listOf(toolResultMessage))
                }
            } else {
                // Permission allow case: do NOT save tool result.
                // executePendingToolCallsIfNeeded will execute the tool directly (allow rule already saved).
                // Skipped placeholder cleanup is handled inside PendingToolCallExecutor.
                toolResultMessage = null
                messagesWithResult = messages
            }

            val stream = session.promptWithHistory(messagesWithResult)

            val preEvents: List<ChatStreamEvent> = if (toolResultMessage != null) {
                val result = toolResultMessage.toolResults.lastOrNull()
                buildToolCompletionEvents(result)
            } else {
                emptyList()
            }
            val mainFlow = buildSseFlow(session, stream, context = context, isRetryable = true)
            if (preEvents.isEmpty()) {
                mainFlow
            } else {
                flow {
                    preEvents.forEach { emit(it.toSse()) }
                    mainFlow.collect { emit(it) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChatContextRejectedException) {
            throw e
        } catch (e: Exception) {
            flowOf(errorSse(e.message))
        }
    }

    /**
     * Build SSE events for a completed tool execution.
     * Used for permission deny and ask_question cases where the tool result is final
     * and no further tool execution will occur.
     */
    private fun buildToolCompletionEvents(result: ToolResultEntry?): List<ChatStreamEvent> {
        if (result == null) return emptyList()
        return listOf(
            ChatStreamEvent.ToolCallStatus(
                toolCallId = result.toolCallId,
                toolName = result.toolName,
                status = "COMPLETED"
            ),
            ChatStreamEvent.ToolExecutionEnd(
                toolCallId = result.toolCallId,
                toolName = result.toolName,
                result = result.result,
                isError = result.isError
            )
        )
    }

    /**
     * Build a text answer for the given answers.
     */
    private fun buildAnswerText(answers: List<List<String>>): String {
        return buildString {
            append("[Question answered]")
            for ((i, answerList) in answers.withIndex()) {
                append("\nQ${i + 1}: ${answerList.joinToString(", ")}")
            }
        }
    }

    /**
     * Find the tool name from the last AssistantMessage's ToolCallContent by toolCallId.
     */
    private fun findToolNameById(messages: List<EasyAiMessage>, toolCallId: String): String {
        return messages.reversed().firstOrNull { it.role == Role.ASSISTANT }
            ?.let { msg ->
                if (msg is AssistantMessage) {
                    msg.content.filterIsInstance<ToolCallContent>()
                        .find { it.id == toolCallId }?.name ?: "unknown"
                } else "unknown"
            } ?: "unknown"
    }

    /**
     * Shared SSE flow builder for stream conversion.
     *
     * The agent execution is decoupled from the SSE connection lifecycle: a background pump
     * collects the agent stream and broadcasts each event to both the HTTP consumer (via an
     * unlimited bridge channel) and the per-session tap (watch endpoint). If the primary SSE
     * connection drops (page refresh / navigation), the pump keeps the agent running — the
     * client can reattach via streaming-status + watch endpoint, results are still persisted on
     * completion and SessionCompletedEvent is published as usual. Only an explicit [cancelChat]
     * aborts the agent.
     *
     * @param session The chat session
     * @param stream The event stream to convert
     * @param context Context label for logging (e.g., "chat", "retry", "resume", "answer")
     * @param isRetryable Whether errors should be retried by the client
     * @param preGoalChannel Pre-created goal channel (listener registered before command expansion
     *                       in chatFlow to avoid missing the initial goal_status event)
     * @param preGoalListener The listener associated with preGoalChannel, for cleanup on completion
     */
    private fun buildSseFlow(
        session: ChatSession,
        stream: EventStream<AgentEvent, List<AssistantMessage>>,
        context: String = "chat",
        isRetryable: Boolean = false,
        preGoalChannel: Channel<AgentEvent>? = null,
        preGoalListener: GoalStatusListener? = null
    ): Flow<ServerSentEvent<ChatStreamEvent>> = flow {
        val userId = session.agentContext.userId ?: "system"
        // Register execution (in-memory + DB streaming status)
        val handle = executionService?.beginExecution(session.id, userId, session)
        // Wire up background task cleanup: cancel all running background tasks when session is aborted
        session.onAbortCallback = {
            backgroundTaskManagerRegistry?.get(session.id)?.cancelAll()
        }
        // Create per-session broadcast tap for secondary subscribers (historical session viewer)
        val tap = MutableSharedFlow<ServerSentEvent<ChatStreamEvent>>(
            replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        sessionTaps[session.id] = tap
        // Create or retrieve BackgroundTaskManager for this session (lazy initialization).
        // eventPublisher is set after bridge channel exists so background task events reach
        // the primary SSE client (not only tap/watch subscribers).
        // (bridge channel created below; getOrCreate deferred to after bridge init)

        // Bridge channel: background pump -> this HTTP consumer. UNLIMITED so the pump never
        // blocks while the client is disconnected (events are bounded by the execution itself).
        val bridge = Channel<ServerSentEvent<ChatStreamEvent>>(Channel.UNLIMITED)
        sessionBridges[session.id] = bridge

        backgroundTaskManagerRegistry?.getOrCreate(
            sessionId = session.id,
            sessionLookup = { executionService?.getActiveSession(session.id) },
            isExecutingCheck = { executionService?.isLocallyExecuting(session.id) == true },
            eventPublisher = { event ->
                val chatEvents = ChatEventConverter.convert(event, customEventConverters)
                chatEvents.forEach { chatEvent ->
                    val sse = chatEvent.toSse()
                    // Resolve bridge/tap at publish time: this manager lives for the whole
                    // session while each SSE flow (turn, retry, auto-resume) owns a fresh
                    // bridge+tap. Capturing the creating flow's sinks would silently drop
                    // every event published by later turns.
                    sessionBridges[session.id]?.trySend(sse)
                    sessionTaps[session.id]?.tryEmit(sse)
                }
            },
            autoResumeTrigger = { sessionId, uid ->
                autoResumeForBackgroundTask(sessionId, uid)
            }
        )

        // Replay tasks that already settled before this flow attached, so the client's
        // task cards start from the real state instead of the RUNNING default.
        backgroundTaskReplaySses(session.id).forEach { sse ->
            bridge.trySend(sse)
            tap.tryEmit(sse)
        }

        // SSE handshake: push the session context (active model's context window) before
        // any agent event, so the frontend token bar uses the real window size from the
        // first frame. Also broadcast to the tap for watch subscribers already attached.
        val contextSse = sessionContextSse(session)
        tap.tryEmit(contextSse)
        emit(contextSse)

        // Set up goal status channel for real-time SSE notifications.
        // Reuse the pre-registered channel/listener if provided (chatFlow path);
        // otherwise create fresh ones (resume/retry/answer paths).
        val goalChannel = preGoalChannel ?: Channel(Channel.UNLIMITED)
        val goalListener: GoalStatusListener? = if (preGoalChannel == null) {
            createGoalListener(session.id, goalChannel)?.also { goalStatusNotifier?.addListener(it) }
        } else {
            preGoalListener
        }

        // Detached execution pump: owns the agent lifecycle (execution handle, tap, goal
        // listener). Runs independently of the HTTP connection below.
        backgroundScope.launch {
            // Capture endReason from AgentEndEvent flowing through the stream
            var endReason = "normal"
            try {
                val mainFlow: Flow<AgentEvent> = stream.asFlow()
                val goalFlow: Flow<AgentEvent> = goalChannel.consumeAsFlow()
                val mergedFlow: Flow<AgentEvent> = merge(mainFlow, goalFlow)

                // Break merge deadlock: merge() waits for ALL flows to complete,
                // but goalChannel only closes in the outer finally — which can't run
                // until collect returns. Close goalChannel from INSIDE collect when
                // AgentEndEvent arrives (signaling mainFlow is about to complete).
                mergedFlow.collect { event ->
                    // Persist pending permission for PermissionRequestEvent
                    if (event is PermissionRequestEvent && sessionStore != null) {
                        val json = objectMapper.writeValueAsString(mapOf(
                            "toolCallId" to event.toolCallId,
                            "toolName" to event.toolName,
                            "permission" to event.permission,
                            "pattern" to event.pattern,
                            "arguments" to event.arguments,
                            "subAgentToolCallId" to event.subAgentToolCallId,
                            "subAgentName" to event.subAgentName
                        ))
                        sessionStore.savePendingPermission(session.id, json)
                        logger.info("Saved pending permission for session {}: tool={}, id={}",
                            session.id, event.toolName, event.toolCallId)
                    }

                    // Capture endReason from AgentEndEvent for the Done event
                    if (event is AgentEndEvent) {
                        endReason = event.endReason
                        session.lastEndReason = event.endReason
                        // Close goalChannel now so goalFlow completes and merge can return.
                        goalChannel.close()
                    }

                    val chatEvents = ChatEventConverter.convert(event, customEventConverters)
                    chatEvents.forEach { chatEvent ->
                        val sse = chatEvent.toSse()
                        bridge.trySend(sse)
                        tap.tryEmit(sse)
                    }
                }

                // Emit done event after stream completes
                val resultMessages = stream.result()
                val doneEvent = ChatEventConverter.createDoneEvent(resultMessages, endReason = endReason)
                val doneSse = doneEvent.toSse("done")
                bridge.trySend(doneSse)
                tap.tryEmit(doneSse)
            } catch (e: CancellationException) {
                // Explicit cancel (cancelChat -> session.abort()) or service shutdown.
                // cancelChat already removed the handle and persisted "cancelled", so the
                // endExecution below is a no-op; just notify any remaining subscribers.
                logger.info("Agent execution cancelled during {} for session {}", context, session.id)
                val cancelSse = errorSse("Session cancelled", isRetryable = false)
                bridge.trySend(cancelSse)
                tap.tryEmit(cancelSse)
            } catch (e: Exception) {
                logger.error("Error in {} chat stream for session {}", context, session.id, e)
                val errSse = errorSse(e.message, isRetryable)
                bridge.trySend(errSse)
                tap.tryEmit(errSse)
            } finally {
                sessionTaps.remove(session.id)
                // Two-arg remove: never unregister a newer flow's bridge if this flow's
                // finally races behind the next execution's registration.
                sessionBridges.remove(session.id, bridge)
                goalListener?.let { goalStatusNotifier?.removeListener(it) }
                goalChannel.close()
                bridge.close()
                // End execution: conditional remove + DB status transition (fire-and-forget)
                handle?.let { executionService.endExecution(it, endReason) }
                logger.debug("Agent execution terminated during {} for session {}", context, session.id)
            }
        }

        // HTTP consumer: forwards pumped events to the client. On disconnect (cancellation)
        // it detaches WITHOUT aborting the agent — the pump keeps running, so a page refresh
        // no longer cancels the session and the client can reattach via the watch endpoint.
        try {
            for (sse in bridge) {
                emit(sse)
            }
        } catch (e: CancellationException) {
            logger.info("SSE connection detached during {} for session {}, agent continues in background",
                context, session.id)
            throw e
        }
    }

    /**
     * Creates a [GoalStatusListener] that forwards goal state changes into the given [channel]
     * as [CustomEvent]s with type "goal_status".
     *
     * Returns `null` if [goalStatusNotifier] is not configured (non-web / test environments).
     */
    private fun createGoalListener(sessionId: String, channel: Channel<AgentEvent>): GoalStatusListener? {
        if (goalStatusNotifier == null) return null
        return GoalStatusListener { goal ->
            if (goal.sessionId == sessionId) {
                channel.trySend(CustomEvent(
                    customType = "goal_status",
                    sessionId = sessionId,
                    metadata = mapOf(
                        "objective" to goal.objective,
                        "status" to goal.status.name.lowercase(),
                        "turnCount" to goal.turnCount,
                        "maxTurns" to goal.maxTurns,
                        "elapsedSeconds" to (goal.elapsedMs / 1000),
                        "evidence" to goal.completionEvidence,
                        "blockedReason" to goal.blockedReason,
                    )
                ))
            }
        }
    }

    /**
     * Manually trigger context compaction for a session.
     * Streams compaction events as SSE in real-time via a Channel bridge.
     */
    suspend fun compactChat(sessionId: String, userId: String = "system", owners: Collection<String> = emptyList(), turn: ChatTurnContext? = null): Flow<ServerSentEvent<ChatStreamEvent>> {
        // Validate before registering anything: a rejected identity must not overwrite the
        // handle of a running chat or turn the 403 into an SSE error.
        val session = sessionManager.getSession(sessionId, userId, owners)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found: $sessionId")
        // Keep the request identity in the set compaction persists; trusted values also
        // survive the strategy's replaceAll for the rest of this run.
        applyContextContributors(session.agentContext, turn?.copy(sessionId = sessionId))
        val cancelled = AtomicBoolean(false)
        // Published to the cancel thread: a plain var has no happens-before, and a stale null
        // read there would leave the compaction running unowned.
        val compactionJob = AtomicReference<Job?>(null)

        return flow {
            // Admission happens at collection time: a response that is never subscribed (the
            // client disconnected before flatMapMany subscribed) must not leave a registered
            // handle and a `streaming` row behind, since nothing would ever call endExecution.
            // Atomic: a separate isLocallyExecuting check can lose a race with a chat that
            // registers between the check and this line.
            val handle = executionService?.let { service ->
                service.beginExecutionIfIdle(
                    sessionId, userId, session = null, resetEndReason = false,
                    onCancel = {
                        cancelled.set(true)
                        compactionJob.get()?.cancel()
                    },
                ) ?: throw ResponseStatusException(HttpStatus.CONFLICT, "Session is running; cannot compact")
            }
            val channel = Channel<AgentEvent>(Channel.UNLIMITED)

            // Launch compaction in a background coroutine that pushes events to the channel.
            // Job is held so cancellation can abort it when the SSE connection drops.
            compactionJob.set(CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                try {
                    val context = session.agentContext
                    val messages = sessionManager.loadMessages(sessionId)
                    val messageTimestamps = sessionManager.getMessageTimestamps(sessionId)

                    val compactionService = transformContextService as? CompactionTransformContextService
                        ?: throw IllegalStateException("Compaction service not available")

                    val eventPusher = ContextCompactionOrchestrator.EventPusher { event ->
                        channel.send(event)
                    }

                    compactionService.manualCompactWithPusher(
                        agentContext = context,
                        messages = messages,
                        turnId = 0,
                        modelContextLength = context.modelContextLength,
                        eventPusher = eventPusher,
                        messageTimestamps = messageTimestamps,
                        chatModel = session.getChatModel()
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Error during compaction for session {}", sessionId, e)
                    channel.close(e)
                    return@launch
                } finally {
                    channel.close()
                }
            })

            // A cancel that landed between admission and this subscription already removed the
            // handle; abort the job it could not see yet instead of running compaction unowned.
            // The stop is still reported: a silent close reaches the client as connection_lost.
            if (cancelled.get()) {
                compactionJob.get()?.cancel()
                emit(ChatStreamEvent.Done(reason = "cancelled").toSse("done"))
                return@flow
            }

            try {
                channel.consumeAsFlow().collect { event ->
                    val chatEvents = ChatEventConverter.convert(event, customEventConverters)
                    chatEvents.forEach { chatEvent -> emit(chatEvent.toSse()) }
                }
                // A cancel closes the producer without an error, so a normal completion here also
                // covers "a cancel stopped it" — reporting completion then would be a lie.
                emit(
                    ChatStreamEvent.Done(
                        reason = if (cancelled.get()) "cancelled" else "compaction_complete"
                    ).toSse("done")
                )
            } catch (e: CancellationException) {
                logger.info("Compaction SSE cancelled for session {}, aborting background compaction", sessionId)
                compactionJob.get()?.cancel()
                throw e
            } catch (e: Exception) {
                emit(errorSse(e.message?.removePrefix("Session not found: ") ?: e.message))
            } finally {
                handle?.let { executionService.endExecution(it, endReason = null) }
            }
        }
    }
}
