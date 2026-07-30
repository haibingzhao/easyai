package com.easy.easyai.web.service

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.compaction.CompactionTransformContextService
import com.easy.easyai.compaction.ContextCompactionOrchestrator
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.ChatSession
import com.easy.easyai.core.agent.SessionManager
import com.easy.easyai.core.agent.TransformContextService
import com.easy.easyai.core.event.*
import com.easy.easyai.core.goal.GoalStatusListener
import com.easy.easyai.core.goal.GoalStatusNotifier
import com.easy.easyai.core.goal.GoalStore
import com.easy.easyai.core.model.*
import com.easy.easyai.core.permission.PermissionService
import com.easy.easyai.core.tool.ScriptEnvProvider
import com.easy.easyai.repository.project.AsyncProjectStore
import com.easy.easyai.repository.session.AsyncSessionStore
import com.easy.easyai.repository.session.SessionExecutionService
import com.easy.easyai.skills.command.CommandService
import com.easy.easyai.snapshot.SnapshotService
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
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.util.*
import java.util.concurrent.ConcurrentHashMap

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
    private val executionService: SessionExecutionService? = null
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
        sessionTaps.clear()
    }

    /**
     * Per-session broadcast tap for secondary SSE subscribers (e.g., historical session viewer).
     * Primary SSE consumer (buildSseFlow) emits via non-blocking tryEmit;
     * the watch endpoint collects from this flow.
     * Config mirrors SwarmEventBridge: replay=0, buffer=256, DROP_OLDEST.
     */
    private val sessionTaps = ConcurrentHashMap<String, MutableSharedFlow<ServerSentEvent<ChatStreamEvent>>>()

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
        tap.transformWhile { sse ->
            emit(sse)
            sse.event() != "done" && sse.event() != "error"
        }.collect { emit(it) }
    }

    /**
     * Return the number of sessions with active SSE streams ON THIS SERVER.
     * Replaces the old SessionManager.getActiveSessionCount() which was based on a global cache.
     */
    fun getActiveSessionCount(): Int = executionService?.getActiveSessionCount() ?: 0

    // ==================== Helper functions ====================

    private fun ChatStreamEvent.toSse(eventType: String = this.type): ServerSentEvent<ChatStreamEvent> =
        ServerSentEvent.builder<ChatStreamEvent>().event(eventType).data(this).build()

    private fun errorSse(message: String?, isRetryable: Boolean = false): ServerSentEvent<ChatStreamEvent> =
        ChatStreamEvent.Error(errorMessage = message, isRetryable = isRetryable).toSse("error")

    // ==================== Public API ====================

    /**
     * Stream chat events as a Kotlin Flow.
     */
    suspend fun streamChat(request: ChatRequest, userId: String = "system"): Flow<ServerSentEvent<ChatStreamEvent>> {
        val configId = request.modelProviderConfigId
            ?: return flowOf(errorSse("modelProviderConfigId is required"))

        val config = try {
            configStore.getConfig(configId, userId)
        } catch (e: Exception) {
            return flowOf(errorSse(e.message))
        } ?: return flowOf(errorSse("Config not found: $configId"))

        val factory = modelFactories.firstOrNull { it.supports(config.protocol) }
            ?: return flowOf(errorSse("No ChatModelFactory for protocol: ${config.protocol}"))

        val agentId = request.agentId

        return try {
            val agentContext = createAgentContext(agentId, config, request.sessionId, request.projectId, userId).let { ctx ->
                if (request.inputData != null) ctx.copy(inputVariables = request.inputData) else ctx
            }

            val session = sessionManager.getOrCreateSession(
                agentContext = agentContext,
                config = config,
                chatOptionsFactory = factory
            )
            chatFlow(agentContext, session, request.message, request.attachments)
        } catch (e: Exception) {
            flowOf(errorSse(e.message))
        }
    }

    /**
     * Cancel an ongoing chat session.
     * Delegates to [SessionExecutionService.cancelExecution] which handles:
     * handle removal → session.abort() → DB status update (synchronous).
     */
    suspend fun cancelChat(sessionId: String, userId: String = "system") {
        try {
            if (executionService != null) {
                executionService.cancelExecution(sessionId, userId)
            } else {
                // Fallback: direct DB write when no execution service
                sessionStore?.let { store ->
                    try {
                        store.updateStatus(sessionId, "active", userId, expectedStatus = "streaming")
                        store.saveEndReason(sessionId, "cancelled", userId)
                    } catch (e: Exception) {
                        logger.warn("Failed to update status after cancel for {}", sessionId, e)
                    }
                }
            }
            sessionStore?.savePendingPermission(sessionId, null)
        } catch (e: Exception) {
            logger.warn("Failed to cancel session: {}", sessionId, e)
        }
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
        val session = executionService?.getActiveSession(sessionId)
            ?: throw IllegalStateException("No active session for queue operation: $sessionId")

        // Process command expansion for queued messages (e.g., /goal creates a fresh goal).
        // Side effects (goal creation, notification) happen immediately so the Summary
        // panel reflects the new goal state before the agent picks up the message.
        commandService?.resolveAndExpand(content, userId, sessionId)?.let { expansion ->
            logger.info("Processed command '{}' in queued {} message for session {}",
                expansion.commandName, type, sessionId)
        }

        // Validate and decode image attachments; also extract inline @ file references
        val projectDir = session.agentContext.projectPath?.toAbsolutePath()?.normalize()
        val contentBlocks = AttachmentProcessor.buildContentBlocks(content, projectDir).toMutableList()
        if (fileStorageService != null) {
            // New path: process all attachments via FileRefContent (no base64 in DB)
            contentBlocks.addAll(AttachmentProcessor.processAttachments(attachments, fileStorageService, sessionId))
        } else {
            // Legacy path: base64 ImageContent (backward compatibility)
            val decodedImages = AttachmentProcessor.decodeImageAttachments(attachments)
            for ((img, bytes) in decodedImages) {
                contentBlocks.add(ImageContent(bytes, img.mimeType))
            }
        }
        val userMessage = UserMessage(content = contentBlocks)

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
        val updated = session.updateQueuedMessage(queueId, newContent)
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
    suspend fun resumeChat(sessionId: String, userId: String = "system", message: String? = null): Flow<ServerSentEvent<ChatStreamEvent>> {
        return try {
            val session = sessionManager.getSession(sessionId, userId)
                ?: throw IllegalStateException("Session not found: $sessionId")

            // Resume goal timer if it was paused waiting for user input
            resumeGoalTimer(sessionId, userId)

            // Load fresh messages from DB (single source of truth)
            val messages = sessionManager.loadMessages(sessionId)

            // Re-detect command from last UserMessage and inject SystemMessage
            val messagesWithCommand = injectCommandSystemMessage(messages, userId, sessionId)

            // Resume the session (injects resumption context internally)
            val stream = session.resume(message, messagesWithCommand)

            buildSseFlow(session, stream, context = "resume", isRetryable = true)
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

        // Process attachments: prefer new FileRefContent path over legacy base64
        val fileRefBlocks = if (fileStorageService != null) {
            try {
                AttachmentProcessor.processAttachments(attachments, fileStorageService, session.id)
            } catch (e: AttachmentValidationException) {
                return flowOf(errorSse(e.message))
            }
        } else {
            try {
                val decodedImages = AttachmentProcessor.decodeImageAttachments(attachments)
                decodedImages.map { (img, bytes) -> ImageContent(bytes, img.mimeType) }
            } catch (e: AttachmentValidationException) {
                return flowOf(errorSse(e.message))
            }
        }

        // Text attachments are inlined by the frontend; backend only handles images.
        val messageText = message.orEmpty()

        logger.info("Processing chat for session: {} with agentId={}, modelId={}",
            session.id, agentContext.agentId, agentContext.modelId)

        // Generate a messageId for this user turn
        val userMessageId = UUID.randomUUID().toString()
        val projectPath = agentContext.projectPath

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

        // Resume goal timer if paused (defensive: user may send a new message instead of using dedicated resume endpoints)
        resumeGoalTimer(session.id, agentContext.userId ?: "system")

        // Pre-register goal listener BEFORE command expansion so the initial goal_status
        // event from GoalCommandHandler is captured (UNLIMITED channel buffers it until
        // the flow is collected).
        val preGoalChannel = Channel<AgentEvent>(Channel.UNLIMITED)
        val preGoalListener = createGoalListener(session.id, preGoalChannel)
        preGoalListener?.let { goalStatusNotifier?.addListener(it) }

        // Command detection: resolve /command (including builtin /goal) and inject as SystemMessage
        val commandExpansion = commandService?.resolveAndExpand(
            message, agentContext.userId ?: "system", session.id
        )

        // Build content blocks (text + file refs / images)
        // Extract inline @ file references from message text (e.g. ‛[name](path)‛)
        // and convert them to FileRefContent blocks so the LLM can read file contents.
        val projectDir = projectPath?.toAbsolutePath()?.normalize()
        val contentBlocks = AttachmentProcessor.buildContentBlocks(messageText, projectDir).toMutableList()
        contentBlocks.addAll(fileRefBlocks)

        val messages = buildList {
            addAll(history)
            if (commandExpansion != null) {
                add(SystemMessage(text = commandExpansion.expandedPrompt))
                logger.info("Injected command SystemMessage for '{}' ({} chars)",
                    commandExpansion.commandName, commandExpansion.expandedPrompt.length)
            }
            add(UserMessage(id = userMessageId, content = contentBlocks))
        }

        // Persist UserMessage to DB with command expansion cached in metadata (SystemMessage is ephemeral)
        val metadata = buildMap {
            if (commandExpansion != null) {
                put(UserMessage.COMMAND_EXPANSION, commandExpansion.expandedPrompt)
                put(UserMessage.COMMAND_NAME, commandExpansion.commandName)
            }
        }
        val userMessage = UserMessage(
            id = userMessageId,
            content = contentBlocks,
            metadata = metadata
        )
        sessionManager.saveSessionMessages(session.agentContext, listOf(userMessage))
        // Update session agent with fresh inputVariables from current request
        session.updateInputVariables(agentContext.inputVariables)
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
     * Re-detect a slash command in the last UserMessage and inject a SystemMessage before it.
     * Used by retry/resume to restore command context that was not persisted to DB.
     *
     * Priority:
     * 1. Restore from cached expansion in UserMessage metadata (works even if MCP is offline)
     * 2. Fall back to re-expanding via commandService (requires MCP to be connected)
     */
    private suspend fun injectCommandSystemMessage(
        messages: List<EasyAiMessage>,
        userId: String = "system",
        sessionId: String = ""
    ): List<EasyAiMessage> {
        val lastUserMsg = messages.lastOrNull { it is UserMessage } as? UserMessage ?: return messages
        val lastIndex = messages.indexOfLast { it is UserMessage && it.id == lastUserMsg.id }
        if (lastIndex < 0) return messages

        // 1. Try cached expansion from metadata (survives MCP disconnection)
        val cachedExpansion = lastUserMsg.metadata[UserMessage.COMMAND_EXPANSION]
        val cachedName = lastUserMsg.metadata[UserMessage.COMMAND_NAME]
        if (cachedExpansion != null) {
            logger.info("Re-injected cached command SystemMessage for '{}' during retry/resume ({} chars)",
                cachedName, cachedExpansion.length)
            return messages.toMutableList().apply {
                add(lastIndex, SystemMessage(text = cachedExpansion))
            }
        }

        // 2. Fall back to re-expansion (may fail if MCP server is disconnected)
        val cs = commandService ?: return messages
        val userText = lastUserMsg.content.filterIsInstance<TextContent>().joinToString("") { it.text }
        val expansion = cs.resolveAndExpand(userText, userId, sessionId) ?: return messages

        logger.info("Re-injected command SystemMessage for '{}' during retry/resume ({} chars)",
            expansion.commandName, expansion.expandedPrompt.length)
        return messages.toMutableList().apply {
            add(lastIndex, SystemMessage(text = expansion.expandedPrompt))
        }
    }

    private suspend fun createAgentContext(
        agentId: String,
        config: ModelProviderConfig,
        sessionId: String? = null,
        projectId: String? = null,
        userId: String = "system"
    ): AgentContext {
        // Load project to get memoryAutoGeneration and projectPath
        val project = if (projectId != null && projectStore != null) {
            projectStore.findById(projectId, userId)
        } else null

        // Build a partial context for script env generation
        val partialContext = AgentContext(
            agentId = agentId,
            modelConfig = config,
            sessionId = sessionId,
            userId = userId
        )
        val scriptEnv = scriptEnvProvider?.getScriptEnv(partialContext) ?: emptyMap()

        return AgentContext(
            agentId = agentId,
            modelConfig = config,
            sessionId = sessionId,
            userId = userId,
            projectId = projectId,
            projectPath = project?.let { java.nio.file.Path.of(it.path) },
            memoryAutoGeneration = project?.memoryAutoGeneration ?: true,
            modelContextLength = config.options?.contextToken ?: 204_800,
            scriptEnv = scriptEnv
        )
    }

    /**
     * Resume after answering a question.
     * Constructs a ToolResultMessage (role=TOOL) and resumes the session.
     */
    suspend fun resumeAfterAnswer(sessionId: String, userId: String = "system", toolCallId: String, answers: List<List<String>>): Flow<ServerSentEvent<ChatStreamEvent>> {
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
            context = "answer"
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
    suspend fun rejectAndResume(sessionId: String, userId: String = "system", toolCallId: String): Flow<ServerSentEvent<ChatStreamEvent>> {
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
            context = "resume"
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
    suspend fun allowPermissionAndResume(sessionId: String, userId: String = "system", toolCallId: String, remember: Boolean, permission: String?, pattern: String?): Flow<ServerSentEvent<ChatStreamEvent>> {
        // If remember=true, save the allow rule before resuming
        if (remember && permissionService != null && permission != null) {
            val context = sessionManager.getSessionContext(sessionId, userId)
            context?.projectId?.let { projectId ->
                permissionService.addAllowRule(projectId, permission, pattern ?: "*")
                logger.info("Saved allow rule for {} {} in project {}", permission, pattern, projectId)
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
            saveToolResult = false
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
    suspend fun denyPermissionAndResume(sessionId: String, userId: String = "system", toolCallId: String, remember: Boolean, reason: String?, permission: String?, pattern: String?): Flow<ServerSentEvent<ChatStreamEvent>> {
        // If remember=true, save the deny rule before resuming
        if (remember && permissionService != null && permission != null) {
            val context = sessionManager.getSessionContext(sessionId, userId)
            context?.projectId?.let { projectId ->
                permissionService.addDenyRule(projectId, permission, pattern ?: "*")
                logger.info("Saved deny rule for {} {} in project {}", permission, pattern, projectId)
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
            context = "permission_deny"
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
        resolveToolName: (messages: List<EasyAiMessage>, toolCallId: String) -> ToolResultEntry
    ): Flow<ServerSentEvent<ChatStreamEvent>> {
        return try {
            val session = sessionManager.getSession(sessionId, userId)
                ?: throw IllegalStateException("Session not found: $sessionId")

            // Clear pending permission from DB (permission has been answered)
            if (context.startsWith("permission_") && sessionStore != null) {
                sessionStore.savePendingPermission(sessionId, null)
                logger.info("Cleared pending permission for session {}", sessionId)
            }

            // Resume goal timer if it was paused waiting for user input
            resumeGoalTimer(sessionId, userId)

            // Load fresh messages from DB
            val messages = sessionManager.loadMessages(session.id)

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
     * Uses Kotlin Flow with try/catch/finally for lifecycle management.
     *
     * @param session The chat session
     * @param stream The event stream to convert
     * @param context Context label for logging (e.g., "chat", "retry", "resume", "answer")
     * @param isRetryable Whether errors should be retried by the client
     * @param preGoalChannel Pre-created goal channel (listener registered before command expansion
     *                       in chatFlow to avoid missing the initial goal_status event)
     * @param preGoalListener The listener associated with preGoalChannel, for cleanup in finally
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
        // Create per-session broadcast tap for secondary subscribers (historical session viewer)
        val tap = MutableSharedFlow<ServerSentEvent<ChatStreamEvent>>(
            replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        sessionTaps[session.id] = tap

        // Capture endReason from AgentEndEvent flowing through the stream
        var endReason = "normal"

        // Set up goal status channel for real-time SSE notifications.
        // Reuse the pre-registered channel/listener if provided (chatFlow path);
        // otherwise create fresh ones (resume/retry/answer paths).
        val goalChannel = preGoalChannel ?: Channel(Channel.UNLIMITED)
        var goalListener: GoalStatusListener? = preGoalListener
        if (preGoalChannel == null) {
            goalListener = createGoalListener(session.id, goalChannel)
            goalListener?.let { goalStatusNotifier?.addListener(it) }
        }

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
                    // Without this, merge waits forever for goalFlow while the outer finally
                    // (which also closes goalChannel) can't run until collect returns — deadlock.
                    goalChannel.close()
                }

                val chatEvents = ChatEventConverter.convert(event, customEventConverters)
                chatEvents.forEach { chatEvent ->
                    val sse = chatEvent.toSse()
                    emit(sse)
                    tap.tryEmit(sse)
                }
            }

            // Emit done event after stream completes
            val resultMessages = stream.result()
            val doneEvent = ChatEventConverter.createDoneEvent(resultMessages, endReason = endReason)
            val doneSse = doneEvent.toSse("done")
            emit(doneSse)
            tap.tryEmit(doneSse)
        } catch (e: CancellationException) {
            logger.info("SSE connection cancelled during {} for session {}, aborting agent", context, session.id)
            session.abort()
            // Notify secondary subscribers (watch endpoint) so they terminate immediately
            // instead of hanging until the 30-minute controller timeout.
            tap.tryEmit(errorSse("Primary SSE connection cancelled", isRetryable = false))
            // Don't clear pendingPermission here — the permission may still need user response.
            // cancelChat() handles explicit user cancellation separately.
            throw e
        } catch (e: Exception) {
            logger.error("Error in {} chat stream for session {}", context, session.id, e)
            val errSse = errorSse(e.message, isRetryable)
            emit(errSse)
            tap.tryEmit(errSse)
        } finally {
            sessionTaps.remove(session.id)
            goalListener?.let { goalStatusNotifier?.removeListener(it) }
            goalChannel.close()
            // End execution: conditional remove + DB status transition (fire-and-forget)
            handle?.let { executionService?.endExecution(it, endReason) }
            logger.debug("SSE stream terminated during {} for session {}", context, session.id)
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
    fun compactChat(sessionId: String, userId: String = "system"): Flow<ServerSentEvent<ChatStreamEvent>> = flow {
        val channel = Channel<AgentEvent>(Channel.UNLIMITED)

        // Launch compaction in a background coroutine that pushes events to the channel.
        // Job is held so cancellation can abort it when the SSE connection drops.
        val compactionJob = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val session = sessionManager.getSession(sessionId, userId)
                    ?: throw IllegalStateException("Session not found: $sessionId")

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
        }

        // Register execution + persist streaming status
        // NOTE: Do NOT reset endReason for compaction — it's not a new Agent execution
        val handle = executionService?.beginExecution(sessionId, userId, session = null, resetEndReason = false)

        try {
            channel.consumeAsFlow().collect { event ->
                val chatEvents = ChatEventConverter.convert(event, customEventConverters)
                chatEvents.forEach { chatEvent -> emit(chatEvent.toSse()) }
            }
            emit(ChatStreamEvent.Done(reason = "compaction_complete").toSse("done"))
        } catch (e: CancellationException) {
            logger.info("Compaction SSE cancelled for session {}, aborting background compaction", sessionId)
            compactionJob.cancel()
            throw e
        } catch (e: Exception) {
            emit(errorSse(e.message?.removePrefix("Session not found: ") ?: e.message))
        } finally {
            handle?.let { executionService?.endExecution(it, endReason = null) }
        }
    }
}
