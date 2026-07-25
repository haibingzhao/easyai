package com.easy.easyai.web.controller

import com.easy.easyai.core.agent.SessionManager
import com.easy.easyai.core.goal.GoalState
import com.easy.easyai.core.goal.GoalStatus
import com.easy.easyai.core.goal.GoalStatusNotifier
import com.easy.easyai.core.goal.GoalStore
import com.easy.easyai.repository.session.AsyncSessionStore
import com.easy.easyai.snapshot.RevertService
import com.easy.easyai.snapshot.SnapshotService
import com.easy.easyai.web.model.*
import com.easy.easyai.web.security.getCurrentUserId
import com.easy.easyai.web.service.ChatStreamService
import com.easy.easyai.web.service.SessionService
import com.easy.easyai.web.util.AttachmentValidationException
import kotlinx.coroutines.reactor.asFlux
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * WebFlux REST controller exposing SSE endpoints for web client integration.
 *
 * Endpoints:
 * - POST /api/chat - SSE streaming chat
 * - POST /api/chat/cancel - Cancel chat
 * - POST /api/chat/resume - Resume cancelled chat
 * - POST /api/chat/question/{sessionId}/{toolCallId}/answer - Answer a pending question (SSE)
 * - POST /api/chat/question/{sessionId}/{toolCallId}/reject - Reject/dismiss a pending question (SSE)
 * - GET /api/chat/sessions/count - Get active session count
 * - GET /api/chat/health - Health check
 */
