package com.easy.easyai.repository.config

import com.easy.easyai.api.config.ModelCatalogStore
import com.easy.easyai.api.model.CatalogCapabilities
import com.easy.easyai.api.model.CatalogOptions
import com.easy.easyai.api.model.ModelCatalogEntry
import com.easy.easyai.api.model.ModelPricing
import com.easy.easyai.api.model.ReasoningProfile
import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.repository.database.Tables
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import tools.jackson.databind.ObjectMapper

/**
 * R2DBC implementation of [ModelCatalogStore] over the shared `model_catalog` table.
 * The catalog is global (no user partition), so no [com.easy.easyai.repository.database.UserScope]
 * filtering applies. All operations are async via `suspendTransaction`.
 */
class R2dbcModelCatalogStore(
    private val db: R2dbcDatabase,
    private val objectMapper: ObjectMapper = SharedObjectMapper.instance
) : ModelCatalogStore {

    override suspend fun getByModelId(modelId: String): ModelCatalogEntry? {
        return suspendTransaction(db) {
            Tables.ModelCatalogTable
                .selectAll()
                .where { Tables.ModelCatalogTable.modelId eq modelId }
                .limit(1)
                .firstOrNull()
                ?.let { mapRow(it) }
        }
    }

    override suspend fun listAll(): List<ModelCatalogEntry> {
        return suspendTransaction(db) {
            Tables.ModelCatalogTable
                .selectAll()
                .orderBy(Tables.ModelCatalogTable.modelId)
                .map { mapRow(it) }
                .toList()
        }
    }

    override suspend fun upsert(entry: ModelCatalogEntry): Boolean {
        return suspendTransaction(db) {
            val now = System.currentTimeMillis()
            val existing = Tables.ModelCatalogTable
                .select(Tables.ModelCatalogTable.modelId, Tables.ModelCatalogTable.reasoningProfile)
                .where { Tables.ModelCatalogTable.modelId eq entry.modelId }
                .limit(1)
                .firstOrNull()
            // A hand-declared matrix outranks the vendor-doc resolver: refreshes must not undo it.
            val preservedProfile = existing?.get(Tables.ModelCatalogTable.reasoningProfile)
                ?.takeIf { parseProfile(it)?.source == ReasoningProfile.SOURCE_MANUAL }
            val profileJson = preservedProfile
                ?: entry.reasoningProfile?.let { objectMapper.writeValueAsString(it) }
            if (existing != null) {
                Tables.ModelCatalogTable.update(
                    where = { Tables.ModelCatalogTable.modelId eq entry.modelId }
                ) {
                    it[vendor] = entry.vendor
                    it[displayName] = entry.displayName
                    it[description] = entry.description
                    it[capabilities] = entry.capabilities?.let { c -> objectMapper.writeValueAsString(c) }
                    it[defaultOptions] = entry.defaultOptions?.let { o -> objectMapper.writeValueAsString(o) }
                    it[reasoningProfile] = profileJson
                    it[pricing] = entry.pricing?.let { p -> objectMapper.writeValueAsString(p) }
                    it[catalogSource] = entry.source
                    it[rawPayload] = entry.rawPayload
                    it[updatedAt] = now
                }
                false
            } else {
                Tables.ModelCatalogTable.insert {
                    it[modelId] = entry.modelId
                    it[vendor] = entry.vendor
                    it[displayName] = entry.displayName
                    it[description] = entry.description
                    it[capabilities] = entry.capabilities?.let { c -> objectMapper.writeValueAsString(c) }
                    it[defaultOptions] = entry.defaultOptions?.let { o -> objectMapper.writeValueAsString(o) }
                    it[reasoningProfile] = profileJson
                    it[pricing] = entry.pricing?.let { p -> objectMapper.writeValueAsString(p) }
                    it[catalogSource] = entry.source
                    it[rawPayload] = entry.rawPayload
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                true
            }
        }
    }

    private fun parseProfile(json: String): ReasoningProfile? =
        runCatching { objectMapper.readValue(json, ReasoningProfile::class.java) }.getOrNull()

    private fun mapRow(row: ResultRow): ModelCatalogEntry {
        return ModelCatalogEntry(
            modelId = row[Tables.ModelCatalogTable.modelId],
            vendor = row[Tables.ModelCatalogTable.vendor],
            displayName = row[Tables.ModelCatalogTable.displayName],
            description = row[Tables.ModelCatalogTable.description],
            capabilities = row[Tables.ModelCatalogTable.capabilities]?.let {
                objectMapper.readValue(it, CatalogCapabilities::class.java)
            },
            defaultOptions = row[Tables.ModelCatalogTable.defaultOptions]?.let {
                objectMapper.readValue(it, CatalogOptions::class.java)
            },
            reasoningProfile = row[Tables.ModelCatalogTable.reasoningProfile]?.let {
                objectMapper.readValue(it, ReasoningProfile::class.java)
            },
            pricing = row[Tables.ModelCatalogTable.pricing]?.let {
                objectMapper.readValue(it, ModelPricing::class.java)
            },
            source = row[Tables.ModelCatalogTable.catalogSource],
            rawPayload = row[Tables.ModelCatalogTable.rawPayload],
            createdAt = row[Tables.ModelCatalogTable.createdAt],
            updatedAt = row[Tables.ModelCatalogTable.updatedAt]
        )
    }
}
