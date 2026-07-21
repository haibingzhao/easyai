package com.easy.easyai.api.config

import com.easy.easyai.api.model.ModelProviderConfig

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
     * Save a configuration.
     */
    suspend fun saveConfig(config: ModelProviderConfig, userId: String = "system")

    /**
     * Delete a configuration by ID.
     * @return true if the configuration was found and deleted
     */
    suspend fun deleteConfig(id: String, userId: String = "system"): Boolean

    /**
     * Get all configurations.
     */
    suspend fun getAllConfigs(userId: String = "system"): List<ModelProviderConfig>
}