@RestController
@RequestMapping("/api/chat")
class ChatController(
    private val chatStreamService: ChatStreamService,
    private val sessionManager: SessionManager,
    private val revertService: RevertService,
    private val sessionService: SessionService,
    private val sessionStore: AsyncSessionStore,
    private val snapshotService: SnapshotService? = null,
    private val goalStore: GoalStore? = null,
    private val goalStatusNotifier: GoalStatusNotifier? = null
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Verify that the current user owns (or can access) the given session.
     * Throws ResponseStatusException(403) if the session is not accessible.
     */
    private suspend fun verifyOwnership(sessionId: String): String {
        val userId = getCurrentUserId()
        if (!sessionStore.isSessionOwnedByUser(sessionId, userId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Session not accessible: $sessionId")
        }
        return userId
    }

    /**
     * SSE streaming chat endpoint.
     * Returns a Flux<ServerSentEvent> which Spring WebFlux automatically
     * serializes as text/event-stream.
     */
    @PostMapping(
        value = ["", "/"],
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    fun chat(
        @RequestBody request: ChatRequest
    ): Flux<ServerSentEvent<ChatStreamEvent>> {
        logger.debug("Received chat request: {}", request)
        return mono {
            val userId = getCurrentUserId()
            // Verify ownership when targeting an existing session
            request.sessionId?.let { verifyOwnership(it) }
            chatStreamService.streamChat(request, userId)
        }.flatMapMany { it.asFlux() }
    }

    /**
     * Get active session count (sessions with active SSE streams ON THIS SERVER).
     */
    @GetMapping("/sessions/count")
    fun getActiveSessionCount(): Mono<Map<String, Int>> {
        return mono {
            mapOf("count" to chatStreamService.getActiveSessionCount())
        }
    }

    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    fun health(): Mono<Map<String, String>> {
        return Mono.just(mapOf("status" to "ok"))
    }

    /**
     * Check if a session has an active SSE stream.
     * Used by frontend after page refresh to detect a running agent.
     */
    @GetMapping("/session/{sessionId}/streaming-status")
    fun getStreamingStatus(@PathVariable sessionId: String): Mono<Map<String, Any>> {
        return mono {
            val userId = verifyOwnership(sessionId)
            val status = chatStreamService.isSessionStreaming(sessionId, userId)
            mapOf("streaming" to status.remote, "local" to status.local)
        }
    }

    /**
     * Get current goal state for a session.
     * Used by frontend after page refresh to restore the goal banner.
     * Returns 404 if no goal is set.
     */
    @GetMapping("/session/{sessionId}/goal")
    fun getGoal(@PathVariable sessionId: String): Mono<ChatStreamEvent.GoalStatus> {
        return mono {
            val userId = verifyOwnership(sessionId)
            val goal = goalStore?.getGoal(sessionId, userId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No goal set for session: $sessionId")
            ChatStreamEvent.GoalStatus(
                sessionId = goal.sessionId,
                objective = goal.objective,
                status = goal.status.name.lowercase(),
                turnCount = goal.turnCount,
                maxTurns = goal.maxTurns,
                elapsedSeconds = goal.elapsedMs / 1000,
                evidence = goal.completionEvidence,
                blockedReason = goal.blockedReason
            )
        }
    }

    // ==================== Goal management endpoints ====================

    private data class ResolvedGoal(
        val userId: String,
        val store: GoalStore,
        val goal: GoalState
    )

    private suspend fun resolveGoal(sessionId: String): ResolvedGoal {
        val userId = verifyOwnership(sessionId)
        val store = goalStore ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Goal store not configured")
        val goal = store.getGoal(sessionId, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No goal set for session: $sessionId")
        return ResolvedGoal(userId, store, goal)
    }

    /**
     * Pause the active goal for a session.
     * Sets the goal status to PAUSED and notifies via SSE.
     */
    @PutMapping("/session/{sessionId}/goal/pause")
    fun pauseGoal(@PathVariable sessionId: String): Mono<Map<String, Any>> {
        return mono {
            val (userId, store, goal) = resolveGoal(sessionId)

            if (!goal.isActive) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Goal is not active (current status: ${goal.status.name.lowercase()})")
            }

            val pausedGoal = goal.copy(
                status = GoalStatus.PAUSED,
                stopReason = "paused by user"
            ).withHistory("paused", "Paused by user via API")

            store.saveGoal(pausedGoal, userId)
            goalStatusNotifier?.notifyGoalChanged(pausedGoal)

            logger.info("Goal paused for session {}", sessionId)
            mapOf(
                "status" to pausedGoal.status.name.lowercase(),
                "objective" to pausedGoal.objective
            )
        }
    }

    /**
     * Resume a paused goal for a session.
     * Sets the goal status back to ACTIVE and notifies via SSE.
     */
    @PutMapping("/session/{sessionId}/goal/resume")
    fun resumeGoal(@PathVariable sessionId: String): Mono<Map<String, Any>> {
        return mono {
            val (userId, store, goal) = resolveGoal(sessionId)

            if (goal.status != GoalStatus.PAUSED) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Goal is not paused (current status: ${goal.status.name.lowercase()})")
            }

            val resumedGoal = goal.copy(
                status = GoalStatus.ACTIVE,
                stopReason = null
            ).withHistory("resumed", "Resumed by user via API")

            store.saveGoal(resumedGoal, userId)
            goalStatusNotifier?.notifyGoalChanged(resumedGoal)

            logger.info("Goal resumed for session {}", sessionId)
            mapOf(
                "status" to resumedGoal.status.name.lowercase(),
                "objective" to resumedGoal.objective
            )
        }
    }

    /**
     * Update the goal objective text.
     * Allows the user to refine the goal while it's active.
     */
    @PutMapping("/session/{sessionId}/goal")
    fun updateGoal(
        @PathVariable sessionId: String,
        @RequestBody request: UpdateGoalRequest
    ): Mono<Map<String, Any>> {
        return mono {
            val (userId, store, goal) = resolveGoal(sessionId)

            if (!goal.isActive) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot update a non-active goal (current status: ${goal.status.name.lowercase()})")
            }

            val updatedGoal = goal.copy(objective = request.objective)
                .withHistory("objective_updated", "Objective updated via API: ${request.objective.take(100)}")

            store.saveGoal(updatedGoal, userId)
            goalStatusNotifier?.notifyGoalChanged(updatedGoal)

            logger.info("Goal updated for session {}", sessionId)
            mapOf(
                "status" to updatedGoal.status.name.lowercase(),
                "objective" to updatedGoal.objective
            )
        }
    }

    /**
     * Delete the goal for a session.
     * Removes the goal entirely and notifies via SSE.
     */
    @DeleteMapping("/session/{sessionId}/goal")
    fun deleteGoal(@PathVariable sessionId: String): Mono<Map<String, String>> {
        return mono {
            val (userId, store, goal) = resolveGoal(sessionId)

            // Create a terminal state for notification before deleting
            val deletedGoal = goal.copy(
                status = GoalStatus.COMPLETED,
                stopReason = "deleted by user"
            ).withHistory("deleted", "Deleted by user via API")
            goalStatusNotifier?.notifyGoalChanged(deletedGoal)

            store.deleteGoal(sessionId, userId)

            logger.info("Goal deleted for session {}", sessionId)
            mapOf("status" to "deleted")
        }
    }

    /**
     * Get messages created after [after] timestamp for incremental recovery.
     * Used by frontend after streaming ends to fetch only new messages instead of full session detail.
     * Returns compaction/dirty-marker metadata so the frontend can fall back to full reload when needed.
     */
    @GetMapping("/session/{sessionId}/messages")
    fun getSessionMessagesAfter(
        @PathVariable sessionId: String,
        @RequestParam after: Long
    ): Mono<SessionMessagesAfterResponse> {
        return mono {
            val userId = verifyOwnership(sessionId)
            sessionService.getSessionMessagesAfter(sessionId, after, userId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found: $sessionId")
        }
    }

    /**
     * Cancel an ongoing chat session.
     */
    @PostMapping("/cancel")
    fun cancelChat(@RequestBody request: CancelRequest): Mono<Map<String, String>> {
        return mono {
            val userId = verifyOwnership(request.sessionId)
            chatStreamService.cancelChat(request.sessionId, userId)
            mapOf("status" to "cancelled")
        }
    }

    /**
     * Resume a cancelled or errored chat session with SSE streaming.
     * Optionally accepts a user message to guide the resumption.
     */
    @PostMapping(
        value = ["/resume"],
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    fun resumeChat(
        @RequestBody request: ResumeRequest
    ): Flux<ServerSentEvent<ChatStreamEvent>> {
        return mono {
            val userId = verifyOwnership(request.sessionId)
            chatStreamService.resumeChat(request.sessionId, userId, request.message)
        }.flatMapMany { it.asFlux() }
    }

    /**
     * Get the todo list for a session.
     * Used by the frontend to restore todo state after page refresh or SSE reconnect.
     */
    @GetMapping("/{sessionId}/todos")
    fun getTodos(@PathVariable sessionId: String): Mono<List<TodoResponse>> {
        return mono {
            verifyOwnership(sessionId)
            sessionManager.getTodos(sessionId).map { it.toResponse() }
        }
    }

    /**
     * Get grouped todos for a session: main agent todos + sub-agent todo groups.
     * Used by the frontend Progress panel to display both main and sub-agent progress.
     */
    @GetMapping("/{sessionId}/todos/grouped")
    fun getGroupedTodos(@PathVariable sessionId: String): Mono<TodoGroupResponse> {
        return mono {
            verifyOwnership(sessionId)
            val allTodos = sessionManager.getAllTodos(sessionId)
            val main = allTodos[null]?.map { it.toResponse() } ?: emptyList()
            val subAgents = allTodos
                .filterKeys { it != null }
                .map { (agentRunId, todos) ->
                    SubAgentTodoGroup(
                        agentName = agentRunId!!,
                        todos = todos.map { it.toResponse() }
                    )
                }
            TodoGroupResponse(main = main, subAgents = subAgents)
        }
    }

    // ==================== Queue management endpoints ====================

    /**
     * Add a message to the session queue (steer or followUp).
     * Returns the generated queue ID.
     */
    @PostMapping("/session/{sessionId}/queue")
    fun addQueueMessage(
        @PathVariable sessionId: String,
        @RequestBody request: QueueMessageRequest
    ): Mono<QueuedMessageResponse> {
        return mono {
            val userId = verifyOwnership(sessionId)
            try {
                chatStreamService.addQueuedMessage(sessionId, userId, request.content, request.type, request.attachments)
            } catch (e: AttachmentValidationException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
            }
        }
    }

    /**
     * Get the current queue snapshot (steering + followUp messages) for a session.
     */
    @GetMapping("/session/{sessionId}/queue")
    fun getQueueMessages(
        @PathVariable sessionId: String
    ): Mono<List<QueuedMessageResponse>> {
        return mono {
            verifyOwnership(sessionId)
            chatStreamService.getQueuedMessages(sessionId)
        }
    }

    /**
     * Remove a queued message by ID.
     */
    @DeleteMapping("/session/{sessionId}/queue/{queueId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeQueueMessage(
        @PathVariable sessionId: String,
        @PathVariable queueId: String
    ): Mono<Void> {
        return mono {
            verifyOwnership(sessionId)
            val removed = chatStreamService.removeQueuedMessage(sessionId, queueId)
            if (!removed) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "Queued message not found: $queueId")
            }
        }.then()
    }

    /**
     * Update the content of a queued message.
     */
    @PutMapping("/session/{sessionId}/queue/{queueId}")
    fun updateQueueMessage(
        @PathVariable sessionId: String,
        @PathVariable queueId: String,
        @RequestBody request: QueueUpdateRequest
    ): Mono<Map<String, String>> {
        return mono {
            verifyOwnership(sessionId)
            val updated = chatStreamService.updateQueuedMessage(sessionId, queueId, request.content)
            if (!updated) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "Queued message not found: $queueId")
            }
            mapOf("status" to "updated")
        }
    }

    /**
     * Reorder queued messages by the given ID list.
     */
    @PutMapping("/session/{sessionId}/queue/reorder")
    fun reorderQueueMessages(
        @PathVariable sessionId: String,
        @RequestBody request: QueueReorderRequest
    ): Mono<Map<String, String>> {
        return mono {
            verifyOwnership(sessionId)
            chatStreamService.reorderQueuedMessages(sessionId, request.ids)
            mapOf("status" to "reordered")
        }
    }

    // ==================== Question endpoints ====================

    /**
     * Answer a pending question.
     * Stores the answer to DB and resumes the agent via SSE streaming.
     */
    @PostMapping(
        value = ["/question/{sessionId}/{toolCallId}/answer"],
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    fun answerQuestion(
        @PathVariable sessionId: String,
        @PathVariable toolCallId: String,
        @RequestBody request: QuestionAnswerRequest
    ): Flux<ServerSentEvent<ChatStreamEvent>> {
        return mono {
            val userId = verifyOwnership(sessionId)
            chatStreamService.resumeAfterAnswer(sessionId, userId, toolCallId, request.answers)
        }.flatMapMany { it.asFlux() }
    }

    /**
     * Reject/dismiss a pending question.
     * Records the rejection and resumes the agent via SSE streaming.
     */
    @PostMapping(
        value = ["/question/{sessionId}/{toolCallId}/reject"],
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    fun rejectQuestion(
        @PathVariable sessionId: String,
        @PathVariable toolCallId: String
    ): Flux<ServerSentEvent<ChatStreamEvent>> {
        return mono {
            val userId = verifyOwnership(sessionId)
            chatStreamService.rejectAndResume(sessionId, userId, toolCallId)
        }.flatMapMany { it.asFlux() }
    }

    /**
     * Manually trigger context compaction for a session.
     * Streams compaction events as SSE.
     */
    @PostMapping(
        value = ["/session/{sessionId}/compact"],
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    fun compactSession(
        @PathVariable sessionId: String
    ): Flux<ServerSentEvent<ChatStreamEvent>> {
        return mono {
            val userId = verifyOwnership(sessionId)
            chatStreamService.compactChat(sessionId, userId)
        }.flatMapMany { it.asFlux() }
    }

    // ==================== Permission endpoints ====================

    /**
     * Allow a pending permission request.
     * Optionally saves an "always allow" rule and resumes the agent via SSE streaming.
     */
    @PostMapping(
        value = ["/permission/{sessionId}/{toolCallId}/allow"],
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    fun allowPermission(
        @PathVariable sessionId: String,
        @PathVariable toolCallId: String,
        @RequestBody request: PermissionReplyRequest
    ): Flux<ServerSentEvent<ChatStreamEvent>> {
        return mono {
            val userId = verifyOwnership(sessionId)
            chatStreamService.allowPermissionAndResume(sessionId, userId, toolCallId, request.remember,
                request.permission, request.pattern)
        }.flatMapMany { it.asFlux() }
    }

    /**
     * Deny a pending permission request.
     * Records the denial and resumes the agent via SSE streaming.
     */
    @PostMapping(
        value = ["/permission/{sessionId}/{toolCallId}/deny"],
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    fun denyPermission(
        @PathVariable sessionId: String,
        @PathVariable toolCallId: String,
        @RequestBody request: PermissionReplyRequest
    ): Flux<ServerSentEvent<ChatStreamEvent>> {
        return mono {
            val userId = verifyOwnership(sessionId)
            chatStreamService.denyPermissionAndResume(sessionId, userId, toolCallId, request.remember,
                request.reason, request.permission, request.pattern)
        }.flatMapMany { it.asFlux() }
    }
    // ==================== Snapshot / Revert endpoints ====================

    /**
     * Get checkpoint summaries for a session.
     * Used by the frontend to restore file change information when loading a historical session.
     */
    @GetMapping("/session/{sessionId}/checkpoints")
    fun getCheckpoints(@PathVariable sessionId: String): Mono<List<CheckpointSummary>> {
        return mono {
            val userId = verifyOwnership(sessionId)
            sessionService.getCheckpoints(sessionId, userId)
        }
    }

    /**
     * Revert files to the state before the specified message's changes.
     */
    @PostMapping("/session/{sessionId}/revert")
    fun revert(
        @PathVariable sessionId: String,
        @RequestBody request: RevertRequest
    ): Mono<RevertResponse> {
        return mono {
            val projectPath = resolveProjectPath(sessionId)
            val result = revertService.revert(projectPath, sessionId, request.messageId)
            RevertResponse(
                messageId = result.messageId,
                filesCount = result.filesCount,
                additions = result.additions,
                deletions = result.deletions
            )
        }
    }

    /**
     * Unrevert: restore files to the state before the revert.
     */
    @PostMapping("/session/{sessionId}/unrevert")
    fun unrevert(
        @PathVariable sessionId: String
    ): Mono<UnrevertResponse> {
        return mono {
            val projectPath = resolveProjectPath(sessionId)
            val result = revertService.unrevert(projectPath, sessionId)
            UnrevertResponse(
                messageId = result.messageId,
                filesCount = result.filesCount,
                additions = result.additions,
                deletions = result.deletions
            )
        }
    }

    /**
     * Get session-level file diff summary.
     */
    @GetMapping("/session/{sessionId}/diff")
    fun getSessionDiff(
        @PathVariable sessionId: String
    ): Mono<List<DiffResponse>> {
        return mono {
            val projectPath = resolveProjectPath(sessionId)
            revertService.getSessionDiff(projectPath, sessionId).map { it.toDiffResponse() }
        }
    }

    /**
     * Get diff for a specific checkpoint.
     */
    @GetMapping("/session/{sessionId}/checkpoint/{messageId}/diff")
    fun getCheckpointDiff(
        @PathVariable sessionId: String,
        @PathVariable messageId: String
    ): Mono<List<DiffResponse>> {
        return mono {
            val projectPath = resolveProjectPath(sessionId)
            revertService.getCheckpointDiff(projectPath, sessionId, messageId).map { it.toDiffResponse() }
        }
    }

    /**
     * Get commit history for a session with per-commit diffs and author attribution.
     * Used by the per-commit view in the frontend Review panel.
     */
    @GetMapping("/session/{sessionId}/commit-history")
    fun getCommitHistory(
        @PathVariable sessionId: String
    ): Mono<List<CommitHistoryResponse>> {
        return mono {
            val projectPath = resolveProjectPath(sessionId)
            if (snapshotService == null || !snapshotService.isEnabled(projectPath)) {
                emptyList()
            } else {
                snapshotService.listCommitsWithDiffs(projectPath, sessionId).map { commit ->
                    CommitHistoryResponse(
                        commitHash = commit.commitHash,
                        author = commit.author,
                        message = commit.message,
                        timestamp = commit.timestamp,
                        files = commit.files.map { it.toDiffResponse() }
                    )
                }
            }
        }
    }

    /**
     * Get the current revert state for a session.
     */
    @GetMapping("/session/{sessionId}/revert-state")
    fun getRevertState(
        @PathVariable sessionId: String
    ): Mono<RevertStateResponse> {
        return mono {
            val projectPath = resolveProjectPath(sessionId)
            val state = revertService.getRevertState(projectPath, sessionId)
                ?: return@mono null
            // Compute diff stats for the revert
            val diffs = try {
                revertService.getDiff(projectPath, state.commitHash, state.preRevertCommitHash)
            } catch (_: Exception) {
                emptyList()
            }
            RevertStateResponse(
                messageId = state.messageId,
                commitHash = state.commitHash,
                timestamp = state.timestamp,
                filesCount = diffs.size,
                additions = diffs.sumOf { it.additions },
                deletions = diffs.sumOf { it.deletions }
            )
        }
    }

    // ==================== File Review endpoints ====================

    /**
     * Resolve projectPath for a session using lightweight context lookup.
     * Falls back to full session restoration only when necessary.
     */
    private suspend fun resolveProjectPath(sessionId: String): java.nio.file.Path {
        val userId = verifyOwnership(sessionId)
        val context = sessionManager.getSessionContext(sessionId, userId)
        val path = context?.projectPath
        if (path != null) return path
        // Fallback: try full session restoration
        val session = sessionManager.getSession(sessionId, userId)
            ?: throw IllegalStateException("Session not found: $sessionId")
        return session.agentContext.projectPath
            ?: throw IllegalStateException("Project path not available for session: $sessionId")
    }

    /**
     * Accept a file: persist review state (file remains unchanged).
     */
    @PostMapping("/session/{sessionId}/file-accept")
    fun acceptFile(
        @PathVariable sessionId: String,
        @RequestBody request: FileReviewRequest
    ): Mono<FileReviewResponse> {
        return mono {
            val projectPath = resolveProjectPath(sessionId)
            val result = revertService.acceptFile(projectPath, sessionId, request.filePath)
            FileReviewResponse(path = result.path, action = result.action)
        }
    }

    /**
     * Reject a file: persist review state + restore file from git history.
     */
    @PostMapping("/session/{sessionId}/file-reject")
    fun rejectFile(
        @PathVariable sessionId: String,
        @RequestBody request: FileReviewRequest
    ): Mono<FileReviewResponse> {
        return mono {
            val projectPath = resolveProjectPath(sessionId)
            val result = revertService.rejectFile(projectPath, sessionId, request.filePath)
            FileReviewResponse(path = result.path, action = result.action)
        }
    }

    /**
     * Batch accept multiple files atomically.
     * All review states are updated in a single read-modify-write operation.
     */
    @PostMapping("/session/{sessionId}/file-accept-batch")
    fun batchAcceptFiles(
        @PathVariable sessionId: String,
        @RequestBody request: BatchFileReviewRequest
    ): Mono<BatchFileReviewResponse> {
        return mono {
            val projectPath = resolveProjectPath(sessionId)
            val results = revertService.batchAcceptFiles(projectPath, sessionId, request.filePaths)
            BatchFileReviewResponse(results.map { FileReviewResponse(it.path, it.action) })
        }
    }

    /**
     * Batch reject multiple files atomically.
     * File restoration + review state update in a single operation.
     */
    @PostMapping("/session/{sessionId}/file-reject-batch")
    fun batchRejectFiles(
        @PathVariable sessionId: String,
        @RequestBody request: BatchFileReviewRequest
    ): Mono<BatchFileReviewResponse> {
        return mono {
            val projectPath = resolveProjectPath(sessionId)
            val results = revertService.batchRejectFiles(projectPath, sessionId, request.filePaths)
            BatchFileReviewResponse(results.map { FileReviewResponse(it.path, it.action) })
        }
    }

    /**
     * Get file review states for a session.
     * Returns the saved review state, or empty Mono if no review state exists.
     */
    @GetMapping("/session/{sessionId}/file-review")
    fun getFileReviewState(
        @PathVariable sessionId: String
    ): Mono<FileReviewStateResponse> {
        return mono {
            if (snapshotService == null) return@mono null
            val projectPath = resolveProjectPath(sessionId)
            if (!snapshotService.isEnabled(projectPath)) return@mono null
            val state = snapshotService.loadFileReviewState(projectPath, sessionId)
                ?: return@mono null
            FileReviewStateResponse(reviews = state.reviews)
        }
    }

    // ==================== Edit Message endpoints ====================

    /**
     * Edit a message: undo compaction (if any), delete the message and all subsequent messages,
     * then rollback files. The new message content should be sent via the regular sendMessage flow afterwards.
     * Note: Session streaming check is handled by the frontend via isStreaming state.
     */
    @PostMapping("/session/{sessionId}/edit-message")
    fun editMessage(
        @PathVariable sessionId: String,
        @RequestBody request: EditMessageRequest
    ): Mono<EditMessageResponse> {
        return mono {
            verifyOwnership(sessionId)
            // 1. Query message timestamp
            val createdAt = sessionService.getMessageCreatedAt(sessionId, request.messageId)
                ?: throw IllegalArgumentException("Message not found: ${request.messageId}")

            // 2. Undo compaction after this message if any (instead of blocking)
            val compactionTs = sessionService.getFirstCompactionAfter(sessionId, createdAt)
            if (compactionTs != null) {
                sessionService.undoCompactionAfter(sessionId, createdAt)
            }

            // 3. Get projectPath BEFORE deleting messages (session may not be in memory for historical sessions)
            val userId = getCurrentUserId()
            val sessionContext = sessionManager.getSessionContext(sessionId, userId)
            val projectPath = sessionContext?.projectPath

            // 4. Delete messages
            val deletedCount = sessionService.deleteMessagesFrom(sessionId, request.messageId)

            // 5. Rollback files
            val rollbackResult = if (projectPath != null) {
                revertService.rollbackToBeforeMessage(projectPath, sessionId, createdAt)
            } else null

            // 6. Clear file review state and revert state
            if (projectPath != null && snapshotService != null && snapshotService.isEnabled(projectPath)) {
                snapshotService.clearFileReviewState(projectPath, sessionId)
                snapshotService.clearRevertState(projectPath, sessionId)
            }

            EditMessageResponse(
                deletedMessageCount = deletedCount,
                rollback = rollbackResult?.let {
                    RollbackResponse(
                        filesCount = it.filesCount,
                        additions = it.additions,
                        deletions = it.deletions
                    )
                }
            )
        }
    }
}

/**
 * Request body for answering a question.
 */
data class QuestionAnswerRequest(
    val answers: List<List<String>>
)

/**
 * Request body for updating a goal's objective.
 */
data class UpdateGoalRequest(
    val objective: String
)

/**
 * Request body for reverting to a specific message's checkpoint.
 */
data class RevertRequest(
    val messageId: String
)

/**
 * Response for revert operation.
 */
data class RevertResponse(
    val messageId: String,
    val filesCount: Int,
    val additions: Int,
    val deletions: Int
)

/**
 * Response for unrevert operation.
 */
data class UnrevertResponse(
    val messageId: String,
    val filesCount: Int,
    val additions: Int,
    val deletions: Int
)

/**
 * Response for a single file diff.
 */
data class DiffResponse(
    val path: String,
    val patch: String?,
    val additions: Int,
    val deletions: Int,
    val status: String,
    val changedBy: String? = null
)

/**
 * Response for revert state query.
 */
data class RevertStateResponse(
    val messageId: String,
    val commitHash: String,
    val timestamp: Long,
    val filesCount: Int = 0,
    val additions: Int = 0,
    val deletions: Int = 0
)

/**
 * Request body for file accept/reject operations.
 */
data class FileReviewRequest(
    val filePath: String
)

/**
 * Request body for batch file accept/reject operations.
 */
data class BatchFileReviewRequest(
    val filePaths: List<String>
)

/**
 * Response for a single file accept/reject operation.
 */
data class FileReviewResponse(
    val path: String,
    val action: String
)

/**
 * Response for batch file accept/reject operation.
 */
data class BatchFileReviewResponse(
    val results: List<FileReviewResponse>
)

/**
 * Response for file review state query.
 */
data class FileReviewStateResponse(
    val reviews: Map<String, String>
)

/**
 * Request body for edit message operation.
 */
data class EditMessageRequest(
    val messageId: String
)

/**
 * Response for edit message operation.
 */
data class EditMessageResponse(
    val deletedMessageCount: Int,
    val rollback: RollbackResponse?
)

/**
 * Rollback result in edit message response.
 */
data class RollbackResponse(
    val filesCount: Int,
    val additions: Int,
    val deletions: Int
)


/**
 * Response for commit history API.
 */
data class CommitHistoryResponse(
    val commitHash: String,
    val author: String,
    val message: String,
    val timestamp: Long,
    val files: List<DiffResponse>
)

/**
 * Extension to convert FileDiff to DiffResponse.
 */
private fun com.easy.easyai.snapshot.model.FileDiff.toDiffResponse() = DiffResponse(
    path = path,
    patch = patch,
    additions = additions,
    deletions = deletions,
    status = status.name.lowercase(),
    changedBy = changedBy
)