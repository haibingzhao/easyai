package com.easy.easyai.repository.session

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.PersistedSession
import com.easy.easyai.core.event.MessageUpdateField
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.Usage
import com.easy.easyai.core.model.UserMessage

/**
 * Configuration extracted from the last message in a session.
 * Used to restore session state (agentId, modelId) after memory cache miss.
 */
data class LastMessageConfig(
    val agentId: String?,
    val configId: String?,
    val modelId: String?
)

/**
 * Paginated session ID result with hasMore indicator.
 */
data class SessionPageResult(
    val ids: List<String>,
    val hasMore: Boolean
)

/**
 * Lightweight session metadata for list views.
 * Avoids loading full message content — only includes count and title text.
 */
data class SessionListMetadata(
    val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
    val firstUserMessageText: String?,
    val streaming: Boolean = false
)

/**
 * Message with metadata including timestamp.
 * Used for loading messages with their original creation time.
 */
data class MessageWithTimestamp(
    val message: EasyAiMessage,
    val timestamp: Long,
    val compactedAt: Long? = null,
    val parentMessageId: String? = null,
    val parentToolCallId: String? = null
)

/**
 * Async SessionStore interface using R2DBC.
 * All operations are suspend functions.
 */
interface AsyncSessionStore {
    suspend fun save(session: PersistedSession, userId: String = "system")
    suspend fun findById(id: String, userId: String = "system"): PersistedSession?
    suspend fun findIdsByLimit(limit: Int, offset: Int = 0, projectId: String? = null, userId: String = "system", excludeSwarm: Boolean = true): SessionPageResult

    /**
     * Lightweight query for session list views.
     * Returns session metadata (id, timestamps, message count, first user message text)
     * without loading full message content. Ordered by creation time (most recent first).
     *
     * Uses fetch-size = limit + 1 to detect whether more pages exist,
     * returning the pair of (results, hasMore).
     *
     * @param limit Maximum number of results to return per page
     * @param offset Pagination offset (number of rows to skip)
     * @param projectId Optional project ID filter; null to include all projects
     * @param userId User scope filter (defaults to "system"); uses strict user isolation
     * @param excludeSwarm When true, excludes swarm (multi-agent) sessions from results
     * @return A pair where `first` is the list of [SessionListMetadata] (at most [limit] entries)
     *         and `second` is a boolean indicating whether more results exist beyond this page
     */
    suspend fun findMetadataByLimit(limit: Int, offset: Int = 0, projectId: String? = null, userId: String = "system", excludeSwarm: Boolean = true): Pair<List<SessionListMetadata>, Boolean>
    suspend fun delete(id: String, userId: String = "system")
    /**
     * Upsert messages for a session with explicit chat context.
     * The context values (agentId, modelId) are from the chat request, not from the session,
     * to support agent/model switching during a session.
     * Only new messages (not yet in DB) will be inserted; existing ones are skipped.
     */
    suspend fun upsertMessages(context: AgentContext, sessionId: String, messages: List<EasyAiMessage>, parentMessageId: String? = null, parentToolCallId: String? = null)

    /**
     * Find all session IDs associated with a project.
     * Used for cascade deletion when removing a project.
     * @param userId Optional user ID filter for data isolation.
     */
    suspend fun findIdsByProjectId(projectId: String, userId: String = "system"): List<String>

    /**
     * Delete all sessions (and their messages) associated with a project.
     * Executes in a single transaction for atomicity.
     * @param userId Optional user ID filter for data isolation.
     */
    suspend fun deleteByProjectId(projectId: String, userId: String = "system"): Int

    /**
     * Check whether a session is owned by (or shared with) the given user.
     * Returns true when:
     * - userId is "system" (auth disabled)
     * - the session does not yet exist in the DB (new session, not yet persisted)
     * - the session belongs to the user or the system user
     *
     * Returns false only when the session exists and is owned by a different user.
     *
     * This method returns a plain boolean so the repository layer stays free
     * of HTTP/Web framework dependencies. Callers in the web layer are
     * responsible for translating a false result into an appropriate HTTP
     * status (e.g. 403 Forbidden).
     */
    suspend fun isSessionOwnedByUser(sessionId: String, userId: String = "system"): Boolean {
        // Default: allow for backward compatibility (non-R2DBC implementations)
        return true
    }

    /**
     * Load messages with their original creation timestamps.
     * Returns messages ordered by creation time (oldest first).
     */
    suspend fun loadMessagesWithTimestamps(sessionId: String): List<MessageWithTimestamp>

    /**
     * Load messages created after the given timestamp for a session.
     * Used for incremental recovery instead of full session detail reload.
     *
     * @param sessionId The session ID
     * @param afterTimestamp Only return messages with createdAt strictly greater than this value
     * @return Messages ordered by creation time (oldest first)
     */
    suspend fun loadMessagesWithTimestampsAfter(sessionId: String, afterTimestamp: Long): List<MessageWithTimestamp> = emptyList()

