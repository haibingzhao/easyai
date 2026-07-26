package com.easy.easyai.repository.session

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.PersistedSession
import com.easy.easyai.core.event.MessageUpdateField
import com.easy.easyai.core.event.MessageListener
import com.easy.easyai.core.model.*
import com.easy.easyai.repository.database.Tables
import com.easy.easyai.repository.database.UserScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import com.easy.easyai.common.util.SharedObjectMapper
import java.time.Instant

/**
 * R2DBC-based implementation of AsyncSessionStore.
 * Uses Exposed R2DBC for pure async database operations.
 * Tool results are persisted as ToolResultMessage in the Message table.
 */
class R2dbcAsyncSessionStore(
    private val db: R2dbcDatabase,
    private val objectMapper: ObjectMapper = SharedObjectMapper.instance
) : AsyncSessionStore {
    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun save(session: PersistedSession, userId: String) {
        suspendTransaction(db) {
            val existingCount = Tables.Session
                .selectAll()
                .where { (Tables.Session.id eq session.id) and UserScope.filterStrict(Tables.Session.userId, userId) }
                .count()

            val createdAt = session.createdAt.toEpochMilli()
            val updatedAt = session.updatedAt.toEpochMilli()

            if (existingCount > 0) {
                Tables.Session.update(
                    where = { (Tables.Session.id eq session.id) and UserScope.filterStrict(Tables.Session.userId, userId) }
                ) { s ->
                    // Only update projectId when explicitly provided (non-null) to avoid clearing existing association
                    session.projectId?.let { s[Tables.Session.projectId] = it }
                    s[Tables.Session.updatedAt] = updatedAt
                    session.swarmRunId?.let { s[Tables.Session.swarmRunId] = it }
                    session.swarmTaskId?.let { s[Tables.Session.swarmTaskId] = it }
                }
                logger.info("Updated session: {} with {} messages", session.id, session.messages.size)
            } else {
                Tables.Session.insert {
                    it[Tables.Session.id] = session.id
                    it[Tables.Session.projectId] = session.projectId
                    it[Tables.Session.status] = "active"
                    it[Tables.Session.userId] = userId
                    it[Tables.Session.createdAt] = createdAt
                    it[Tables.Session.updatedAt] = updatedAt
                    it[Tables.Session.swarmRunId] = session.swarmRunId
                    it[Tables.Session.swarmTaskId] = session.swarmTaskId
                }
                logger.info("Inserted session: {} with {} messages", session.id, session.messages.size)
            }
        }
    }

    override suspend fun findById(id: String, userId: String): PersistedSession? {
        return suspendTransaction(db) {
            val sessionRow = Tables.Session
                .selectAll()
                .where { (Tables.Session.id eq id) and UserScope.filterStrict(Tables.Session.userId, userId) }
                .limit(1)
                .firstOrNull() ?: return@suspendTransaction null

            val messages = loadMessagesInternal(id)

            toPersistedSession(sessionRow, messages)
        }
    }

    override suspend fun findIdsByLimit(limit: Int, offset: Int, projectId: String?, userId: String, excludeSwarm: Boolean): SessionPageResult {
        return suspendTransaction(db) {
            val fetchSize = limit + 1
            val userFilter = UserScope.filterStrict(Tables.Session.userId, userId)
            val ids = Tables.Session
                .select(Tables.Session.id)
                .where {
                    val activeStatuses = Tables.Session.status inList listOf("active", "streaming")
                    val baseFilter = if (projectId != null) {
                        userFilter and activeStatuses and (Tables.Session.projectId eq projectId)
                    } else {
                        userFilter and activeStatuses
                    }
                    if (excludeSwarm) {
                        baseFilter and Tables.Session.swarmRunId.isNull()
                    } else {
                        baseFilter
                    }
                }
                .orderBy(Tables.Session.createdAt to SortOrder.DESC)
                .limit(fetchSize)
                .offset(offset.toLong())
                .toList()
                .map { it[Tables.Session.id] }

            val hasMore = ids.size > limit

            SessionPageResult(
                ids = if (hasMore) ids.dropLast(1) else ids,
                hasMore = hasMore
            )
        }
    }

    override suspend fun findMetadataByLimit(limit: Int, offset: Int, projectId: String?, userId: String, excludeSwarm: Boolean): Pair<List<SessionListMetadata>, Boolean> {
        return suspendTransaction(db) {
            val fetchSize = limit + 1
            val userFilter = UserScope.filterStrict(Tables.Session.userId, userId)

            // Query 1: Get session rows (lightweight — Session table only, no messages)
            val sessionRows = Tables.Session
                .select(Tables.Session.id, Tables.Session.createdAt, Tables.Session.updatedAt, Tables.Session.status)
                .where {
                    val activeStatuses = Tables.Session.status inList listOf("active", "streaming")
                    val baseFilter = if (projectId != null) {
                        userFilter and activeStatuses and (Tables.Session.projectId eq projectId)
                    } else {
                        userFilter and activeStatuses
                    }
                    if (excludeSwarm) {
                        baseFilter and Tables.Session.swarmRunId.isNull()
                    } else {
                        baseFilter
                    }
                }
                .orderBy(Tables.Session.createdAt to SortOrder.DESC)
                .limit(fetchSize)
                .offset(offset.toLong())
                .toList()

            val hasMore = sessionRows.size > limit
            val resultRows = if (hasMore) sessionRows.dropLast(1) else sessionRows

            if (resultRows.isEmpty()) {
                return@suspendTransaction emptyList<SessionListMetadata>() to hasMore
            }

            // Build metadata with lightweight per-session queries
            val metadataList = resultRows.map { row ->
                val id = row[Tables.Session.id]

                // Message count: lightweight SQL COUNT query (returns 1 row, no data loading)
                val msgCount = Tables.Message
                    .selectAll()
                    .where { Tables.Message.sessionId eq id }
                    .count()
                    .toInt()

                // First user message text only (1 lightweight row per session)
                val firstUserText = Tables.Message
                    .select(Tables.Message.contentBlocks)
                    .where {
                        (Tables.Message.sessionId eq id) and
                            (Tables.Message.role eq "USER") and
                            (Tables.Message.parentToolCallId.isNull())
                    }
                    .orderBy(Tables.Message.createdAt to SortOrder.ASC)
                    .limit(1)
                    .firstOrNull()
                    ?.getOrNull(Tables.Message.contentBlocks)
                    ?.let { extractTextFromContentBlocks(it) }

                SessionListMetadata(
                    id = id,
                    createdAt = row[Tables.Session.createdAt],
                    updatedAt = row[Tables.Session.updatedAt],
                    messageCount = msgCount,
                    firstUserMessageText = firstUserText?.take(50),
                    streaming = row[Tables.Session.status] == "streaming"
                )
            }

            metadataList to hasMore
        }
    }

    /**
     * Extract plain text from serialized content_blocks JSON.
     * Returns the concatenated text from all TextContent blocks, or null.
     */
    private fun extractTextFromContentBlocks(json: String?): String? {
        if (json.isNullOrBlank()) return null
        return try {
            val blocks: List<Map<String, Any?>> = objectMapper.readValue(json, object : TypeReference<List<Map<String, Any?>>>() {})
            val text = blocks
                .filter { it["type"] == "text" }
                .mapNotNull { it["text"] as? String }
                .joinToString("")
            text.ifBlank { null }
        } catch (e: Exception) {
            logger.warn("Failed to parse content_blocks for title extraction: {}", e.message)
            null
        }
    }

    override suspend fun findIdsByProjectId(projectId: String, userId: String): List<String> {
        return suspendTransaction(db) {
            val userFilter = UserScope.filterStrict(Tables.Session.userId, userId)
            Tables.Session
                .select(Tables.Session.id)
                .where { (Tables.Session.projectId eq projectId) and userFilter }
                .toList()
                .map { it[Tables.Session.id] }
        }
    }

    override suspend fun deleteByProjectId(projectId: String, userId: String): Int {
        return suspendTransaction(db) {
            val userFilter = UserScope.filterStrict(Tables.Session.userId, userId)
            // Delete messages for all sessions belonging to this project (user-scoped)
            val sessionIds = Tables.Session
                .select(Tables.Session.id)
                .where { (Tables.Session.projectId eq projectId) and userFilter }
                .toList()
                .map { it[Tables.Session.id] }

            for (sessionId in sessionIds) {
                Tables.TodoTable.deleteWhere { Tables.TodoTable.sessionId eq sessionId }
                Tables.Message.deleteWhere { Tables.Message.sessionId eq sessionId }
            }

            // Delete all sessions belonging to this project (user-scoped)
            val deleted = Tables.Session.deleteWhere {
                (Tables.Session.projectId eq projectId) and userFilter
            }
            if (deleted > 0) {
                logger.info("Cascade deleted {} sessions for project: {}", deleted, projectId)
            }
            deleted
        }
    }

    override suspend fun isSessionOwnedByUser(sessionId: String, userId: String): Boolean {
        if (userId == UserScope.SYSTEM_USER_ID) return true
        return suspendTransaction(db) {
            val row = Tables.Session
                .select(Tables.Session.userId)
                .where { Tables.Session.id eq sessionId }
                .limit(1)
                .firstOrNull()
                ?: return@suspendTransaction true // session not yet in DB — allow
            UserScope.matchesStrict(row[Tables.Session.userId], userId)
        }
    }

    override suspend fun delete(id: String, userId: String) {
        suspendTransaction(db) {
            // Verify session ownership before deleting
            val ownedCount = Tables.Session
                .selectAll()
                .where { (Tables.Session.id eq id) and UserScope.filterStrict(Tables.Session.userId, userId) }
                .count()

            if (ownedCount > 0) {
                // Delete todos (all scopes) + messages (includes ToolResultMessage)
                Tables.TodoTable.deleteWhere { sessionId eq id }
                Tables.Message.deleteWhere { sessionId eq id }
                Tables.Session.deleteWhere {
                    (Tables.Session.id eq id) and UserScope.filterStrict(Tables.Session.userId, userId)
                }
                logger.info("Deleted session: {}", id)
            }
        }
    }

    override suspend fun upsertMessages(context: AgentContext, sessionId: String, messages: List<EasyAiMessage>, parentMessageId: String?, parentToolCallId: String?) {
        if (messages.isEmpty()) return
        suspendTransaction(db) {
            messages.forEach { message ->
                val contentJson = serializeContentBlocks(message.content)
                val metadataJson = serializeMetadata(message)
                val tokenCounts = extractTokenCounts(message)
                val stopReasonStr = extractStopReason(message)

                Tables.Message.insertIgnore {
                    it[Tables.Message.id] = message.id
                    it[Tables.Message.sessionId] = sessionId
                    it[Tables.Message.agentId] = context.agentId
                    it[Tables.Message.configId] = context.configId.takeIf { it.isNotEmpty() }
                    it[Tables.Message.modelId] = context.modelId.takeIf { it.isNotEmpty() }
                    it[Tables.Message.role] = message.role.name
                    it[Tables.Message.contentBlocks] = contentJson
                    it[Tables.Message.metadata] = metadataJson
                    it[Tables.Message.inputTokenCount] = tokenCounts.inputTokens
                    it[Tables.Message.outputTokenCount] = tokenCounts.outputTokens
                    it[Tables.Message.cacheReadTokenCount] = tokenCounts.cacheReadTokens
                    it[Tables.Message.cacheWriteTokenCount] = tokenCounts.cacheWriteTokens
                    it[Tables.Message.durationMs] = tokenCounts.durationMs
                    it[Tables.Message.stopReason] = stopReasonStr
                    it[Tables.Message.parentMessageId] = parentMessageId
                    it[Tables.Message.parentToolCallId] = parentToolCallId
                    it[Tables.Message.createdAt] = System.currentTimeMillis()
                }
            }
        }
        logger.debug("Upserted {} messages for session {}", messages.size, sessionId)
    }

    /**
     * Update an existing message in the database.
     * @param fields If null, updates all fields and bumps session contentUpdatedAt.
     *               If non-null, only updates the specified fields without bumping contentUpdatedAt.
     */
    override suspend fun updateMessage(sessionId: String, messageId: String, message: EasyAiMessage, fields: Set<MessageUpdateField>?) {
        suspendTransaction(db) {
            if (fields == null) {
                // Full update: all fields + bump contentUpdatedAt
                val contentJson = serializeContentBlocks(message.content)
                val metadataJson = serializeMetadata(message)
                val tokenCounts = extractTokenCounts(message)
                val stopReasonStr = extractStopReason(message)

                Tables.Message.update({ Tables.Message.id eq messageId }) {
                    it[Tables.Message.contentBlocks] = contentJson
                    it[Tables.Message.metadata] = metadataJson
                    it[Tables.Message.inputTokenCount] = tokenCounts.inputTokens
                    it[Tables.Message.outputTokenCount] = tokenCounts.outputTokens
                    it[Tables.Message.cacheReadTokenCount] = tokenCounts.cacheReadTokens
                    it[Tables.Message.cacheWriteTokenCount] = tokenCounts.cacheWriteTokens
                    it[Tables.Message.durationMs] = tokenCounts.durationMs
                    it[Tables.Message.stopReason] = stopReasonStr
                }
                Tables.Session.update({ Tables.Session.id eq sessionId }) {
                    it[Tables.Session.contentUpdatedAt] = System.currentTimeMillis()
                }
                logger.debug("Updated message {} for session {}", messageId, sessionId)
            } else {
                // Partial update: only specified fields, no contentUpdatedAt bump
                Tables.Message.update({ Tables.Message.id eq messageId }) {
                    if (MessageUpdateField.CONTENT_BLOCKS in fields) {
                        it[Tables.Message.contentBlocks] = serializeContentBlocks(message.content)
                    }
                    if (MessageUpdateField.METADATA in fields) {
                        val metadataJson = serializeMetadata(message)
                        it[Tables.Message.metadata] = metadataJson
                    }
                    if (MessageUpdateField.TOKEN_COUNTS in fields) {
                        val tokenCounts = extractTokenCounts(message)
                        it[Tables.Message.inputTokenCount] = tokenCounts.inputTokens
                        it[Tables.Message.outputTokenCount] = tokenCounts.outputTokens
                        it[Tables.Message.cacheReadTokenCount] = tokenCounts.cacheReadTokens
                        it[Tables.Message.cacheWriteTokenCount] = tokenCounts.cacheWriteTokens
                        it[Tables.Message.durationMs] = tokenCounts.durationMs
                    }
                    if (MessageUpdateField.STOP_REASON in fields) {
                        it[Tables.Message.stopReason] = extractStopReason(message)
                    }
                }
                logger.debug("Updated fields {} for message {} in session {}", fields, messageId, sessionId)
            }
        }
    }

    /**
     * Get the configuration (agentId, modelId) from the last message in a session.
     * Used to restore session state when memory cache is lost.
     */
    override suspend fun getLastMessageConfig(sessionId: String): LastMessageConfig? {
        return suspendTransaction(db) {
            val lastMessage = Tables.Message
                .selectAll()
                .where { Tables.Message.sessionId eq sessionId }
                .orderBy(Tables.Message.createdAt to SortOrder.DESC)
                .limit(1)
                .firstOrNull()

            lastMessage?.let { row ->
                LastMessageConfig(
                    agentId = row[Tables.Message.agentId],
                    configId = row[Tables.Message.configId],
                    modelId = row[Tables.Message.modelId]
                )
            }
        }
    }

    /**
     * Ensure a session row exists for [sessionId] (idempotent).
     *
     * Team member sessions are created lazily: the member session ID is generated at
     * delegation time, but no session row is written until messages are persisted.
     * Without this, session lookups ([findById]) fail with "Session not found" even
     * though messages exist. Uses insertIgnore so pre-existing sessions (main chat,
     * swarm, sub-agent) are left untouched.
     */
    suspend fun ensureSessionExists(sessionId: String, context: AgentContext) {
        suspendTransaction(db) {
            val userId = context.userId ?: UserScope.SYSTEM_USER_ID
            val now = System.currentTimeMillis()
            Tables.Session.insertIgnore {
                it[Tables.Session.id] = sessionId
                it[Tables.Session.projectId] = context.projectId
                it[Tables.Session.status] = "active"
                it[Tables.Session.userId] = userId
                it[Tables.Session.createdAt] = now
                it[Tables.Session.updatedAt] = now
            }
        }
    }

    /**
     * Returns a MessageListener that persists messages to the database in real-time.
     * Call this and pass it to AgentService.messageListener.
     */
    fun createMessageListener(sessionId: String, context: AgentContext, parentMessageId: String? = null, parentToolCallId: String? = null): MessageListener =
        R2dbcMessageListener(this, sessionId, context, parentMessageId, parentToolCallId)

    /**
     * Load messages within a transaction, including timestamps.
     * ToolResultMessage is reconstructed from the persisted content blocks.
     */
    override suspend fun loadMessagesWithTimestamps(sessionId: String): List<MessageWithTimestamp> {
        return suspendTransaction(db) {
            val messageRows = Tables.Message
                .selectAll()
                .where { Tables.Message.sessionId eq sessionId }
                .orderBy(Tables.Message.createdAt to SortOrder.ASC)
                .toList()

            messageRows.mapNotNull { row -> parseMessageRow(row) }
        }
    }

    /**
     * Load messages created strictly after the given timestamp.
     * Used for incremental recovery — avoids re-fetching messages already present on the client.
     * Leverages the (session_id, created_at) composite index for efficient range scans.
     */
    override suspend fun loadMessagesWithTimestampsAfter(sessionId: String, afterTimestamp: Long): List<MessageWithTimestamp> {
        return suspendTransaction(db) {
            Tables.Message
                .selectAll()
                .where {
                    (Tables.Message.sessionId eq sessionId) and
                        (Tables.Message.createdAt greater afterTimestamp)
                }
                .orderBy(Tables.Message.createdAt to SortOrder.ASC)
                .toList()
                .mapNotNull { row -> parseMessageRow(row) }
        }
    }

    override suspend fun getMessageCreatedAt(sessionId: String, messageId: String): Long? {
        return suspendTransaction(db) {
            Tables.Message
                .selectAll()
                .where { (Tables.Message.sessionId eq sessionId) and (Tables.Message.id eq messageId) }
                .firstOrNull()
                ?.get(Tables.Message.createdAt)
        }
    }

    override suspend fun getFirstCompactionAfter(sessionId: String, messageCreatedAt: Long): Long? {
        return suspendTransaction(db) {
            Tables.Message
                .select(Tables.Message.createdAt, Tables.Message.role, Tables.Message.metadata)
                .where {
                    (Tables.Message.sessionId eq sessionId) and
                        (Tables.Message.createdAt greaterEq messageCreatedAt)
                }
                .orderBy(Tables.Message.createdAt to SortOrder.ASC)
                .firstOrNull { row ->
                    val role = row[Tables.Message.role]
                    if (role == "CUSTOM") return@firstOrNull true
                    if (role == "USER") {
                        val metadata = parseMetadata(row[Tables.Message.metadata])
                        return@firstOrNull metadata["isCompactionSummary"] == "true"
                    }
                    false
                }
                ?.get(Tables.Message.createdAt)
        }
    }

    override suspend fun undoCompactionAfter(sessionId: String, messageCreatedAt: Long) {
        suspendTransaction(db) {
            // Step 1: Find compaction INDICATORS at/after the edit point.
            // Indicator.createdAt = compactedAt (wall-clock time when compaction ran),
            // which is always >= the compacted messages' timestamps.
            val indicatorRows = Tables.Message
                .select(Tables.Message.id, Tables.Message.metadata, Tables.Message.contentBlocks)
                .where {
                    (Tables.Message.sessionId eq sessionId) and
                        (Tables.Message.role eq "CUSTOM") and
                        (Tables.Message.createdAt greaterEq messageCreatedAt)
                }
                .toList()
                .filter { row ->
                    parseMetadata(row[Tables.Message.metadata])["isCompactionIndicator"] == "true"
                }

            if (indicatorRows.isEmpty()) return@suspendTransaction

            val indicatorIds = indicatorRows.map { it[Tables.Message.id] }.toSet()

            // Step 2: Extract summaryMessageId from each indicator's CustomContent block
            val summaryIds = indicatorRows.mapNotNull { row ->
                extractSummaryMessageId(row[Tables.Message.contentBlocks])
            }.toSet()

            if (summaryIds.isEmpty()) return@suspendTransaction

            // Step 3: Load the corresponding summaries to get compactedMessageIds.
            // Note: a summary's createdAt may be BEFORE the edit point — that's fine,
            // we still need to restore its compacted messages and delete the summary.
            val summaryRows = Tables.Message
                .select(Tables.Message.id, Tables.Message.metadata)
                .where {
                    (Tables.Message.sessionId eq sessionId) and
                        (Tables.Message.id inList summaryIds)
                }
                .toList()

            val allCompactedIds = summaryRows.flatMap { row ->
                val meta = parseMetadata(row[Tables.Message.metadata])
                val idsJson = meta["compactedMessageIds"] ?: return@flatMap emptyList<String>()
                try {
                    objectMapper.readValue(idsJson, object : TypeReference<List<String>>() {})
                } catch (e: Exception) {
                    logger.warn("Failed to parse compactedMessageIds from summary {}: {}", row[Tables.Message.id], e.message)
                    emptyList()
                }
            }

            // Step 4: Restore original messages (clear compactedAt)
            if (allCompactedIds.isNotEmpty()) {
                Tables.Message.update(
                    where = { (Tables.Message.sessionId eq sessionId) and (Tables.Message.id inList allCompactedIds) }
                ) {
                    it[Tables.Message.compactedAt] = null
                }
                logger.info(
                    "Restored {} compacted messages for undoCompaction in session {}",
                    allCompactedIds.size, sessionId
                )
            }

            // Step 5: Delete summaries + indicators
            val toDelete = summaryIds + indicatorIds
            Tables.Message.deleteWhere {
                (Tables.Message.sessionId eq sessionId) and (Tables.Message.id inList toDelete)
            }
            logger.info(
                "Deleted {} compaction artifacts ({} summaries, {} indicators) for session {}",
                toDelete.size, summaryIds.size, indicatorIds.size, sessionId
            )
        }
    }

    /**
     * Extract [CustomContent.metadata] `summaryMessageId` from a compaction indicator's contentBlocks JSON.
     * Returns null if parsing fails or no compaction block is found.
     */
    private fun extractSummaryMessageId(contentBlocksJson: String?): String? {
        if (contentBlocksJson.isNullOrBlank()) return null
        return try {
            val javaType = objectMapper.typeFactory.constructCollectionType(
                List::class.java, ContentBlock::class.java
            )
            val blocks: List<ContentBlock> = objectMapper.readValue(contentBlocksJson, javaType)
            blocks.filterIsInstance<CustomContent>()
                .firstOrNull { it.customType == "compaction" }
                ?.metadata?.get("summaryMessageId")?.toString()
        } catch (e: Exception) {
            logger.warn("Failed to extract summaryMessageId from contentBlocks: {}", e.message)
            null
        }
    }

    override suspend fun findContentUpdatedAt(sessionId: String, userId: String): Long? {
        return suspendTransaction(db) {
            val userFilter = UserScope.filterStrict(Tables.Session.userId, userId)
            Tables.Session
                .select(Tables.Session.contentUpdatedAt)
                .where { (Tables.Session.id eq sessionId) and userFilter }
                .limit(1)
                .firstOrNull()
                ?.get(Tables.Session.contentUpdatedAt)
        }
    }

    private suspend fun loadMessagesInternal(sessionId: String): List<EasyAiMessage> {
        return loadMessagesWithTimestamps(sessionId).map { it.message }
    }

    private fun toPersistedSession(row: ResultRow, messages: List<EasyAiMessage>): PersistedSession {
        return PersistedSession(
            id = row[Tables.Session.id],
            messages = messages,
            projectId = row[Tables.Session.projectId],
            createdAt = Instant.ofEpochMilli(row[Tables.Session.createdAt]),
            updatedAt = Instant.ofEpochMilli(row[Tables.Session.updatedAt]),
            swarmRunId = row[Tables.Session.swarmRunId],
            swarmTaskId = row[Tables.Session.swarmTaskId]
        )
    }

    private fun serializeContentBlocks(content: List<ContentBlock>): String {
        return objectMapper.writerFor(object : TypeReference<List<ContentBlock>>(){})
            .writeValueAsString(content)
    }

    private fun serializeMetadata(message: EasyAiMessage): String? {
        return when (message) {
            is UserMessage -> {
                if (message.metadata.isEmpty()) null else objectMapper.writeValueAsString(message.metadata)
            }
            is AssistantMessage -> {
                if (message.references != null) {
                    objectMapper.writeValueAsString(message.toMetadataMap())
                } else null
            }
            else -> null
        }
    }

    private fun parseMetadata(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val raw = objectMapper.readValue(json, object : TypeReference<Map<String, Any>>() {})
            raw.mapValues { (_, v) ->
                when (v) {
                    is String -> v
                    else -> objectMapper.writeValueAsString(v)
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse metadata: {}", json, e)
            emptyMap()
        }
    }

    /**
     * Extract ContextReferences from the metadata JSON column of an AssistantMessage row.
     * Handles both legacy double-encoded format and the current single-level format.
     */
    private fun parseReferencesFromMetadata(json: String?): ContextReferences? {
        if (json.isNullOrBlank()) return null
        return try {
            val tree = objectMapper.readTree(json)
            val refsNode = tree.get("references") ?: return null
            if (refsNode.isString) {
                // Legacy double-encoded format: references value is a JSON string
                objectMapper.readValue(refsNode.asString(), ContextReferences::class.java)
            } else {
                // Current format: references is a nested JSON object
                objectMapper.treeToValue(refsNode, ContextReferences::class.java)
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse references from metadata: {}", e.message)
            null
        }
    }

    private fun extractTokenCounts(message: EasyAiMessage): TokenCounts {
        return when (message) {
            is AssistantMessage -> TokenCounts(
                inputTokens = message.usage.inputTokens,
                outputTokens = message.usage.outputTokens,
                cacheReadTokens = message.usage.cacheReadTokens,
                cacheWriteTokens = message.usage.cacheWriteTokens,
                durationMs = message.usage.durationMs
            )
            else -> TokenCounts()
        }
    }

    private data class TokenCounts(
        val inputTokens: Int? = null,
        val outputTokens: Int? = null,
        val cacheReadTokens: Int? = null,
        val cacheWriteTokens: Int? = null,
        val durationMs: Long? = null
    )

    /**
     * Mark messages as compacted by setting compactedAt timestamp.
     */
    override suspend fun markCompacted(sessionId: String, messageIds: List<String>, compactedAt: Long) {
        if (messageIds.isEmpty()) return
        suspendTransaction(db) {
            Tables.Message.update(
                where = { (Tables.Message.sessionId eq sessionId) and (Tables.Message.id inList messageIds) }
            ) {
                it[Tables.Message.compactedAt] = compactedAt
            }
            logger.info("Marked {} messages as compacted for session {}", messageIds.size, sessionId)
        }
    }

    /**
     * Load active (non-compacted) parent-level messages for a session.
     * Excludes messages where compactedAt IS NOT NULL, role is CUSTOM,
     * or parentToolCallId IS NOT NULL (sub-agent messages).
     * Sub-agent messages are persisted with parentToolCallId for frontend display
     * but must not be included in the LLM context.
     */
    override suspend fun loadActiveMessages(sessionId: String): List<EasyAiMessage> {
        return suspendTransaction(db) {
            Tables.Message
                .selectAll()
                .where { 
                    (Tables.Message.sessionId eq sessionId) and 
                    (Tables.Message.compactedAt.isNull()) and
                    (Tables.Message.role neq Role.CUSTOM.name) and
                    (Tables.Message.parentToolCallId.isNull())
                }
                .orderBy(Tables.Message.createdAt to SortOrder.ASC)
                .toList()
                .mapNotNull { row -> parseMessageRow(row)?.message }
        }
    }

    override suspend fun loadSubAgentMessages(sessionId: String, parentToolCallId: String): List<EasyAiMessage> {
        return suspendTransaction(db) {
            Tables.Message
                .selectAll()
                .where {
                    (Tables.Message.sessionId eq sessionId) and
                    (Tables.Message.parentToolCallId eq parentToolCallId)
                }
                .orderBy(Tables.Message.createdAt to SortOrder.ASC)
                .toList()
                .mapNotNull { row -> parseMessageRow(row)?.message }
        }
    }

    /**
     * Save a compaction summary message.
     * Uses [createdAt] (the last compacted message's timestamp) so the summary
     * sorts correctly between prefix and recent messages (ORDER BY created_at ASC).
     */
    override suspend fun saveCompactionSummary(agentContext: AgentContext, summary: UserMessage, createdAt: Long, usage: Usage) {
        val sessionId = agentContext.sessionId ?: error("agentContext.sessionId must not be null for compaction summary")
        suspendTransaction(db) {
            val contentJson = serializeContentBlocks(summary.content)
            val metadataJson = serializeMetadata(summary)

            Tables.Message.upsert {
                it[Tables.Message.id] = summary.id
                it[Tables.Message.sessionId] = sessionId
                it[Tables.Message.agentId] = agentContext.agentId
                it[Tables.Message.configId] = agentContext.configId.takeIf { it.isNotEmpty() }
                it[Tables.Message.modelId] = agentContext.modelId.takeIf { it.isNotEmpty() }
                it[Tables.Message.role] = Role.USER.name
                it[Tables.Message.contentBlocks] = contentJson
                it[Tables.Message.metadata] = metadataJson
                it[Tables.Message.inputTokenCount] = usage.inputTokens
                it[Tables.Message.outputTokenCount] = usage.outputTokens
                it[Tables.Message.cacheReadTokenCount] = usage.cacheReadTokens
                it[Tables.Message.cacheWriteTokenCount] = usage.cacheWriteTokens
                it[Tables.Message.createdAt] = createdAt
                // compactedAt is null — summary is an active message
            }
        }
        logger.debug("Saved compaction summary for session {} at t={}", sessionId, createdAt)
    }

    /**
     * Save a compaction indicator message with CUSTOM role.
     * This message is used for frontend display only - excluded from LLM context loading.
     */
    override suspend fun saveCompactionIndicator(agentContext: AgentContext, indicator: UserMessage, createdAt: Long) {
        val sessionId = agentContext.sessionId ?: error("agentContext.sessionId must not be null for compaction indicator")
        suspendTransaction(db) {
            val contentJson = serializeContentBlocks(indicator.content)
            val metadataJson = serializeMetadata(indicator)

            Tables.Message.upsert {
                it[Tables.Message.id] = indicator.id
                it[Tables.Message.sessionId] = sessionId
                it[Tables.Message.agentId] = agentContext.agentId
                it[Tables.Message.configId] = agentContext.configId.takeIf { it.isNotEmpty() }
                it[Tables.Message.modelId] = agentContext.modelId.takeIf { it.isNotEmpty() }
                it[Tables.Message.role] = Role.CUSTOM.name
                it[Tables.Message.contentBlocks] = contentJson
                it[Tables.Message.metadata] = metadataJson
                it[Tables.Message.createdAt] = createdAt
                // compactedAt is null — indicator is an active message (for frontend display)
            }
        }
        logger.debug("Saved compaction indicator for session {} at t={}", sessionId, createdAt)
    }

    /**
     * Load messages by their IDs, including compacted (soft-deleted) messages.
     * Excludes CUSTOM role messages (e.g. compaction indicators).
     */
    override suspend fun loadMessagesByIds(messageIds: List<String>): List<EasyAiMessage> {
        if (messageIds.isEmpty()) return emptyList()
        return suspendTransaction(db) {
            Tables.Message
                .selectAll()
                .where {
                    (Tables.Message.id inList messageIds) and
                    (Tables.Message.role neq Role.CUSTOM.name)
                }
                .orderBy(Tables.Message.createdAt to SortOrder.ASC)
                .toList()
                .mapNotNull { row -> parseMessageRow(row)?.message }
        }
    }

    /**
     * Parse a message row into EasyAiMessage.
     * Shared between loadMessagesWithTimestamps and loadActiveMessages.
     */
    private fun parseMessageRow(row: ResultRow): MessageWithTimestamp? {
        val contentBlocksJson = row[Tables.Message.contentBlocks]
        if (contentBlocksJson.isNullOrBlank()) return null

        val role = Role.valueOf(row[Tables.Message.role])
        val javaType = objectMapper.typeFactory.constructCollectionType(
            List::class.java, ContentBlock::class.java
        )
        val contentBlocks: List<ContentBlock> = objectMapper.readValue(contentBlocksJson, javaType)
        val messageId = row[Tables.Message.id]
        val stopReasonStr = row[Tables.Message.stopReason]
        val stopReason = stopReasonStr?.let {
            try { StopReason.valueOf(it) } catch (_: IllegalArgumentException) { null }
        }
        val metadata = parseMetadata(row[Tables.Message.metadata])
        val timestamp = row[Tables.Message.createdAt]

        // Read usage from DB columns (present for AssistantMessage and compaction summary UserMessages)
        val usage = Usage(
            inputTokens = row[Tables.Message.inputTokenCount] ?: 0,
            outputTokens = row[Tables.Message.outputTokenCount] ?: 0,
            cacheReadTokens = row[Tables.Message.cacheReadTokenCount] ?: 0,
            cacheWriteTokens = row[Tables.Message.cacheWriteTokenCount] ?: 0,
            durationMs = row[Tables.Message.durationMs] ?: 0
        )
        val hasUsage = usage.inputTokens > 0 || usage.outputTokens > 0

        val message: EasyAiMessage? = when (role) {
            Role.USER -> UserMessage(id = messageId, content = contentBlocks, metadata = metadata, usage = if (hasUsage) usage else null)
            Role.ASSISTANT -> {
                val references = parseReferencesFromMetadata(row[Tables.Message.metadata])
                AssistantMessage(
                    id = messageId,
                    content = contentBlocks,
                    stopReason = stopReason,
                    usage = usage,
                    references = references
                )
            }
            Role.TOOL -> {
                val toolResultContents = contentBlocks.filterIsInstance<ToolResultContent>()
                if (toolResultContents.isNotEmpty()) {
                    ToolResultMessage(
                        id = messageId,
                        toolResults = toolResultContents.map { trc ->
                            ToolResultEntry(
                                toolCallId = trc.toolCallId,
                                toolName = trc.toolName,
                                result = trc.output,
                                exitCode = trc.exitCode,
                                durationMs = trc.durationMs,
                                mimeType = trc.mimeType,
                                isError = trc.isError,
                                truncated = trc.truncated,
                                isSkipped = trc.isSkipped,
                                usage = trc.usage
                            )
                        }
                    )
                } else null
            }
            Role.SYSTEM -> {
                val text = contentBlocks.filterIsInstance<TextContent>().joinToString("\n") { it.text }
                SystemMessage(id = messageId, text = text)
            }
            Role.CUSTOM -> CustomMessage(id = messageId, content = contentBlocks, metadata = metadata)
            Role.ERROR -> null
        }

        val compactedAt = row[Tables.Message.compactedAt]
        val parentMessageId = row[Tables.Message.parentMessageId]
        val parentToolCallId = row[Tables.Message.parentToolCallId]
        return message?.let { MessageWithTimestamp(it, timestamp, compactedAt = compactedAt, parentMessageId = parentMessageId, parentToolCallId = parentToolCallId) }
    }

    private fun extractStopReason(message: EasyAiMessage): String? {
        return when (message) {
            is AssistantMessage -> message.stopReason?.name
            else -> null
        }
    }

    /**
     * Delete all messages with createdAt >= the target message's createdAt (inclusive).
     */
    override suspend fun deleteMessagesFrom(sessionId: String, messageId: String): Int {
        return suspendTransaction(db) {
            // 1. Look up the target message's createdAt timestamp
            val targetRow = Tables.Message
                .select(Tables.Message.createdAt)
                .where { (Tables.Message.sessionId eq sessionId) and (Tables.Message.id eq messageId) }
                .limit(1)
                .firstOrNull() ?: return@suspendTransaction 0

            val targetCreatedAt = targetRow[Tables.Message.createdAt]

            // 2. Delete all messages with createdAt >= target
            val deleted = Tables.Message.deleteWhere {
                (Tables.Message.sessionId eq sessionId) and (Tables.Message.createdAt greaterEq targetCreatedAt)
            }
            logger.info("Deleted {} messages from createdAt >= {} for session {}", deleted, targetCreatedAt, sessionId)
            deleted
        }
    }

    override suspend fun savePendingPermission(sessionId: String, permissionJson: String?) {
        suspendTransaction(db) {
            Tables.Session.update(
                where = { Tables.Session.id eq sessionId }
            ) {
                if (permissionJson != null) {
                    it[Tables.Session.pendingPermission] = permissionJson
                } else {
                    it[Tables.Session.pendingPermission] = null as String?
                }
                it[Tables.Session.updatedAt] = System.currentTimeMillis()
            }
        }
        logger.debug("Saved pending permission for session {}: {}", sessionId, if (permissionJson != null) "set" else "cleared")
    }

    override suspend fun findPendingPermission(sessionId: String): String? {
        return suspendTransaction(db) {
            val row = Tables.Session
                .select(Tables.Session.pendingPermission)
                .where { Tables.Session.id eq sessionId }
                .limit(1)
                .firstOrNull() ?: return@suspendTransaction null
            row[Tables.Session.pendingPermission]
        }
    }

    override suspend fun updateStatus(sessionId: String, status: String, userId: String, expectedStatus: String?) {
        suspendTransaction(db) {
            val userFilter = UserScope.filterStrict(Tables.Session.userId, userId)
            val whereClause = expectedStatus?.let {
                (Tables.Session.id eq sessionId) and userFilter and (Tables.Session.status eq expectedStatus)
            } ?: ((Tables.Session.id eq sessionId) and userFilter)
            Tables.Session.update({ whereClause }) {
                it[Tables.Session.status] = status
                it[Tables.Session.updatedAt] = System.currentTimeMillis()
            }
        }
    }

    override suspend fun findStatus(sessionId: String, userId: String): String? {
        return suspendTransaction(db) {
            val userFilter = UserScope.filterStrict(Tables.Session.userId, userId)
            Tables.Session
                .select(Tables.Session.status)
                .where { (Tables.Session.id eq sessionId) and userFilter }
                .limit(1)
                .firstOrNull()
                ?.getOrNull(Tables.Session.status)
        }
    }

    override suspend fun findSessionIdBySwarmTask(swarmRunId: String, swarmTaskId: String): String? {
        return suspendTransaction(db) {
            Tables.Session
                .select(Tables.Session.id)
                .where {
                    (Tables.Session.swarmRunId eq swarmRunId) and
                        (Tables.Session.swarmTaskId eq swarmTaskId)
                }
                .orderBy(Tables.Session.createdAt to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.get(Tables.Session.id)
        }
    }

    override suspend fun saveEndReason(sessionId: String, endReason: String, userId: String) {
        suspendTransaction(db) {
            val userFilter = UserScope.filterStrict(Tables.Session.userId, userId)
            Tables.Session.update(
                where = { (Tables.Session.id eq sessionId) and userFilter }
            ) {
                it[Tables.Session.endReason] = endReason
                it[Tables.Session.updatedAt] = System.currentTimeMillis()
            }
        }
        logger.debug("Saved end reason for session {}: {}", sessionId, endReason)
    }

    override suspend fun findEndReason(sessionId: String, userId: String): String? {
        return suspendTransaction(db) {
            val userFilter = UserScope.filterStrict(Tables.Session.userId, userId)
            Tables.Session
                .select(Tables.Session.endReason)
                .where { (Tables.Session.id eq sessionId) and userFilter }
                .limit(1)
                .firstOrNull()
                ?.getOrNull(Tables.Session.endReason)
        }
    }
}
