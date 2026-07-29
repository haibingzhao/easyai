package com.easy.easyai.core.agent

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.TodoInfo
import com.easy.easyai.core.tool.ToolDefinition

/**
 * Manages conversation sessions with agents.
 * Fully async implementation using suspend functions.
 * Each session can have its own ChatModel based on the provider configuration.
 *
 * Sessions are NOT cached across requests. Each request creates or restores a session
 * fresh from the database, ensuring request parameters (model, agent, etc.) always take effect.
 */
interface SessionManager {
    /**
     * Get an existing session or create a new one with default configuration.
     * @param userId Optional user ID for data isolation.
     */
    suspend fun getOrCreateSession(sessionId: String? = null, userId: String = "system"): ChatSession

    /**
     * Get an existing session or create a new one with configuration.
     *
     * Always builds a fresh ChatSession using the request-provided parameters.
     * If the session exists in DB, it restores history but uses the provided
     * config/agentId (frontend parameters always override DB values).
     *
     * Identity fields (sessionId, agentId, projectId, projectPath, tools)
     * are read from [agentContext]. Only model connection params are passed separately.
     *
     * @param agentContext Agent context containing identity and behavior config.
     * @param config Model provider configuration for the LLM connection.
     * @param chatOptionsFactory Factory to create chat options from config.
     */
    suspend fun getOrCreateSession(
        agentContext: AgentContext,
        config: ModelProviderConfig,
        chatOptionsFactory: ChatModelFactory
    ): ChatSession

    /**
     * Get a session by ID.
     * Always restores from DB (no cross-request cache).
     * @param userId Optional user ID for data isolation during DB restoration.
     */
    suspend fun getSession(sessionId: String, userId: String = "system"): ChatSession?

    /**
     * Remove and close a session.
     */
    suspend fun closeSession(sessionId: String)

    /**
     * Paginated session result with hasMore indicator.
     */
    data class SessionListResult(
        val sessions: List<PersistedSession>,
        val hasMore: Boolean
    )

    /**
     * List recent sessions ordered by creation time (most recent first).
     * Returns paginated result with hasMore indicator.
     * @param projectId Optional project ID to filter sessions by project.
     */
    suspend fun listSessions(limit: Int, offset: Int = 0, projectId: String? = null): SessionListResult = SessionListResult(emptyList(), false)

    /**
     * Get full session detail by ID including messages.
     * Returns null if not found or not supported.
     * @param userId Optional user ID for data isolation.
     */
    suspend fun getSessionDetail(sessionId: String, userId: String = "system"): PersistedSession? = null

    /**
     * Save session messages to persistent storage.
     * Should be called after each conversation turn.
     *
     * @param context The chat context containing agentId, modelId, etc.
     */
    suspend fun saveSessionMessages(context: AgentContext, messages: List<EasyAiMessage>)

    /**
     * Load messages for a session from persistent storage.
     * Returns empty list if no messages exist or persistence is not supported.
     * Used to sync in-memory session state with the database.
     */
    suspend fun loadMessages(sessionId: String): List<EasyAiMessage> = emptyList()

    /**
     * Get the current todo list for a session.
     * Returns empty list if no todos exist or todo tracking is not supported.
     */
    suspend fun getTodos(sessionId: String): List<TodoInfo> = emptyList()

    /**
     * Get all todos for a session across all scopes (main agent + sub-agents).
     * Returns a map: null key = main agent, non-null key = agentRunId.
     */
    suspend fun getAllTodos(sessionId: String): Map<String?, List<TodoInfo>> = emptyMap()

    /**
     * Get the ChatContext for a session (agentId, modelId, mode, projectPath).
     * Returns null if the session is not found or context is unavailable.
     * Used to construct the context needed for saveSessionMessages.
     * @param userId Optional user ID for data isolation during DB restoration.
     */
    suspend fun getSessionContext(sessionId: String, userId: String = "system"): AgentContext? = null

    /**
     * Get message timestamps for a session.
     * Returns a map of messageId to createdAt timestamp (epoch millis).
     * Used by context compaction to determine correct ordering of summary messages.
     * Default returns empty map for implementations without timestamp support.
     */
    suspend fun getMessageTimestamps(sessionId: String): Map<String, Long> = emptyMap()
}