    /**
     * Get the createdAt timestamp of a specific message by its ID.
     * Lightweight single-row query — avoids loading all messages.
     * @return The timestamp (epoch millis), or null if the message is not found.
     */
    suspend fun getMessageCreatedAt(sessionId: String, messageId: String): Long? = null

    /**
     * Find the earliest compaction indicator that occurred at or after the given timestamp.
     * Lightweight query — only reads CUSTOM role messages and checks isCompactionIndicator metadata.
     *
     * Only checks compaction indicators (role = 'CUSTOM' with isCompactionIndicator metadata).
     * Indicators' createdAt = compactedAt (wall-clock time), which is always after the
     * triggering user message, making them the reliable entry point for compaction detection.
     * Orphaned summaries (whose indicators were deleted) are handled by deleteMessagesFromTimestamp
     * naturally and do not need to be detected here.
     *
     * @return The timestamp (epoch millis) of the earliest compaction indicator, or null.
     */
    suspend fun getFirstCompactionAfter(sessionId: String, messageCreatedAt: Long): Long? = null

    /**
     * Check if any compaction SUMMARY exists with createdAt >= the given timestamp.
     * Used to determine whether editing a message requires a full compaction undo.
     *
     * If no summary exists at/after the timestamp, the message is NOT in any compacted set
     * (it was in the "recent" portion during compaction). In that case, the expensive
     * undoCompactionAfter (restore + re-delete) can be skipped — deleteMessagesFromTimestamp alone
     * is sufficient, and the summary remains valid as context for the re-sent message.
     *
     * @return true if a compaction summary exists at/after the timestamp.
     */
    suspend fun hasCompactionSummaryAtOrAfter(sessionId: String, messageCreatedAt: Long): Boolean = false

    /**
     * Lightweight query for Session.contentUpdatedAt dirty marker.
     * Only reads a single column — avoids loading all messages.
     *
     * @return The contentUpdatedAt timestamp (epoch millis), or null if session not found.
     */
    suspend fun findContentUpdatedAt(sessionId: String, userId: String = "system"): Long? = null

    /**
     * Get the configuration (agentId, modelId, mode) from the last message in a session.
     * Used to restore session state when memory cache is lost.
     * Returns null if no messages exist or not supported.
     */
    suspend fun getLastMessageConfig(sessionId: String): LastMessageConfig? = null

    /**
     * Undo compaction that occurred at or after [messageCreatedAt].
     * Entry point: compaction indicators (CUSTOM role) with createdAt >= messageCreatedAt.
     * Traces indicator → summary → compactedMessageIds to precisely restore only the messages
     * compacted by rounds whose summary is at/after the edit point.
     *
     * Compactions whose summary is BEFORE the edit point are left intact (their context
     * does not include the edited message). Their indicator IDs are returned so the caller
     * can exclude them from the subsequent [deleteMessagesFromTimestamp] call.
     *
     * @param sessionId The session ID
     * @param messageCreatedAt The createdAt timestamp of the message being edited
     * @return IDs of preserved indicators (not undone, should survive deleteMessagesFromTimestamp)
     */
    suspend fun undoCompactionAfter(sessionId: String, messageCreatedAt: Long): Set<String> = emptySet()

    /**
     * Mark messages as compacted by setting compactedAt timestamp.
     * Used during context compaction to soft-delete old messages.
     *
     * @param sessionId The session ID
     * @param messageIds IDs of messages to mark as compacted
     * @param compactedAt Timestamp when compaction occurred (epoch millis)
     */
    suspend fun markCompacted(sessionId: String, messageIds: List<String>, compactedAt: Long)

    /**
     * Load active (non-compacted) parent-level messages for a session.
     * Returns messages ordered by creation time (oldest first),
     * excluding those with compactedAt IS NOT NULL, CUSTOM role,
     * or parentToolCallId IS NOT NULL (sub-agent messages).
     * Sub-agent messages are excluded because they are nested under
     * their parent tool call and must not appear in the LLM context.
     */
    suspend fun loadActiveMessages(sessionId: String): List<EasyAiMessage>

    /**
     * Load sub-agent messages for a specific parent tool call.
     * Returns messages where parentToolCallId matches, ordered by createdAt ASC.
     * Used for Sub Agent restart recovery after server restart.
     */
    suspend fun loadSubAgentMessages(sessionId: String, parentToolCallId: String): List<EasyAiMessage> = emptyList()

    /**
     * Save a compaction summary message.
     * The summary's createdAt is set to [createdAt] (the last compacted message's timestamp)
     * so it sorts correctly between prefix and recent messages when loaded for LLM.
     *
     * @param agentContext The current agent context (sessionId, agentId, modelId, mode) for persistence.
     * @param summary The compaction summary as a UserMessage
     * @param createdAt The createdAt timestamp to use (last compacted message's createdAt)
     */
    suspend fun saveCompactionSummary(agentContext: AgentContext, summary: UserMessage, createdAt: Long, usage: Usage = Usage())

