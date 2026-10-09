package com.easy.easyai.skills.command

import com.easy.easyai.core.command.AsyncUserCommandStore
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.UserMessage
import com.easy.easyai.skills.ScopedSkill
import com.easy.easyai.skills.SkillAccessResolver
import com.easy.easyai.skills.SkillInfo
import com.easy.easyai.skills.SkillLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

class CommandService(
    private val registry: CommandRegistry,
    private val promptProvider: McpPromptProvider?,
    private val userCommandStore: AsyncUserCommandStore? = null,
    private val builtinHandlers: List<BuiltinCommandHandler> = emptyList(),
    private val skillAccessResolver: SkillAccessResolver? = null,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private val NUMBERED_PLACEHOLDER = Regex("\\$(\\d+)")
    }

    /** Skill-derived slash commands, addressed by name; the access resolver already shadowed owners. */
    suspend fun listSkillCommands(userId: String, owners: Collection<String> = listOf(userId)): List<CommandInfo> =
        enabledSkills(owners).map { candidate ->
            val skill = candidate.skill
            CommandInfo(
                name = skill.name, description = skill.description, category = CommandCategory.SKILL,
                source = skill.name, hints = extractHints(skill.content), shared = candidate.shared
            )
        }

    suspend fun resolveAndExpand(
        message: String?,
        userId: String = "system",
        sessionId: String = "",
        allowSideEffects: Boolean = true,
        owners: Collection<String> = listOf(userId)
    ): CommandExpansion? {
        val parsed = message?.let(CommandUtils::parse) ?: return null
        if (parsed.source != null) {
            val skill = resolveByName(parsed.source, owners)
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
        enabledSkills(owners)
            .firstOrNull { it.skill.name == parsed.name }?.let { return expandSkill(it.skill, parsed.arguments) }
        val cmd = registry.resolve(parsed.name, owners)?.takeIf { it.category == CommandCategory.MCP } ?: return null
        return CommandExpansion(cmd.name, fetchMcpTemplate(cmd, parsed.arguments, owners), cmd.category, cmd.source)
    }

    private suspend fun resolveByName(name: String, owners: Collection<String>): SkillInfo =
        enabledSkills(owners).firstOrNull { it.skill.name == name }?.skill
            ?: throw CommandReferenceException("Skill '$name' is not available for the current user")

    /**
     * Skills the menu may offer and an expansion may run: shadowing is the resolver's job, but a
     * disabled row is off for every entry point, not just the model's. Replay validation stays on
     * the unfiltered list — a saved message must not fail to load because its skill was disabled.
     * Resolved over the caller's full owner set so a member can run their group's skills.
     */
    private suspend fun enabledSkills(owners: Collection<String>): List<ScopedSkill> =
        skillAccessResolver?.listScopedSkillsForOwners(owners).orEmpty().filter { it.catalogEntry?.enabled != false }

    private suspend fun expandSkill(skill: SkillInfo, arguments: String): CommandExpansion {
        val current = try {
            withContext(Dispatchers.IO) { SkillLoader.parse(skill.location) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw CommandReferenceException("Skill source is missing, unreadable or invalid; refresh and select it again")
        }
        if (current.name != skill.name) throw CommandReferenceException("Skill identity changed; refresh and select it again")
        return CommandExpansion(current.name, renderTemplate(current.content, arguments), CommandCategory.SKILL, skill.name)
    }

    fun metadata(expansion: CommandExpansion?, userId: String): Map<String, String> {
        if (expansion == null) return emptyMap()
        return mapOf(
            UserMessage.COMMAND_NAME to expansion.commandName,
            UserMessage.COMMAND_EXPANSION to expansion.expandedPrompt,
            UserMessage.COMMAND_CATEGORY to expansion.commandCategory.name,
            UserMessage.COMMAND_SOURCE to expansion.commandSource,
            UserMessage.COMMAND_USER_ID to userId
        )
    }

    /**
     * Replays must still resolve the referenced skill for the same user. Skill commands pin only
     * the user and the skill name — content is re-read from the owner's installed directory.
     */
    suspend fun validateReplay(messages: List<EasyAiMessage>, userId: String, owners: Collection<String> = listOf(userId)) {
        val snapshots = messages.filterIsInstance<UserMessage>().filter {
            it.metadata[UserMessage.COMMAND_CATEGORY] == CommandCategory.SKILL.name &&
                !it.metadata[UserMessage.COMMAND_EXPANSION].isNullOrBlank() &&
                it.metadata["isCompactionSummary"] != "true"
        }
        if (snapshots.isEmpty()) return
        val available = skillAccessResolver?.listScopedSkillsForOwners(owners).orEmpty()
            .associateBy { it.skill.name }
        for (message in snapshots) {
            val metadata = message.metadata
            val name = metadata[UserMessage.COMMAND_NAME]
            if (metadata[UserMessage.COMMAND_USER_ID] != userId || !available.containsKey(name)) {
                throw CommandReferenceException("Saved Skill '$name' is no longer available for the current user")
            }
        }
    }

    /**
     * Renders an MCP prompt through whichever bucket in [owners] actually serves the server. Passing
     * the visibility set (rather than a bare user id) is what lets a member expand a group-shared
     * server's prompt; the provider still refuses a server nobody in the set is connected to.
     */
    private suspend fun fetchMcpTemplate(cmd: CommandInfo, args: String, owners: Collection<String>): String {
        val serverName = cmd.mcpServer ?: return ""
        val promptName = cmd.mcpPromptName ?: return ""
        val provider = promptProvider
        if (provider == null) {
            logger.warn("MCP prompt provider not available for command '{}'", cmd.name)
            return ""
        }
        val mcpArgs = buildMcpArgs(cmd, args)
        return try {
            provider.getPrompt(serverName, promptName, mcpArgs, owners)
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
        val hasArguments = template.contains($$"$ARGUMENTS")
        var rendered = template
        if (hasNumbered) {
            val lastNumbered =
                NUMBERED_PLACEHOLDER.findAll(template).maxOfOrNull { it.groupValues[1].toIntOrNull() ?: 0 } ?: 0
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
            rendered = rendered.replace($$"$ARGUMENTS", arguments)
        }
        if (!hasNumbered && !hasArguments && arguments.isNotBlank()) {
            rendered = "$rendered\n\n$arguments"
        }
        return rendered.trim()
    }
}
