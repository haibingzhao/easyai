package com.easy.easyai.repository.config

import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.model.ModelCapabilities
import com.easy.easyai.api.model.ModelOptions
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
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
                this@R2dbcModelConfigStore.toConfig(row)
            }
        }
    }

    override suspend fun saveConfig(config: ModelProviderConfig, userId: String) {
        suspendTransaction(db) {
            val existingCount = Tables.ModelProviderConfigTable
                .selectAll()
                .where { Tables.ModelProviderConfigTable.id eq config.id }
                .count()

            val now = System.currentTimeMillis()
            val optionsJson = config.options?.let { objectMapper.writeValueAsString(it) }
            val capabilitiesJson = config.capabilities?.let { objectMapper.writeValueAsString(it) }

            if (existingCount > 0) {
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
                    it[Tables.ModelProviderConfigTable.userId] = userId
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                logger.info("Inserted model config: {}", config.id)
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

    override suspend fun getAllConfigs(userId: String): List<ModelProviderConfig> {
        return suspendTransaction(db) {
            Tables.ModelProviderConfigTable
                .selectAll()
                .where(UserScope.filter(Tables.ModelProviderConfigTable.userId, userId))
                .map { row -> this@R2dbcModelConfigStore.toConfig(row) }
                .toList()
        }
    }

    private fun toConfig(row: ResultRow): ModelProviderConfig {
        val optionsJson = row[Tables.ModelProviderConfigTable.options]
        val options: ModelOptions? = optionsJson?.takeIf { it.isNotBlank() }
            ?.let { objectMapper.readValue(it, ModelOptions::class.java) }

        val capabilitiesJson = row[Tables.ModelProviderConfigTable.capabilities]
        val capabilities: ModelCapabilities? = capabilitiesJson?.takeIf { it.isNotBlank() }
            ?.let { objectMapper.readValue(it, ModelCapabilities::class.java) }

        return ModelProviderConfig(
            id = row[Tables.ModelProviderConfigTable.id],
            name = row[Tables.ModelProviderConfigTable.name],
            protocol = Protocol.valueOf(row[Tables.ModelProviderConfigTable.protocol]),
            isCustom = row[Tables.ModelProviderConfigTable.isCustom],
            baseUrl = row[Tables.ModelProviderConfigTable.baseUrl],
            apiKey = row[Tables.ModelProviderConfigTable.apiKey],
            modelId = row[Tables.ModelProviderConfigTable.modelId],
            modelName = row[Tables.ModelProviderConfigTable.modelName],
            isCustomModel = row[Tables.ModelProviderConfigTable.isCustomModel],
            enabled = row[Tables.ModelProviderConfigTable.enabled],
            options = options,
            timeoutSeconds = row[Tables.ModelProviderConfigTable.timeoutSeconds],
            capabilities = capabilities,
            groupId = row[Tables.ModelProviderConfigTable.groupId]
        )
    }
}