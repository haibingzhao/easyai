package com.easy.easyai.tools.mcp

import com.easy.easyai.skills.command.McpPromptArgument
import com.easy.easyai.skills.command.McpPromptMeta
import com.easy.easyai.skills.command.McpPromptProvider
import io.modelcontextprotocol.client.McpAsyncClient
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.*
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.SmartInitializingSingleton
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages MCP server connections and tool caches.
 * - On startup (afterSingletonsInstantiated), loads ALL enabled configs across all users and connects
 * - Uses McpAsyncClient for non-blocking MCP communication (Reactor Mono → coroutine await)
 * - Maintains per-user-per-server tool caches using composite keys (userId:serverName)
 */
class McpClientManager(
    private val mcpServerStore: AsyncMcpServerStore,
) : SmartInitializingSingleton, DisposableBean, McpPromptProvider {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        /** System user ID for default/shared MCP servers visible to all users. */
        const val SYSTEM_USER_ID = "system"
    }

    private val clients = ConcurrentHashMap<String, McpAsyncClient>()
    private val statuses = ConcurrentHashMap<String, McpServerStatus>()
    private val toolCache = ConcurrentHashMap<String, List<McpSchema.Tool>>()
    private val promptCache = ConcurrentHashMap<String, List<McpSchema.Prompt>>()
    private val connectLocks = ConcurrentHashMap<String, Mutex>()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Build composite key for per-user-per-server isolation. */
    private fun key(userId: String, serverName: String) = "$userId:$serverName"

    override fun afterSingletonsInstantiated() {
        scope.launch {
            try {
                val configs = mcpServerStore.findAllEnabled()
                logger.info("Initializing {} MCP server connections across all users", configs.size)
                for (config in configs) {
                    connect(config, config.userId)
                }
            } catch (e: Exception) {
                logger.error("Failed to initialize MCP servers", e)
            }
        }
    }

    /**
     * Connect to an MCP server and cache its tool list.
     * Updates status to Connected on success, Failed on error.
     * Uses per-user-per-server mutex to prevent concurrent connect races.
     */
    suspend fun connect(config: McpServerConfig, userId: String = "system"): McpServerStatus {
        val k = key(userId, config.name)
        val mutex = connectLocks.computeIfAbsent(k) { Mutex() }
        return mutex.withLock { connectInternal(config, userId) }
    }

    private suspend fun connectInternal(config: McpServerConfig, userId: String): McpServerStatus {
        val k = key(userId, config.name)
        statuses[k] = McpServerStatus.Connecting
        try {
            // Disconnect existing client if any
            clients[k]?.let { old ->
                try { old.close() } catch (_: Exception) {}
            }

            val transport = createTransport(config)
            val client = McpClient.async(transport)
                .clientInfo(McpSchema.Implementation.builder("easyai", "1.0.0").build())
                .requestTimeout(Duration.ofSeconds(config.timeoutSeconds))
                .build()

            client.initialize().awaitSingle()

            val tools = client.listTools().awaitSingle().tools() ?: emptyList()
            clients[k] = client
            toolCache[k] = tools
            statuses[k] = McpServerStatus.Connected

            // Cache prompts if the server supports them
            val serverCapabilities = client.getServerCapabilities()
            if (serverCapabilities?.prompts() != null) {
                try {
                    val prompts = client.listPrompts().awaitSingle().prompts() ?: emptyList()
                    promptCache[k] = prompts
                    logger.info("Cached {} prompts from MCP server '{}' (user={})", prompts.size, config.name, userId)
                } catch (e: Exception) {
                    logger.debug("MCP server '{}' (user={}) does not support prompts: {}", config.name, userId, e.message)
                }
            }

            logger.info("Connected to MCP server '{}' (user={}) with {} tools", config.name, userId, tools.size)
            return McpServerStatus.Connected
        } catch (e: Exception) {
            val msg = e.message ?: "Unknown error"
            logger.error("Failed to connect to MCP server '{}' (user={}): {}", config.name, userId, msg)
            val status = McpServerStatus.Failed(msg)
            statuses[k] = status
            clients.remove(k)
            toolCache.remove(k)
            promptCache.remove(k)
            return status
        }
    }

    /**
     * Disconnect from an MCP server and clean up resources.
     */
    fun disconnect(name: String, userId: String = "system") {
        val k = key(userId, name)
        clients.remove(k)?.let { client ->
            try { client.close() } catch (e: Exception) {
                logger.warn("Error closing MCP client '{}' (user={}): {}", name, userId, e.message)
            }
        }
        toolCache.remove(k)
        promptCache.remove(k)
        statuses[k] = McpServerStatus.Disabled
    }

    /**
     * Returns cached tool definitions per server for a given user.
     * Includes both the user's own servers AND system-level servers (UserScope semantics).
     * Only includes servers with Connected status.
     */
    fun getAllToolDefs(userId: String): Map<String, List<McpSchema.Tool>> {
        return toolCache
            .filter { (k, _) ->
                val owner = k.substringBefore(":")
                statuses[k] == McpServerStatus.Connected
                    && (owner == userId || owner == SYSTEM_USER_ID)
            }
            .mapKeys { it.key.substringAfter(":") }
    }

    /**
     * Returns connected servers with owner info for tool resolution.
     * Used by McpToolProvider to construct McpToolDefinition with correct ownerUserId.
     */
    fun getConnectedServers(requestUserId: String): List<McpServerTools> {
        return toolCache
            .filter { (k, _) ->
                val owner = k.substringBefore(":")
                statuses[k] == McpServerStatus.Connected
                    && (owner == requestUserId || owner == SYSTEM_USER_ID)
            }
            .map { (k, tools) ->
                McpServerTools(
                    userId = k.substringBefore(":"),
                    serverName = k.substringAfter(":"),
                    tools = tools
                )
            }
    }

    /**
     * Executes an MCP tool call on the named server for the given user.
     * Returns result as a string (joined text content).
     * Throws exception on failure (caller should handle and return ToolResult.isError=true).
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun callTool(serverName: String, toolName: String, args: Map<String, Any?>, userId: String = "system"): String {
        val k = key(userId, serverName)
        val client = clients[k]
            ?: throw IllegalStateException("MCP server '$serverName' is not connected for user '$userId'")

        val javaArgs: Map<String, Any> = args.filterValues { it != null } as Map<String, Any>
        val request = McpSchema.CallToolRequest.builder(toolName).arguments(javaArgs).build()
        val result = client.callTool(request).awaitSingle()

        if (result.isError == true) {
            val errorText = result.content()
                ?.filterIsInstance<McpSchema.TextContent>()
                ?.joinToString("\n") { it.text() }
                ?: "Unknown MCP tool error"
            throw McpToolCallException(errorText)
        }

        return result.content()
            ?.filterIsInstance<McpSchema.TextContent>()
            ?.joinToString("\n") { it.text() }
            ?: ""
    }

    fun getStatus(name: String, userId: String = "system"): McpServerStatus =
        statuses[key(userId, name)] ?: McpServerStatus.Disabled

    /**
     * Returns statuses for a given user.
     * Includes both the user's own servers AND system-level servers.
     */
    fun getAllStatuses(userId: String): Map<String, McpServerStatus> {
        return statuses
            .filter { (k, _) ->
                val owner = k.substringBefore(":")
                owner == userId || owner == SYSTEM_USER_ID
            }
            .mapKeys { it.key.substringAfter(":") }
    }

    override fun getAllPrompts(): Map<String, List<McpPromptMeta>> {
        return promptCache
            .filter { (k, _) -> statuses[k] == McpServerStatus.Connected }
            .mapKeys { it.key.substringAfter(":") }
            .mapValues { (_, prompts) ->
                prompts.map { p ->
                    McpPromptMeta(
                        name = p.name() ?: "",
                        description = p.description(),
                        arguments = p.arguments()?.map { arg ->
                            McpPromptArgument(
                                name = arg.name() ?: "",
                                description = arg.description(),
                                required = arg.required() == true,
                            )
                        } ?: emptyList(),
                    )
                }
            }
    }

    /**
     * Returns prompts for a specific server, scoped by userId.
     */
    fun getAllPrompts(userId: String): Map<String, List<McpPromptMeta>> {
        return promptCache
            .filter { (k, _) ->
                val owner = k.substringBefore(":")
                statuses[k] == McpServerStatus.Connected
                    && (owner == userId || owner == SYSTEM_USER_ID)
            }
            .mapKeys { it.key.substringAfter(":") }
            .mapValues { (_, prompts) ->
                prompts.map { p ->
                    McpPromptMeta(
                        name = p.name() ?: "",
                        description = p.description(),
                        arguments = p.arguments()?.map { arg ->
                            McpPromptArgument(
                                name = arg.name() ?: "",
                                description = arg.description(),
                                required = arg.required() == true,
                            )
                        } ?: emptyList(),
                    )
                }
            }
    }

    override suspend fun getPrompt(serverName: String, promptName: String, args: Map<String, String>?): String {
        return getPrompt(serverName, promptName, args, "system")
    }

    suspend fun getPrompt(serverName: String, promptName: String, args: Map<String, String>?, userId: String): String {
        val k = key(userId, serverName)
        val client = clients[k]
            ?: throw IllegalStateException("MCP server '$serverName' is not connected for user '$userId'")
        val builder = McpSchema.GetPromptRequest.builder(promptName)
        if (!args.isNullOrEmpty()) {
            builder.arguments(args)
        }
        val request = builder.build()
        val result = client.getPrompt(request).awaitSingle()
        return result.messages()?.joinToString("\n") { msg ->
            (msg.content() as? McpSchema.TextContent)?.text() ?: ""
        } ?: ""
    }

    /**
     * Returns raw MCP Prompt metadata for a specific server (used by REST API).
     */
    fun getServerPrompts(serverName: String, userId: String = "system"): List<McpSchema.Prompt> {
        return promptCache[key(userId, serverName)] ?: emptyList()
    }

    // ─── Private helpers ───────────────────────────────────────────────────────

    private fun createTransport(config: McpServerConfig): io.modelcontextprotocol.spec.McpClientTransport {
        return when (config.type) {
            "local" -> {
                val command = config.command
                    ?: throw IllegalArgumentException("Local MCP server '${config.name}' must have a command")
                require(command.isNotEmpty()) { "Command list must not be empty for server '${config.name}'" }
                val params = ServerParameters.builder(command[0])
                    .args(command.drop(1))
                    .env(config.env)
                    .build()
                val mapper = McpJsonDefaults.getMapper()
                if (config.cwd != null) {
                    object : StdioClientTransport(params, mapper) {
                        override fun getProcessBuilder(): ProcessBuilder =
                            super.getProcessBuilder().directory(java.io.File(config.cwd))
                    }
                } else {
                    StdioClientTransport(params, mapper)
                }
            }
            "remote" -> {
                val url = config.url
                    ?: throw IllegalArgumentException("Remote MCP server '${config.name}' must have a URL")
                val builder = HttpClientStreamableHttpTransport.builder(url)
                if (config.headers.isNotEmpty()) {
                    builder.httpRequestCustomizer { requestBuilder, _, _, _, _ ->
                        config.headers.forEach { (k, v) -> requestBuilder.header(k, v) }
                    }
                }
                builder.build()
            }
            else -> throw IllegalArgumentException("Unknown MCP server type: ${config.type}")
        }
    }

    override fun destroy() {
        logger.info("Shutting down MCP client manager, closing {} connections", clients.size)
        scope.coroutineContext.job.cancel()
        clients.values.forEach { client ->
            try { client.close() } catch (_: Exception) {}
        }
        clients.clear()
        toolCache.clear()
        promptCache.clear()
    }
}

/** Thrown when an MCP tool returns an error result. */
class McpToolCallException(message: String) : RuntimeException(message)

/** Connected server info with owner userId for tool resolution. */
data class McpServerTools(
    val userId: String,
    val serverName: String,
    val tools: List<McpSchema.Tool>
)
