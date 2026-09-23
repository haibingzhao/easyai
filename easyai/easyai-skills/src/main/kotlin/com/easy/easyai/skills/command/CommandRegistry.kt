package com.easy.easyai.skills.command

interface CommandRegistry {
    fun resolve(name: String): CommandInfo?
    fun all(): List<CommandInfo>
}

/**
 * Registry for MCP commands.
 * Queries McpPromptProvider on every call — no caching, so it always reflects the current
 * MCP connections.
 *
 * SKILL commands are user/project-scoped and served by CommandService.listSkillCommands, never
 * from this registry: a registry-wide snapshot would advertise other owners' and projects' skills.
 * USER commands are served from DB via AsyncUserCommandStore and are NOT part of this registry.
 * BUILTIN commands are exposed via [builtinHandlers] and included in [all] for autocomplete.
 */
class DefaultCommandRegistry(
    private val promptProvider: McpPromptProvider?,
    private val builtinHandlers: List<BuiltinCommandHandler> = emptyList(),
) : CommandRegistry {

    override fun resolve(name: String): CommandInfo? {
        // Skills require user/project resolution in CommandService, never a global fallback.
        // 1. Try MCP "server:prompt" exact match
        if (name.contains(":")) {
            val (server, prompt) = name.split(":", limit = 2)
            val prompts = promptProvider?.getAllPrompts()?.get(server)
            prompts?.find { it.name == prompt }?.let {
                return it.toCommand(server)
            }
        }

        // 2. Try MCP prompt alias (short name without server prefix)
        promptProvider?.getAllPrompts()?.forEach { (serverName, prompts) ->
            prompts.find { it.name == name }?.let {
                return it.toCommand(serverName)
            }
        }

        return null
    }

    override fun all(): List<CommandInfo> {
        val mcp = promptProvider?.getAllPrompts()?.flatMap { (server, prompts) ->
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
