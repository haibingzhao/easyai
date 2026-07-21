package com.easy.easyai.web.controller

import com.easy.easyai.core.agent.AgentToolConfig
import com.easy.easyai.core.agent.AsyncAgentStore
import com.easy.easyai.core.command.AsyncUserCommandStore
import com.easy.easyai.core.command.UserCommandDefinition
import com.easy.easyai.skills.command.CommandCategory
import com.easy.easyai.skills.command.CommandInfo
import com.easy.easyai.skills.command.CommandRegistry
import com.easy.easyai.web.security.getCurrentUserId
import com.fasterxml.jackson.annotation.JsonInclude
import kotlinx.coroutines.reactor.mono
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper
import com.easy.easyai.common.util.SharedObjectMapper

/**
 * REST controller for slash command queries.
 *
 * Merges USER commands from DB with SKILL/MCP commands from CommandRegistry.
 *
 * Endpoints:
 * - GET /api/commands - List available commands (optionally filtered by agentId)
 */
@RestController
@RequestMapping("/api/commands")
class CommandController(
    @param:Autowired(required = false)
    private val commandRegistry: CommandRegistry? = null,
    @param:Autowired(required = false)
    private val agentStore: AsyncAgentStore? = null,
    @param:Autowired(required = false)
    private val userCommandStore: AsyncUserCommandStore? = null,
) {

    private val objectMapper: ObjectMapper = SharedObjectMapper.instance

    @GetMapping
    fun listCommands(
        @RequestParam(required = false) agentId: String?
    ): Mono<List<CommandDto>> {
        return mono {
            val userId = getCurrentUserId()

            // Merge USER commands from DB + SKILL/MCP/BUILTIN from registry
            val userCommands = userCommandStore?.findAll(userId)?.map { it.toCommandInfo() } ?: emptyList()
            val registryCommands = commandRegistry?.all() ?: emptyList()
            val all = userCommands + registryCommands

            if (agentId == null || agentStore == null) {
                all.map { it.toDto() }
            } else {
                val allowedSkills = agentStore.getAgentSkillNames(agentId)
                val mcpConfigs = agentStore.getAgentMcpConfigs(agentId)
                val allowedCommands = agentStore.getAgentCommandNames(agentId)
                all.filter { cmd -> filterCommand(cmd, allowedSkills, mcpConfigs, allowedCommands) }
                    .map { it.toDto() }
            }
        }
    }

    private fun filterCommand(
        cmd: CommandInfo,
        allowedSkills: List<String>,
        mcpConfigs: List<AgentToolConfig>,
        allowedCommands: List<String>,
    ): Boolean = when (cmd.category) {
        // USER commands: whitelist mode - empty = none allowed
        CommandCategory.USER -> cmd.name in allowedCommands
        // SKILL commands: lenient - controlled independently via agent's skill bindings
        CommandCategory.SKILL -> allowedSkills.isEmpty() || cmd.name in allowedSkills
        // MCP commands: filter by agent's MCP bindings + promptNames whitelist
        CommandCategory.MCP -> filterByMcpConfig(cmd, mcpConfigs)
        // BUILTIN commands: whitelist mode - must be explicitly allowed
        CommandCategory.BUILTIN -> cmd.name in allowedCommands
    }

    private fun filterByMcpConfig(cmd: CommandInfo, mcpConfigs: List<AgentToolConfig>): Boolean {
        if (mcpConfigs.isEmpty()) return false // No bindings = none allowed
        val serverConfig = mcpConfigs.find { it.targetName == cmd.mcpServer } ?: return false
        val allowedPrompts = parsePromptNames(serverConfig.metadata)
        return allowedPrompts.isEmpty() || cmd.mcpPromptName in allowedPrompts
    }

    private fun parsePromptNames(metadata: String?): List<String> {
        if (metadata.isNullOrBlank()) return emptyList()
        return try {
            val node = objectMapper.readTree(metadata)
            if (node.isArray) {
                emptyList()
            } else {
                val promptNode = node.get("promptNames")
                if (promptNode != null && promptNode.isArray) {
                    val result = mutableListOf<String>()
                    for (el in promptNode) { result.add(el.asString()) }
                    result
                } else {
                    emptyList()
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun UserCommandDefinition.toCommandInfo() = CommandInfo(
        id = id,
        name = name,
        description = description,
        aliases = aliases,
        template = template,
        category = CommandCategory.USER,
        source = "db:$id",
        hints = hints,
    )

    private fun CommandInfo.toDto() = CommandDto(
        name = name,
        description = description,
        aliases = aliases,
        category = category.name,
        hints = hints,
    )
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CommandDto(
    val name: String,
    val description: String?,
    val aliases: List<String>,
    val category: String,
    val hints: List<String>,
)
