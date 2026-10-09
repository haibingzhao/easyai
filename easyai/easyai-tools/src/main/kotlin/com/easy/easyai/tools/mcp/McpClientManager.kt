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
import java.net.URI
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages MCP server connections and tool caches.
 * - On startup, only connects system-level shared MCP servers (userId = "system")
 * - User-specific MCP servers are lazily connected on first access via [ensureUserConnected]
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

        /**
         * Keeps only the highest-priority bucket's server per name, ordered by [owners] priority.
         * A tool is exposed as `{server}__{tool}` with no owner component, so a name shared by two
         * visible buckets would otherwise reach the model twice and a call would resolve to
         * whichever copy came first in a concurrent map's iteration order. An owner missing from
         * [owners] sorts last instead of winning by luck.
         */
        @JvmStatic
        internal fun shadowByName(servers: List<McpServerTools>, owners: Collection<String>): List<McpServerTools> {
            val priority = owners.filter { it.isNotBlank() }.distinct()
            val byName = LinkedHashMap<String, McpServerTools>()
            servers
                .sortedBy { priority.indexOf(it.userId).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE }
                .forEach { byName.putIfAbsent(it.serverName, it) }
            return byName.values.toList()
        }

        /**
         * The prompt-cache counterpart of [shadowByName]: collapses `owner:server` keys to server
         * names, keeping the highest-priority owner's prompts when two visible buckets share a name.
         */
        @JvmStatic
        internal fun shadowPrompts(
            entries: Map<String, List<McpPromptMeta>>,
            owners: Collection<String>
        ): Map<String, List<McpPromptMeta>> {
            val priority = owners.filter { it.isNotBlank() }.distinct()
            val byName = LinkedHashMap<String, List<McpPromptMeta>>()
            entries.entries
                .sortedBy { priority.indexOf(it.key.substringBefore(":")).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE }
                .forEach { (k, metas) -> byName.putIfAbsent(k.substringAfter(":"), metas) }
            return byName
        }
    }

    private val clients = ConcurrentHashMap<String, McpAsyncClient>()
    private val statuses = ConcurrentHashMap<String, McpServerStatus>()
    private val toolCache = ConcurrentHashMap<String, List<McpSchema.Tool>>()
    private val promptCache = ConcurrentHashMap<String, List<McpSchema.Prompt>>()
    private val connectLocks = ConcurrentHashMap<String, Mutex>()

    /** Tracks users whose MCP servers have been lazily initialized. */
    private val initializedUsers = ConcurrentHashMap.newKeySet<String>()

    /** Per-user mutex to prevent concurrent lazy initialization races. */
    private val userInitLocks = ConcurrentHashMap<String, Mutex>()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Build composite key for per-user-per-server isolation. */
    private fun key(userId: String, serverName: String) = "$userId:$serverName"

    override fun afterSingletonsInstantiated() {
        scope.launch {
            try {
                val configs = mcpServerStore.findAllEnabled(SYSTEM_USER_ID)
                if (configs.isEmpty()) {
                    logger.info("No system-level MCP servers to initialize")
                    return@launch
                }
                logger.info("Initializing {} system-level MCP server connections", configs.size)
                for (config in configs) {
                    connect(config, SYSTEM_USER_ID)
                }
                initializedUsers.add(SYSTEM_USER_ID)
            } catch (e: Exception) {
                logger.error("Failed to initialize system MCP servers", e)
            }
        }
    }

    /**
     * Ensures all enabled MCP servers for the given user are connected.
     * Called lazily on first access (e.g., when resolving tools for a chat session).
     * System-level servers are already connected at startup; this only connects user-specific ones.
     * Uses a per-user mutex to prevent concurrent initialization races.
     */
    suspend fun ensureUserConnected(userId: String) = ensureOwnersConnected(listOf(userId))

    /**
     * Ensures every owner's enabled MCP servers are connected. Owners are the request's visibility set
     * `{self, group, system}`; a group bucket is connected once and shared by every member (the per-owner
     * init guard means the first member to touch it pays the connect cost, the rest skip). System servers
     * are already connected at startup, so the `system` owner is a no-op here.
     */
    suspend fun ensureOwnersConnected(owners: Collection<String>) {
        for (owner in owners) {
            if (owner.isNotBlank()) ensureOwnerConnected(owner)
        }
    }

    private suspend fun ensureOwnerConnected(userId: String) {
        if (userId in initializedUsers) return
        val mutex = userInitLocks.computeIfAbsent(userId) { Mutex() }
        mutex.withLock {
            // Double-check after acquiring lock
            if (userId in initializedUsers) return
            try {
                val configs = mcpServerStore.findAllEnabled(userId)
                if (configs.isNotEmpty()) {
                    logger.info("Lazily initializing {} MCP server connections for user '{}'", configs.size, userId)
                    for (config in configs) {
                        connect(config, userId)
                    }
                }
                initializedUsers.add(userId)
                logger.debug("MCP lazy initialization complete for user '{}'", userId)
            } catch (e: Exception) {
                logger.error("Failed to lazily initialize MCP servers for user '{}'", userId, e)
                // Mark as initialized even on failure to avoid repeated attempts
                initializedUsers.add(userId)
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
            val msg = buildString {
                append(e.message ?: "Unknown error")
                generateSequence(e.cause) { it.cause }.forEachIndexed { i, cause ->
                    append(" <- [cause ").append(i + 1).append("] ")
                        .append(cause.javaClass.simpleName).append(": ").append(cause.message)
                }
            }
            logger.error("Failed to connect to MCP server '{}' (user={}): {}", config.name, userId, msg, e)
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
     *
     * The map is keyed by server name alone, so a name present in both buckets collapses to one
     * entry. Callers that need a specific row's tools use [getToolDefs] instead.
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
     * Returns the tools of one specific `(owner, name)` row, or an empty list when that row is not
     * connected. The tool cache is keyed `owner:name`, so this is the only lookup that cannot pick
     * up a same-named server from another bucket.
     */
    fun getToolDefs(owner: String, name: String): List<McpSchema.Tool> {
        val k = key(owner, name)
        if (statuses[k] != McpServerStatus.Connected) return emptyList()
        return toolCache[k] ?: emptyList()
    }

    /**
     * Returns connected servers with owner info for tool resolution, across the owner set.
     * Used by McpServerController to render one row per (owner, name) pair.
     */
    fun getConnectedServers(owners: Collection<String>): List<McpServerTools> {
        val ownerSet = owners.toSet()
        return toolCache
            .filter { (k, _) ->
                val owner = k.substringBefore(":")
                statuses[k] == McpServerStatus.Connected && owner in ownerSet
            }
            .map { (k, tools) ->
                McpServerTools(
                    userId = k.substringBefore(":"),
                    serverName = k.substringAfter(":"),
                    tools = tools
                )
            }
    }

    /** Single-owner form: the user's own servers plus the shared system ones. */
    fun getConnectedServers(requestUserId: String): List<McpServerTools> =
        getConnectedServers(listOf(requestUserId, SYSTEM_USER_ID))

    /**
     * Returns the connected servers an agent may actually call: [getConnectedServers] across the
     * whole visibility set, collapsed to one bucket per server name by [shadowByName]. This is what
     * [McpToolProvider] turns into tool definitions; the management listing keeps every row.
     */
    fun getVisibleServers(owners: Collection<String>): List<McpServerTools> =
        shadowByName(getConnectedServers(owners), owners)

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

    /**
     * Returns prompts of the connected servers visible to [owners], shadowed by name so a server
     * present in two buckets yields one entry (the highest-priority owner's).
     */
    override fun getAllPrompts(owners: Collection<String>): Map<String, List<McpPromptMeta>> {
        val ownerSet = owners.toSet()
        val visible = promptCache
            .filter { (k, _) ->
                statuses[k] == McpServerStatus.Connected && k.substringBefore(":") in ownerSet
            }
            .mapValues { (_, prompts) -> toMetas(prompts) }
        return shadowPrompts(visible, owners)
    }

    /** Single-owner form: the user's own servers plus the shared system ones. */
    fun getAllPrompts(userId: String): Map<String, List<McpPromptMeta>> =
        getAllPrompts(listOf(userId, SYSTEM_USER_ID))

    /**
     * Renders a prompt from whichever bucket in [owners] actually holds a client for [serverName],
     * in priority order. Previously this was pinned to `system`, so a user's own or their group's
     * prompt command always failed to expand.
     */
    override suspend fun getPrompt(
        serverName: String,
        promptName: String,
        args: Map<String, String>?,
        owners: Collection<String>
    ): String {
        val owner = owners.filter { it.isNotBlank() }.distinct()
            .firstOrNull { clients.containsKey(key(it, serverName)) }
            ?: throw IllegalStateException(
                "MCP server '$serverName' is not connected for the caller or any bucket shared with them"
            )
        return getPrompt(serverName, promptName, args, owner)
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

    private fun toMetas(prompts: List<McpSchema.Prompt>): List<McpPromptMeta> =
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
                // Split origin + path so the transport's default "/mcp" endpoint can't clobber a deep configured path.
                val uri = URI(url)
                val origin = "${uri.scheme}://${uri.authority}"
                val endpoint = url.removePrefix(origin).ifBlank { "/mcp" }
                val builder = HttpClientStreamableHttpTransport.builder(origin).endpoint(endpoint)
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
