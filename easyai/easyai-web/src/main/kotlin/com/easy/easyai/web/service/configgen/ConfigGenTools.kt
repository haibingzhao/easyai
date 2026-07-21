package com.easy.easyai.web.service.configgen

import com.easy.easyai.agent.registry.ToolRegistry
import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AsyncAgentStore
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import com.easy.easyai.skills.SkillRegistry
import com.easy.easyai.tools.mcp.McpClientManager
import com.easy.easyai.web.service.ConfigValidator
import kotlinx.coroutines.CoroutineScope
import tools.jackson.databind.JsonNode

// ============================================================================
// Tool 1: validate_config
// ============================================================================

/**
 * Validates a generated configuration using the existing [ConfigValidator].
 * Returns structured validation results so the Agent can self-correct.
 */
class ValidateConfigTool(
    private val configValidator: ConfigValidator,
    private val configType: String,
    private val userId: String
) : BaseToolDefinition(
    ToolMetadata(
        name = "validate_config",
        description = """
            Validate a generated JSON configuration against the schema and business rules.
            Call this tool after generating a configuration to verify correctness.
            If validation fails, fix the errors and validate again.
            Input: { "config": <the JSON configuration object> }
            Output: validation result with valid=true/false and list of errors.
        """.trimIndent(),
        permissionCategory = "config_gen",
        isDefaultTool = false
    )
) {
    private val objectMapper = SharedObjectMapper.instance

    override fun parameterType(): Class<*> = ValidateConfigParameter::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        return try {
            val configObj = args["config"]
            if (configObj == null) {
                return errorResult("Missing 'config' parameter. Provide the JSON configuration to validate.")
            }

            val configNode: JsonNode = objectMapper.valueToTree(configObj)

            val result = when (configType) {
                "agent" -> configValidator.validateAgentConfig(configNode, userId)
                "swarm" -> configValidator.validateSwarmConfig(configNode, userId)
                else -> return errorResult("Unknown configType: $configType")
            }

            val output = buildString {
                if (result.valid) {
                    appendLine("✓ Validation PASSED")
                    if (result.errors.isNotEmpty()) {
                        appendLine("Warnings:")
                        result.errors.forEach { appendLine("  - [${it.field}] ${it.message}") }
                    }
                } else {
                    appendLine("✗ Validation FAILED")
                    appendLine("Errors:")
                    result.errors.filter { it.severity == "error" }.forEach {
                        appendLine("  - [${it.field}] ${it.message}")
                    }
                    if (result.errors.any { it.severity == "warning" }) {
                        appendLine("Warnings:")
                        result.errors.filter { it.severity == "warning" }.forEach {
                            appendLine("  - [${it.field}] ${it.message}")
                        }
                    }
                    appendLine()
                    appendLine("Fix the errors above and call validate_config again.")
                }
            }

            ToolResult(content = listOf(TextContent(output)))
        } catch (e: Exception) {
            errorResult("Validation error: ${e.message}")
        }
    }
}

/** Parameter class for validate_config tool schema generation. */
data class ValidateConfigParameter(
    val config: Map<String, Any?>
)

// ============================================================================
// Tool 2: list_resources
// ============================================================================

/**
 * Lists available resources by type (agents, tools, skills, MCP servers, models, spec).
 * Enables on-demand resource discovery instead of injecting everything into the prompt.
 */
