package com.easy.easyai.skills.command

/**
 * Abstraction over MCP Prompt resources.
 * Implemented by `McpClientManager` in easyai-tools to avoid circular dependency.
 *
 * Both methods take the caller's owner visibility set (self → group → system): prompts are cached
 * per owning bucket, so an unscoped listing would advertise one user's servers to every other user.
 */
interface McpPromptProvider {

    /**
     * Returns prompts from the connected MCP servers visible to [owners].
     * Map key is the server name, value is the list of prompt metadata. A name held by several
     * visible buckets resolves to the highest-priority owner's copy only.
     */
    fun getAllPrompts(owners: Collection<String>): Map<String, List<McpPromptMeta>>

    /**
     * Fetches and renders a specific MCP prompt with the given arguments, from whichever visible
     * bucket actually serves [serverName].
     * @return the rendered prompt text (joined message content).
     */
    suspend fun getPrompt(
        serverName: String,
        promptName: String,
        args: Map<String, String>?,
        owners: Collection<String>
    ): String
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

