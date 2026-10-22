package com.easy.easyai.repository.agent

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.ChatPreference
import com.easy.easyai.core.agent.ChatPreferenceStore
import com.easy.easyai.repository.database.Tables.ChatPreferenceTable
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.upsert
import org.slf4j.LoggerFactory
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

/**
 * R2DBC implementation of [ChatPreferenceStore] — one row per owner bucket, upserted by `user_id`.
 * Mirrors [com.easy.easyai.repository.model.R2dbcAuxModelSettingsStore] for the user-scoped key and
 * [com.easy.easyai.repository.task.R2dbcScheduledTaskSettingStore] for the JSON column handling.
 */
class R2dbcChatPreferenceStore(
    private val db: R2dbcDatabase,
    private val objectMapper: ObjectMapper = SharedObjectMapper.instance
) : ChatPreferenceStore {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun get(userId: String): ChatPreference? = suspendTransaction(db) {
        val table = ChatPreferenceTable
        table.selectAll()
            .where { table.userId eq userId }
            .limit(1)
            .map { it.toPreference() }
            .firstOrNull()
    }

    override suspend fun save(preference: ChatPreference): Unit = suspendTransaction(db) {
        val table = ChatPreferenceTable
        val now = System.currentTimeMillis()
        table.upsert(onUpdateExclude = listOf(table.createdAt)) {
            it[userId] = preference.userId
            it[defaultAgentId] = preference.defaultAgentId
            it[hiddenAgentIds] = objectMapper.writeValueAsString(preference.hiddenAgentIds)
            it[createdAt] = now
            it[updatedAt] = now
        }
        logger.debug(
            "Saved chat preference for owner '{}' (defaultAgent={}, hidden={})",
            preference.userId, preference.defaultAgentId, preference.hiddenAgentIds.size
        )
    }

    override suspend fun delete(userId: String): Boolean = suspendTransaction(db) {
        val table = ChatPreferenceTable
        table.deleteWhere { table.userId eq userId } > 0
    }

    private fun ResultRow.toPreference(): ChatPreference {
        val table = ChatPreferenceTable
        val owner = this[table.userId]
        val hidden = this[table.hiddenAgentIds].let { json ->
            runCatching { objectMapper.readValue(json, object : TypeReference<List<String>>() {}) }
                .onFailure {
                    logger.warn("Unparsable hidden agent ids for chat preference owner '{}': {}", owner, it.message)
                }
                .getOrDefault(emptyList())
        }
        return ChatPreference(
            userId = owner,
            defaultAgentId = this[table.defaultAgentId],
            hiddenAgentIds = hidden,
            updatedAt = this[table.updatedAt]
        )
    }
}