class ListResourcesTool(
    private val toolRegistry: ToolRegistry,
    private val agentStore: AsyncAgentStore,
    private val skillRegistry: SkillRegistry?,
    private val mcpClientManager: McpClientManager?,
    private val modelConfigStore: ModelProviderConfigStore,
    private val userId: String,
    private val configType: String
) : BaseToolDefinition(
    ToolMetadata(
        name = "list_resources",
        description = """
            List available resources by type. Use this to discover what's available before generating config.
            Input: { "type": "<resource_type>" }
            Valid types:
            - "agents": List all available agent definitions (for agentDefinitionId references)
            - "tools": List built-in tools (for toolNames field)
            - "skills": List available skills (for skillNames field)
            - "mcp_servers": List MCP servers and their tools (for mcpConfigs field)
            - "models": List available model configurations
            - "spec": Get the full configuration specification document
        """.trimIndent(),
        permissionCategory = "config_gen",
        isDefaultTool = false
    )
) {
    override fun parameterType(): Class<*> = ListResourcesParameter::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val type = args["type"] as? String
            ?: return errorResult("Missing 'type' parameter. Valid types: agents, tools, skills, mcp_servers, models, spec")

        val output = when (type) {
            "agents" -> listAgents()
            "tools" -> listTools()
            "skills" -> listSkills()
            "mcp_servers" -> listMcpServers()
            "models" -> listModels()
            "spec" -> loadSpec()
            else -> return errorResult("Unknown type: '$type'. Valid types: agents, tools, skills, mcp_servers, models, spec")
        }

        return ToolResult(content = listOf(TextContent(output)))
    }

    private suspend fun listAgents(): String {
        val agents = agentStore.findAll(userId)
        if (agents.isEmpty()) return "No agents available."
        return buildString {
            appendLine("Available Agents (use these IDs for agentDefinitionId):")
            agents.forEach { agent ->
                appendLine("- **${agent.id}** (${agent.name}, type=${agent.agentType})")
                if (!agent.description.isNullOrBlank()) {
                    appendLine("  Description: ${agent.description}")
                }
            }
        }
    }

    private fun listTools(): String {
        val tools = toolRegistry.getAllTools()
        if (tools.isEmpty()) return "No built-in tools available."
        return buildString {
            appendLine("Built-in Tools (valid values for toolNames field):")
            tools.forEach { tool ->
                appendLine("- **${tool.name}**: ${tool.description}")
            }
        }
    }

    private fun listSkills(): String {
        val skills = skillRegistry?.all() ?: emptyList()
        if (skills.isEmpty()) return "No skills available."
        return buildString {
            appendLine("Available Skills (use via skillNames field, NOT toolNames):")
            skills.forEach { skill ->
                appendLine("- **${skill.name}**: ${skill.description ?: "No description"}")
            }
        }
    }

    private fun listMcpServers(): String {
        val servers = mcpClientManager?.getConnectedServers(userId) ?: emptyList()
        if (servers.isEmpty()) return "No MCP servers connected."
        return buildString {
            appendLine("MCP Servers (use via mcpConfigs field, NOT toolNames):")
            servers.forEach { server ->
                val toolNames = server.tools.joinToString(", ") { it.name() }
                appendLine("- **${server.serverName}**: tools=[$toolNames]")
            }
        }
    }

    private suspend fun listModels(): String {
        val models = modelConfigStore.getAllConfigs(userId)
        if (models.isEmpty()) return "No model configurations available."
        return buildString {
            appendLine("Available Models:")
            models.forEach { model ->
                appendLine("- **${model.id}** (${model.modelName ?: model.modelId}, ${model.protocol})")
            }
        }
    }

    private fun loadSpec(): String {
        val specPath = when (configType) {
            "swarm" -> "specs/swarm-spec.md"
            "agent" -> "specs/agent-spec.md"
            else -> return "Unknown configType: $configType"
        }
        val spec = javaClass.classLoader.getResource(specPath)?.readText(Charsets.UTF_8)
        return spec ?: "Specification file not found: $specPath"
    }
}

/** Parameter class for list_resources tool schema generation. */
data class ListResourcesParameter(
    val type: String
)

// ============================================================================
// Tool 3: submit_config
// ============================================================================

/**
 * Submits the final validated configuration, terminating the agent loop.
 */
class SubmitConfigTool : BaseToolDefinition(
    ToolMetadata(
        name = "submit_config",
        description = """
            Submit the final validated configuration. Call this when validation passes
            and you're confident the configuration is correct. This terminates the generation process.
            Input: { "config": <final JSON config>, "explanation": "<brief explanation of the design>" }
        """.trimIndent(),
        permissionCategory = "config_gen",
        isDefaultTool = false
    )
) {
    private val objectMapper = SharedObjectMapper.instance

    override fun parameterType(): Class<*> = SubmitConfigParameter::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val configObj = args["config"]
            ?: return errorResult("Missing 'config' parameter.")
        val explanation = args["explanation"] as? String ?: "Configuration generated successfully."

        val configJson = objectMapper.writeValueAsString(configObj)

        // Return with terminate=true to end the agent loop
        return ToolResult(
            content = listOf(TextContent("CONFIG_SUBMITTED: $configJson\nEXPLANATION: $explanation")),
            terminate = true
        )
    }
}

/** Parameter class for submit_config tool schema generation. */
data class SubmitConfigParameter(
    val config: Map<String, Any?>,
    val explanation: String
)
