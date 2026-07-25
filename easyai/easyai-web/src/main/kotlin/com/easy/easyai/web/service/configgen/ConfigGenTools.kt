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
import com.easy.easyai.web.model.ConfigValidationError
import com.easy.easyai.web.service.ConfigValidator
import kotlinx.coroutines.CoroutineScope
import tools.jackson.databind.JsonNode

/**
 * Formats validation errors into a human-readable string with errors and warnings sections.
 */
internal fun formatValidationErrors(errors: List<ConfigValidationError>): String = buildString {
    appendLine("Errors:")
    errors.filter { it.severity == "error" }.forEach {
        appendLine("  - [${it.field}] ${it.message}")
    }
    if (errors.any { it.severity == "warning" }) {
        appendLine("Warnings:")
        errors.filter { it.severity == "warning" }.forEach {
            appendLine("  - [${it.field}] ${it.message}")
        }
    }
}

/**
 * Tools that the swarm runtime does not support for worker agents:
 * - load_skill: skills are cleared in swarm context
 * - task: SubAgentTool is not created (parentAgentId recursion guard)
 * - run_swarm: mainAgentOnly tool, blocked for non-main agents
 */
internal val SWARM_UNSUPPORTED_TOOLS = setOf("load_skill", "task", "run_swarm")

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
                ?: return errorResult("Missing 'config' parameter. Provide the JSON configuration to validate.")

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
                    append(formatValidationErrors(result.errors))
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
 *
 * @param swarmContext When true, tools unsupported by the swarm runtime
 *   (load_skill, task, run_swarm) are excluded from the tools listing,
 *   and skills are reported as unavailable.
 */
class ListResourcesTool(
    private val toolRegistry: ToolRegistry,
    private val agentStore: AsyncAgentStore,
    private val skillRegistry: SkillRegistry?,
    private val mcpClientManager: McpClientManager?,
    private val modelConfigStore: ModelProviderConfigStore,
    private val userId: String,
    private val configType: String,
    private val swarmContext: Boolean = false
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
            .let { all -> if (swarmContext) all.filter { it.name !in SWARM_UNSUPPORTED_TOOLS } else all }
        if (tools.isEmpty()) return "No built-in tools available."
        return buildString {
            appendLine("Built-in Tools (valid values for toolNames field):")
            tools.forEach { tool ->
                appendLine("- **${tool.name}**: ${tool.description}")
            }
            if (swarmContext) {
                appendLine()
                appendLine("NOTE: load_skill, task, and run_swarm are NOT available in swarm runtime and must NOT be used in toolNames.")
            }
        }
    }

    private fun listSkills(): String {
        if (swarmContext) return "Skills are NOT available in swarm runtime. Do not use skillNames or load_skill."
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

// ============================================================================
// Tool 4: submit_config_block (chunked submission for swarm configs)
// ============================================================================

/**
 * Submits a single block of the swarm configuration for chunked generation.
 * Unlike [SubmitConfigTool], this does NOT terminate the agent loop,
 * allowing the LLM to submit multiple blocks incrementally.
 */
class SubmitConfigBlockTool : BaseToolDefinition(
    ToolMetadata(
        name = "submit_config_block",
        description = """
            Submit a single block of the swarm configuration. Call multiple times, once per logical section.
            Blocks are assembled by the system into the final configuration.

            Block types and their schemas:
            - "meta": {"name": string, "title": string, "description": string}
            - "agent": {"id": string, "agentDefinitionId": string (blank for inline), "name": string,
              "description": string, "role": string, "systemPrompt": string, "toolNames": string[],
              "mcpConfigs": [{"serverName": string, "toolNames": string[], "promptNames": string[]}],
              "modelName": string (optional), "maxIterations": int (optional, default 50)}
            - "task": {"id": string, "agentId": string (for SINGLE), "type": "SINGLE"|"TEAM"|"DELIBERATION",
              "promptTemplate": string, "dependsOn": string[], "inputFrom": {"varName": "upstreamTaskId"},
              "deliberation": {"participants": string[], "judge": string, "maxRounds": int} (required if type=DELIBERATION),
              "team": {"leader": string, "members": string[], "maxIterations": int} (required if type=TEAM)}
            - "variable": {"name": string, "description": string, "defaultValue": string}

            Input: {"blockType": "meta"|"agent"|"task"|"variable", "blockIndex": number, "data": object}
            - blockIndex: 0-based index within the block type (e.g., second agent = blockIndex 1)
            - data: the JSON object for this block (must match the schema above)
        """.trimIndent(),
        permissionCategory = "config_gen",
        isDefaultTool = false
    )
) {
    private val objectMapper = SharedObjectMapper.instance

    override fun parameterType(): Class<*> = SubmitConfigBlockParameter::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val blockType = args["blockType"] as? String
            ?: return errorResult("Missing 'blockType' parameter. Must be: meta, agent, task, or variable.")
        val blockIndex = (args["blockIndex"] as? Number)?.toInt()
            ?: return errorResult("Missing 'blockIndex' parameter. Use 0-based index.")
        if (blockIndex < 0) {
            return errorResult("'blockIndex' must be >= 0. Use 0-based index within the block type.")
        }
        val data = args["data"]
            ?: return errorResult("Missing 'data' parameter. Provide the block JSON object.")
        if (data !is Map<*, *>) {
            return errorResult("'data' must be a JSON object matching the block schema.")
        }

        val validTypes = setOf("meta", "agent", "task", "variable")
        if (blockType !in validTypes) {
            return errorResult("Invalid blockType: '$blockType'. Must be one of: $validTypes")
        }

        val dataJson = objectMapper.writeValueAsString(data)

        // Return confirmation without terminate — allows agent loop to continue
        return ToolResult(
            content = listOf(TextContent("BLOCK_RECEIVED: $blockType #$blockIndex | DATA: $dataJson"))
        )
    }
}

/** Parameter class for submit_config_block tool schema generation. */
data class SubmitConfigBlockParameter(
    val blockType: String,
    val blockIndex: Int,
    val data: Map<String, Any?>
)

// ============================================================================
// Tool 5: finalize_config (zero-config finalization for chunked swarm configs)
// ============================================================================

/**
 * Finalizes the swarm configuration assembled from submitted blocks.
 * The backend automatically assembles all submitted blocks (merged with the
 * existing config if editing), validates the result, and submits it.
 * This eliminates the need for the LLM to output the full config JSON again,
 * which is the root cause of stream stalls with large configurations.
 *
 * @param finalizeAction suspend lambda provided by the generator that performs
 *   assembly + validation + submission and returns a human/LLM-readable result.
 */
class FinalizeConfigTool(
    private val finalizeAction: suspend (String?) -> String
) : BaseToolDefinition(
    ToolMetadata(
        name = "finalize_config",
        description = """
            Finalize and submit the configuration. Call this AFTER submitting all blocks via submit_config_block.
            No configuration parameter needed — the system automatically assembles all submitted blocks
            (merged with the existing configuration if editing), validates the result, and submits it.
            Input: {"explanation": "<brief explanation of your design decisions>"}
            If validation fails, error details are returned — fix by re-submitting corrected blocks, then call finalize_config again.
        """.trimIndent(),
        permissionCategory = "config_gen",
        isDefaultTool = false
    )
) {
    override fun parameterType(): Class<*> = FinalizeConfigParameter::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val designExplanation = args["explanation"] as? String
        return ToolResult(content = listOf(TextContent(finalizeAction(designExplanation))))
    }
}

/** Parameter class for finalize_config tool schema generation. */
data class FinalizeConfigParameter(
    val explanation: String? = null
)
