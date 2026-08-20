package com.easy.easyai.web.controller

import com.easy.easyai.rag.RagClient
import com.easy.easyai.rag.RagConfig
import com.easy.easyai.rag.RagWorkspaceConfigUpdate
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

/**
 * REST controller for managing the EasyRAG integration settings.
 *
 * Endpoints:
 * - GET    /api/system/rag/status           — returns configuration (password masked) plus live connectivity
 * - PUT    /api/system/rag                  — saves configuration (partial update semantics)
 * - POST   /api/system/rag/test             — connectivity test with latency
 * - GET    /api/system/rag/workspace-config — get workspace tenant config from EasyRAG
 * - POST   /api/system/rag/workspace-config — upsert workspace tenant config in EasyRAG
 * - DELETE /api/system/rag/workspace-config — delete workspace tenant config in EasyRAG
 */
@RestController
@RequestMapping("/api/system/rag")
class RagController {

    private val logger = LoggerFactory.getLogger(javaClass)

    /** Own client instance; config is reloaded per request from `~/.easyai/rag.json`. */
    private val client: RagClient = RagClient.create()

    @GetMapping("/status")
    fun getStatus(): Mono<Map<String, Any?>> = mono {
        val config = RagConfig.load()
        val connected = if (config.enabled) client.healthCheck() else false
        mapOf(
            "enabled" to config.enabled,
            "baseUrl" to config.baseUrl,
            "username" to config.username,
            "password" to maskSecret(config.password),
            "workspace" to config.workspace,
            "topK" to config.topK,
            "readTimeoutMs" to config.readTimeoutMs,
            "indexTimeoutMs" to config.indexTimeoutMs,
            "connected" to connected
        )
    }

    @PutMapping
    fun updateSettings(@RequestBody request: RagUpdateRequest): Mono<Map<String, Any?>> = mono {
        try {
            val existing = RagConfig.load()

            // Ignore a password that is just the masked echo of the stored one
            val password = when {
                request.password == null -> existing.password
                request.password.isEmpty() -> null
                request.password == maskSecret(existing.password) -> existing.password
                else -> request.password
            }

            val updated = existing.copy(
                enabled = request.enabled ?: existing.enabled,
                baseUrl = request.baseUrl ?: existing.baseUrl,
                username = if (request.username == null) existing.username else request.username.ifBlank { null },
                password = password,
                workspace = if (request.workspace == null) existing.workspace else request.workspace.ifBlank { null },
                topK = request.topK ?: existing.topK,
                readTimeoutMs = request.readTimeoutMs ?: existing.readTimeoutMs,
                indexTimeoutMs = request.indexTimeoutMs ?: existing.indexTimeoutMs
            )

            RagConfig.save(updated)
            logger.info("RAG settings updated (baseUrl={}, enabled={})", updated.baseUrl, updated.enabled)

            mapOf("success" to true, "message" to "RAG settings saved")
        } catch (e: Exception) {
            logger.error("Failed to save RAG settings", e)
            mapOf("success" to false, "message" to (e.message ?: "Failed to save settings"))
        }
    }

    @PostMapping("/test")
    fun testConnection(): Mono<Map<String, Any?>> = mono {
        val config = RagConfig.load()
        val started = System.currentTimeMillis()
        val connected = client.healthCheck()
        val latencyMs = System.currentTimeMillis() - started
        logger.info("RAG connection test: connected={}, latency={}ms, baseUrl={}", connected, latencyMs, config.baseUrl)
        mapOf(
            "connected" to connected,
            "latencyMs" to latencyMs,
            "message" to if (connected) "Connected to ${config.baseUrl}" else "Failed to reach ${config.baseUrl}"
        )
    }

    // ------------------------------------------------------------------
    // Workspace tenant configuration (proxied to EasyRAG)
    // ------------------------------------------------------------------

    @GetMapping("/workspace-config")
    fun getWorkspaceConfig(
        @RequestParam workspace: String
    ): Mono<Any> = mono {
        val config = RagConfig.load()
        if (!config.enabled) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "RAG integration is not enabled")
        }
        val ws = workspace.ifBlank { config.workspace ?: "default" }
        client.getWorkspaceConfig(ws)
            ?: mapOf("workspace" to ws, "message" to "No workspace-specific config; using global defaults")
    }

    @PostMapping("/workspace-config")
    fun updateWorkspaceConfig(
        @RequestBody request: RagWorkspaceConfigUpdate
    ): Mono<Any> = mono {
        val config = RagConfig.load()
        if (!config.enabled) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "RAG integration is not enabled")
        }
        val result = client.upsertWorkspaceConfig(request)
        logger.info("Workspace config updated for workspace={}", request.workspace)
        result
    }

    @DeleteMapping("/workspace-config")
    fun deleteWorkspaceConfig(
        @RequestParam workspace: String
    ): Mono<Any> = mono {
        val config = RagConfig.load()
        if (!config.enabled) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "RAG integration is not enabled")
        }
        client.deleteWorkspaceConfig(workspace)
        logger.info("Workspace config deleted for workspace={}", workspace)
        mapOf("status" to "success", "message" to "Workspace config for '$workspace' deleted")
    }

    private companion object {
        fun maskSecret(secret: String?): String? {
            if (secret.isNullOrBlank()) return null
            if (secret.length <= 8) return "****"
            return secret.take(4) + "****" + secret.takeLast(4)
        }
    }
}

/**
 * Request body for updating RAG settings.
 * Null fields are not modified (partial update semantics).
 * Empty string clears username/password/workspace.
 */
data class RagUpdateRequest(
    val enabled: Boolean? = null,
    val baseUrl: String? = null,
    val username: String? = null,
    val password: String? = null,
    val workspace: String? = null,
    val topK: Int? = null,
    val readTimeoutMs: Long? = null,
    val indexTimeoutMs: Long? = null
)
