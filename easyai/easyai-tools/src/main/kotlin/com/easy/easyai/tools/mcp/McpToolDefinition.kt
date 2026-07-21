package com.easy.easyai.tools.mcp

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.ToolResultContent
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolExecutionMode
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.CoroutineScope
import tools.jackson.databind.node.ObjectNode

/**
 * Wraps a single MCP tool as an EasyAI ToolDefinition.
 * Implements ToolDefinition directly to provide custom inputSchema from the MCP tool.
 *
 * Tool name is prefixed with a sanitized server name to avoid collisions:
 * e.g. "github__create_issue" for server "github", tool "create_issue".
 */
class McpToolDefinition(
    private val serverName: String,
    private val mcpTool: McpSchema.Tool,
    private val manager: McpClientManager,
    private val ownerUserId: String
) : ToolDefinition {

    companion object {
        private val objectMapper = SharedObjectMapper.instance

        /** Sanitize a name: replace non-alphanumeric chars with underscores. */
        fun sanitize(s: String): String = s.replace(Regex("[^a-zA-Z0-9]"), "_")
    }

    override val name: String = "${sanitize(serverName)}__${sanitize(mcpTool.name())}"
    override val description: String = mcpTool.description() ?: "(MCP tool from $serverName)"
    override val uiRenderer: String = "mcp"
    override val permissionCategory: String = "mcp"
    override val executionMode: ToolExecutionMode = ToolExecutionMode.SEQUENTIAL
    override val isDefaultTool: Boolean = true
    override val patternKeys: List<String> = emptyList()
    override val defaultPatternWildcard: Boolean = true
    override val skipOnResume: Boolean = false
    override val tracksFileChanges: Boolean = false

    /**
     * Build the MCP tool's JSON Schema with additionalProperties forced to false.
     * This prevents the LLM from hallucinating extra parameters not defined by the tool.
     */
    override val inputSchema: String by lazy {
        try {
            val schema = mcpTool.inputSchema()
            if (schema != null) {
                val node = objectMapper.valueToTree<tools.jackson.databind.JsonNode>(schema)
                if (node is ObjectNode) {
                    node.set("additionalProperties", objectMapper.nodeFactory.booleanNode(false))
                }
                objectMapper.writeValueAsString(node)
            } else {
                """{"type":"object","properties":{},"additionalProperties":false}"""
            }
        } catch (_: Exception) {
            """{"type":"object","properties":{},"additionalProperties":false}"""
        }
    }

    override suspend fun execute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult = try {
        val result = manager.callTool(serverName, mcpTool.name(), args, ownerUserId)
        // Detect application-level errors embedded in the JSON output.
        // Some MCP servers return isError=false at protocol level but include
        // an "error" field in the response body (e.g. proxy failures, timeouts).
        val errorMessage = extractErrorFromJson(result)
        if (errorMessage != null) {
            ToolResult(
                content = listOf(ToolResultContent(toolCallId = toolCallId, toolName = name, output = result, isError = true)),
                isError = true
            )
        } else {
            ToolResult(content = listOf(ToolResultContent(toolCallId = toolCallId, toolName = name, output = result)))
        }
    } catch (e: McpToolCallException) {
        ToolResult(
            content = listOf(ToolResultContent(toolCallId = toolCallId, toolName = name, output = "MCP tool error: ${e.message}", isError = true)),
            isError = true
        )
    } catch (e: Exception) {
        ToolResult(
            content = listOf(ToolResultContent(toolCallId = toolCallId, toolName = name, output = "Failed to call MCP tool '${mcpTool.name()}': ${e.message}", isError = true)),
            isError = true
        )
    }

    /**
     * Check if a JSON string contains a top-level "error" field with a non-blank string value.
     * Returns the error message if found, null otherwise.
     */
    private fun extractErrorFromJson(output: String): String? {
        return try {
            val node = objectMapper.readTree(output)
            if (node is ObjectNode && node.has("error")) {
                val errorNode = node.get("error")
                if (errorNode != null && errorNode.isString && errorNode.asString().isNotBlank()) {
                    errorNode.asText()
                } else null
            } else null
        } catch (_: Exception) {
            null
        }
    }
}
