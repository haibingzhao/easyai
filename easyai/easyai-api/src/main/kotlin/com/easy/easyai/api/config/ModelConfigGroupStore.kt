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

    // ─── Group-aware reads ───────────────────────────────────────────────────────
    // Visible if the group's owner is any of [owners] (self, group bucket, system). Defaults fan out
    // over the single-id forms; the R2dbc store overrides with one `IN (owners)` query.

    /** Get a group (with members) visible to any of [owners]. */
    suspend fun getGroup(id: String, owners: Collection<String>): ModelConfigGroup? =
        owners.distinct().mapNotNull { getGroup(id, it) }.firstOrNull()

    /** Get all groups visible to any of [owners]. */
    suspend fun getAllGroups(owners: Collection<String>): List<ModelConfigGroup> =
        owners.distinct().flatMap { getAllGroups(it) }.distinctBy { it.id }
}
