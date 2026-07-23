package com.easy.easyai.web.service.configgen

import com.easy.easyai.agent.registry.ToolRegistry
import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.Agent
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentRunner
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.agent.AsyncAgentStore
import com.easy.easyai.core.event.*
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.UserMessage
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.skills.SkillRegistry
import com.easy.easyai.tools.mcp.McpClientManager
import com.easy.easyai.web.model.AiConfigGenerateRequest
import com.easy.easyai.web.service.ConfigValidator
import com.easy.easyai.web.service.validation.TemplateConsistencyValidator
import tools.jackson.databind.node.ObjectNode
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.collect
import org.slf4j.LoggerFactory
import org.springframework.http.codec.ServerSentEvent
import tools.jackson.databind.JsonNode

/**
 * Agent-based configuration generator that uses AgentLoop for multi-step generation.
 *
 * Instead of a single LLM call, this generator:
 * 1. Provides tools for resource discovery (list_resources)
 * 2. Allows self-validation (validate_config)
 * 3. Enables explicit submission (submit_config)
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
        private const val MAX_ITERATIONS = 8
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

        // 1. Build specialized tools
        val tools = buildTools(request.configType, userId)

        // 2. Resolve model config
        val modelConfig = resolveModelConfig(request.modelConfigId, userId)

        // 3. Build AgentContext (dry-run: no persistence, no default system prompt)
        val context = AgentContext(
            agentId = "config-generator",
            sessionId = null,  // No session persistence
            userId = userId,
            tools = tools,
            maxIterations = MAX_ITERATIONS,
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
        val userMessageText = buildAgentUserMessage(request, userId)

        val initialMessages = listOf(
            com.easy.easyai.core.model.SystemMessage(text = systemPrompt),
            UserMessage(content = listOf(TextContent(userMessageText)))
        )

        // 7. Execute and adapt SSE events
        collector.emit(sseEvent("stream_start", """{"mode":"agent"}"""))

        val stream = runner.prompt(initialMessages)
        var submittedConfig: JsonNode? = null
        var explanation = "Configuration generated successfully."
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
                                explanation = expl
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

            // If no submit_config was called, try to extract JSON from the full text output
            if (submittedConfig == null) {
                submittedConfig = extractJsonFromText(fullTextBuffer.toString())
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
            val resultJson = buildDoneJson(submittedConfig, explanation)
            collector.emit(sseEvent("config_done", resultJson))

        } catch (e: Exception) {
            logger.error("Agent-based config generation failed", e)
            collector.emit(sseEvent("error", """{"message":"${escapeJson(e.message ?: "Generation failed")}"}"""))
        }
    }

    private fun buildTools(configType: String, userId: String): List<ToolDefinition> {
        return listOf(
            ValidateConfigTool(configValidator, configType, userId),
            ListResourcesTool(toolRegistry, agentStore, skillRegistry, mcpClientManager, modelConfigStore, userId, configType),
            SubmitConfigTool()
        )
    }

    private suspend fun resolveModelConfig(modelConfigId: String?, userId: String): ModelProviderConfig? {
        if (modelConfigId == null) return null
        return try {
            modelConfigStore.getConfig(modelConfigId, userId)
        } catch (e: Exception) {
            logger.warn("Failed to resolve model config '{}', using default", modelConfigId)
            null
        }
    }

    private fun buildAgentSystemPrompt(configType: String): String {
        val specBrief = loadSpecResource("specs/${configType}-spec-brief.md")
            ?: loadSpecResource("specs/${configType}-spec.md")
            ?: ""

        val workflow = when (configType) {
            "agent" -> """
## Your Workflow
1. Analyze the user's requirements carefully
2. Call `list_resources` to discover available tools, skills, and MCP servers
3. Select the minimal set of tools/skills/MCP the agent needs
4. Design the promptTemplate (must include {{ custom_instructions }})
5. Generate the JSON configuration
6. Call `validate_config` to verify correctness
7. If validation fails, fix the errors and validate again
8. Call `submit_config` with the final validated configuration""".trimIndent()
            else -> """
## Your Workflow
1. Analyze the user's requirements carefully
2. Call `list_resources` to discover available agents, tools, and MCP servers
3. Design the configuration structure
4. Generate the JSON configuration
5. Call `validate_config` to verify correctness
6. If validation fails, fix the errors and validate again
7. Call `submit_config` with the final validated configuration""".trimIndent()
        }

        val rules = when (configType) {
            "agent" -> """
## Critical Rules
- toolNames: ONLY built-in tools (from list_resources type="tools"); empty array = ALL tools
- MCP tools: via mcpConfigs field, NEVER in toolNames
- Skills: via skillNames field, NEVER in toolNames
- promptTemplate: MUST be valid Jinja2 and include {{ custom_instructions }}
- subAgentIds: MUST reference existing agents (from list_resources type="agents")
- inputSchema: required when using {{ input.xxx }} in promptTemplate
- Minimalism: only include tools/skills/MCP the agent will actually use""".trimIndent()
            else -> """
## Critical Rules
- Agents support two modes: Global (agentDefinitionId references existing agent) or Inline (agentDefinitionId blank, provide name/systemPrompt/toolNames/mcpConfigs)
- toolNames: ONLY built-in tools (from list_resources type="tools")
- MCP tools: via mcpConfigs field, NEVER in toolNames. Format: [{"serverName":"xxx","toolNames":["t1"],"promptNames":[]}]
- mcpConfigs.toolNames empty = all tools from that server allowed
- agentDefinitionId: MUST reference an existing agent for global mode (from list_resources type="agents")
- dependsOn: forms a valid DAG (no cycles)
- inputFrom: variable routing from upstream tasks""".trimIndent()
        }

        return """
You are an expert EasyAI configuration generator specializing in $configType configurations.

$workflow

$rules

## Configuration Specification
$specBrief

## Output Format
When calling submit_config, provide the complete JSON configuration and a brief explanation of your design decisions.
""".trimIndent()
    }

    private suspend fun buildAgentUserMessage(request: AiConfigGenerateRequest, userId: String): String {
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
        }

        sb.appendLine("## Instructions")
        when (request.configType) {
            "agent" -> {
                sb.appendLine("1. Call `list_resources type=\"tools\"` to see available built-in tools")
                sb.appendLine("2. Call `list_resources type=\"skills\"` and `type=\"mcp_servers\"` if relevant")
                sb.appendLine("3. Only include subAgentIds if the agent needs delegation (check type=\"agents\")")
                sb.appendLine("4. Design the promptTemplate with {{ custom_instructions }} included")
                sb.appendLine("5. Generate, validate, and submit the configuration")
            }
            else -> {
                sb.appendLine("1. First call `list_resources type=\"agents\"` to see available agents")
                sb.appendLine("2. Design the configuration based on requirements")
                sb.appendLine("3. Generate and validate the JSON")
                sb.appendLine("4. Submit the final configuration")
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
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
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

    private fun buildDoneJson(config: JsonNode?, explanation: String): String {
        val configStr = if (config != null) objectMapper.writeValueAsString(config) else "{}"
        val valid = config != null
        return """{"generatedConfig":$configStr,"validation":{"valid":$valid,"errors":[]},"explanation":"${escapeJson(explanation)}","retryCount":0,"mode":"agent"}"""
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
