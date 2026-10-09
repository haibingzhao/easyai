package com.easy.easyai.skills.command

interface CommandRegistry {
    fun resolve(name: String, owners: Collection<String>): CommandInfo?
    fun all(owners: Collection<String>): List<CommandInfo>
}

/**
 * Registry for MCP commands.
 * Queries McpPromptProvider on every call — no caching, so it always reflects the current
 * MCP connections.
 *
 * Both entry points take the caller's owner visibility set (self → group → system) and pass it
 * straight through: MCP prompts are cached per owning bucket, and an unscoped lookup would offer
 * one user another user's servers.
 *
 * SKILL commands are owner-scoped and served by CommandService.listSkillCommands, never from
 * this registry: a registry-wide snapshot would advertise other owners' skills.
 * USER commands are served from DB via AsyncUserCommandStore and are NOT part of this registry.
 * BUILTIN commands are exposed via [builtinHandlers] and included in [all] for autocomplete.
 */
class DefaultCommandRegistry(
    private val promptProvider: McpPromptProvider?,
    private val builtinHandlers: List<BuiltinCommandHandler> = emptyList(),
) : CommandRegistry {

    override fun resolve(name: String, owners: Collection<String>): CommandInfo? {
        // Skills require owner resolution in CommandService, never a registry-wide fallback.
        val available = promptProvider?.getAllPrompts(owners) ?: return null
        // 1. Try MCP "server:prompt" exact match
        if (name.contains(":")) {
            val (server, prompt) = name.split(":", limit = 2)
            available[server]?.find { it.name == prompt }?.let {
                return it.toCommand(server)
            }
        }

        // 2. Try MCP prompt alias (short name without server prefix)
        available.forEach { (serverName, prompts) ->
            prompts.find { it.name == name }?.let {
                return it.toCommand(serverName)
            }
        }

        return null
    }

    override fun all(owners: Collection<String>): List<CommandInfo> {
        val mcp = promptProvider?.getAllPrompts(owners)?.flatMap { (server, prompts) ->
            prompts.map { it.toCommand(server) }
        } ?: emptyList()
        val builtins = builtinHandlers.map { handler ->
            CommandInfo(
                name = handler.name,
                description = handler.description,
                category = CommandCategory.BUILTIN,
                source = "builtin",
                hints = handler.hints,
            )
        }
        return (builtins + mcp).sortedBy { it.name }
    }

    private fun McpPromptMeta.toCommand(serverName: String): CommandInfo {
        val cmdHints = arguments.mapIndexed { i, _ -> "\$${i + 1}" }
        return CommandInfo(
            name = "$serverName:$name",
            aliases = listOf(name),
            description = description,
            template = "",
            category = CommandCategory.MCP,
            source = "mcp:$serverName",
            hints = cmdHints,
            mcpServer = serverName,
            mcpPromptName = name,
            mcpArguments = arguments,
        )
    }
}
