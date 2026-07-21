package com.easy.easyai.skills.command

import com.easy.easyai.core.command.AsyncUserCommandStore
import org.slf4j.LoggerFactory

class CommandService(
    private val registry: CommandRegistry,
    private val promptProvider: McpPromptProvider?,
    private val userCommandStore: AsyncUserCommandStore? = null,
    private val builtinHandlers: List<BuiltinCommandHandler> = emptyList(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val ZWSP = "\u200B"
        private val NUMBERED_PLACEHOLDER = Regex("\\$(\\d+)")
        /** Extract ASCII-only command name from text after slash (e.g., "goal目标..." → "goal") */
        private val COMMAND_NAME_PATTERN = Regex("^([a-zA-Z_]\\w*)")
    }

    suspend fun resolveAndExpand(
        message: String?,
        userId: String = "system",
        sessionId: String = ""
    ): CommandExpansion? {
        if (message.isNullOrBlank()) return null
        val cleaned = message.replace(ZWSP, "")
        if (!cleaned.startsWith("/")) return null
        val withoutSlash = cleaned.substring(1)
        // Extract ASCII-only command name to handle "/goal中文..." (no space) correctly
        val nameMatch = COMMAND_NAME_PATTERN.find(withoutSlash)
        val cmdName = nameMatch?.value ?: withoutSlash.substringBefore(" ")
        val cmdArgs = if (nameMatch != null) {
            withoutSlash.substring(nameMatch.range.last + 1).trimStart()
        } else {
            withoutSlash.substringAfter(" ", "")
        }
        if (cmdName.isEmpty()) return null

        // 1. Try BUILTIN commands first (framework features, highest priority)
        builtinHandlers.find { it.name == cmdName }?.let { handler ->
            if (sessionId.isNotBlank()) {
                handler.execute(sessionId, cmdArgs, userId)?.let { return it }
            }
        }

        // 2. Try USER commands from DB
        val userCmd = userCommandStore?.findByName(cmdName, userId)
        if (userCmd != null) {
            logger.debug("Resolved USER command '{}' from DB (userId={})", cmdName, userId)
            val expanded = renderTemplate(userCmd.template, cmdArgs)
            return CommandExpansion(commandName = userCmd.name, expandedPrompt = expanded)
        }

        // 3. Fall back to SKILL/MCP commands from registry
        val cmd = registry.resolve(cmdName) ?: return null
        logger.debug("Resolved command '{}' (category={}) with args='{}'", cmd.name, cmd.category, cmdArgs)
        val expanded = when (cmd.category) {
            CommandCategory.MCP -> fetchMcpTemplate(cmd, cmdArgs)
            else -> renderTemplate(cmd.template, cmdArgs)
        }
        return CommandExpansion(
            commandName = cmd.name,
            expandedPrompt = expanded,
        )
    }

    private suspend fun fetchMcpTemplate(cmd: CommandInfo, args: String): String {
        val serverName = cmd.mcpServer ?: return ""
        val promptName = cmd.mcpPromptName ?: return ""
        val provider = promptProvider
        if (provider == null) {
            logger.warn("MCP prompt provider not available for command '{}'", cmd.name)
            return ""
        }
        val mcpArgs = buildMcpArgs(cmd, args)
        return try {
            provider.getPrompt(serverName, promptName, mcpArgs)
        } catch (e: Exception) {
            logger.error("Failed to fetch MCP prompt '{}:{}': {}", serverName, promptName, e.message)
            "[Error: Failed to expand MCP command '${cmd.name}': ${e.message}]"
        }
    }

    private fun buildMcpArgs(cmd: CommandInfo, args: String): Map<String, String> {
        if (args.isBlank() || cmd.mcpArguments.isEmpty()) return emptyMap()
        val parts = args.split(Regex("\\s+"))
        return cmd.mcpArguments.take(parts.size).mapIndexed { index, argMeta ->
            argMeta.name to parts[index]
        }.toMap()
    }

    private fun renderTemplate(template: String, arguments: String): String {
        if (template.isEmpty()) return arguments.ifBlank { "" }
        val parts = if (arguments.isBlank()) emptyList() else arguments.split(Regex("\\s+"))
        val hasNumbered = NUMBERED_PLACEHOLDER.containsMatchIn(template)
        val hasArguments = template.contains("\$ARGUMENTS")
        var rendered = template
        if (hasNumbered) {
            val lastNumbered = NUMBERED_PLACEHOLDER.findAll(template)
                .map { it.groupValues[1].toIntOrNull() ?: 0 }
                .maxOrNull() ?: 0
            rendered = NUMBERED_PLACEHOLDER.replace(rendered) { match ->
                val idx = (match.groupValues[1].toIntOrNull() ?: 1) - 1
                if (idx + 1 == lastNumbered) {
                    parts.drop(idx).joinToString(" ")
                } else {
                    parts.getOrElse(idx) { "" }
                }
            }
        }
        if (hasArguments) {
            rendered = rendered.replace("\$ARGUMENTS", arguments)
        }
        if (!hasNumbered && !hasArguments && arguments.isNotBlank()) {
            rendered = "$rendered\n\n$arguments"
        }
        return rendered.trim()
    }
}
