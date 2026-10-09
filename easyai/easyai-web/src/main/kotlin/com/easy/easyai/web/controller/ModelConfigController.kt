package com.easy.easyai.web.controller

import com.easy.easyai.api.config.ModelConfigService
import com.easy.easyai.api.model.ModelConfigGroup
import com.easy.easyai.api.model.ModelInfo
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo
import com.easy.easyai.api.model.ModelType
import com.easy.easyai.api.model.SaveModelConfigGroupRequest
import com.easy.easyai.api.model.SaveModelProviderConfigRequest
import com.easy.easyai.core.media.MediaProviderResolver
import com.easy.easyai.web.security.currentOwners
import com.easy.easyai.web.security.getCurrentUserId
import com.easy.easyai.web.security.parseAssetScope
import com.easy.easyai.web.security.resolveWriteOwner
import kotlinx.coroutines.reactor.mono
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/api/chat")
class ModelConfigController(
    private val modelConfigService: ModelConfigService,
    @param:Autowired(required = false)
    private val mediaProviderResolver: MediaProviderResolver? = null
) {

    companion object {
        private fun maskApiKey(apiKey: String?): String? {
            if (apiKey.isNullOrBlank()) return null
            if (apiKey.length <= 8) return "****"
            return apiKey.take(4) + "****" + apiKey.takeLast(4)
        }

        private fun ModelConfigGroup.masked(): ModelConfigGroup = copy(
            apiKey = maskApiKey(apiKey),
            models = models.map { it.masked() }
        )

        private fun ModelProviderConfig.masked(): ModelProviderConfig = copy(
            apiKey = maskApiKey(apiKey)
        )
    }

    @GetMapping("/model-providers")
    fun getAvailableProviders(): Mono<List<ModelProviderInfo>> =
        mono {
            val userId = getCurrentUserId()
            modelConfigService.getAvailableProviders(userId)
        }

    @GetMapping("/model-providers/{id}")
    fun getProviderById(@PathVariable id: String): Mono<ModelProviderInfo> =
        mono {
            val userId = getCurrentUserId()
            modelConfigService.getProviderById(id, userId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Model provider not found: $id")
        }

    @GetMapping("/model-providers/{id}/models")
    fun getModelsForProvider(@PathVariable id: String): Mono<List<ModelInfo>> =
        mono {
            val userId = getCurrentUserId()
            val provider = modelConfigService.getProviderById(id, userId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Model provider not found: $id")
            provider.models
        }

    @GetMapping("/model-configs")
    fun getUserConfigurations(@RequestParam(required = false) modelType: ModelType?): Mono<List<ModelProviderConfig>> =
        mono {
            modelConfigService
                .getUserConfigurations(modelType ?: ModelType.CHAT, currentOwners())
                .map { it.masked() }
        }

    @GetMapping("/model-configs/{id}")
    fun getUserConfiguration(@PathVariable id: String): Mono<ModelProviderConfig> =
        mono {
            (modelConfigService.getUserConfiguration(id, currentOwners())
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Configuration not found: $id")).masked()
        }

    @PostMapping("/model-configs")
    @ResponseStatus(HttpStatus.CREATED)
    fun saveUserConfiguration(
        @RequestBody request: SaveModelProviderConfigRequest,
        @RequestParam(required = false) scope: String? = null
    ): Mono<ModelProviderConfig> =
        mono {
            val owner = resolveWriteOwner(parseAssetScope(scope))
            try {
                modelConfigService.saveUserConfiguration(request, owner, currentOwners()).masked()
            } catch (e: IllegalArgumentException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message ?: "Invalid model configuration")
            }.also { refreshMediaCache(owner, request.modelType) }
        }

    /** Structural probe of a generation-type draft; never persists. Blank keys fall back to stored ones. */
    @PostMapping("/model-configs/test")
    fun testGenerationConfiguration(
        @RequestBody request: SaveModelProviderConfigRequest,
        @RequestParam(required = false) scope: String? = null
    ): Mono<ModelConfigTestDto> =
        mono {
            val owner = resolveWriteOwner(parseAssetScope(scope))
            val failure = modelConfigService.testGenerationConfiguration(request, owner, currentOwners())
            if (failure == null) ModelConfigTestDto(true, "Configuration OK")
            else ModelConfigTestDto(false, failure)
        }

    @DeleteMapping("/model-configs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteUserConfiguration(
        @PathVariable id: String,
        @RequestParam(required = false) scope: String? = null
    ): Mono<Void> =
        mono {
            val owner = resolveWriteOwner(parseAssetScope(scope))
            val existing = modelConfigService.getUserConfiguration(id, owner)
            val deleted = modelConfigService.deleteUserConfiguration(id, owner)
            if (!deleted) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "Configuration not found: $id")
            }
            existing?.let { refreshMediaCache(owner, it.modelType) }
        }.then()

    // ─── Model Config Groups ─────────────────────────────────────────────────────

    @GetMapping("/model-groups")
    fun getGroups(@RequestParam(required = false) modelType: ModelType?): Mono<List<ModelConfigGroup>> =
        mono {
            modelConfigService.getGroups(currentOwners())
                .map { group -> group.copy(models = group.models.filter { it.modelType == (modelType ?: ModelType.CHAT) }) }
                // An explicitly requested type only shows groups that carry members of it
                .filter { modelType == null || it.models.isNotEmpty() }
                .map { it.masked() }
        }

    @PostMapping("/model-groups")
    @ResponseStatus(HttpStatus.CREATED)
    fun saveGroup(
        @RequestBody request: SaveModelConfigGroupRequest,
        @RequestParam(required = false) scope: String? = null
    ): Mono<ModelConfigGroup> =
        mono {
            modelConfigService.saveGroup(request, resolveWriteOwner(parseAssetScope(scope))).masked()
        }

    @PutMapping("/model-groups/{id}")
    fun updateGroup(
        @PathVariable id: String,
        @RequestBody request: SaveModelConfigGroupRequest,
        @RequestParam(required = false) scope: String? = null
    ): Mono<ModelConfigGroup> =
        mono {
            modelConfigService.updateGroup(id, request, resolveWriteOwner(parseAssetScope(scope))).masked()
        }

    @DeleteMapping("/model-groups/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteGroup(
        @PathVariable id: String,
        @RequestParam(required = false) scope: String? = null
    ): Mono<Void> =
        mono {
            val deleted = modelConfigService.deleteGroup(id, resolveWriteOwner(parseAssetScope(scope)))
            if (!deleted) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found: $id")
            }
        }.then()

    /** Generation rows are cached per (user, kind) by the media resolver; hot-apply on any change. */
    private fun refreshMediaCache(userId: String, modelType: ModelType) {
        if (modelType == ModelType.CHAT) return
        mediaProviderResolver?.refresh(userId)
    }
}

/** Outcome of a generation-config structural probe. */
data class ModelConfigTestDto(
    val success: Boolean,
    val message: String
)