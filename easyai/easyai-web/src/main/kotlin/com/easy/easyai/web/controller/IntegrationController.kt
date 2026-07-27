package com.easy.easyai.web.controller

import com.easy.easyai.tools.web.IntegrationConfig
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono

/**
 * REST controller for managing third-party integration settings.
 *
 * Endpoints:
 * - GET  /api/system/integrations/status — returns configuration status (masked keys)
 * - PUT  /api/system/integrations        — saves integration settings
 */
@RestController
@RequestMapping("/api/system/integrations")
class IntegrationController {

    private val logger = LoggerFactory.getLogger(javaClass)

    @GetMapping("/status")
    fun getStatus(): Mono<Map<String, Any?>> = mono {
        val config = IntegrationConfig.load()
        val exaKey = IntegrationConfig.resolveExaApiKey()
        val parallelKey = IntegrationConfig.resolveParallelApiKey()
        val provider = IntegrationConfig.resolveWebsearchProvider()

        mapOf(
            "webSearch" to mapOf(
                "configured" to (!exaKey.isNullOrBlank() || !parallelKey.isNullOrBlank()),
                "exaConfigured" to !exaKey.isNullOrBlank(),
                "parallelConfigured" to !parallelKey.isNullOrBlank(),
                "exaApiKey" to maskApiKey(config?.exaApiKey),
                "parallelApiKey" to maskApiKey(config?.parallelApiKey),
                "provider" to (provider ?: "exa")
            )
        )
    }

    @PutMapping
    fun updateSettings(@RequestBody request: IntegrationUpdateRequest): Mono<Map<String, Any?>> = mono {
        try {
            val existing = IntegrationConfig.load() ?: IntegrationConfig()

            val updated = existing.copy(
                exaApiKey = request.exaApiKey ?: existing.exaApiKey,
                parallelApiKey = request.parallelApiKey ?: existing.parallelApiKey,
                websearchProvider = request.websearchProvider ?: existing.websearchProvider
            )

            IntegrationConfig.save(updated)
            logger.info("Integration settings updated")

            mapOf("success" to true, "message" to "Integration settings saved")
        } catch (e: Exception) {
            logger.error("Failed to save integration settings", e)
            mapOf("success" to false, "message" to (e.message ?: "Failed to save settings"))
        }
    }

    companion object {
        private fun maskApiKey(apiKey: String?): String? {
            if (apiKey.isNullOrBlank()) return null
            if (apiKey.length <= 8) return "****"
            return apiKey.take(4) + "****" + apiKey.takeLast(4)
        }
    }
}

/**
 * Request body for updating integration settings.
 * Null fields are not modified (partial update semantics).
 * Empty string clears the key.
 */
data class IntegrationUpdateRequest(
    val exaApiKey: String? = null,
    val parallelApiKey: String? = null,
    val websearchProvider: String? = null
)
