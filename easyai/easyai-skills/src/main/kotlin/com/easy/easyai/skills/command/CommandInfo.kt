package com.easy.easyai.skills.command

data class CommandInfo(
    val id: String? = null,
    val name: String,
    val description: String? = null,
    val aliases: List<String> = emptyList(),
    val template: String = "",
    val category: CommandCategory,
    val source: String = "",
    val hints: List<String> = emptyList(),
    val mcpServer: String? = null,
    val mcpPromptName: String? = null,
    val mcpArguments: List<McpPromptArgument> = emptyList(),
)

enum class CommandCategory { USER, SKILL, MCP, BUILTIN }

data class CommandExpansion(
    val commandName: String,
    val expandedPrompt: String,
)
