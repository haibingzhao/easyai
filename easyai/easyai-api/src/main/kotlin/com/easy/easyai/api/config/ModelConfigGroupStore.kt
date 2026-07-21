package com.easy.easyai.api.config

import com.easy.easyai.api.model.ModelConfigGroup
import com.easy.easyai.api.model.SaveModelConfigGroupRequest

/**
 * Interface for managing model config groups.
 * A group holds shared connection settings (protocol, baseUrl, apiKey) for a set of model configs.
 */
interface ModelConfigGroupStore {

    /**
     * Get a group by ID, including its member model configs.
     */
    suspend fun getGroup(id: String, userId: String = "system"): ModelConfigGroup?

    /**
     * Get all groups for a user, each including its member model configs.
     */
    suspend fun getAllGroups(userId: String = "system"): List<ModelConfigGroup>

    /**
     * Create or update a group (without modifying members).
     */
    suspend fun saveGroup(request: SaveModelConfigGroupRequest, userId: String = "system"): ModelConfigGroup

    /**
     * Delete a group and all its member model configs (cascade).
     * @return true if the group was found and deleted
     */
    suspend fun deleteGroup(id: String, userId: String = "system"): Boolean

    /**
     * Update a group's connection settings and cascade-update all member configs'
     * denormalized connection fields (protocol, baseUrl, apiKey, timeoutSeconds, isCustom).
     */
    suspend fun updateGroupConnection(id: String, request: SaveModelConfigGroupRequest, userId: String = "system"): ModelConfigGroup
}
