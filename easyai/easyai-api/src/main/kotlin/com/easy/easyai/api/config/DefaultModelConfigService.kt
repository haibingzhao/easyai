package com.easy.easyai.api.config

import com.easy.easyai.api.model.ModelConfigGroup
import com.easy.easyai.api.model.ModelCapabilities
import com.easy.easyai.api.model.ModelInfo
import com.easy.easyai.api.model.ModelOptions
import com.easy.easyai.api.model.ModelOverridePolicy
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.ModelType
import com.easy.easyai.api.model.ReasoningMode
import com.easy.easyai.api.model.ReasoningProfile
import com.easy.easyai.api.model.ReasoningProfiles
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

    override suspend fun getUserConfigurations(modelType: ModelType, userId: String): List<ModelProviderConfig> {
        return configStore.getModelConfigs(modelType, userId)
            .filter { it.isCustom }
    }

    override suspend fun getUserConfiguration(id: String, userId: String): ModelProviderConfig? {
        return configStore.getConfig(id, userId)
            ?.takeIf { it.isCustom }
    }

    override suspend fun getUserConfigurations(modelType: ModelType, owners: Collection<String>): List<ModelProviderConfig> {
        return configStore.getModelConfigs(modelType, owners)
            .filter { it.isCustom }
    }

    override suspend fun getUserConfiguration(id: String, owners: Collection<String>): ModelProviderConfig? {
        return configStore.getConfig(id, owners)
            ?.takeIf { it.isCustom }
    }

    override suspend fun saveUserConfiguration(
        request: SaveModelProviderConfigRequest,
        userId: String,
        owners: Collection<String>
    ): ModelProviderConfig {
        request.capabilities?.reasoning?.let { validateReasoningProfile(it) }
        // Canonicalize before anything compares effort names: a mixed-case declaration would
        // otherwise validate cleanly and then reject every effort (save and per-request alike).
        val normalizedCapabilities = request.capabilities?.let { capabilities ->
            capabilities.reasoning?.let { capabilities.copy(reasoning = canonicalizeReasoningProfile(it)) }
                ?: capabilities
        }
        val normalizedOptions = request.options?.let { options ->
            normalizeReasoningOptions(options, normalizedCapabilities, request.protocol).also {
                validateOptions(it, request.protocol)
            }
        }
        validateGenerationRow(request)
        val id = request.id ?: UUID.randomUUID().toString()
        // When apiKey is null, preserve existing key or resolve from group
        // (frontend sends null for masked/unchanged keys to avoid overwriting with masked values).
        // The two reads differ in scope on purpose: the row being upserted lives in the write owner's
        // bucket alone, while the referenced group is any group the caller can see — a member joining
        // a group-owned config group must resolve its key, or the save fails on a group they can read.
        val effectiveApiKey = request.apiKey
            ?: configStore.getConfig(id, listOf(userId))?.apiKey
            ?: request.groupId?.let { groupStore?.getGroup(it, owners)?.apiKey }
        if (request.modelType != ModelType.CHAT && effectiveApiKey.isNullOrBlank()) {
            throw IllegalArgumentException(
                "a ${request.modelType.name.lowercase()} model needs an api key on itself or its group"
            )
        }
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
            options = normalizedOptions,
            timeoutSeconds = request.timeoutSeconds,
            capabilities = normalizedCapabilities,
            groupId = request.groupId,
            modelType = request.modelType,
            mediaOptions = request.mediaOptions?.trim()?.takeIf { it.isNotEmpty() },
            isDefault = request.isDefault
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

    override suspend fun testGenerationConfiguration(
        request: SaveModelProviderConfigRequest,
        userId: String,
        owners: Collection<String>
    ): String? {
        if (request.modelType == ModelType.CHAT) return "the test endpoint only covers generation models"
        return try {
            validateGenerationRow(request)
            // Same scoping as saveUserConfiguration, so a draft that tests clean also saves.
            val effectiveApiKey = request.apiKey
                ?: request.id?.let { configStore.getConfig(it, listOf(userId))?.apiKey }
                ?: request.groupId?.let { groupStore?.getGroup(it, owners)?.apiKey }
            if (effectiveApiKey.isNullOrBlank()) {
                "a ${request.modelType.name.lowercase()} model needs an api key on itself or its group"
            } else {
                null
            }
        } catch (e: IllegalArgumentException) {
            e.message
        }
    }

    // ─── Group operations ────────────────────────────────────────────────────────

    override suspend fun getGroups(userId: String): List<ModelConfigGroup> {
        return groupStore?.getAllGroups(userId) ?: emptyList()
    }

    override suspend fun getGroups(owners: Collection<String>): List<ModelConfigGroup> {
        return groupStore?.getAllGroups(owners) ?: emptyList()
    }

    override suspend fun saveGroup(request: SaveModelConfigGroupRequest, userId: String): ModelConfigGroup {
        val store = groupStore ?: throw UnsupportedOperationException("ModelConfigGroupStore not available")
        requireGroupNameAvailable(request, userId, excludeGroupId = request.id)
        return store.saveGroup(request, userId)
    }

    override suspend fun updateGroup(id: String, request: SaveModelConfigGroupRequest, userId: String): ModelConfigGroup {
        val store = groupStore ?: throw UnsupportedOperationException("ModelConfigGroupStore not available")
        requireGroupNameAvailable(request, userId, excludeGroupId = id)
        return store.updateGroupConnection(id, request, userId)
    }

    /**
     * Group names address the shared-connection entry point for a vendor, so a duplicate is always a
     * mistake: the caller meant to reuse the existing group. Rejecting it here covers both the Text and
     * the generation write paths, which create groups from their own inline forms.
     */
    private suspend fun requireGroupNameAvailable(request: SaveModelConfigGroupRequest, userId: String, excludeGroupId: String?) {
        val groups = groupStore?.getAllGroups(userId) ?: return
        val name = request.name.trim()
        val clash = groups.firstOrNull { it.id != excludeGroupId && it.name.trim().equals(name, ignoreCase = true) }
        require(clash == null) {
            "group \"$name\" already exists — add this model from that group's entry instead"
        }
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

    private fun validateGenerationRow(request: SaveModelProviderConfigRequest) {
        if (request.modelType == ModelType.CHAT) {
            require(request.protocol != Protocol.KLING) {
                "KLING is a generation-only protocol and cannot back a chat model"
            }
            return
        }
        require(request.protocol in GENERATION_PROTOCOLS) {
            "protocol ${request.protocol} cannot back a ${request.modelType.name.lowercase()} model " +
                "(supported: ${GENERATION_PROTOCOLS.joinToString { it.name.lowercase() }})"
        }
        require(request.modelId.isNotBlank()) {
            "${request.modelType.name.lowercase()} models need a model id"
        }
    }

    /**
     * Coerces options to what the declared profile allows and rejects contradictory combinations.
     *
     * NONE/ALWAYS_ON carry the mode's own truth, so those two fields are normalized rather than
     * rejected (a stale row being re-saved should not fail on a value the mode already decides).
     * Everything the user chose explicitly — effort level and its protocol gate — is validated.
     */
    private fun normalizeReasoningOptions(
        options: ModelOptions,
        capabilities: ModelCapabilities?,
        protocol: Protocol
    ): ModelOptions {
        val profile = capabilities?.reasoning ?: return options
        // UNKNOWN is the legacy escape hatch: the model was never characterized, so whatever the
        // row carried before must still save. Only the three characterized modes enforce a gate.
        if (profile.mode == ReasoningMode.UNKNOWN) return options
        var next = options
        when (profile.mode) {
            ReasoningMode.NONE -> next = next.copy(thinking = false, effort = null)
            ReasoningMode.ALWAYS_ON -> next = next.copy(thinking = true)
            ReasoningMode.TOGGLEABLE, ReasoningMode.UNKNOWN -> Unit
        }
        next.effort?.let { raw ->
            val effort = raw.trim().lowercase()
            require(effort in profile.effortLevels) {
                "effort '$raw' is not offered by this model; available: ${profile.effortLevels}"
            }
            val editable = profile.mode == ReasoningMode.ALWAYS_ON ||
                if (protocol == Protocol.ANTHROPIC) !next.thinking else next.thinking
            require(editable) {
                ModelOverridePolicy.effortGateMessage(protocol, next.thinking)
                    ?: "effort cannot be set for this model right now"
            }
            next = next.copy(effort = effort)
        }
        return next
    }

    private fun validateReasoningProfile(profile: ReasoningProfile) {
        val declared = profile.effortLevels.map { it.trim().lowercase() }
        val unknown = declared.filter { it !in ReasoningProfile.EFFORT_LEVELS }
        require(unknown.isEmpty()) {
            "unknown effort levels: $unknown; expected a subset of ${ReasoningProfile.EFFORT_LEVELS}"
        }
        profile.defaultEffort?.let { raw ->
            require(raw.trim().lowercase() in declared) {
                "defaultEffort '$raw' must be one of the declared effortLevels ${profile.effortLevels}"
            }
        }
        when (profile.mode) {
            ReasoningMode.NONE -> require(profile.effortLevels.isEmpty() && profile.defaultEffort == null) {
                "a NONE reasoning profile cannot offer effort levels"
            }
            ReasoningMode.ALWAYS_ON -> require(profile.defaultThinking) {
                "an ALWAYS_ON reasoning profile must default thinking to true"
            }
            ReasoningMode.TOGGLEABLE, ReasoningMode.UNKNOWN -> Unit
        }
    }

    /** Lowercase, trim, dedupe and order declared levels; the wire names are always canonical. */
    private fun canonicalizeReasoningProfile(profile: ReasoningProfile): ReasoningProfile =
        profile.copy(
            effortLevels = ReasoningProfiles.canonicalLevels(profile.effortLevels),
            defaultEffort = profile.defaultEffort?.trim()?.lowercase()
        )

    private fun validateOptions(options: ModelOptions, protocol: Protocol) {
        // Null is a real value here: it means "do not send temperature at all" and lets the
        // vendor's per-model default apply. Only a present number is range-checked.
        options.temperature?.let { temperature ->
            require(isTemperatureInRange(protocol, temperature)) {
                "temperature must be in ${temperatureRangeLabel(protocol)} for $protocol, got: $temperature"
            }
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

    companion object {
        /** Protocols the media tools can drive: OpenAI-compatible gateways (incl. DashScope compat), DashScope, Kling. */
        private val GENERATION_PROTOCOLS: Set<Protocol> = setOf(Protocol.OPENAI, Protocol.DASHSCOPE, Protocol.KLING)

        /**
         * Vendor-published temperature span for each wire protocol. One shared 0.0-2.0 check would
         * accept values the gateway then rejects: Anthropic allows at most 1.0, DashScope documents
         * a half-open [0, 2).
         */
        private fun isTemperatureInRange(protocol: Protocol, temperature: Double): Boolean = when (protocol) {
            Protocol.ANTHROPIC -> temperature in 0.0..1.0
            Protocol.DASHSCOPE -> temperature in 0.0..<2.0
            else -> temperature in 0.0..2.0
        }

        private fun temperatureRangeLabel(protocol: Protocol): String = when (protocol) {
            Protocol.ANTHROPIC -> "[0.0, 1.0]"
            Protocol.DASHSCOPE -> "[0.0, 2.0)"
            else -> "[0.0, 2.0]"
        }
    }
}
