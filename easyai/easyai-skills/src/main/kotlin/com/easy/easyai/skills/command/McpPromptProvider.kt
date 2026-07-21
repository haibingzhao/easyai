package com.easy.easyai.skills.command

/**
 * Abstraction over MCP Prompt resources.
 * Implemented by `McpClientManager` in easyai-tools to avoid circular dependency.
 */
interface McpPromptProvider {

    /**
     * Returns all prompts from connected MCP servers.
     * Map key is the server name, value is the list of prompt metadata.
     */
    fun getAllPrompts(): Map<String, List<McpPromptMeta>>

    /**
     * Fetches and renders a specific MCP prompt with the given arguments.
     * @return the rendered prompt text (joined message content).
     */
    suspend fun getPrompt(serverName: String, promptName: String, args: Map<String, String>?): String
}

/**
 * Lightweight prompt metadata extracted from MCP SDK's `McpSchema.Prompt`.
 * Avoids leaking MCP SDK types into easyai-skills.
 */
data class McpPromptMeta(
    val name: String,
    val description: String? = null,
    val arguments: List<McpPromptArgument> = emptyList(),
)

/**
 * Single argument of an MCP prompt.
 */
data class McpPromptArgument(
    val name: String,
    val description: String? = null,
    val required: Boolean = false,
)

