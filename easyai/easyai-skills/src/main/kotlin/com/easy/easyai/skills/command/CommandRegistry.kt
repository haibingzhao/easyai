package com.easy.easyai.skills.command

import com.easy.easyai.skills.SkillInfo
import com.easy.easyai.skills.SkillRegistry

interface CommandRegistry {
    fun resolve(name: String): CommandInfo?
    fun all(): List<CommandInfo>
}

/**
 * Registry for SKILL and MCP commands.
 * Queries SkillRegistry and McpPromptProvider on every call — no caching,
 * so it always reflects the current state (MCP connections, dynamically added skills, etc.).
 *
 * USER commands are served from DB via AsyncUserCommandStore and are NOT part of this registry.
 * BUILTIN commands are exposed via [builtinHandlers] and included in [all] for autocomplete.
 */
class DefaultCommandRegistry(
    private val skillRegistry: SkillRegistry?,
    private val promptProvider: McpPromptProvider?,
    private val builtinHandlers: List<BuiltinCommandHandler> = emptyList(),
) : CommandRegistry {

    override fun resolve(name: String): CommandInfo? {
        // 1. Try skill by name
        skillRegistry?.get(name)?.let { return it.toCommand() }

        // 2. Try MCP "server:prompt" exact match
        if (name.contains(":")) {
            val (server, prompt) = name.split(":", limit = 2)
            val prompts = promptProvider?.getAllPrompts()?.get(server)
            prompts?.find { it.name == prompt }?.let {
                return it.toCommand(server)
            }
        }

        // 3. Try MCP prompt alias (short name without server prefix)
        promptProvider?.getAllPrompts()?.forEach { (serverName, prompts) ->
            prompts.find { it.name == name }?.let {
                return it.toCommand(serverName)
            }
        }

        return null
    }

    override fun all(): List<CommandInfo> {
        val skills = skillRegistry?.all()?.map { it.toCommand() } ?: emptyList()
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
        return (builtins + skills + mcp).sortedBy { it.name }
    }

    private fun SkillInfo.toCommand(): CommandInfo = CommandInfo(
        name = name,
        description = description,
        template = content,
        category = CommandCategory.SKILL,
        source = location.toString(),
        hints = extractHints(content),
    )

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
