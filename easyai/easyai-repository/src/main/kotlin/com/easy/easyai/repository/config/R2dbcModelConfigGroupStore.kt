package com.easy.easyai.repository.config

import com.easy.easyai.api.config.ModelConfigGroupStore
import com.easy.easyai.api.model.ModelCapabilities
import com.easy.easyai.api.model.ModelConfigGroup
import com.easy.easyai.api.model.ModelOptions
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.SaveModelConfigGroupRequest
import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.repository.database.Tables
import com.easy.easyai.repository.database.UserScope
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * R2DBC implementation of [ModelConfigGroupStore].
 * Uses Exposed R2DBC for pure async database operations.
 */
class R2dbcModelConfigGroupStore(
    private val db: R2dbcDatabase,
    private val objectMapper: ObjectMapper = SharedObjectMapper.instance
) : ModelConfigGroupStore {
    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun getGroup(id: String, userId: String): ModelConfigGroup? {
        return suspendTransaction(db) {
            val groupRow = Tables.ModelConfigGroupTable
                .selectAll()
                .where { (Tables.ModelConfigGroupTable.id eq id) and UserScope.filter(Tables.ModelConfigGroupTable.userId, userId) }
                .toList()
                .firstOrNull() ?: return@suspendTransaction null

            val memberRows = Tables.ModelProviderConfigTable
                .selectAll()
                .where { (Tables.ModelProviderConfigTable.groupId eq id) and UserScope.filter(Tables.ModelProviderConfigTable.userId, userId) }
                .toList()

            toGroup(groupRow, memberRows)
        }
    }

    override suspend fun getAllGroups(userId: String): List<ModelConfigGroup> {
        return suspendTransaction(db) {
            val groupRows = Tables.ModelConfigGroupTable
                .selectAll()
                .where(UserScope.filter(Tables.ModelConfigGroupTable.userId, userId))
                .toList()

            if (groupRows.isEmpty()) return@suspendTransaction emptyList()

            val groupIds = groupRows.map { it[Tables.ModelConfigGroupTable.id] }
            val memberRows = Tables.ModelProviderConfigTable
                .selectAll()
                .where {
                    (Tables.ModelProviderConfigTable.groupId inList groupIds) and
                        UserScope.filter(Tables.ModelProviderConfigTable.userId, userId)
                }
                .toList()

            val membersByGroup = memberRows.groupBy { it[Tables.ModelProviderConfigTable.groupId] }

            groupRows.map { row ->
                val gid = row[Tables.ModelConfigGroupTable.id]
                toGroup(row, membersByGroup[gid] ?: emptyList())
            }
        }
    }

    override suspend fun saveGroup(request: SaveModelConfigGroupRequest, userId: String): ModelConfigGroup {
        val id = request.id ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        suspendTransaction(db) {
            val existing = Tables.ModelConfigGroupTable
                .selectAll()
                .where { (Tables.ModelConfigGroupTable.id eq id) and UserScope.filterStrict(Tables.ModelConfigGroupTable.userId, userId) }
                .toList()

            if (existing.isNotEmpty()) {
                Tables.ModelConfigGroupTable.update(
                    where = { (Tables.ModelConfigGroupTable.id eq id) and UserScope.filterStrict(Tables.ModelConfigGroupTable.userId, userId) }
                ) {
                    it[name] = request.name
                    it[protocol] = request.protocol.name
                    it[isCustom] = request.isCustom
                    it[baseUrl] = request.baseUrl
                    it[apiKey] = request.apiKey
                    it[timeoutSeconds] = request.timeoutSeconds
                    it[updatedAt] = now
                }
                logger.info("Updated model config group: {}", id)
            } else {
                Tables.ModelConfigGroupTable.insert {
                    it[Tables.ModelConfigGroupTable.id] = id
                    it[name] = request.name
                    it[protocol] = request.protocol.name
                    it[isCustom] = request.isCustom
                    it[baseUrl] = request.baseUrl
                    it[apiKey] = request.apiKey
                    it[timeoutSeconds] = request.timeoutSeconds
                    it[Tables.ModelConfigGroupTable.userId] = userId
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                logger.info("Inserted model config group: {}", id)
            }
        }

        return getGroup(id, userId) ?: throw IllegalStateException("Failed to load saved group: $id")
    }

    override suspend fun deleteGroup(id: String, userId: String): Boolean {
        return suspendTransaction(db) {
            val existing = Tables.ModelConfigGroupTable
                .selectAll()
                .where { (Tables.ModelConfigGroupTable.id eq id) and UserScope.filterStrict(Tables.ModelConfigGroupTable.userId, userId) }
                .toList()

            if (existing.isEmpty()) return@suspendTransaction false

            // Cascade delete member configs
            Tables.ModelProviderConfigTable.deleteWhere {
                (Tables.ModelProviderConfigTable.groupId eq id) and UserScope.filterStrict(Tables.ModelProviderConfigTable.userId, userId)
            }

            // Delete the group itself
            Tables.ModelConfigGroupTable.deleteWhere {
                (Tables.ModelConfigGroupTable.id eq id) and UserScope.filterStrict(Tables.ModelConfigGroupTable.userId, userId)
            }

            logger.info("Deleted model config group and its members: {}", id)
            true
        }
    }

    override suspend fun updateGroupConnection(id: String, request: SaveModelConfigGroupRequest, userId: String): ModelConfigGroup {
        val now = System.currentTimeMillis()

        // Resolve effective apiKey: null means "keep existing"
        val existingGroup = getGroup(id, userId)
        val effectiveApiKey = request.apiKey ?: existingGroup?.apiKey

        suspendTransaction(db) {
            // Update the group row
            Tables.ModelConfigGroupTable.update(
                where = { (Tables.ModelConfigGroupTable.id eq id) and UserScope.filterStrict(Tables.ModelConfigGroupTable.userId, userId) }
            ) {
                it[name] = request.name
                it[protocol] = request.protocol.name
                it[isCustom] = request.isCustom
                it[baseUrl] = request.baseUrl
                it[apiKey] = effectiveApiKey
                it[timeoutSeconds] = request.timeoutSeconds
                it[updatedAt] = now
            }

            // Cascade update denormalized connection fields on all member configs
            Tables.ModelProviderConfigTable.update(
                where = { (Tables.ModelProviderConfigTable.groupId eq id) and UserScope.filterStrict(Tables.ModelProviderConfigTable.userId, userId) }
            ) {
                it[protocol] = request.protocol.name
                it[isCustom] = request.isCustom
                it[baseUrl] = request.baseUrl
                it[apiKey] = effectiveApiKey
                it[timeoutSeconds] = request.timeoutSeconds
                it[updatedAt] = now
            }

            logger.info("Updated group connection and cascaded to members: {}", id)
        }

        return getGroup(id, userId) ?: throw IllegalStateException("Failed to load updated group: $id")
    }

    private fun toGroup(groupRow: ResultRow, memberRows: List<ResultRow>): ModelConfigGroup {
        return ModelConfigGroup(
            id = groupRow[Tables.ModelConfigGroupTable.id],
            name = groupRow[Tables.ModelConfigGroupTable.name],
            protocol = Protocol.valueOf(groupRow[Tables.ModelConfigGroupTable.protocol]),
            isCustom = groupRow[Tables.ModelConfigGroupTable.isCustom],
            baseUrl = groupRow[Tables.ModelConfigGroupTable.baseUrl],
            apiKey = groupRow[Tables.ModelConfigGroupTable.apiKey],
            timeoutSeconds = groupRow[Tables.ModelConfigGroupTable.timeoutSeconds],
            models = memberRows.map { mapToModelProviderConfig(it, objectMapper) }
        )
    }
}
