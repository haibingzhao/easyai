package com.easy.easyai.repository.config

import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelType
import com.easy.easyai.repository.database.Tables
import com.easy.easyai.repository.database.UserScope
import tools.jackson.databind.ObjectMapper
import com.easy.easyai.common.util.SharedObjectMapper
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory

/**
 * R2DBC-compatible implementation of ModelProviderConfigStore.
 * Uses Exposed R2DBC for pure async database operations.
 * JDBC is strictly forbidden - all operations use suspendTransaction.
 */
class R2dbcModelConfigStore(
    private val db: R2dbcDatabase,
    private val objectMapper: ObjectMapper = SharedObjectMapper.instance
) : ModelProviderConfigStore {
    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun getConfig(id: String, userId: String): ModelProviderConfig? {
        return suspendTransaction(db) {
            val query = Tables.ModelProviderConfigTable
                .selectAll()
                .where { (Tables.ModelProviderConfigTable.id eq id) and UserScope.filter(Tables.ModelProviderConfigTable.userId, userId) }
                .limit(1)
            query.firstOrNull()?.let { row ->
                mapToModelProviderConfig(row, objectMapper)
            }
        }
    }

    override suspend fun saveConfig(config: ModelProviderConfig, userId: String) {
        suspendTransaction(db) {
            // `id` is this table's whole primary key, so it is globally unique across owner buckets.
            // The probe therefore has to ask *who owns it*, not merely whether it exists: an id held by
            // another bucket can neither be updated here (the scoped UPDATE matches nothing) nor
            // inserted (the PK rejects it). Probing existence alone — the previous shape — took the
            // update branch, matched zero rows, and reported success while persisting nothing.
            val existingOwner = Tables.ModelProviderConfigTable
                .select(Tables.ModelProviderConfigTable.userId)
                .where { Tables.ModelProviderConfigTable.id eq config.id }
                .limit(1)
                .firstOrNull()
                ?.get(Tables.ModelProviderConfigTable.userId)
            require(existingOwner == null || existingOwner == userId) {
                "Model config '${config.id}' belongs to another owner (a shared group or system " +
                    "config); it cannot be saved into '$userId'. Pick a different id, or save it " +
                    "with the scope that owns it."
            }

            val now = System.currentTimeMillis()
            val optionsJson = config.options?.let { objectMapper.writeValueAsString(it) }
            val capabilitiesJson = config.capabilities?.let { objectMapper.writeValueAsString(it) }

            if (existingOwner != null) {
                Tables.ModelProviderConfigTable.update(
                    where = { (Tables.ModelProviderConfigTable.id eq config.id) and UserScope.filterStrict(Tables.ModelProviderConfigTable.userId, userId) }
                ) {
                    it[name] = config.name
                    it[protocol] = config.protocol.name
                    it[isCustom] = config.isCustom
                    it[baseUrl] = config.baseUrl
                    it[apiKey] = config.apiKey
                    it[modelId] = config.modelId
                    it[modelName] = config.modelName
                    it[isCustomModel] = config.isCustomModel
                    it[enabled] = config.enabled
                    it[options] = optionsJson
                    it[capabilities] = capabilitiesJson
                    it[timeoutSeconds] = config.timeoutSeconds
                    it[groupId] = config.groupId
                    it[modelType] = config.modelType.name
                    it[mediaOptions] = config.mediaOptions
                    it[isDefault] = config.isDefault
                    it[updatedAt] = now
                }
                logger.info("Updated model config: {}", config.id)
            } else {
                Tables.ModelProviderConfigTable.insert {
                    it[id] = config.id
                    it[name] = config.name
                    it[protocol] = config.protocol.name
                    it[isCustom] = config.isCustom
                    it[baseUrl] = config.baseUrl
                    it[apiKey] = config.apiKey
                    it[modelId] = config.modelId
                    it[modelName] = config.modelName
                    it[isCustomModel] = config.isCustomModel
                    it[enabled] = config.enabled
                    it[options] = optionsJson
                    it[capabilities] = capabilitiesJson
                    it[timeoutSeconds] = config.timeoutSeconds
                    it[groupId] = config.groupId
                    it[modelType] = config.modelType.name
                    it[mediaOptions] = config.mediaOptions
                    it[isDefault] = config.isDefault
                    it[Tables.ModelProviderConfigTable.userId] = userId
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                logger.info("Inserted model config: {}", config.id)
            }

            // One default per owner+modelType: clearing siblings stays inside this transaction.
            if (config.isDefault) {
                Tables.ModelProviderConfigTable.update(
                    where = {
                        (Tables.ModelProviderConfigTable.modelType eq config.modelType.name) and
                            (Tables.ModelProviderConfigTable.id neq config.id) and
                            UserScope.filterStrict(Tables.ModelProviderConfigTable.userId, userId)
                    }
                ) {
                    it[isDefault] = false
                }
            }
        }
    }

    override suspend fun deleteConfig(id: String, userId: String): Boolean {
        return suspendTransaction(db) {
            val existingCount = Tables.ModelProviderConfigTable
                .selectAll()
                .where { (Tables.ModelProviderConfigTable.id eq id) and UserScope.filterStrict(Tables.ModelProviderConfigTable.userId, userId) }
                .count()

            if (existingCount > 0) {
                Tables.ModelProviderConfigTable.deleteWhere {
                    (this.id eq id) and UserScope.filterStrict(Tables.ModelProviderConfigTable.userId, userId)
                }
                logger.info("Deleted model config: {}", id)
                true
            } else {
                false
            }
        }
    }

    override suspend fun getAllConfigs(userId: String): List<ModelProviderConfig> =
        getModelConfigs(ModelType.CHAT, userId)

    override suspend fun getModelConfigs(modelType: ModelType, userId: String): List<ModelProviderConfig> {
        return suspendTransaction(db) {
            Tables.ModelProviderConfigTable
                .selectAll()
                .where {
                    UserScope.filter(Tables.ModelProviderConfigTable.userId, userId) and
                        (Tables.ModelProviderConfigTable.modelType eq modelType.name)
                }
                // Deterministic fallback for "no explicit default" resolution: default first, then oldest.
                .orderBy(
                    Tables.ModelProviderConfigTable.isDefault to SortOrder.DESC,
                    Tables.ModelProviderConfigTable.createdAt to SortOrder.ASC,
                )
                .map { row -> mapToModelProviderConfig(row, objectMapper) }
                .toList()
        }
    }

    override suspend fun getConfig(id: String, owners: Collection<String>): ModelProviderConfig? {
        val ordered = owners.filter { it.isNotBlank() }.distinct()
        if (ordered.isEmpty()) return null
        return suspendTransaction(db) {
            // `id` is this table's whole primary key, so the owner set only decides visibility — at
            // most one row can ever match.
            Tables.ModelProviderConfigTable
                .selectAll()
                .where {
                    (Tables.ModelProviderConfigTable.id eq id) and
                        UserScope.filterOwners(Tables.ModelProviderConfigTable.userId, ordered)
                }
                .limit(1)
                .firstOrNull()?.let { row -> mapToModelProviderConfig(row, objectMapper) }
        }
    }

    override suspend fun getModelConfigs(modelType: ModelType, owners: Collection<String>): List<ModelProviderConfig> {
        val ordered = owners.filter { it.isNotBlank() }.distinct()
        if (ordered.isEmpty()) return emptyList()
        return suspendTransaction(db) {
            Tables.ModelProviderConfigTable
                .selectAll()
                .where {
                    UserScope.filterOwners(Tables.ModelProviderConfigTable.userId, ordered) and
                        (Tables.ModelProviderConfigTable.modelType eq modelType.name)
                }
                .orderBy(
                    Tables.ModelProviderConfigTable.isDefault to SortOrder.DESC,
                    Tables.ModelProviderConfigTable.createdAt to SortOrder.ASC,
                )
                .map { row -> mapToModelProviderConfig(row, objectMapper) }
                .toList()
        }
    }
}
