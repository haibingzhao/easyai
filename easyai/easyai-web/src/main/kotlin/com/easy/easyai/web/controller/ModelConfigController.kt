package com.easy.easyai.web.controller

import com.easy.easyai.api.config.ModelConfigService
import com.easy.easyai.api.model.ModelInfo
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo
import com.easy.easyai.api.model.SaveModelProviderConfigRequest
import com.easy.easyai.web.security.getCurrentUserId
import kotlinx.coroutines.reactor.mono
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/api/chat")
class ModelConfigController(
    private val modelConfigService: ModelConfigService
) {

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
    fun getUserConfigurations(): Mono<List<ModelProviderConfig>> =
        mono {
            val userId = getCurrentUserId()
            modelConfigService.getUserConfigurations(userId)
        }

    @GetMapping("/model-configs/{id}")
    fun getUserConfiguration(@PathVariable id: String): Mono<ModelProviderConfig> =
        mono {
            val userId = getCurrentUserId()
            modelConfigService.getUserConfiguration(id, userId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Configuration not found: $id")
        }

    @PostMapping("/model-configs")
    @ResponseStatus(HttpStatus.CREATED)
    fun saveUserConfiguration(@RequestBody request: SaveModelProviderConfigRequest): Mono<ModelProviderConfig> =
        mono {
            val userId = getCurrentUserId()
            modelConfigService.saveUserConfiguration(request, userId)
        }

    @DeleteMapping("/model-configs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteUserConfiguration(@PathVariable id: String): Mono<Void> =
        mono {
            val userId = getCurrentUserId()
            val deleted = modelConfigService.deleteUserConfiguration(id, userId)
            if (!deleted) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "Configuration not found: $id")
            }
        }.then()
}