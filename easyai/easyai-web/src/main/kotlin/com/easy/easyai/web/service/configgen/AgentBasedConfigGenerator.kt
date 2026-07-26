package com.easy.easyai.web.service.configgen

import com.easy.easyai.agent.registry.ToolRegistry
import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.*
import com.easy.easyai.core.event.*
import com.easy.easyai.core.model.SystemMessage
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.UserMessage
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.skills.SkillRegistry
import com.easy.easyai.tools.mcp.McpClientManager
import com.easy.easyai.web.model.AiConfigGenerateRequest
import com.easy.easyai.web.model.ConfigValidationResult
import com.easy.easyai.web.service.ConfigValidator
import com.easy.easyai.web.service.validation.TemplateConsistencyValidator
import kotlinx.coroutines.flow.FlowCollector
import org.slf4j.LoggerFactory
import org.springframework.http.codec.ServerSentEvent
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * A single configuration block submitted via submit_config_block tool.
 */
internal data class ConfigBlock(
    val blockType: String,
    val blockIndex: Int,
    val data: JsonNode
)

/**
 * Agent-based configuration generator that uses AgentLoop for multi-step generation.
 *
 * Instead of a single LLM call, this generator:
 * 1. Provides tools for resource discovery (list_resources)
 * 2. Allows self-validation (validate_config) for agent configs
 * 3. Enables explicit submission (submit_config) for agent configs
 * 4. Supports chunked submission for swarm configs (submit_config_block + finalize_config)
 *    — the LLM never outputs the full config JSON as a tool parameter, preventing stream stalls
 *
 * The AgentLoop naturally handles the "generate → validate → fix" cycle.
 */
