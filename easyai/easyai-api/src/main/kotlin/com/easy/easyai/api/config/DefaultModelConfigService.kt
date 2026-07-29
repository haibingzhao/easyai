package com.easy.easyai.api.config

import com.easy.easyai.api.model.ModelConfigGroup
import com.easy.easyai.api.model.ModelInfo
import com.easy.easyai.api.model.ModelOptions
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo
import com.easy.easyai.api.model.SaveModelConfigGroupRequest
import com.easy.easyai.api.model.SaveModelProviderConfigRequest
import java.util.UUID

/**
 * Default implementation of ModelConfigService backed by H2 database.
 * Combines pre-defined provider catalog (from model_provider_config table with isCustom=false)
 * with user-specific configurations (isCustom=true).
 * All methods are suspend functions - fully async, no runBlocking.
 */
class DefaultModelConfigService(
    private val configStore: ModelProviderConfigStore,
    private val groupStore: ModelConfigGroupStore? = null
) : ModelConfigService {

    override suspend fun getAvailableProviders(userId: String): List<ModelProviderInfo> {
        return configStore.getAllConfigs(userId)
            .filter { !it.isCustom && it.enabled }
            .map { toProviderInfo(it) }
    }

    override suspend fun getProviderById(id: String, userId: String): ModelProviderInfo? {
        return configStore.getConfig(id, userId)
            ?.takeIf { !it.isCustom && it.enabled }
            ?.let { toProviderInfo(it) }
    }

    override suspend fun getModelsForProvider(providerId: String, userId: String): List<ModelInfo> {
        return configStore.getConfig(providerId, userId)
            ?.takeIf { !it.isCustom && it.enabled }
            ?.let { listOf(ModelInfo(id = it.modelId, name = it.modelName ?: it.modelId)) }
            ?: emptyList()
    }

    override suspend fun getUserConfigurations(userId: String): List<ModelProviderConfig> {
        return configStore.getAllConfigs(userId)
            .filter { it.isCustom }
    }

    override suspend fun getUserConfiguration(id: String, userId: String): ModelProviderConfig? {
        return configStore.getConfig(id, userId)
            ?.takeIf { it.isCustom }
    }

    override suspend fun saveUserConfiguration(request: SaveModelProviderConfigRequest, userId: String): ModelProviderConfig {
        request.options?.let { validateOptions(it) }
        val id = request.id ?: UUID.randomUUID().toString()
        // When apiKey is null, preserve existing key or resolve from group
        // (frontend sends null for masked/unchanged keys to avoid overwriting with masked values)
        val effectiveApiKey = request.apiKey
            ?: configStore.getConfig(id, userId)?.apiKey
            ?: request.groupId?.let { groupStore?.getGroup(it, userId)?.apiKey }
        val config = ModelProviderConfig(
            id = id,
            name = request.name,
            protocol = request.protocol,
            isCustom = request.isCustom,
            baseUrl = request.baseUrl,
            apiKey = effectiveApiKey,
            modelId = request.modelId,
            modelName = request.modelName,
            isCustomModel = request.isCustomModel,
            enabled = request.enabled,
            options = request.options,
            timeoutSeconds = request.timeoutSeconds,
            capabilities = request.capabilities,
            groupId = request.groupId
        )
        configStore.saveConfig(config, userId)
        return config
    }

    override suspend fun deleteUserConfiguration(id: String, userId: String): Boolean {
        return configStore.getConfig(id, userId)
            ?.takeIf { it.isCustom }
            ?.let { configStore.deleteConfig(id, userId) }
            ?: false
    }

    // ─── Group operations ────────────────────────────────────────────────────────

    override suspend fun getGroups(userId: String): List<ModelConfigGroup> {
        return groupStore?.getAllGroups(userId) ?: emptyList()
    }

    override suspend fun saveGroup(request: SaveModelConfigGroupRequest, userId: String): ModelConfigGroup {
        val store = groupStore ?: throw UnsupportedOperationException("ModelConfigGroupStore not available")
        return store.saveGroup(request, userId)
    }

    override suspend fun updateGroup(id: String, request: SaveModelConfigGroupRequest, userId: String): ModelConfigGroup {
        val store = groupStore ?: throw UnsupportedOperationException("ModelConfigGroupStore not available")
        return store.updateGroupConnection(id, request, userId)
    }

    override suspend fun deleteGroup(id: String, userId: String): Boolean {
        val store = groupStore ?: throw UnsupportedOperationException("ModelConfigGroupStore not available")
        return store.deleteGroup(id, userId)
    }

    private fun toProviderInfo(config: ModelProviderConfig): ModelProviderInfo {
        return ModelProviderInfo(
            id = config.id,
            name = config.name,
            protocol = config.protocol,
            isCustom = config.isCustom,
            models = listOf(ModelInfo(id = config.modelId, name = config.modelName ?: config.modelId))
        )
    }

    private fun validateOptions(options: ModelOptions) {
        require(options.temperature in 0.0..2.0) {
            "temperature must be between 0.0 and 2.0, got: ${options.temperature}"
        }
        require(options.maxTokens > 0) {
            "maxTokens must be positive, got: ${options.maxTokens}"
        }
        require(options.maxContextTokens > 0) {
            "maxContextTokens must be positive, got: ${options.maxContextTokens}"
        }
        require(options.contextToken > 0) {
            "contextToken must be positive, got: ${options.contextToken}"
        }
        require(options.contextToken <= options.maxContextTokens) {
            "contextToken (${options.contextToken}) must not exceed maxContextTokens (${options.maxContextTokens})"
        }
    }
}