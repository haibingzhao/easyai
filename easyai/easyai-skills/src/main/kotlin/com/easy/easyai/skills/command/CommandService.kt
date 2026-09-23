package com.easy.easyai.skills.command

import com.easy.easyai.core.command.AsyncUserCommandStore
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.UserMessage
import com.easy.easyai.skills.SkillAccessResolver
import com.easy.easyai.skills.SkillConfig
import com.easy.easyai.skills.SkillInfo
import com.easy.easyai.skills.SkillLoader
import com.easy.easyai.skills.SkillPaths
import com.easy.easyai.skills.SkillScopeResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Path

class CommandService(
    private val registry: CommandRegistry,
    private val promptProvider: McpPromptProvider?,
    private val userCommandStore: AsyncUserCommandStore? = null,
    private val builtinHandlers: List<BuiltinCommandHandler> = emptyList(),
    private val skillAccessResolver: SkillAccessResolver? = null,
    private val skillConfig: SkillConfig = SkillConfig(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private val NUMBERED_PLACEHOLDER = Regex("\\$(\\d+)")
    }

    suspend fun listSkillCommands(userId: String, projectPath: Path?): List<CommandInfo> =
        skillAccessResolver?.listScopedSkills(userId, projectPath).orEmpty().map { candidate ->
            val skill = candidate.skill
            val (scope, project) = SkillScopeResolver.resolve(skill, skillConfig)
            CommandInfo(
                name = skill.name, description = skill.description, category = CommandCategory.SKILL,
                source = SkillPaths.canonicalize(skill.location), hints = extractHints(skill.content),
                scope = scope.name, projectPath = project?.toString()
            )
        }

    suspend fun resolveAndExpand(
        message: String?,
        userId: String = "system",
        sessionId: String = "",
        projectPath: Path? = null,
        allowSideEffects: Boolean = true
    ): CommandExpansion? {
        val parsed = message?.let(CommandUtils::parse) ?: return null
        if (parsed.source != null) {
            val skill = resolveSource(parsed.source, userId, projectPath)
            return expandSkill(skill, parsed.arguments)
        }
        builtinHandlers.find { it.name == parsed.name }?.let { handler ->
            if (!allowSideEffects) throw CommandReferenceException("Queued built-in commands cannot be edited; use Goal management instead")
            return if (sessionId.isBlank()) null else handler.execute(sessionId, parsed.arguments, userId)?.copy(
                commandCategory = CommandCategory.BUILTIN, commandSource = "builtin"
            )
        }
        val userCmd = userCommandStore?.findByName(parsed.name, userId)
        if (userCmd != null) {
            return CommandExpansion(userCmd.name, renderTemplate(userCmd.template, parsed.arguments), CommandCategory.USER, "db:${userCmd.id}")
        }
        val candidates = skillAccessResolver?.listScopedSkills(userId, projectPath).orEmpty()
            .filter { it.skill.name == parsed.name }
        if (candidates.size > 1) throw CommandReferenceException("Ambiguous Skill name; select its source from the command menu")
        candidates.singleOrNull()?.let { return expandSkill(it.skill, parsed.arguments) }
        val cmd = registry.resolve(parsed.name)?.takeIf { it.category == CommandCategory.MCP } ?: return null
        return CommandExpansion(cmd.name, fetchMcpTemplate(cmd, parsed.arguments), cmd.category, cmd.source)
    }

    private suspend fun resolveSource(source: String, userId: String, projectPath: Path?): SkillInfo {
        val canonical = SkillPaths.canonicalize(Path.of(source))
        return skillAccessResolver?.listScopedSkills(userId, projectPath).orEmpty()
            .firstOrNull { SkillPaths.canonicalize(it.skill.location) == canonical }?.skill
            ?: throw CommandReferenceException("Skill source is not available in the current user/project scope")
    }

    private suspend fun expandSkill(skill: SkillInfo, arguments: String): CommandExpansion {
        val current = try {
            withContext(Dispatchers.IO) { SkillLoader.parse(skill.location) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw CommandReferenceException("Skill source is missing, unreadable or invalid; refresh and select it again")
        }
        if (current.name != skill.name) throw CommandReferenceException("Skill identity changed; refresh and select it again")
        return CommandExpansion(current.name, renderTemplate(current.content, arguments), CommandCategory.SKILL, SkillPaths.canonicalize(skill.location))
    }

    fun metadata(expansion: CommandExpansion?, userId: String, projectPath: Path?): Map<String, String> {
        if (expansion == null) return emptyMap()
        return mapOf(
            UserMessage.COMMAND_NAME to expansion.commandName,
            UserMessage.COMMAND_EXPANSION to expansion.expandedPrompt,
            UserMessage.COMMAND_CATEGORY to expansion.commandCategory.name,
            UserMessage.COMMAND_SOURCE to expansion.commandSource,
            UserMessage.COMMAND_USER_ID to userId,
            UserMessage.COMMAND_PROJECT_PATH to (projectPath?.let(SkillPaths::canonicalize) ?: "")
        )
    }

    suspend fun validateReplay(messages: List<EasyAiMessage>, userId: String, projectPath: Path?) {
        val snapshots = messages.filterIsInstance<UserMessage>().filter {
            it.metadata[UserMessage.COMMAND_CATEGORY] == CommandCategory.SKILL.name &&
                !it.metadata[UserMessage.COMMAND_EXPANSION].isNullOrBlank() &&
                it.metadata["isCompactionSummary"] != "true"
        }
        if (snapshots.isEmpty()) return
        val sources = skillAccessResolver?.listScopedSkills(userId, projectPath).orEmpty()
            .associateBy { SkillPaths.canonicalize(it.skill.location) }
        for (message in snapshots) {
            val metadata = message.metadata
            val source = metadata[UserMessage.COMMAND_SOURCE]
            if (metadata[UserMessage.COMMAND_USER_ID] != userId ||
                metadata[UserMessage.COMMAND_PROJECT_PATH] != (projectPath?.let(SkillPaths::canonicalize) ?: "") ||
                sources[source]?.skill?.name != metadata[UserMessage.COMMAND_NAME]) {
                throw CommandReferenceException("Saved Skill source is no longer available in the current user/project scope")
            }
        }
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
        } catch (e: CancellationException) {
            throw e
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
