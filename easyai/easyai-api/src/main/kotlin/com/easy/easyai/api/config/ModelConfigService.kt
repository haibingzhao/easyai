package com.easy.easyai.api.config

import com.easy.easyai.api.model.ModelConfigGroup
import com.easy.easyai.api.model.ModelInfo
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo
import com.easy.easyai.api.model.SaveModelConfigGroupRequest
import com.easy.easyai.api.model.SaveModelProviderConfigRequest

/**
 * Service interface for managing model provider configurations.
 * Combines pre-defined provider loading and user configuration management.
 * All methods are suspend functions for fully async operation.
 */
interface ModelConfigService {
    /**
     * Get all available model providers from pre-defined configuration.
     */
    suspend fun getAvailableProviders(userId: String = "system"): List<ModelProviderInfo>

    /**
     * Get a specific provider by ID from pre-defined configuration.
     */
    suspend fun getProviderById(id: String, userId: String = "system"): ModelProviderInfo?

    /**
     * Get models for a specific provider from pre-defined configuration.
     */
    suspend fun getModelsForProvider(providerId: String, userId: String = "system"): List<ModelInfo>

    /**
     * Get user's saved provider configurations.
     */
    suspend fun getUserConfigurations(userId: String = "system"): List<ModelProviderConfig>

    /**
     * Get a user's specific provider configuration by ID.
     */
    suspend fun getUserConfiguration(id: String, userId: String = "system"): ModelProviderConfig?

    /**
     * Save a user's provider configuration.
     */
    suspend fun saveUserConfiguration(request: SaveModelProviderConfigRequest, userId: String = "system"): ModelProviderConfig

    /**
     * Delete a user's provider configuration by ID.
     * @return true if the configuration was found and deleted
     */
    suspend fun deleteUserConfiguration(id: String, userId: String = "system"): Boolean

    // ─── Group operations ────────────────────────────────────────────────────────

    /**
     * Get all model config groups with their member models.
     */
    suspend fun getGroups(userId: String = "system"): List<ModelConfigGroup>

    /**
     * Create a new model config group.
     */
    suspend fun saveGroup(request: SaveModelConfigGroupRequest, userId: String = "system"): ModelConfigGroup

    /**
     * Update a group's connection settings, cascading to all member configs.
     */
    suspend fun updateGroup(id: String, request: SaveModelConfigGroupRequest, userId: String = "system"): ModelConfigGroup

    /**
     * Delete a group and all its member model configs.
     */
    suspend fun deleteGroup(id: String, userId: String = "system"): Boolean
}