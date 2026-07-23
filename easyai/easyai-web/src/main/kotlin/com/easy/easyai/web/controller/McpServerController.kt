package com.easy.easyai.web.controller

import com.easy.easyai.tools.mcp.AsyncMcpServerStore
import com.easy.easyai.tools.mcp.McpClientManager
import com.easy.easyai.tools.mcp.McpServerConfig
import com.easy.easyai.tools.mcp.McpServerStatus
import com.easy.easyai.web.security.getCurrentUserId
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.reactor.mono
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import java.util.UUID

// ─── Request / Response DTOs ──────────────────────────────────────────────────

data class McpServerDto(
    val name: String,
    val type: String,
    val command: List<String>? = null,
    val env: Map<String, String> = emptyMap(),
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val timeoutSeconds: Long = 120L,
    val enabled: Boolean = true,
    val status: String,          // "connected" | "disabled" | "failed" | "connecting"
    val error: String? = null,
    val tools: List<McpToolInfoDto> = emptyList(),
    val prompts: List<McpPromptInfoDto> = emptyList()
)

data class McpToolInfoDto(
    val name: String,
    val description: String
)

data class McpPromptInfoDto(
    val name: String,
    val description: String?,
    val arguments: List<McpPromptArgumentDto> = emptyList()
)

data class McpPromptArgumentDto(
    val name: String,
    val description: String?,
    val required: Boolean = false
)

data class McpServerCreateRequest(
    val name: String,
    val type: String,
    val command: List<String>? = null,
    val env: Map<String, String> = emptyMap(),
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val timeoutSeconds: Long = 120L,
    val enabled: Boolean = true
)

/** Bulk import format: { "mcpServers": { "name": { ...config } } }
 *
 * Accepts both EasyAI format (command) and Claude Desktop / Cursor format (args).
 * Unknown properties are silently ignored via @JsonIgnoreProperties.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class McpBulkImportRequest(
    val mcpServers: Map<String, McpServerImportEntry>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class McpServerImportEntry(
    /** Accepts both String (Claude Desktop: "command": "/path/to/bin") and List (EasyAI: "command": ["npx", "..."]) */
    val command: Any? = null,
    /** Claude Desktop / Cursor: additional arguments for the command */
    val args: List<String>? = null,
    val env: Map<String, String?>? = null,
    val url: String? = null,
    val headers: Map<String, String?>? = null,
    /** Some configs use "type" field (e.g. "stdio", "sse", "streamable-http") */
    val type: String? = null,
    /** Working directory (Claude Desktop / Cursor format) */
    val cwd: String? = null,
    /** Request timeout in seconds (default 120) */
    val timeoutSeconds: Long? = null
) {
    /** Resolve command into a single list: [executable, ...args] */
    fun resolvedCommand(): List<String>? {
        val cmd = when (command) {
            is String -> listOf(command)
            is List<*> -> command.filterIsInstance<String>()
            else -> null
        }
        val extraArgs = args?.takeIf { it.isNotEmpty() } ?: emptyList()
        val combined = (cmd ?: emptyList()) + extraArgs
        return combined.takeIf { it.isNotEmpty() }
    }
    fun resolvedEnv(): Map<String, String> = env?.mapValues { it.value ?: "" } ?: emptyMap()
    fun resolvedHeaders(): Map<String, String> = headers?.mapValues { it.value ?: "" } ?: emptyMap()
}

// ─── Controller ───────────────────────────────────────────────────────────────

/**
 * REST API for managing MCP server configurations.
 *
 * GET    /api/mcp/servers                   - List all servers with status and tools
 * POST   /api/mcp/servers                   - Add a single server
 * POST   /api/mcp/servers/import            - Bulk import servers from JSON
 * PUT    /api/mcp/servers/{name}            - Update a server config
 * DELETE /api/mcp/servers/{name}            - Delete a server
 * POST   /api/mcp/servers/{name}/connect    - Reconnect
 * POST   /api/mcp/servers/{name}/disconnect - Disconnect
 * GET    /api/mcp/servers/{name}/tools      - List tools for a specific server
 */
