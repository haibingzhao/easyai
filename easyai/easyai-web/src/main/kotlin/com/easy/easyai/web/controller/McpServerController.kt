package com.easy.easyai.web.controller

import com.easy.easyai.tools.mcp.AsyncMcpServerStore
import com.easy.easyai.tools.mcp.McpClientManager
import com.easy.easyai.tools.mcp.McpServerConfig
import com.easy.easyai.tools.mcp.McpServerStatus
import com.easy.easyai.web.security.currentGroupOwners
import com.easy.easyai.web.security.getCurrentUserId
import com.easy.easyai.web.security.isGroupOwner
import com.easy.easyai.web.security.parseAssetScope
import com.easy.easyai.web.security.resolveWriteOwner
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
        // MCP never folds in the shared layer, so the listing and the connection warm-up both cover
        // exactly the caller's own bucket plus any group bucket they belong to.
        val owners = currentGroupOwners()
        mcpClientManager.ensureOwnersConnected(owners)
        val configs = mcpServerStore.findAll(owners)
        // `name` is not unique across buckets (the table's PK is `id`), so status and tools are keyed
        // by (owner, name): a member's own server and their group's same-named one are distinct rows
        // and must not overwrite each other in the rendered list.
        val connected = mcpClientManager.getConnectedServers(owners)
            .associateBy { it.userId to it.serverName }

        configs.map { config ->
            val server = connected[config.userId to config.name]
            val status = if (server != null) McpServerStatus.Connected
            else mcpClientManager.getStatus(config.name, config.userId)
            config.toDto(status, server?.tools ?: emptyList(), config.userId)
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @RequestBody request: McpServerCreateRequest,
        @RequestParam(required = false) scope: String? = null
    ): Mono<McpServerDto> = mono {
        val owner = resolveWriteOwner(parseAssetScope(scope))
        validateCreateRequest(request)
        val existing = mcpServerStore.findByName(request.name, owner)
        if (existing != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "MCP server already exists: ${request.name}")
        }
        val config = request.toConfig()
        mcpServerStore.save(config, owner)

        val status = if (config.enabled) {
            mcpClientManager.connect(config, owner)
        } else {
            McpServerStatus.Disabled
        }
        config.toDto(status, mcpClientManager.getToolDefs(owner, config.name), owner)
    }

    @PostMapping("/import")
    fun bulkImport(
        @RequestBody request: McpBulkImportRequest,
        @RequestParam(required = false) scope: String? = null
    ): Mono<List<McpServerDto>> = mono {
        val owner = resolveWriteOwner(parseAssetScope(scope))
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
                val existing = mcpServerStore.findByName(name, owner)
                if (existing != null) {
                    mcpServerStore.update(config, owner)
                } else {
                    mcpServerStore.save(config, owner)
                }
                val status = mcpClientManager.connect(config, owner)
                results.add(config.toDto(status, mcpClientManager.getToolDefs(owner, name), owner))
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
    fun update(
        @PathVariable name: String,
        @RequestBody request: McpServerCreateRequest,
        @RequestParam(required = false) scope: String? = null
    ): Mono<McpServerDto> = mono {
        val owner = resolveWriteOwner(parseAssetScope(scope))
        val existing = mcpServerStore.findByName(name, owner)
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
        mcpServerStore.update(updated, owner)

        // Reconnect with new config
        if (updated.enabled) {
            mcpClientManager.connect(updated, owner)
        } else {
            mcpClientManager.disconnect(name, owner)
        }

        val status = mcpClientManager.getStatus(name, owner)
        updated.toDto(status, mcpClientManager.getToolDefs(owner, name), owner)
    }

    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable name: String,
        @RequestParam(required = false) scope: String? = null
    ): Mono<Void> = mono {
        val owner = resolveWriteOwner(parseAssetScope(scope))
        mcpServerStore.findByName(name, owner)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "MCP server not found: $name")
        mcpClientManager.disconnect(name, owner)
        mcpServerStore.delete(name, owner)
    }.then()

    @PostMapping("/{name}/connect")
    fun reconnect(@PathVariable name: String): Mono<McpServerDto> = mono {
        val config = mcpServerStore.findByName(name, currentGroupOwners())
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "MCP server not found: $name")
        // Connect under the config's true owner so a group server is shared, not duplicated per member.
        val owner = config.userId
        assertCanToggle(owner)
        val status = mcpClientManager.connect(config, owner)
        config.toDto(status, mcpClientManager.getToolDefs(owner, name), owner)
    }

    @PostMapping("/{name}/disconnect")
    fun disconnect(@PathVariable name: String): Mono<McpServerDto> = mono {
        val config = mcpServerStore.findByName(name, currentGroupOwners())
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "MCP server not found: $name")
        val owner = config.userId
        assertCanToggle(owner)
        mcpClientManager.disconnect(name, owner)
        config.toDto(McpServerStatus.Disabled, emptyList(), owner)
    }

    @GetMapping("/{name}/tools")
    fun getTools(@PathVariable name: String): Mono<List<McpToolInfoDto>> = mono {
        val config = mcpServerStore.findByName(name, currentGroupOwners())
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "MCP server not found: $name")
        // Read the tools under the server's own bucket: the tool cache is keyed `owner:name`, so a
        // name-keyed lookup across the whole visibility set could answer from a same-named server
        // belonging to another visible bucket.
        val tools = mcpClientManager.getToolDefs(config.userId, name)
        tools.map { McpToolInfoDto(name = it.name(), description = it.description() ?: "") }
    }

    @GetMapping("/{name}/prompts")
    fun getPrompts(@PathVariable name: String): Mono<List<McpPromptInfoDto>> = mono {
        val config = mcpServerStore.findByName(name, currentGroupOwners())
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "MCP server not found: $name")
        // Prompts are cached under the connected owner's key. A group server is connected as the group
        // bucket, so query by config.userId — not the caller — or a member sees an empty list.
        mcpClientManager.getServerPrompts(name, config.userId).map { toPromptDto(it) }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Connect/disconnect a server owned by the group bucket affects every member, so it is a group
     * write: only the group owner may toggle it. A caller's own server is always toggleable.
     */
    private suspend fun assertCanToggle(owner: String) {
        if (owner != getCurrentUserId() && !isGroupOwner()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only the group owner can manage a shared MCP server")
        }
    }

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
