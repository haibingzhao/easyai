package com.easy.easyai.api.config

import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelType

/**
 * Interface for managing model provider configurations.
 * Implementations can store configurations in R2DBC, etc.
 */
interface ModelProviderConfigStore {
    /**
     * Get a configuration by ID.
     */
    suspend fun getConfig(id: String, userId: String = "system"): ModelProviderConfig?

    /**
     * Save a configuration. When [ModelProviderConfig.isDefault] is true, the store clears the
     * flag on the owner's other rows of the same [ModelType].
     */
    suspend fun saveConfig(config: ModelProviderConfig, userId: String = "system")

    /**
     * Delete a configuration by ID.
     * @return true if the configuration was found and deleted
     */
    suspend fun deleteConfig(id: String, userId: String = "system"): Boolean

    /**
     * Get all configurations of one model type (user rows plus shared system rows).
     */
    suspend fun getAllConfigs(userId: String = "system"): List<ModelProviderConfig> =
        getModelConfigs(ModelType.CHAT, userId)

    /**
     * Get all configurations of [modelType] (user rows plus shared system rows).
     * Generation consumers pass the tool's type; CHAT keeps every model picker clean of
     * generation rows by default.
     */
    suspend fun getModelConfigs(modelType: ModelType, userId: String = "system"): List<ModelProviderConfig>

    // ─── Group-aware reads ───────────────────────────────────────────────────────
    // A request sees its own rows, its group bucket's rows, and the shared system rows. These
    // overloads default to a fan-out over the single-id forms so alternate/test implementations keep
    // working; the R2dbc store overrides them with one ordered `IN (owners)` query. Callers pass the
    // full visibility set (`SecurityUtils.currentOwners()`), which already includes system.

    /** Get a configuration by ID visible to any of [owners]. */
    suspend fun getConfig(id: String, owners: Collection<String>): ModelProviderConfig? =
        owners.distinct().mapNotNull { getConfig(id, it) }.firstOrNull()

    /** Get all CHAT configurations visible to any of [owners]. */
    suspend fun getAllConfigs(owners: Collection<String>): List<ModelProviderConfig> =
        getModelConfigs(ModelType.CHAT, owners)

    /** Get all configurations of [modelType] visible to any of [owners]. */
    suspend fun getModelConfigs(modelType: ModelType, owners: Collection<String>): List<ModelProviderConfig> =
        owners.distinct().flatMap { getModelConfigs(modelType, it) }.distinctBy { it.id }
}