@RestController
@RequestMapping("/api/mcp/servers")
class McpServerController(
    private val mcpServerStore: AsyncMcpServerStore,
    private val mcpClientManager: McpClientManager
) {

    @GetMapping
    fun listAll(): Mono<List<McpServerDto>> = mono {
        val userId = getCurrentUserId()
        // Trigger lazy connection for this user's MCP servers
        mcpClientManager.ensureUserConnected(userId)
        val configs = mcpServerStore.findAll(userId)
        val statuses = mcpClientManager.getAllStatuses(userId)
        val toolDefs = mcpClientManager.getAllToolDefs(userId)

        configs.map { config ->
            val status = statuses[config.name] ?: McpServerStatus.Disabled
            config.toDto(status, toolDefs[config.name] ?: emptyList(), userId)
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: McpServerCreateRequest): Mono<McpServerDto> = mono {
        val userId = getCurrentUserId()
        validateCreateRequest(request)
        val existing = mcpServerStore.findByName(request.name, userId)
        if (existing != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "MCP server already exists: ${request.name}")
        }
        val config = request.toConfig()
        mcpServerStore.save(config, userId)

        val status = if (config.enabled) {
            mcpClientManager.connect(config, userId)
        } else {
            McpServerStatus.Disabled
        }
        config.toDto(status, mcpClientManager.getAllToolDefs(userId)[config.name] ?: emptyList(), userId)
    }

    @PostMapping("/import")
    fun bulkImport(@RequestBody request: McpBulkImportRequest): Mono<List<McpServerDto>> = mono {
        val userId = getCurrentUserId()
        val results = mutableListOf<McpServerDto>()
        for ((name, entry) in request.mcpServers.orEmpty()) {
            try {
                if (name.isBlank()) {
                    results.add(McpServerDto(name = name, type = "unknown", status = "failed", error = "Server name must not be blank"))
                    continue
                }
                val resolvedCmd = entry.resolvedCommand()
                val type = if (entry.url != null) "remote" else "local"
                if (type == "local" && resolvedCmd.isNullOrEmpty()) {
                    results.add(McpServerDto(name = name, type = type, status = "failed", error = "Local server must specify a command"))
                    continue
                }
                if (type == "remote" && entry.url.isNullOrBlank()) {
                    results.add(McpServerDto(name = name, type = type, status = "failed", error = "Remote server must specify a URL"))
                    continue
                }
                val config = McpServerConfig(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    type = type,
                    command = resolvedCmd,
                    env = entry.resolvedEnv(),
                    url = entry.url,
                    headers = entry.resolvedHeaders(),
                    cwd = entry.cwd,
                    timeoutSeconds = entry.timeoutSeconds ?: 120L,
                    enabled = true
                )
                val existing = mcpServerStore.findByName(name, userId)
                if (existing != null) {
                    mcpServerStore.update(config, userId)
                } else {
                    mcpServerStore.save(config, userId)
                }
                val status = mcpClientManager.connect(config, userId)
                results.add(config.toDto(status, mcpClientManager.getAllToolDefs(userId)[name] ?: emptyList(), userId))
            } catch (e: Exception) {
                // Skip failed entries but continue with others
                results.add(McpServerDto(
                    name = name,
                    type = if (entry.url != null) "remote" else "local",
                    status = "failed",
                    error = e.message
                ))
            }
        }
        results
    }

    @PutMapping("/{name}")
    fun update(@PathVariable name: String, @RequestBody request: McpServerCreateRequest): Mono<McpServerDto> = mono {
        val userId = getCurrentUserId()
        val existing = mcpServerStore.findByName(name, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "MCP server not found: $name")

        val updated = existing.copy(
            type = request.type,
            command = request.command,
            env = request.env,
            url = request.url,
            headers = request.headers,
            timeoutSeconds = request.timeoutSeconds,
            enabled = request.enabled,
            updatedAt = System.currentTimeMillis()
        )
        mcpServerStore.update(updated, userId)

        // Reconnect with new config
        if (updated.enabled) {
            mcpClientManager.connect(updated, userId)
        } else {
            mcpClientManager.disconnect(name, userId)
        }

        val status = mcpClientManager.getStatus(name, userId)
        updated.toDto(status, mcpClientManager.getAllToolDefs(userId)[name] ?: emptyList(), userId)
    }

    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable name: String): Mono<Void> = mono {
        val userId = getCurrentUserId()
        mcpServerStore.findByName(name, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "MCP server not found: $name")
        mcpClientManager.disconnect(name, userId)
        mcpServerStore.delete(name, userId)
    }.then()

    @PostMapping("/{name}/connect")
    fun reconnect(@PathVariable name: String): Mono<McpServerDto> = mono {
        val userId = getCurrentUserId()
        val config = mcpServerStore.findByName(name, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "MCP server not found: $name")
        val status = mcpClientManager.connect(config, userId)
        config.toDto(status, mcpClientManager.getAllToolDefs(userId)[name] ?: emptyList(), userId)
    }

    @PostMapping("/{name}/disconnect")
    fun disconnect(@PathVariable name: String): Mono<McpServerDto> = mono {
        val userId = getCurrentUserId()
        val config = mcpServerStore.findByName(name, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "MCP server not found: $name")
        mcpClientManager.disconnect(name, userId)
        config.toDto(McpServerStatus.Disabled, emptyList(), userId)
    }

    @GetMapping("/{name}/tools")
    fun getTools(@PathVariable name: String): Mono<List<McpToolInfoDto>> = mono {
        val userId = getCurrentUserId()
        mcpServerStore.findByName(name, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "MCP server not found: $name")
        val tools = mcpClientManager.getAllToolDefs(userId)[name] ?: emptyList()
        tools.map { McpToolInfoDto(name = it.name(), description = it.description() ?: "") }
    }

    @GetMapping("/{name}/prompts")
    fun getPrompts(@PathVariable name: String): Mono<List<McpPromptInfoDto>> = mono {
        val userId = getCurrentUserId()
        mcpServerStore.findByName(name, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "MCP server not found: $name")
        mcpClientManager.getServerPrompts(name, userId).map { toPromptDto(it) }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private fun validateCreateRequest(request: McpServerCreateRequest) {
        if (request.name.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Server name must not be blank")
        }
        when (request.type) {
            "local" -> if (request.command.isNullOrEmpty()) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Local server must specify a command")
            }
            "remote" -> if (request.url.isNullOrBlank()) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Remote server must specify a URL")
            }
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid type: ${request.type}. Must be 'local' or 'remote'")
        }
    }

    private fun McpServerCreateRequest.toConfig() = McpServerConfig(
        id = UUID.randomUUID().toString(),
        name = name,
        type = type,
        command = command,
        env = env,
        url = url,
        headers = headers,
        timeoutSeconds = timeoutSeconds,
        enabled = enabled
    )

    private fun McpServerConfig.toDto(
        status: McpServerStatus,
        tools: List<McpSchema.Tool>,
        userId: String = "system"
    ) = McpServerDto(
        name = name,
        type = type,
        command = command,
        env = env,
        url = url,
        headers = headers,
        timeoutSeconds = timeoutSeconds,
        enabled = enabled,
        status = when (status) {
            McpServerStatus.Connected -> "connected"
            McpServerStatus.Disabled -> "disabled"
            McpServerStatus.Connecting -> "connecting"
            is McpServerStatus.Failed -> "failed"
        },
        error = (status as? McpServerStatus.Failed)?.error,
        tools = tools.map { McpToolInfoDto(name = it.name(), description = it.description() ?: "") },
        prompts = mcpClientManager.getServerPrompts(name, userId).map { toPromptDto(it) }
    )

    private fun toPromptDto(prompt: McpSchema.Prompt) = McpPromptInfoDto(
        name = prompt.name() ?: "",
        description = prompt.description(),
        arguments = prompt.arguments()?.map { arg ->
            McpPromptArgumentDto(
                name = arg.name() ?: "",
                description = arg.description(),
                required = arg.required() == true
            )
        } ?: emptyList()
    )
}