class AgentBasedConfigGenerator(
    private val agentService: AgentService,
    private val configValidator: ConfigValidator,
    private val toolRegistry: ToolRegistry,
    private val agentStore: AsyncAgentStore,
    private val skillRegistry: SkillRegistry?,
    private val mcpClientManager: McpClientManager?,
    private val modelConfigStore: ModelProviderConfigStore,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper = SharedObjectMapper.instance

    companion object {
        private const val MAX_ITERATIONS = 15
        private const val MAX_ITERATIONS_SWARM = 20
        private const val BATCH_SIZE = 20
    }

    /**
     * Generate configuration using AgentLoop with tools.
     * Emits SSE events compatible with the existing frontend protocol.
     */
    suspend fun generate(
        request: AiConfigGenerateRequest,
        userId: String,
        collector: FlowCollector<ServerSentEvent<String>>
    ) {
        logger.info("Starting agent-based config generation for type={}, user={}", request.configType, userId)

        // Mutable generation state (declared early so the finalize tool lambda can capture it)
        var submittedConfig: JsonNode? = null
        var explanation = "Configuration generated successfully."
        var submittedViaTool = false  // Whether config was finalized/submitted explicitly
        var validationResult: ConfigValidationResult? = null
        val configBlocks = mutableListOf<ConfigBlock>()  // Chunked block accumulation

        // Finalize action for chunked mode: assemble blocks + existingConfig → validate → submit.
        // This eliminates the need for the LLM to output the full config JSON as a tool parameter.
        val finalizeAction: suspend (String?) -> String = action@{ designExplanation ->
            if (configBlocks.isEmpty()) {
                return@action "FINALIZE_FAILED: No configuration blocks received yet. Submit blocks via submit_config_block first."
            }
            val assembled = assembleConfigFromBlocks(configBlocks, request.existingConfig, request.configType)
                ?: return@action "FINALIZE_FAILED: Could not assemble configuration from ${configBlocks.size} submitted blocks."
            val validation = try {
                if (request.configType == "swarm") {
                    configValidator.validateSwarmConfig(assembled, userId)
                } else {
                    configValidator.validateAgentConfig(assembled, userId)
                }
            } catch (e: Exception) {
                logger.warn("Validation during finalize failed: {}", e.message)
                null
            }
            if (validation != null && !validation.valid) {
                buildString {
                    appendLine("✗ VALIDATION FAILED")
                    append(formatValidationErrors(validation.errors))
                    appendLine("Fix the issues by re-submitting corrected blocks via submit_config_block, then call finalize_config again.")
                }
            } else {
                submittedConfig = assembled
                submittedViaTool = true
                validationResult = validation
                designExplanation?.let { explanation = it }
                logger.info("Config finalized from {} blocks", configBlocks.size)
                buildString {
                    appendLine("✓ CONFIG_FINALIZED: Configuration assembled from ${configBlocks.size} blocks and validated successfully.")
                    if (validation != null && validation.errors.isNotEmpty()) {
                        appendLine("Warnings:")
                        validation.errors.forEach { appendLine("  - [${it.field}] ${it.message}") }
                    }
                    appendLine("The configuration has been submitted. Output a brief summary to the user (do NOT call any more tools).")
                }
            }
        }

        // 1. Build specialized tools
        val tools = buildTools(request.configType, userId, finalizeAction)

        // 2. Resolve model config
        val modelConfig = resolveModelConfig(request.modelConfigId, userId)

        // 3. Build AgentContext (dry-run: no persistence, no default system prompt)
        val context = AgentContext(
            agentId = "config-generator",
            sessionId = null,  // No session persistence
            userId = userId,
            tools = tools,
            maxIterations = if (request.configType == "swarm") MAX_ITERATIONS_SWARM else MAX_ITERATIONS,
            modelConfig = modelConfig,
            promptTemplate = "",  // Suppress default system prompt; we provide our own in initialMessages
            dryRun = true,
        )

        // 4. Create dry-run AgentService (no persistence)
        val dryRunServices = DryRunAgentService(agentService)

        // 5. Create Agent and AgentRunner
        val agent = Agent(context = context, services = dryRunServices)
        val runner = AgentRunner(agent = agent, messages = mutableListOf())

        // 6. Build initial messages
        val systemPrompt = buildAgentSystemPrompt(request.configType)
        val userMessageText = buildAgentUserMessage(request)

        val initialMessages = listOf(
            SystemMessage(text = systemPrompt),
            UserMessage(content = listOf(TextContent(userMessageText)))
        )

        // 7. Execute and adapt SSE events
        collector.emit(sseEvent("stream_start", """{"mode":"agent"}"""))

        val stream = runner.prompt(initialMessages)
        val textBuffer = StringBuilder()
        val fullTextBuffer = StringBuilder()  // Full accumulation for fallback JSON extraction
        val thinkingBuffer = StringBuilder()
        var isThinkingPhase = false
        var hasError = false

        try {
            stream.asFlow().collect { event ->
                if (hasError) return@collect
                when (event) {
                    is ThinkingUpdateEvent -> {
                        // Flush text buffer before switching to thinking
                        if (textBuffer.isNotEmpty()) {
                            collector.emit(sseEvent("text_delta", encodeSseData(textBuffer.toString())))
                            textBuffer.clear()
                        }
                        if (!isThinkingPhase) {
                            isThinkingPhase = true
                        }
                        thinkingBuffer.append(event.delta)
                        if (thinkingBuffer.length >= BATCH_SIZE) {
                            collector.emit(sseEvent("thinking_delta", encodeSseData(thinkingBuffer.toString())))
                            thinkingBuffer.clear()
                        }
                    }

                    is ThinkingEndEvent -> {
                        if (isThinkingPhase) {
                            if (thinkingBuffer.isNotEmpty()) {
                                collector.emit(sseEvent("thinking_delta", encodeSseData(thinkingBuffer.toString())))
                                thinkingBuffer.clear()
                            }
                            collector.emit(sseEvent("thinking_end", ""))
                            isThinkingPhase = false
                        }
                    }

                    is MessageUpdateEvent -> {
                        // Flush thinking buffer before switching to text
                        if (isThinkingPhase) {
                            if (thinkingBuffer.isNotEmpty()) {
                                collector.emit(sseEvent("thinking_delta", encodeSseData(thinkingBuffer.toString())))
                                thinkingBuffer.clear()
                            }
                            collector.emit(sseEvent("thinking_end", ""))
                            isThinkingPhase = false
                        }
                        fullTextBuffer.append(event.delta)
                        textBuffer.append(event.delta)
                        if (textBuffer.length >= BATCH_SIZE) {
                            collector.emit(sseEvent("text_delta", encodeSseData(textBuffer.toString())))
                            textBuffer.clear()
                        }
                    }

                    is ToolExecutionStartEvent -> {
                        val statusMsg = when (event.toolName) {
                            "validate_config" -> "Validating configuration..."
                            "list_resources" -> "Fetching available ${event.args["type"] ?: "resources"}..."
                            "submit_config" -> "Submitting final configuration..."
                            "submit_config_block" -> "Generating ${event.args["blockType"] ?: "block"} #${event.args["blockIndex"] ?: 0}..."
                            "finalize_config" -> "Assembling and validating configuration..."
                            else -> "Executing ${event.toolName}..."
                        }
                        val payload = objectMapper.writeValueAsString(
                            mapOf("toolCallId" to event.toolCallId, "tool" to event.toolName, "status" to "running", "message" to statusMsg)
                        )
                        collector.emit(sseEvent("status_update", payload))
                    }

                    is ToolExecutionEndEvent -> {
                        // Check if this is submit_config with the final result
                        if (event.toolName == "submit_config" && !event.isError) {
                            val resultText = event.result.content
                                .filterIsInstance<TextContent>()
                                .joinToString("") { it.text }
                            parseSubmittedConfig(resultText)?.let { (config, expl) ->
                                submittedConfig = config
                                submittedViaTool = true
                                explanation = expl
                            }
                        }
                        // Handle chunked block submission
                        if (event.toolName == "submit_config_block" && !event.isError) {
                            val resultText = event.result.content
                                .filterIsInstance<TextContent>()
                                .joinToString("") { it.text }
                            parseConfigBlock(resultText)?.let { block ->
                                configBlocks.add(block)
                                // Emit config_block SSE event immediately
                                val chunkJson = objectMapper.writeValueAsString(
                                    mapOf("blockType" to block.blockType, "blockIndex" to block.blockIndex, "data" to block.data)
                                )
                                collector.emit(sseEvent("config_block", chunkJson))
                            }
                        }
                        val status = if (event.isError) "error" else "completed"
                        val payload = objectMapper.writeValueAsString(
                            mapOf("toolCallId" to event.toolCallId, "tool" to event.toolName, "status" to status)
                        )
                        collector.emit(sseEvent("status_update", payload))
                    }

                    is ErrorEvent -> {
                        logger.error("Agent error during config generation", event.error)
                        collector.emit(sseEvent("error", """{"message":"${escapeJson(event.error.message ?: "Unknown error")}"}"""))
                        hasError = true
                        return@collect
                    }

                    else -> {
                        // Ignore other events (TurnStart, TurnEnd, AgentStart, etc.)
                    }
                }
            }

            // Flush remaining buffers (skip if error occurred)
            if (!hasError) {
                if (isThinkingPhase && thinkingBuffer.isNotEmpty()) {
                    collector.emit(sseEvent("thinking_delta", encodeSseData(thinkingBuffer.toString())))
                    collector.emit(sseEvent("thinking_end", ""))
                }
                if (textBuffer.isNotEmpty()) {
                    collector.emit(sseEvent("text_delta", encodeSseData(textBuffer.toString())))
                }
            }

            // If error occurred, don't emit config_done (error event already sent)
            if (hasError) return

            // Fallback priority: blocks (structured) > text extraction (heuristic)
            if (submittedConfig == null && configBlocks.isNotEmpty()) {
                submittedConfig = assembleConfigFromBlocks(configBlocks, request.existingConfig, request.configType)
                logger.info("Assembled config from {} blocks", configBlocks.size)
            }
            if (submittedConfig == null) {
                submittedConfig = extractJsonFromText(fullTextBuffer.toString())
            }

            // Validate assembled/extracted config (fallback paths bypass validate_config tool)
            if (submittedConfig != null && !submittedViaTool) {
                validationResult = try {
                    when (request.configType) {
                        "swarm" -> configValidator.validateSwarmConfig(submittedConfig!!, userId)
                        "agent" -> configValidator.validateAgentConfig(submittedConfig!!, userId)
                        else -> null
                    }
                } catch (e: Exception) {
                    logger.warn("Post-assembly validation failed: {}", e.message)
                    null
                }
            }

            // Post-process: strip customInstructions if template doesn't reference it
            if (request.configType == "agent" && submittedConfig is ObjectNode) {
                val obj = submittedConfig as ObjectNode
                val promptTemplate = obj.get("promptTemplate")?.asString() ?: ""
                if (promptTemplate.isNotBlank() &&
                    !TemplateConsistencyValidator.referencesTemplateVariable(promptTemplate, "custom_instructions")) {
                    obj.remove("customInstructions")
                }
            }

            collector.emit(sseEvent("stream_end", ""))

            // Emit final result
            val resultJson = buildDoneJson(submittedConfig, explanation, validationResult)
            collector.emit(sseEvent("config_done", resultJson))

        } catch (e: Exception) {
            logger.error("Agent-based config generation failed", e)
            collector.emit(sseEvent("error", """{"message":"${escapeJson(e.message ?: "Generation failed")}"}"""))
        }
    }

    private fun buildTools(configType: String, userId: String, finalizeAction: suspend (String?) -> String): List<ToolDefinition> {
        val listResources = ListResourcesTool(
            toolRegistry, agentStore, skillRegistry, mcpClientManager, modelConfigStore, userId, configType,
            swarmContext = configType == "swarm"
        )
        // Both agent and swarm use chunked block mode to prevent stream stalls with large configs
        return listOf(listResources, SubmitConfigBlockTool(), FinalizeConfigTool(finalizeAction))
    }

    private suspend fun resolveModelConfig(modelConfigId: String?, userId: String): ModelProviderConfig? {
        if (modelConfigId == null) return null
        return try {
            modelConfigStore.getConfig(modelConfigId, userId)
        } catch (_: Exception) {
            logger.warn("Failed to resolve model config '{}', using default", modelConfigId)
            null
        }
    }

    private fun buildAgentSystemPrompt(configType: String): String {
        return when (configType) {
            "agent" -> {
                val specBrief = loadSpecResource("specs/agent-spec-brief.md")
                    ?: loadSpecResource("specs/agent-spec.md")
                    ?: ""
                """
You are an expert EasyAI configuration generator specializing in agent configurations.

## Your Workflow
1. Analyze the user's requirements carefully
2. Call `list_resources` to discover available tools, skills, MCP servers, and agents
3. Call `submit_config_block` with blockType="basic" for agent identity and prompt
4. Call `submit_config_block` with blockType="tools" for toolNames/skillNames/commandNames
5. If MCP servers are needed: call `submit_config_block` with blockType="mcp"
6. For EACH sub-agent: call `submit_config_block` with blockType="subagent" (one call per sub-agent)
7. For EACH team member: call `submit_config_block` with blockType="member" (one call per member)
8. Call `finalize_config` with a brief explanation — the system assembles, validates, and submits.
   If validation fails, fix by re-submitting corrected blocks, then call finalize_config again.
9. After finalize_config succeeds, output a brief summary to the user. Done.

CRITICAL RULES — NEVER output the full configuration JSON as a tool parameter:
- Each submit_config_block call contains ONLY ONE small section (~200-800 tokens)
- finalize_config takes ONLY an explanation string — assembly is automatic
- You do NOT have validate_config or submit_config tools — do not attempt to call them

## Block Schemas
- "basic": {"id": string, "name": string, "description": string, "agentType": "PRIMARY"|"SUBAGENT"|"TEAM"|"ALL",
  "agentContext": "CHAT"|"SWARM"|"BOTH", "promptTemplate": string (MUST include {{ custom_instructions }}),
  "customInstructions": string (optional), "maxIterations": int, "maxSubAgentDepth": int}
- "tools": {"toolNames": string[], "skillNames": string[], "commandNames": string[]}
- "mcp": {"mcpConfigs": [{"serverName": string, "toolNames": string[], "promptNames": string[]}]}
- "subagent": {"agentId": string} for global reference, OR {"name": string, "description": string,
  "systemPrompt": string, "toolNames": string[], "skillNames": string[], "mcpConfigs": [...]} for inline custom
- "member": same schema as "subagent"

## Critical Rules
- toolNames: ONLY built-in tools (from list_resources type="tools"); empty array = No tools
- MCP tools: via mcpConfigs field, NEVER in toolNames
- Skills: via skillNames field, NEVER in toolNames
- **load_skill requirement**: if skillNames is non-empty, `load_skill` MUST be included in toolNames (otherwise the agent cannot load skill content at runtime)
- **goal command requirement**: if commandNames includes "goal", `goal` MUST be included in toolNames (otherwise created goals cannot be completed/blocked, stalling the agent loop)
- promptTemplate: MUST be valid Jinja2 and include {{ custom_instructions }}
- subagent blocks: use {"agentId": "xxx"} for existing agents (from list_resources type="agents"),
  or provide full inline fields for custom sub-agents
- inputSchema: required when using {{ input.xxx }} in promptTemplate
- Minimalism: only include tools/skills/MCP the agent will actually use
- For TEAM agents: set agentType="TEAM" in basic block and provide member blocks
- **Agent-type tool restrictions**:
  - SUBAGENT: `task` and `run_swarm` are blocked at runtime (sub-agents cannot use them) — do NOT include in toolNames
  - TEAM: leaders coordinate members via auto-injected delegate_to_member/wait_for_member_events/resume_member; `task` is NOT usable (no sub-agent whitelist) — do NOT include in toolNames
- **SWARM context restriction**: when agentContext is SWARM, `load_skill`, `task`, and `run_swarm` are NOT available

## Configuration Specification
$specBrief
""".trimIndent()
            }
            "swarm" -> """
You are an expert EasyAI Swarm workflow configuration generator.

## Your Workflow
1. Analyze requirements and output a brief design overview (agents, tasks, data flow)
2. Call `list_resources` to discover available agents/tools/MCP servers
3. Call `submit_config_block` with blockType="meta" for name/title/description
4. For EACH agent, call `submit_config_block` with blockType="agent" (one call per agent)
5. For EACH task, call `submit_config_block` with blockType="task" (one call per task)
6. Optionally submit variables with blockType="variable"
7. Call `finalize_config` with a brief explanation — the system automatically assembles all
   your blocks (merged with the existing config if editing), validates, and submits.
   If validation fails, fix by re-submitting corrected blocks, then call finalize_config again.
8. After finalize_config succeeds, output a brief summary to the user. Done.

CRITICAL RULES — NEVER output the full configuration JSON as a tool parameter:
- Each submit_config_block call contains ONLY ONE small section (~200-800 tokens)
- finalize_config takes ONLY an explanation string — assembly is automatic
- You do NOT have validate_config or submit_config tools — do not attempt to call them
If you need the full specification, call `list_resources type="spec"`.

## Critical Rules
- Agents support two modes: Global (agentDefinitionId references existing agent) or Inline (agentDefinitionId blank, provide name/systemPrompt/toolNames/mcpConfigs)
- toolNames: ONLY built-in tools (from list_resources type="tools")
- **Swarm runtime restrictions**: `load_skill`, `task`, and `run_swarm` are NOT available in swarm runtime — NEVER include them in toolNames. Skills and Sub-Agents are not supported for swarm agents.
- MCP tools: via mcpConfigs field, NEVER in toolNames. Format: [{"serverName":"xxx","toolNames":["t1"],"promptNames":[]}]
- mcpConfigs.toolNames empty = all tools from that server allowed
- agentDefinitionId: MUST reference an existing agent for global mode (from list_resources type="agents")
- dependsOn: forms a valid DAG (no cycles)
- inputFrom: variable routing from upstream tasks
- Variables: ALWAYS define a required variable named `user_input` (description: "The user query or task description"). This is the standard entry point variable used by the runtime. Use `{{ user_input }}` in task prompt templates to reference the user's input. Do NOT invent alternative names like "userProblem", "query", "task_description" — always use `user_input`.
""".trimIndent()
            else -> buildGenericSystemPrompt(configType)
        }
    }

    private fun buildGenericSystemPrompt(configType: String): String {
        val specBrief = loadSpecResource("specs/${configType}-spec-brief.md")
            ?: loadSpecResource("specs/${configType}-spec.md")
            ?: ""
        return """
You are an expert EasyAI configuration generator.

## Your Workflow
1. Analyze the user's requirements carefully
2. Call `list_resources` to discover available resources
3. Generate the JSON configuration
4. Call `validate_config` to verify correctness
5. If validation fails, fix the errors and validate again
6. Call `submit_config` with the final validated configuration

## Configuration Specification
$specBrief

## Output Format
When calling submit_config, provide the complete JSON configuration and a brief explanation of your design decisions.
""".trimIndent()
    }

    private fun buildAgentUserMessage(request: AiConfigGenerateRequest): String {
        val sb = StringBuilder()

        sb.appendLine("## Requirements")
        sb.appendLine(request.description)
        sb.appendLine()

        // Add existing config context if editing
        if (request.existingConfig != null) {
            sb.appendLine("## Current Configuration (for reference/editing)")
            sb.appendLine("The user is editing an existing configuration. Preserve fields not mentioned in requirements.")
            sb.appendLine("<existing_config>")
            sb.appendLine(objectMapper.writeValueAsString(request.existingConfig))
            sb.appendLine("</existing_config>")
            sb.appendLine()

            // Partial edit instructions for swarm chunked mode
            if (request.configType == "swarm") {
                sb.appendLine("## Partial Edit Mode")
                sb.appendLine("Only submit blocks that need changes. Use blockIndex to identify which agent/task to update.")
                sb.appendLine("Unchanged sections will be preserved from the existing configuration.")
                sb.appendLine("Example: if only agent #2 needs modification, submit just one block:")
                sb.appendLine("submit_config_block(blockType=\"agent\", blockIndex=2, data={...updated agent...})")
                sb.appendLine("Then call finalize_config — the system merges your changes into the existing configuration automatically.")
                sb.appendLine()
            }
        }

        sb.appendLine("## Instructions")
        when (request.configType) {
            "agent" -> {
                sb.appendLine("1. Call `list_resources type=\"tools\"` to see available built-in tools")
                sb.appendLine("2. Call `list_resources type=\"skills\"` and `type=\"mcp_servers\"` if relevant")
                sb.appendLine("3. Call `list_resources type=\"agents\"` if sub-agents or team members are needed")
                sb.appendLine("4. Submit each section via `submit_config_block` (basic → tools → mcp → each subagent → each member)")
                sb.appendLine("5. Call `finalize_config` with a brief explanation — assembly, validation, and submission are automatic")
            }
            "swarm" -> {
                sb.appendLine("1. Call `list_resources type=\"agents\"` to see available agents")
                sb.appendLine("2. Call `list_resources type=\"mcp_servers\"` if MCP tools are needed")
                sb.appendLine("3. Submit each section via `submit_config_block` (meta → each agent → each task → variables)")
                sb.appendLine("4. Call `finalize_config` with a brief explanation — assembly, validation, and submission are automatic")
            }
            else -> {
                sb.appendLine("1. Call `list_resources` to discover available resources")
                sb.appendLine("2. Generate the JSON configuration")
                sb.appendLine("3. Call `validate_config` to verify correctness")
                sb.appendLine("4. Call `submit_config` with the final validated configuration")
            }
        }

        return sb.toString()
    }

    private fun loadSpecResource(path: String): String? {
        return javaClass.classLoader.getResource(path)?.readText(Charsets.UTF_8)
    }

    private fun parseSubmittedConfig(resultText: String): Pair<JsonNode, String>? {
        return try {
            val configMarker = "CONFIG_SUBMITTED: "
            val explanationMarker = "EXPLANATION: "

            val configStart = resultText.indexOf(configMarker)
            if (configStart < 0) return null

            val jsonStart = configStart + configMarker.length
            val explanationStart = resultText.indexOf(explanationMarker, jsonStart)

            val jsonStr = if (explanationStart > 0) {
                resultText.substring(jsonStart, explanationStart).trim()
            } else {
                resultText.substring(jsonStart).trim()
            }

            val expl = if (explanationStart > 0) {
                resultText.substring(explanationStart + explanationMarker.length).trim()
            } else {
                "Configuration generated successfully."
            }

            val configNode = objectMapper.readTree(jsonStr)
            configNode to expl
        } catch (e: Exception) {
            logger.warn("Failed to parse submitted config: {}", e.message)
            null
        }
    }

    private fun extractJsonFromText(text: String): JsonNode? {
        return try {
            // Try to find JSON object in the text
            val jsonStart = text.indexOf('{')
            val jsonEnd = text.lastIndexOf('}')
            if (jsonStart in 0..<jsonEnd) {
                val jsonStr = text.substring(jsonStart, jsonEnd + 1)
                objectMapper.readTree(jsonStr)
            } else {
                null
            }
        } catch (e: Exception) {
            logger.debug("Could not extract JSON from text output: {}", e.message)
            null
        }
    }

    /**
     * Parse a config block from the tool result text.
     * Expected format: "BLOCK_RECEIVED: agent #0 | DATA: {...}"
     */
    private fun parseConfigBlock(resultText: String): ConfigBlock? {
        return try {
            val marker = "BLOCK_RECEIVED: "
            val dataMarker = " | DATA: "
            val markerStart = resultText.indexOf(marker)
            if (markerStart < 0) return null

            val afterMarker = resultText.substring(markerStart + marker.length)
            val dataStart = afterMarker.indexOf(dataMarker)
            if (dataStart < 0) return null

            val header = afterMarker.substring(0, dataStart) // e.g. "agent #0"
            val dataJson = afterMarker.substring(dataStart + dataMarker.length).trim()

            val parts = header.split(" #")
            if (parts.size != 2) return null
            val blockType = parts[0].trim()
            val blockIndex = parts[1].trim().toIntOrNull() ?: return null

            val dataNode = objectMapper.readTree(dataJson)
            ConfigBlock(blockType = blockType, blockIndex = blockIndex, data = dataNode)
        } catch (e: Exception) {
            logger.warn("Failed to parse config block: {}", e.message)
            null
        }
    }

    /**
     * Assemble a complete config JSON from individual blocks.
     * When [existingConfig] is provided, uses it as the base and merges blocks
     * by their blockIndex (partial edit mode).
     * Supports both swarm blocks (meta/agent/task/variable) and agent blocks (basic/tools/mcp/subagent/member).
     */
    private fun assembleConfigFromBlocks(blocks: List<ConfigBlock>, existingConfig: JsonNode?, configType: String = "swarm"): JsonNode? {
        return try {
            val root = if (existingConfig is ObjectNode) existingConfig.deepCopy() else objectMapper.createObjectNode()

            if (configType == "agent") {
                assembleAgentConfigFromBlocks(blocks, root)
            } else {
                assembleSwarmConfigFromBlocks(blocks, root)
            }

            root
        } catch (e: Exception) {
            logger.warn("Failed to assemble config from blocks: {}", e.message)
            null
        }
    }

    private fun assembleAgentConfigFromBlocks(blocks: List<ConfigBlock>, root: ObjectNode) {
        // Basic block: merge fields into root
        blocks.lastOrNull { it.blockType == "basic" }?.let { basic ->
            listOf("id", "name", "description", "agentType", "agentContext",
                "promptTemplate", "customInstructions", "inputSchema", "outputSchema").forEach { field ->
                basic.data.path(field).takeIf { !it.isMissingNode && !it.isNull }?.let { root.set(field, it) }
            }
            basic.data.path("maxIterations").takeIf { !it.isMissingNode && !it.isNull }?.let { root.set("maxIterations", it) }
            basic.data.path("maxSubAgentDepth").takeIf { !it.isMissingNode && !it.isNull }?.let { root.set("maxSubAgentDepth", it) }
        }

        // Tools block: merge toolNames/skillNames/commandNames
        blocks.lastOrNull { it.blockType == "tools" }?.let { tools ->
            tools.data.path("toolNames").takeIf { !it.isMissingNode && !it.isNull }?.let { root.set("toolNames", it) }
            tools.data.path("skillNames").takeIf { !it.isMissingNode && !it.isNull }?.let { root.set("skillNames", it) }
            tools.data.path("commandNames").takeIf { !it.isMissingNode && !it.isNull }?.let { root.set("commandNames", it) }
        }

        // MCP block: merge mcpConfigs
        blocks.lastOrNull { it.blockType == "mcp" }?.let { mcp ->
            mcp.data.path("mcpConfigs").takeIf { !it.isMissingNode && !it.isNull }?.let { root.set("mcpConfigs", it) }
        }

        // Subagent blocks: collect into subAgentIds (global) + customSubAgents (inline)
        val subagentBlocks = blocks.filter { it.blockType == "subagent" }.sortedBy { it.blockIndex }
        if (subagentBlocks.isNotEmpty()) {
            val subAgentIds = objectMapper.createArrayNode()
            val customSubAgents = objectMapper.createArrayNode()
            subagentBlocks.forEach { block ->
                val agentId = block.data.path("agentId")
                if (!agentId.isMissingNode && !agentId.isNull && agentId.asText().isNotBlank()) {
                    subAgentIds.add(agentId.asText())
                } else {
                    customSubAgents.add(block.data)
                }
            }
            if (subAgentIds.size() > 0) root.set("subAgentIds", subAgentIds)
            if (customSubAgents.size() > 0) root.set("customSubAgents", customSubAgents)
        }

        // Member blocks: collect into memberIds (global) + customMembers (inline)
        val memberBlocks = blocks.filter { it.blockType == "member" }.sortedBy { it.blockIndex }
        if (memberBlocks.isNotEmpty()) {
            val memberIds = objectMapper.createArrayNode()
            val customMembers = objectMapper.createArrayNode()
            memberBlocks.forEach { block ->
                val agentId = block.data.path("agentId")
                if (!agentId.isMissingNode && !agentId.isNull && agentId.asText().isNotBlank()) {
                    memberIds.add(agentId.asText())
                } else {
                    customMembers.add(block.data)
                }
            }
            if (memberIds.size() > 0) root.set("memberIds", memberIds)
            if (customMembers.size() > 0) root.set("customMembers", customMembers)
        }
    }

    private fun assembleSwarmConfigFromBlocks(blocks: List<ConfigBlock>, root: ObjectNode) {
        // Meta block: overlay non-null fields (last submission wins)
        blocks.lastOrNull { it.blockType == "meta" }?.let { meta ->
            meta.data.path("name").takeIf { !it.isMissingNode && !it.isNull }?.let { root.set("name", it) }
            meta.data.path("title").takeIf { !it.isMissingNode && !it.isNull }?.let { root.set("title", it) }
            meta.data.path("description").takeIf { !it.isMissingNode && !it.isNull }?.let { root.set("description", it) }
        }

        // Agent blocks: merge by blockIndex into existing array
        val agentBlocks = blocks.filter { it.blockType == "agent" }.sortedBy { it.blockIndex }
        if (agentBlocks.isNotEmpty()) {
            val agentsArr = (root.get("agents") as? ArrayNode)?.deepCopy()
                ?: objectMapper.createArrayNode()
            agentBlocks.forEach { block ->
                if (block.blockIndex in 0 until agentsArr.size()) {
                    agentsArr.set(block.blockIndex, block.data)
                } else {
                    agentsArr.add(block.data)
                }
            }
            root.set("agents", agentsArr)
        }

        // Task blocks: merge by blockIndex into existing array
        val taskBlocks = blocks.filter { it.blockType == "task" }.sortedBy { it.blockIndex }
        if (taskBlocks.isNotEmpty()) {
            val tasksArr = (root.get("tasks") as? ArrayNode)?.deepCopy()
                ?: objectMapper.createArrayNode()
            taskBlocks.forEach { block ->
                if (block.blockIndex in 0 until tasksArr.size()) {
                    tasksArr.set(block.blockIndex, block.data)
                } else {
                    tasksArr.add(block.data)
                }
            }
            root.set("tasks", tasksArr)
        }

        // Variable blocks: merge by blockIndex into existing array
        val varBlocks = blocks.filter { it.blockType == "variable" }.sortedBy { it.blockIndex }
        if (varBlocks.isNotEmpty()) {
            val varsArr = (root.get("variables") as? ArrayNode)?.deepCopy()
                ?: objectMapper.createArrayNode()
            varBlocks.forEach { block ->
                if (block.blockIndex in 0 until varsArr.size()) {
                    varsArr.set(block.blockIndex, block.data)
                } else {
                    varsArr.add(block.data)
                }
            }
            root.set("variables", varsArr)
        }
    }

    private fun buildDoneJson(config: JsonNode?, explanation: String, validation: ConfigValidationResult? = null): String {
        val configStr = if (config != null) objectMapper.writeValueAsString(config) else "{}"
        val valid = if (validation != null) validation.valid else config != null
        val errorsJson = if (validation != null && validation.errors.isNotEmpty()) {
            objectMapper.writeValueAsString(validation.errors.map { mapOf("field" to it.field, "message" to it.message, "severity" to it.severity) })
        } else {
            "[]"
        }
        return """{"generatedConfig":$configStr,"validation":{"valid":$valid,"errors":$errorsJson},"explanation":"${escapeJson(explanation)}","retryCount":0,"mode":"agent"}"""
    }

    private fun sseEvent(event: String, data: String): ServerSentEvent<String> =
        ServerSentEvent.builder<String>().event(event).data(data).build()

    private fun encodeSseData(text: String): String =
        "\"" + text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\""

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