    /**
     * Save a compaction indicator message with CUSTOM role.
     * This message is used for frontend display only - not sent to LLM.
     * Contains metadata about the compaction (count, tokens saved, etc.).
     *
     * @param agentContext The current agent context (sessionId, agentId, modelId, mode) for persistence.
     * @param indicator The compaction indicator as a UserMessage with CustomContent block
     * @param createdAt The createdAt timestamp to use (same as summary message for correct ordering)
     */
    suspend fun saveCompactionIndicator(agentContext: AgentContext, indicator: UserMessage, createdAt: Long)

    /**
     * Load messages by their IDs, including compacted (soft-deleted) messages.
     * Used by OriginalMessageLoader to retrieve original messages for subsequent compaction rounds.
     *
     * @param messageIds IDs of messages to load
     * @return Messages matching the given IDs, ordered by creation time
     */
    suspend fun loadMessagesByIds(messageIds: List<String>): List<EasyAiMessage>

    /**
     * Delete all messages with createdAt >= [fromTimestamp] (inclusive).
     * Used for editing a historical user message: deletes the message and all subsequent ones.
     *
     * @param sessionId The session ID
     * @param fromTimestamp The timestamp boundary (messages at or after this time are deleted)
     * @param excludeIds Message IDs to exclude from deletion (e.g. preserved compaction indicators)
     * @return Number of messages deleted
     */
    suspend fun deleteMessagesFromTimestamp(sessionId: String, fromTimestamp: Long, excludeIds: Set<String> = emptySet()): Int

    /**
     * Save or clear the pending permission request for a session.
     * @param sessionId The session ID
     * @param permissionJson JSON representation of the pending permission, or null to clear
     */
    suspend fun savePendingPermission(sessionId: String, permissionJson: String?)

    /**
     * Find the pending permission request for a session.
     * @param sessionId The session ID
     * @return JSON representation of the pending permission, or null if none
     */
    suspend fun findPendingPermission(sessionId: String): String?

    /**
     * Update an existing message in the database.
     * Used to modify message fields (content, metadata, token counts, stop reason).
     *
     * @param sessionId The session ID
     * @param messageId The ID of the message to update
     * @param message The updated message
     * @param fields The set of fields to update, or null to update all fields (and bump session contentUpdatedAt).
     *               When non-null, only the specified fields are written; session contentUpdatedAt is NOT bumped.
     */
    suspend fun updateMessage(sessionId: String, messageId: String, message: EasyAiMessage, fields: Set<MessageUpdateField>? = null)

    /**
     * Delete a single message by ID.
     * Used to remove orphaned skipped-placeholder ToolResultMessages after permission
     * approval merges real results into the target message.
     *
     * @param sessionId The session ID
     * @param messageId The ID of the message to delete
     */
    suspend fun deleteMessage(sessionId: String, messageId: String)

    /**
     * Find a session ID by swarm run and task IDs.
     * Used to locate the session associated with a specific swarm worker execution.
     *
     * @param swarmRunId The swarm run ID
     * @param swarmTaskId The swarm task ID
     * @return The session ID, or null if not found
     */
    suspend fun findSessionIdBySwarmTask(swarmRunId: String, swarmTaskId: String): String? = null

    /**
     * Update the runtime status of a session (e.g. "active", "streaming").
     * Used to persist SSE stream lifecycle to DB for multi-instance deployment.
     *
     * @param sessionId The session ID
     * @param status New status value ("active" or "streaming")
     * @param userId User ID for data isolation
     * @param expectedStatus Optional expected current status. When provided, the update
     *        is only applied if the DB row still has this status. Use this to avoid
     *        overwriting a newer status written by another instance.
     */
    suspend fun updateStatus(sessionId: String, status: String, userId: String = "system", expectedStatus: String? = null)

    /**
     * Get the current runtime status of a session.
     * @return The status string, or null if session not found.
     */
    suspend fun findStatus(sessionId: String, userId: String = "system"): String?

    /**
     * Save the end reason for a session (why the last agent execution ended).
     * Called when an AgentEndEvent is emitted. Cleared at the start of each new run.
     *
     * @param sessionId The session ID
     * @param endReason End reason value: "normal", "max_iterations", "cancelled", "error"
     * @param userId User ID for data isolation
     */
    suspend fun saveEndReason(sessionId: String, endReason: String, userId: String = "system")

    /**
     * Read the end reason for a session.
     * @param userId User ID for data isolation
     * @return The end reason string, or null if not set.
     */
    suspend fun findEndReason(sessionId: String, userId: String = "system"): String? = null

    /**
     * Load session variables from the latest compaction summary message's metadata.
     * Queries the most recent message with isCompactionSummary metadata and extracts
     * the sessionVariables field from its metadata JSON.
     *
     * This is the single source of truth for session variable restoration.
     *
     * @param sessionId The session ID
     * @param userId User ID for data isolation
     * @return JSON-serialized variables map, or null if no compaction summary contains variables
     */
    suspend fun loadVariablesFromCompactionSummary(sessionId: String, userId: String = "system"): String? = null
}