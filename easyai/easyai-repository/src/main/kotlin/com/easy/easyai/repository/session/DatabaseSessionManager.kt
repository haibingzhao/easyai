package com.easy.easyai.repository.session

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.core.agent.*
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.TodoInfo
import com.easy.easyai.core.prompt.InstructionsLoader
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.repository.todo.AsyncTodoStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant
import java.util.*

/**
 * Database-backed implementation of SessionManager.
 * Uses AsyncSessionStore for persistence. Sessions are NOT cached across requests —
 * each request builds a fresh ChatSession to ensure frontend parameters always take effect.
 * All operations are fully async (suspend functions).
 *
 * Delegates Agent creation to SessionAgentFactory and tool/path resolution to SessionToolResolver.
 */
class DatabaseSessionManager(
    private val sessionStore: AsyncSessionStore,
    private val agentFactory: SessionAgentFactory,
    private val toolResolver: SessionToolResolver,
    /** Optional: config store to load ModelProviderConfig for session restoration */
    private val configStore: ModelProviderConfigStore? = null,
    /** Optional: suspend function to look up AgentDefinition by ID and userId */
    private val agentLookup: (suspend (String, String) -> AgentDefinition?)? = null,
    /** Optional: todo store for deleting session todos on session close */
    private val todoStore: AsyncTodoStore? = null,
    /** Skills data for prompt rendering */
    private val skills: List<Map<String, Any?>> = emptyList(),
    /** Optional: agent store for dynamic sub-agents resolution */
    private val agentStore: AsyncAgentStore? = null
) : SessionManager {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val saveMutex = Mutex()

    /**
     * Resolve skills, instructions, and tools for an agent definition.
     * Returns (enriched AgentContext, resolved tool list).
     */
    private suspend fun resolveAgentContextAndTools(
        agentDef: AgentDefinition,
        baseContext: AgentContext,
        projectPath: Path?
    ): Pair<AgentContext, List<ToolDefinition>> {
        val (agentSkills, allowedSkillNames) = resolveSkillsForAgent(agentDef.id)
        val enrichedContext = baseContext.copy(
            skills = agentSkills,
            allowedSkillNames = allowedSkillNames,
            instructions = if (agentDef.instructionsEnabled) {
                baseContext.instructions.ifEmpty { InstructionsLoader.load(projectPath) }
            } else emptyList()
        )
        val resolvedTools = toolResolver.resolveToolsForAgent(agentDef, enrichedContext)
        return enrichedContext to resolvedTools
    }

    /**
     * Resolve sub-agents data for prompt rendering from agent store.
     * Applies whitelist filtering based on the parent agent's SUBAGENT tool configs.
     */
    private suspend fun resolveSubAgentsData(agentId: String?, userId: String): List<Map<String, Any?>> {
        val store = agentStore ?: return emptyList()
        val allSubAgents = store.findSubAgents(userId)
        if (agentId == null) return allSubAgents.map { it.toSubAgentMap() }
        val allowedConfigs = store.getAgentToolConfigs(agentId, TargetType.SUBAGENT)
        if (allowedConfigs.isEmpty()) return allSubAgents.map { it.toSubAgentMap() }  // No whitelist = all sub-agents
        val allowedSet = allowedConfigs.map { it.targetName }.toSet()
        return allSubAgents.filter { it.id in allowedSet }.map { it.toSubAgentMap() }
    }

    private fun AgentDefinition.toSubAgentMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "description" to description,
        "inputSchema" to inputSchema
    )

    /**
     * Resolve skills for a specific agent, applying whitelist filtering.
     * Returns (filtered skills data, allowed skill names list).
     * Empty allowedSkillNames means no skills are allowed (consistent with toolNames semantics).
     */
    private suspend fun resolveSkillsForAgent(agentId: String): Pair<List<Map<String, Any?>>, List<String>> {
        val store = agentStore ?: return emptyList<Map<String, Any?>>() to emptyList()
        val allowedConfigs = store.getAgentToolConfigs(agentId, TargetType.SKILL)
        if (allowedConfigs.isEmpty()) return emptyList<Map<String, Any?>>() to emptyList()  // No whitelist = no skills
        val allowedNames = allowedConfigs.map { it.targetName }
        val allowedSet = allowedNames.toSet()
        val filteredSkills = skills.filter { (it["name"] as? String) in allowedSet }
        return filteredSkills to allowedNames
    }

    /**
     * Get an existing session or create a new one with default configuration.
     */
    override suspend fun getOrCreateSession(sessionId: String?, userId: String): ChatSession {
        val id = sessionId ?: UUID.randomUUID().toString()
        return createNewVirtualSession(id)
    }

    /**
     * Get an existing session or create a new one with configuration.
     *
     * Always builds a fresh ChatSession using the request-provided parameters.
     * If the session exists in DB, restores history but always applies the provided
     * config/agentId/options (frontend parameters always override DB values).
     *
     * Identity fields (sessionId, agentId, projectId, projectPath, tools)
     * are read from [agentContext]. Only model connection params are passed separately.
     */
    override suspend fun getOrCreateSession(
        agentContext: AgentContext,
        config: ModelProviderConfig,
        chatOptionsFactory: ChatModelFactory,
        options: Map<String, Any?>?
    ): ChatSession {
        val id = agentContext.sessionId ?: UUID.randomUUID().toString()
        val agentId = agentContext.agentId
        val projectId = agentContext.projectId
        val projectPath = agentContext.projectPath ?: toolResolver.resolveProjectPath(projectId, agentContext.userId ?: "system")
        val explicitTools = agentContext.tools.takeIf { it.isNotEmpty() }
        // Build resolved context with actual projectPath for downstream tool creation
        // Also fill skills/subAgents from session-level defaults if not already set by caller
        val resolvedSubAgents = resolveSubAgentsData(agentId, agentContext.userId ?: "system")
        val resolvedContext = agentContext.copy(
            sessionId = id,
            projectPath = projectPath,
            skills = agentContext.skills.ifEmpty { skills },
            subAgents = agentContext.subAgents.ifEmpty { resolvedSubAgents }
        )

        // Agent-based path: look up agent definition and resolve tools
        val agentDef = agentLookup?.invoke(agentId, agentContext.userId ?: "system")
        if (agentDef != null) {
            val (contextWithSkills, resolvedTools) = resolveAgentContextAndTools(agentDef, resolvedContext, projectPath)

            // Check database for existing session (to restore endReason)
            val persistedSession = sessionStore.findById(id, agentContext.userId ?: "system")
            val agent = agentFactory.createAgentWithAgentDef(agentDef, id, config, options, resolvedTools, contextWithSkills)
            val chatSession = ChatSession(id, agent)
            if (persistedSession != null) {
                logger.info("Restoring session from database with agent {}: {}", agentId, id)
                restoreEndReason(chatSession, id, agentContext.userId ?: "system")
            } else {
                logger.info("Creating new session with agent {}: {}", agentDef.id, id)
                persistNewSession(id, agentContext.userId ?: "system", projectId)
            }
            return chatSession
        }

        // Fallback: no agent definition found, use explicit tools or resolve defaults
        if (explicitTools == null) {
            logger.debug("Agent definition not found for: {}, using default tools", agentId)
        }
        val resolvedTools = explicitTools ?: toolResolver.createSessionTools(resolvedContext)

        // Check database for existing session (to restore endReason)
        val persistedSession = sessionStore.findById(id, agentContext.userId ?: "system")
        val agent = agentFactory.createAgentWithConfig(id, config, options, resolvedTools, resolvedContext)
        val chatSession = ChatSession(id, agent)
        if (persistedSession != null) {
            logger.info("Restoring session from database with config {}: {}", config.id, id)
            restoreEndReason(chatSession, id, agentContext.userId ?: "system")
        } else {
            logger.info("Creating new session with config {}: {}", config.id, id)
            persistNewSession(id, agentContext.userId ?: "system", projectId)
        }
        return chatSession
    }

    /**
     * Restore lastEndReason from DB into a freshly-created ChatSession.
     * Without this, a server restart would reset lastEndReason to "normal",
     * causing resume() to skip the max_iterations continuation guidance.
     */
    private suspend fun restoreEndReason(chatSession: ChatSession, sessionId: String, userId: String) {
        try {
            val endReason = sessionStore.findEndReason(sessionId, userId)
            if (endReason != null && endReason != "normal") {
                chatSession.lastEndReason = endReason
                logger.debug("Restored endReason '{}' for session {}", endReason, sessionId)
            }
        } catch (e: Exception) {
            logger.warn("Failed to restore endReason for session {}: {}", sessionId, e.message)
        }
    }

    /**
     * Get a session by ID.
     * Always restores from DB — no cross-request cache.
     * Recovers configuration from the last message to ensure chatModel and availableTools
     * match the original session.
     */
    override suspend fun getSession(sessionId: String, userId: String): ChatSession? {
        val persistedSession = sessionStore.findById(sessionId, userId) ?: return null
        logger.debug("Restoring session from database: {}", sessionId)
        return restoreSessionFromDatabase(persistedSession, sessionId, userId)
    }

    /**
     * Restore session from database with config recovery from last message.
     */
    private suspend fun restoreSessionFromDatabase(
        persistedSession: PersistedSession,
        sessionId: String,
        userId: String = "system"
    ): ChatSession {
        // Get config from last message (highest priority - reflects latest state)
        val lastConfig = (sessionStore as? R2dbcAsyncSessionStore)?.getLastMessageConfig(sessionId)
        val agentId = lastConfig?.agentId

        // Resolve a project path
        val projectPath = toolResolver.resolveProjectPath(persistedSession.projectId, userId)

        // Get config from last message's configId (stored per-message, not on session)
        val configId = lastConfig?.configId
        val config = if (configId != null) {
            configStore?.getConfig(configId, userId)
                ?: throw IllegalStateException("Config not found for id=$configId, session=$sessionId")
        } else {
            throw IllegalStateException("No configId found in last message for session $sessionId")
        }

        // Restore session with correct agent configuration
        val chatSession = if (agentId != null) {
            restoreSessionWithAgent(agentId, sessionId, config, projectPath, persistedSession.projectId, userId)
        } else {
            restoreSessionWithConfig(sessionId, config, projectPath, persistedSession.projectId, userId)
        }

        // Restore endReason from DB so resume() can inject the correct continuation guidance
        restoreEndReason(chatSession, sessionId, userId)

        return chatSession
    }

    /**
     * Restore a session using AgentDefinition from the agentId.
     */
    private suspend fun restoreSessionWithAgent(
        agentId: String,
        sessionId: String,
        config: ModelProviderConfig,
        projectPath: Path?,
        projectId: String?,
        userId: String = "system"
    ): ChatSession {
        val agentDef = agentLookup?.invoke(agentId, userId)
        if (agentDef == null) {
            logger.warn("AgentDefinition not found for agentId {}, falling back to config-based session", agentId)
            return restoreSessionWithConfig(sessionId, config, projectPath, projectId, userId)
        }

        // Apply skill filtering for this agent
        val subAgentsData = resolveSubAgentsData(agentId, userId)
        val (agentContext, resolvedTools) = resolveAgentContextAndTools(agentDef, AgentContext(
            agentId = agentId,
            modelConfig = config,
            sessionId = sessionId,
            userId = userId,
            projectId = projectId,
            projectPath = projectPath,
            subAgents = subAgentsData
        ), projectPath)
        val agent = agentFactory.createAgentWithAgentDef(agentDef, sessionId, config, null, resolvedTools, agentContext)
        return ChatSession(sessionId, agent)
    }

    /**
     * Restore a session using only config (no agentId).
     */
    private suspend fun restoreSessionWithConfig(
        sessionId: String,
        config: ModelProviderConfig,
        projectPath: Path?,
        projectId: String?,
        userId: String = "system"
    ): ChatSession {
        val subAgentsData = resolveSubAgentsData(DEFAULT_AGENT_ID, userId)
        val agentContext = AgentContext(
            agentId = DEFAULT_AGENT_ID,
            modelConfig = config,
            sessionId = sessionId,
            userId = userId,
            projectId = projectId,
            projectPath = projectPath,
            skills = skills,
            subAgents = subAgentsData,
            instructions = emptyList()
        )
        val resolvedTools = toolResolver.createSessionTools(agentContext)
        val agent = agentFactory.createAgentWithConfig(sessionId, config, null, resolvedTools, agentContext)
        return ChatSession(sessionId, agent)
    }

    /**
     * Remove and close a session.
     * Cleans up dependent todos (all scopes).
     */
    override suspend fun closeSession(sessionId: String) {
        try {
            todoStore?.deleteAllTodos(sessionId)
        } catch (e: Exception) {
            logger.error("Failed to delete todos for session: {}", sessionId, e)
        }
    }

    /**
     * List recent sessions from database ordered by creation time (most recent first).
     * @param projectId Optional project ID to filter sessions by project.
     */
    override suspend fun listSessions(limit: Int, offset: Int, projectId: String?): SessionManager.SessionListResult {
        val pageResult = sessionStore.findIdsByLimit(limit, offset, projectId)

        val sessionList = pageResult.ids.mapNotNull { id ->
            try {
                sessionStore.findById(id)
            } catch (e: Exception) {
                logger.warn("Failed to load session {}: {}", id, e.message)
                null
            }
        }

        return SessionManager.SessionListResult(
            sessions = sessionList,
            hasMore = pageResult.hasMore
        )
    }

    /**
     * Get full session detail by ID including messages.
     */
    override suspend fun getSessionDetail(sessionId: String, userId: String): PersistedSession? {
        return sessionStore.findById(sessionId, userId)
    }

    /**
     * Save session messages to database.
     * Should be called after each conversation turn.
     *
     * @param context The chat context containing agentId, modelId, etc.
     * @throws RuntimeException if save fails, allowing caller to handle appropriately.
     */
    override suspend fun saveSessionMessages(
        context: AgentContext,
        messages: List<EasyAiMessage>
    ) {
        val sessionId = requireNotNull(context.sessionId) { "AgentContext.sessionId must not be null when saving messages" }
        saveMutex.withLock {
            sessionStore.upsertMessages(context, sessionId, messages)
            logger.info("Saved {} messages for session {} with agentId={}, modelId={}",
                messages.size, sessionId, context.agentId, context.modelId)
        }
    }

    /**
     * Load active (non-compacted) messages for a session from the database.
     * Excludes messages that have been compacted (compactedAt IS NOT NULL),
     * so the agent loop receives only the compaction summary + recent messages.
     */
    override suspend fun loadMessages(sessionId: String): List<EasyAiMessage> {
        return sessionStore.loadActiveMessages(sessionId)
    }

    /**
     * Get message timestamps for a session from the database.
     * Returns a map of messageId to createdAt timestamp (epoch millis).
     */
    override suspend fun getMessageTimestamps(sessionId: String): Map<String, Long> {
        return sessionStore.loadMessagesWithTimestamps(sessionId)
            .associate { it.message.id to it.timestamp }
    }

    /**
     * Get the current todo list for a session.
     * Returns empty list if no todos exist or todo tracking is not supported.
     * Always returns the main agent's session-level todos (agentRunId = null).
     */
    override suspend fun getTodos(sessionId: String): List<TodoInfo> {
        return todoStore?.getTodos(sessionId, agentRunId = null) ?: emptyList()
    }

    /**
     * Get all todos for a session across all scopes (main agent + sub-agents).
     * Returns empty map if todo tracking is not supported.
     */
    override suspend fun getAllTodos(sessionId: String): Map<String?, List<TodoInfo>> {
        return todoStore?.getAllTodos(sessionId) ?: emptyMap()
    }

    /**
     * Get the ChatContext for a session.
     * Always loads from DB — no cross-request cache.
     */
    override suspend fun getSessionContext(sessionId: String, userId: String): AgentContext? {
        val persistedSession = sessionStore.findById(sessionId, userId)
            ?: return null

        val lastConfig = (sessionStore as? R2dbcAsyncSessionStore)?.getLastMessageConfig(sessionId)
        val agentId = lastConfig?.agentId ?: DEFAULT_AGENT_ID

        val configId = lastConfig?.configId
        val modelConfig = if (configId != null) {
            configStore?.getConfig(configId, userId)
                ?: throw IllegalStateException("Config not found for id=$configId, session=$sessionId")
        } else {
            null
        }
        val projectPath = toolResolver.resolveProjectPath(persistedSession.projectId, userId)

        return AgentContext(
            sessionId = sessionId,
            agentId = agentId,
            modelConfig = modelConfig,
            userId = userId,
            projectId = persistedSession.projectId,
            projectPath = projectPath
        )
    }

    private fun createNewVirtualSession(id: String): ChatSession {
        logger.info("Creating new virtual session: {}", id)
        val agent = agentFactory.createDefaultAgent()
        return ChatSession(id, agent)
    }

    /**
     * Persist a new session record to the database.
     */
    private suspend fun persistNewSession(
        id: String,
        userId: String,
        projectId: String?
    ) {
        saveMutex.withLock {
            try {
                val persistedSession = PersistedSession(
                    id = id,
                    messages = emptyList(),
                    projectId = projectId,
                    createdAt = Instant.now(),
                    updatedAt = Instant.now()
                )
                sessionStore.save(persistedSession, userId)
                logger.debug("Persisted new session to database: {} with projectId: {}", id, projectId)
            } catch (e: Exception) {
                logger.error("Failed to persist new session to database: {}", id, e)
            }
        }
    }

    companion object {
        private const val DEFAULT_AGENT_ID = "default-agent"
    }
}
