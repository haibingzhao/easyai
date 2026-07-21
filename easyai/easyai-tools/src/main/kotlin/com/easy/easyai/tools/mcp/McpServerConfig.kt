package com.easy.easyai.tools.mcp

/**
 * MCP server configuration data class.
 * type = "local"  → runs a local process via stdio transport
 * type = "remote" → connects to a remote HTTP/SSE endpoint
 */
data class McpServerConfig(
    val id: String,
    val name: String,
    val type: String,                                    // "local" | "remote"
    val command: List<String>? = null,                   // e.g. ["npx", "-y", "@modelcontextprotocol/server-github"]
    val env: Map<String, String> = emptyMap(),
    val url: String? = null,                             // remote only
    val headers: Map<String, String> = emptyMap(),       // remote only
    val cwd: String? = null,                             // working directory for local servers
    val timeoutSeconds: Long = 120L,                       // request timeout in seconds (default 120s)
    val enabled: Boolean = true,
    val userId: String = "system",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
