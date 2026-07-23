package com.easy.easyai.web.service

import com.easy.easyai.agent.registry.ToolRegistry
import com.easy.easyai.api.config.ModelProviderConfigStore

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.agent.AsyncAgentStore
import com.easy.easyai.skills.SkillRegistry
import com.easy.easyai.tools.mcp.McpClientManager
import com.easy.easyai.web.model.AiConfigGenerateRequest
import com.easy.easyai.web.model.ConfigValidationError
import com.easy.easyai.web.model.ConfigValidationResult
import com.easy.easyai.web.service.ConfigGeneratorService.Companion.MAX_RETRIES
import com.easy.easyai.web.service.validation.TemplateConsistencyValidator
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.StructuredOutputChatOptions
import org.springframework.http.codec.ServerSentEvent
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.milliseconds

/**
 * Service for AI-powered config generation.
 *
 * Collects resource context (tools, skills, MCP, models, agents),
 * constructs a system prompt, calls LLM, parses and validates the result,
 * and auto-retries on validation failure (up to [MAX_RETRIES] times).
 */
class ConfigGeneratorService(
    private val toolRegistry: ToolRegistry,
    private val agentStore: AsyncAgentStore,
    private val agentService: AgentService,
    private val modelConfigStore: ModelProviderConfigStore,
    private val configValidator: ConfigValidator,
    private val skillRegistry: SkillRegistry? = null,
    private val mcpClientManager: McpClientManager? = null,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper = SharedObjectMapper.instance

    /**
     * Load a spec resource file from the classpath.
     * Returns null if the resource is not found (graceful degradation).
     */
    private fun loadSpecResource(path: String): String? {
        return javaClass.classLoader.getResource(path)?.readText(Charsets.UTF_8)
    }

    /**
     * Streaming generate that emits SSE events via the given [collector].
     * Emits config_delta events with raw JSON tokens as they arrive from the LLM,
     * then emits config_done with the final validated result.
     * Auto-retry on validation failure is preserved within the same SSE connection.
     */
    suspend fun generateStream(
        request: AiConfigGenerateRequest,
        userId: String,
        collector: FlowCollector<ServerSentEvent<String>>,
    ) {
        val systemPrompt = buildSystemPrompt(request.configType)
        val userMessageText = buildUserMessage(request, userId)
        var retryCount = 0
        var retryReason: String? = null
        var lastExplanation: String

        // Build multi-turn conversation: system + user request preserved across retries
        val messages = mutableListOf<Message>(
            SystemMessage(systemPrompt),
            UserMessage(userMessageText)
        )

        while (retryCount <= MAX_RETRIES) {
            val attempt = retryCount + 1

            // Emit retry_start for retries (attempt > 1), including the reason
            if (attempt > 1) {
                val reasonJson = objectMapper.writeValueAsString(retryReason)
                collector.emit(sseEvent("retry_start", """{"attempt":$attempt,"maxRetries":${MAX_RETRIES + 1},"reason":$reasonJson}"""))
            }

            // Emit stream_start
            collector.emit(sseEvent("stream_start", """{"attempt":$attempt}"""))

            val chatModel = createChatModel(request.modelConfigId, userId)
            val accumulated = StringBuilder()
            val batchBuffer = StringBuilder()
            var isThinkingPhase = false
            val thinkingBatchBuffer = StringBuilder()

            try {
                val chatPrompt = buildPromptWithSchema(chatModel, messages, getJsonSchema(request.configType))

                // Channel-based streaming with per-chunk stall detection
                // (same pattern as AgentLoopRunner). The producer coroutine feeds
                // LLM chunks into the channel; the consumer receives with a timeout
                // to detect stalled streams independently of the HTTP read timeout.
                coroutineScope {
                    val channel = Channel<ChatResponse>(Channel.UNLIMITED)
                    val producerJob = launch {
                        try {
                            chatModel.stream(chatPrompt).asFlow().collect { chunk ->
                                channel.send(chunk)
                            }
                        } finally {
                            channel.close()
                        }
                    }

                    var receivedContentChunk = false
                    var chunkCount = 0

                    while (true) {
                        // Longer timeout for the first chunk (TTFT) since the LLM
                        // needs time to process the prompt before emitting tokens.
                        val timeout = if (!receivedContentChunk && chunkCount == 0) {
                            (FIRST_CHUNK_TIMEOUT_SECONDS * 1000L).milliseconds
                        } else {
                            (STREAM_STALL_TIMEOUT_SECONDS * 1000L).milliseconds
                        }
                        val received = withTimeoutOrNull(timeout) {
                            channel.receiveCatching()
                        }
                        if (received == null) {
                            val phase = if (chunkCount == 0) "first token (TTFT)" else "subsequent chunk"
                            val timeoutSec = if (chunkCount == 0) FIRST_CHUNK_TIMEOUT_SECONDS else STREAM_STALL_TIMEOUT_SECONDS
                            producerJob.cancel()
                            throw TimeoutException(
                                "LLM stream stalled: no $phase received within ${timeoutSec}s"
                            )
                        }
                        val chunk = received.getOrNull() ?: break // Channel closed (normal or error)
                        val results = chunk.results

                        // Skip empty chunks (SSE keepalive/ping) without resetting the stall timer
                        if (results.isEmpty() || results.all { r -> r.output.text.isNullOrEmpty() }) {
                            continue
                        }

                        // Content-bearing chunk: reset stall detection state
                        receivedContentChunk = true
                        chunkCount++

                        results.forEach { result ->
                            val output = result.output
                            val text = output.text
                            if (text.isNullOrEmpty()) return@forEach
                            val metadata = output.metadata

                            val isThinkingContent = metadata.containsKey("signature") || metadata.containsKey("thinking")

                            if (isThinkingContent) {
                                // Flush any pending text buffer before switching to thinking
                                if (batchBuffer.isNotEmpty()) {
                                    collector.emit(sseEvent("text_delta", encodeSseData(batchBuffer.toString())))
                                    batchBuffer.clear()
                                }
                                if (!isThinkingPhase) {
                                    isThinkingPhase = true
                                }
                                thinkingBatchBuffer.append(text)
                                if (thinkingBatchBuffer.length >= BATCH_SIZE) {
                                    collector.emit(sseEvent("thinking_delta", encodeSseData(thinkingBatchBuffer.toString())))
                                    thinkingBatchBuffer.clear()
                                }
                            } else {
                                // Flush any pending thinking buffer before switching to text
                                if (isThinkingPhase) {
                                    if (thinkingBatchBuffer.isNotEmpty()) {
                                        collector.emit(sseEvent("thinking_delta", encodeSseData(thinkingBatchBuffer.toString())))
                                        thinkingBatchBuffer.clear()
                                    }
                                    collector.emit(sseEvent("thinking_end", ""))
                                    isThinkingPhase = false
                                }
                                accumulated.append(text)
                                batchBuffer.append(text)
                                if (batchBuffer.length >= BATCH_SIZE) {
                                    collector.emit(sseEvent("text_delta", encodeSseData(batchBuffer.toString())))
                                    batchBuffer.clear()
                                }
                            }
                        }
                    }
                }

                // Flush remaining thinking buffer
                if (isThinkingPhase) {
                    if (thinkingBatchBuffer.isNotEmpty()) {
                        collector.emit(sseEvent("thinking_delta", encodeSseData(thinkingBatchBuffer.toString())))
                    }
                    collector.emit(sseEvent("thinking_end", ""))
                    isThinkingPhase = false
                }

                // Flush remaining text buffer
                if (batchBuffer.isNotEmpty()) {
                    collector.emit(sseEvent("text_delta", encodeSseData(batchBuffer.toString())))
                }
            } catch (e: Exception) {
                logger.error("LLM stream failed on attempt $attempt", e)
                val errorMsg = e.message ?: e.javaClass.simpleName
                if (retryCount >= MAX_RETRIES) {
                    collector.emit(sseEvent("error", """{"message":"LLM stream failed: ${escapeJson(errorMsg)}"}"""))
                    return
                }
                // Treat stream failure as retryable — feed error back to LLM
                val partialResponse = accumulated.toString()
                val retryFeedback = "The previous request failed with an error: $errorMsg\nPlease retry and generate a valid JSON configuration."
                addRetryMessages(messages, partialResponse.ifBlank { "(no output)" }, retryFeedback)
                retryReason = "LLM stream error: $errorMsg"
                retryCount++
                continue
            }

            // Emit stream_end
            collector.emit(sseEvent("stream_end", ""))

            val llmResponse = accumulated.toString()
            lastExplanation = extractExplanation(llmResponse)

            // Parse JSON
            val (configNode, parseError) = parseJsonFromResponse(llmResponse)
            if (parseError != null) {
                if (retryCount >= MAX_RETRIES) {
                    val resultJson = buildDoneJson(
                        config = null,
                        valid = false,
                        errors = listOf(ConfigValidationError("config", parseError)),
                        explanation = lastExplanation,
                        retryCount = retryCount
                    )
                    collector.emit(sseEvent("config_done", resultJson))
                    return
                }
                // Add assistant response + error feedback as multi-turn context
                addRetryMessages(messages, llmResponse, parseError)
                retryReason = parseError
                retryCount++
                continue
            }

            // Post-process: strip customInstructions if template doesn't reference it
            // (the LLM often generates both fields even when only one is needed)
            if (request.configType == "agent" && configNode is ObjectNode) {
                val promptTemplate = configNode.get("promptTemplate")?.asString() ?: ""
                if (promptTemplate.isNotBlank() &&
                    !TemplateConsistencyValidator.referencesTemplateVariable(promptTemplate, "custom_instructions")) {
                    configNode.remove("customInstructions")
                }
            }

            // Validate
            val validation = when (request.configType) {
                "agent" -> configValidator.validateAgentConfig(configNode!!, userId)
                "swarm" -> configValidator.validateSwarmConfig(configNode!!, userId)
                else -> ConfigValidationResult(
                    valid = false,
                    errors = listOf(ConfigValidationError("configType", "Unknown config type: ${request.configType}"))
                )
            }

            if (validation.valid || validation.errors.none { it.severity == "error" } || retryCount >= MAX_RETRIES) {
                val resultJson = buildDoneJson(
                    config = configNode,
                    valid = validation.valid,
                    errors = validation.errors,
                    explanation = lastExplanation,
                    retryCount = retryCount
                )
                collector.emit(sseEvent("config_done", resultJson))
                return
            }

            // Add assistant response + error feedback as multi-turn context
            val errorSummary = formatErrorSummary(validation.errors)
            addRetryMessages(messages, llmResponse, errorSummary)
            retryReason = errorSummary
            retryCount++
        }
    }

    /**
     * Create a ChatModel from the given modelConfigId, falling back to default.
     */
    private suspend fun createChatModel(modelConfigId: String?, userId: String): ChatModel {
        if (modelConfigId == null) return agentService.defaultChatModel
        return try {
            val config = modelConfigStore.getConfig(modelConfigId, userId)
            if (config != null) agentService.createChatModel(config)
            else agentService.defaultChatModel
        } catch (e: Exception) {
            logger.warn("Failed to create chat model for '{}', falling back to default", modelConfigId, e)
            agentService.defaultChatModel
        }
    }

    /**
     * Build a [Prompt] with JSON Schema injected via [StructuredOutputChatOptions] when the model
     * supports it. For models that do not implement [StructuredOutputChatOptions], the schema is
     * still embedded in the system prompt (via [buildSystemPrompt]) as a soft constraint.
     */
    private fun buildPromptWithSchema(
        chatModel: ChatModel,
        messages: List<Message>,
        schema: String
    ): Prompt {
        val baseOptions = chatModel.options
        val chatOptions = if (baseOptions is StructuredOutputChatOptions) {
            logger.debug("Model supports StructuredOutputChatOptions, injecting JSON Schema for structured output enforcement")
            baseOptions.mutate().outputSchema(schema).build()
        } else {
            logger.debug("Model does not support StructuredOutputChatOptions, relying on prompt-based schema only")
            baseOptions
        }
        return Prompt(messages, chatOptions)
    }

    private fun sseEvent(event: String, data: String): ServerSentEvent<String> =
        ServerSentEvent.builder<String>().event(event).data(data).build()

    /**
     * JSON-encode a string so it fits on a single SSE `data:` line.
     * Escapes newlines, quotes, and backslashes to prevent SSE multi-line splitting.
     */
    private fun encodeSseData(text: String): String =
        "\"" + text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\""

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun buildDoneJson(
        config: JsonNode?,
        valid: Boolean,
        errors: List<ConfigValidationError>,
        explanation: String,
        retryCount: Int
    ): String {
        val configStr = if (config != null) objectMapper.writeValueAsString(config) else "{}"
        val errorsJson = errors.joinToString(",") {
            """{"field":"${escapeJson(it.field)}","message":"${escapeJson(it.message)}","severity":"${it.severity}"}"""
        }
        return """{"generatedConfig":$configStr,"validation":{"valid":$valid,"errors":[$errorsJson]},"explanation":"${escapeJson(explanation)}","retryCount":$retryCount}"""
    }

    private fun buildSystemPrompt(configType: String): String {
        val sb = StringBuilder()
        sb.appendLine("You are an expert EasyAI configuration generator. Generate valid JSON configurations based on user requirements and available resources.")
        sb.appendLine()
        sb.appendLine("## Critical Rules")
        sb.appendLine("1. **toolNames**: ONLY built-in tools listed in the user message. NEVER put MCP tool names or skill names here.")
        sb.appendLine("2. **mcpConfigs**: MCP tools are accessed via mcpConfigs (serverName + optional toolNames whitelist), NEVER in top-level toolNames.")
        sb.appendLine("3. **skillNames**: Skills are referenced via skillNames field, NEVER in toolNames.")
        sb.appendLine("4. **agentDefinitionId** (swarm): MUST reference an existing agent from the resources list.")
        sb.appendLine("5. **dependsOn** (swarm): Must form a valid DAG (no cycles).")
        sb.appendLine("6. **promptTemplate**: Must use valid Jinja2 syntax.")
        sb.appendLine("7. Include ALL fields the user explicitly requests — never silently drop requested fields.")
        sb.appendLine()
        sb.appendLine("## Thinking Process")
        sb.appendLine("Before generating, analyze step by step:")
        sb.appendLine("1. Identify the required agent roles and capabilities")
        sb.appendLine("2. Map roles to available AgentDefinitions")
        sb.appendLine("3. Design the task DAG with proper dependencies (for swarm)")
        sb.appendLine("4. Select appropriate tools, skills, and MCP servers")
        sb.appendLine("5. Generate the complete JSON configuration")
        sb.appendLine()
        sb.appendLine("## Output Format")
        sb.appendLine("- Output ONLY valid JSON, no markdown fences, no explanation outside JSON")
        sb.appendLine("- Ensure all required fields are present per the schema below")

        when (configType) {
            "agent" -> {
                val spec = loadSpecResource("specs/agent-spec.md")
                if (spec != null) {
                    sb.appendLine()
                    sb.appendLine("## Agent Configuration Specification")
                    sb.appendLine("<file name=\"agent-spec.md\">")
                    sb.appendLine(spec)
                    sb.appendLine("</file>")
                }
                sb.appendLine()
                sb.appendLine("## Target JSON Schema (AgentCreateRequest)")
                sb.appendLine(AGENT_JSON_SCHEMA)
            }
            "swarm" -> {
                val spec = loadSpecResource("specs/swarm-spec-brief.md")
                    ?: loadSpecResource("specs/swarm-spec.md")
                if (spec != null) {
                    sb.appendLine()
                    sb.appendLine("## Swarm Preset Configuration Specification")
                    sb.appendLine("<file name=\"swarm-spec.md\">")
                    sb.appendLine(spec)
                    sb.appendLine("</file>")
                }
                sb.appendLine()
                sb.appendLine("## Target JSON Schema (SwarmPreset)")
                sb.appendLine(SWARM_JSON_SCHEMA)
            }
        }

        return sb.toString()
    }

    private suspend fun buildUserMessage(request: AiConfigGenerateRequest, userId: String): String {
        val tools = toolRegistry.getAllTools()
        val skills = skillRegistry?.all() ?: emptyList()
        val mcpServers = mcpClientManager?.getConnectedServers(userId) ?: emptyList()
        val agents = agentStore.findAll(userId)
        val models = modelConfigStore.getAllConfigs(userId)

        val sb = StringBuilder()

        // User requirements first (highest attention weight)
        sb.appendLine("## Requirements")
        sb.appendLine(request.description)
        sb.appendLine()

        // Available resources summary
        sb.appendLine("## Available Resources")
        sb.appendLine()
        sb.appendLine("### Built-in Tools (valid for toolNames field)")
        for (tool in tools) {
            sb.appendLine("- ${tool.name}: ${tool.description}")
        }
        sb.appendLine()

        if (skills.isNotEmpty()) {
            sb.appendLine("### Skills (use via skillNames, NOT toolNames)")
            for (skill in skills) {
                sb.appendLine("- ${skill.name}: ${skill.description ?: "No description"}")
            }
            sb.appendLine()
        }

        if (mcpServers.isNotEmpty()) {
            sb.appendLine("### MCP Servers (use via mcpConfigs, NOT toolNames)")
            for (server in mcpServers) {
                val toolNames = server.tools.joinToString(", ") { it.name() }
                sb.appendLine("- ${server.serverName}: tools=[$toolNames]")
            }
            sb.appendLine()
        }

        if (agents.isNotEmpty()) {
            sb.appendLine("### Agents (for agentDefinitionId references)")
            for (agent in agents) {
                sb.appendLine("- ${agent.id} (${agent.name}, ${agent.agentType})")
            }
            sb.appendLine()
        }

        if (models.isNotEmpty()) {
            sb.appendLine("### Models")
            for (model in models) {
                sb.appendLine("- ${model.id} (${model.modelName ?: model.modelId}, ${model.protocol})")
            }
            sb.appendLine()
        }

        // Existing config for editing
        if (request.existingConfig != null) {
            sb.appendLine("## Current Configuration (editing mode)")
            sb.appendLine("Only modify fields the user explicitly asks about. Preserve all other fields as-is.")
            sb.appendLine("<existing_config>")
            sb.appendLine(objectMapper.writeValueAsString(request.existingConfig))
            sb.appendLine("</existing_config>")
            sb.appendLine()
        }

        // Instructions
        sb.appendLine("## Instructions")
        sb.appendLine("Analyze the requirements step by step:")
        sb.appendLine("1. Identify needed agent roles and map to existing AgentDefinitions")
        sb.appendLine("2. Design task DAG with proper dependencies")
        sb.appendLine("3. Select appropriate tools, skills, and MCP configurations")
        sb.appendLine("4. Generate the complete JSON configuration")
        sb.appendLine()
        sb.appendLine("IMPORTANT: Ensure ALL explicitly requested fields are present in the output JSON.")

        return sb.toString()
    }

    private fun buildRetryMessage(errorSummary: String): String {
        return """The previous configuration had validation errors. Please fix them:

$errorSummary

Generate a corrected JSON configuration. Output ONLY the corrected JSON."""
    }

    private fun formatErrorSummary(errors: List<ConfigValidationError>): String =
        errors.filter { it.severity == "error" }
            .joinToString("\n") { "- ${it.field}: ${it.message}" }

    private fun addRetryMessages(messages: MutableList<Message>, llmResponse: String, errorSummary: String) {
        messages.add(AssistantMessage(llmResponse))
        messages.add(UserMessage(buildRetryMessage(errorSummary)))
    }

    private fun parseJsonFromResponse(llmResponse: String): Pair<JsonNode?, String?> {
        // Strip markdown code fences if present
        var json = llmResponse.trim()
        if (json.startsWith("```")) {
            json = json.replace(Regex("^```(?:json)?\\s*"), "")
                .replace(Regex("\\s*```$"), "")
                .trim()
        }

        return try {
            val node = objectMapper.readTree(json)
            node to null
        } catch (e: Exception) {
            null to "Failed to parse LLM output as JSON: ${e.message}"
        }
    }

    private fun extractExplanation(llmResponse: String): String {
        // If the response is pure JSON, provide a generic explanation
        val trimmed = llmResponse.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return "Configuration generated successfully based on your description."
        }
        // If there's text before/after JSON, extract it
        val jsonStart = trimmed.indexOf('{')
        return if (jsonStart > 0) {
            trimmed.substring(0, jsonStart).trim()
        } else {
            "Configuration generated successfully."
        }
    }

    companion object {
        private const val MAX_RETRIES = 2
        private const val BATCH_SIZE = 20

        /**
         * Maximum seconds to wait for the first chunk (Time-To-First-Token) before
         * considering the LLM stalled. Same value as AgentLoopRunner for consistency.
         */
        private const val FIRST_CHUNK_TIMEOUT_SECONDS = 120L

        /**
         * Maximum seconds to wait between consecutive stream chunks before
         * considering the LLM stalled. Same value as AgentLoopRunner.
         */
        private const val STREAM_STALL_TIMEOUT_SECONDS = 60L

        private val AGENT_JSON_SCHEMA = """
{
  "type": "object",
  "required": ["id", "name", "promptTemplate"],
  "properties": {
    "id": { "type": "string", "maxLength": 50 },
    "name": { "type": "string", "maxLength": 20 },
    "agentType": { "type": "string", "enum": ["PRIMARY", "SUBAGENT", "ALL"] },
    "agentContext": { "type": "string", "enum": ["CHAT", "SWARM", "BOTH"] },
    "description": { "type": "string", "maxLength": 200 },
    "customInstructions": { "type": "string" },
    "promptTemplate": { "type": "string" },
    "toolNames": { "type": "array", "items": { "type": "string" } },
    "subAgentIds": { "type": "array", "items": { "type": "string" } },
    "skillNames": { "type": "array", "items": { "type": "string" } },
    "mcpConfigs": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["serverName"],
        "properties": {
          "serverName": { "type": "string" },
          "toolNames": { "type": "array", "items": { "type": "string" } },
          "promptNames": { "type": "array", "items": { "type": "string" } }
        }
      }
    },
    "commandNames": { "type": "array", "items": { "type": "string" } },
    "maxIterations": { "type": "integer", "minimum": 1 },
    "maxSubAgentDepth": { "type": "integer", "minimum": 0 },
    "color": { "type": "string", "pattern": "^#[0-9A-Fa-f]{6}$" },
    "enabled": { "type": "boolean" },
    "inputSchema": { "type": ["object", "null"] },
    "outputSchema": { "type": ["object", "null"] }
  },
  "additionalProperties": false
}
""".trimIndent()

        private val SWARM_JSON_SCHEMA = """
{
  "type": "object",
  "required": ["name", "title", "agents", "tasks"],
  "properties": {
    "name": { "type": "string" },
    "title": { "type": "string" },
    "description": { "type": "string" },
    "agents": {
      "type": "array",
      "minItems": 1,
      "items": {
        "type": "object",
        "required": ["id", "role"],
        "properties": {
          "id": { "type": "string" },
          "agentDefinitionId": { "type": "string" },
          "role": { "type": "string" },
          "maxIterations": { "type": "integer", "minimum": 1 },
          "timeoutSeconds": { "type": "integer", "minimum": 1 },
          "modelName": { "type": ["string", "null"] },
          "maxRetries": { "type": "integer", "minimum": 0 },
          "name": { "type": "string" },
          "description": { "type": "string" },
          "systemPrompt": { "type": "string" },
          "toolNames": { "type": "array", "items": { "type": "string" } },
          "mcpConfigs": {
            "type": "array",
            "items": {
              "type": "object",
              "required": ["serverName"],
              "properties": {
                "serverName": { "type": "string" },
                "toolNames": { "type": "array", "items": { "type": "string" } },
                "promptNames": { "type": "array", "items": { "type": "string" } }
              }
            }
          }
        },
        "additionalProperties": false
      }
    },
    "tasks": {
      "type": "array",
      "minItems": 1,
      "items": {
        "type": "object",
        "required": ["id", "agentId", "promptTemplate"],
        "properties": {
          "id": { "type": "string" },
          "agentId": { "type": "string" },
          "promptTemplate": { "type": "string" },
          "dependsOn": { "type": "array", "items": { "type": "string" } },
          "inputFrom": { "type": "object", "additionalProperties": { "type": "string" } },
          "type": { "type": "string", "enum": ["SINGLE", "DELIBERATION", "TEAM"] },
          "deliberation": { "type": ["object", "null"] },
          "team": { "type": ["object", "null"] },
          "maxRetries": { "type": "integer", "minimum": 0 }
        },
        "additionalProperties": false
      }
    },
    "variables": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["name"],
        "properties": {
          "name": { "type": "string" },
          "description": { "type": "string" },
          "required": { "type": "boolean" },
          "defaultValue": { "type": "string" }
        },
        "additionalProperties": false
      }
    }
  },
  "additionalProperties": false
}
""".trimIndent()

        /**
         * Get the JSON Schema string for the given config type.
         */
        private fun getJsonSchema(configType: String): String = when (configType) {
            "agent" -> AGENT_JSON_SCHEMA
            "swarm" -> SWARM_JSON_SCHEMA
            else -> AGENT_JSON_SCHEMA
        }
    }
}
