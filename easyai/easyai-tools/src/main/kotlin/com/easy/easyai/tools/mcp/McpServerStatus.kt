package com.easy.easyai.tools.mcp

/**
 * Runtime status of an MCP server connection.
 */
sealed interface McpServerStatus {
    /** Successfully connected and tools are available. */
    data object Connected : McpServerStatus

    /** Server is disabled by the user. */
    data object Disabled : McpServerStatus

    /** Connection failed with an error message. */
    data class Failed(val error: String) : McpServerStatus

    /** Currently attempting to connect. */
    data object Connecting : McpServerStatus
}
