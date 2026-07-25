package com.easy.easyai.core.tool

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.event.AgentEvent
import com.easy.easyai.core.model.ContentBlock
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.ToolResultContent
import com.easy.easyai.core.model.Usage
import com.fasterxml.jackson.annotation.JsonIgnore
import com.easy.easyai.common.util.SharedObjectMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.springframework.ai.util.json.schema.JsonSchemaGenerator
import tools.jackson.module.kotlin.readValue
import java.util.*

/**
 * Tool execution mode: sequential or parallel.
 */
enum class ToolExecutionMode {
    SEQUENTIAL,
    PARALLEL
}

/**
 * Tool update events for streaming partial results.
 */
sealed interface ToolUpdate {
    data class Progress(val message: String) : ToolUpdate
    data class PartialContent(val text: String) : ToolUpdate
    data class PartialResult(val data: Any) : ToolUpdate

    /** Sub-agent event forwarded to parent stream for real-time UI rendering. */
    data class SubAgentEvent(
        val agentName: String,
        val event: AgentEvent
    ) : ToolUpdate
}

/**
 * Tool execution result.
 */
data class ToolResult(
    val content: List<ContentBlock>,
    val details: Map<String, Any> = emptyMap(),
    val terminate: Boolean = false,
    val isError: Boolean = false,
    @get:JsonIgnore
    val needPause: Boolean = false,
    /** Reason for pause when [needPause] is true (e.g., "ask_question"). Null when not paused. */
    val pauseReason: String? = null,
    /** Token usage incurred by this tool execution (e.g., sub-agent LLM calls). Null if not applicable. */
    val usage: Usage? = null
)

/**
 * Tool definition interface for agent-executable tools.
 */
interface ToolDefinition {
    val name: String
    val description: String
    val inputSchema: String
    val executionMode: ToolExecutionMode get() = ToolExecutionMode.PARALLEL

    /**
     * Permission category for this tool (e.g., "shell", "file", "browser").
     * Used by the permission system to map tools to permission types.
     * Default: uses the tool name as category.
     */
    val permissionCategory: String get() = name

    /**
     * UI renderer identifier for frontend tool message rendering.
     * Used by ToolMessageRouter to select the appropriate renderer component.
     * Default: "generic".
     */
    val uiRenderer: String get() = "generic"

    /**
     * Whether this tool should be included in the default agent's tool set.
     * Default: true.
     */
    val isDefaultTool: Boolean get() = true

    /**
     * Argument keys used to extract a pattern for permission matching.
     * For example, shell tools use ["command"], file tools use ["path"].
     * Default: common keys that cover most tools.
     */
    val patternKeys: List<String> get() = listOf("command", "cmd", "path", "file", "filepath", "file_path", "url")

    /**
     * Whether the permission pattern should default to "*" (match-all) when
     * none of the [patternKeys] are found in the arguments.
     * Default: true.
     */
    val defaultPatternWildcard: Boolean get() = true

    /**
     * Whether this tool should be skipped when resuming a session.
     * Tools that return WaitForUserContent (like ask_question) should set this to true,
     * as they represent pending user interactions, not tool calls that need re-execution.
     * Default: false (tool will be re-executed on resume).
     */
    val skipOnResume: Boolean get() = false

    /**
     * Whether this tool modifies files on disk and should trigger snapshot tracking.
     * When true, the snapshot system will capture file changes after this tool executes.
     * Default: false (read-only or non-file-modifying tools).
     */
    val tracksFileChanges: Boolean get() = false

    /**
     * Whether this tool bypasses agent-level toolNames filtering.
     * Session-scoped coordination tools (e.g., team tools) set this to true so they
     * remain available even when the agent's toolNames list is empty or does not include them.
     * Default: false (subject to toolNames filtering).
     */
    val alwaysInclude: Boolean get() = false

    suspend fun execute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String? = null,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit = {}
    ): ToolResult
}

/**
 * Base class that auto-generates JSON Schema from a parameter data class.
 *
 * Accepts [ToolMetadata] via constructor injection from the corresponding [ToolBuilder],
 * making the Builder the single source of truth for shared metadata properties.
 */
abstract class BaseToolDefinition(
    protected val metadata: ToolMetadata
) : ToolDefinition {

    final override val name: String get() = metadata.name
    final override val description: String get() = metadata.description
    final override val inputSchema: String by lazy { generateJsonSchema() }
    override val permissionCategory: String get() = metadata.permissionCategory
    override val uiRenderer: String get() = metadata.uiRenderer
    override val isDefaultTool: Boolean get() = metadata.isDefaultTool
    override val patternKeys: List<String> get() = metadata.patternKeys
    override val defaultPatternWildcard: Boolean get() = metadata.defaultPatternWildcard
    override val skipOnResume: Boolean get() = metadata.skipOnResume
    override val tracksFileChanges: Boolean get() = metadata.tracksFileChanges
    override val alwaysInclude: Boolean get() = metadata.alwaysInclude

    protected abstract fun parameterType(): Class<*>

    protected abstract suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult

    final override suspend fun execute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult = doExecute(agentContext, toolCallId, messageId, args, coroutineScope, onUpdate)

    private fun generateJsonSchema(): String {
        return JsonSchemaGenerator.generateForType(parameterType())
    }

    /** Convenience helper to build an error ToolResult. */
    protected fun errorResult(message: String): ToolResult = ToolResult(
        content = listOf(TextContent(message)),
        isError = true
    )

    /** Convenience helper to build an error ToolResult with tool call context. */
    protected fun errorResult(toolCallId: String, toolName: String, message: String): ToolResult = ToolResult(
        content = listOf(ToolResultContent(toolCallId = toolCallId, toolName = toolName, output = message, isError = true)),
        isError = true
    )
}

/**
 * Bridges ToolDefinition to Spring AI's ToolCallback interface.
 *
 * Since Spring AI's ToolCallback.call() is synchronous, we use runBlocking with
 * Dispatchers.IO to avoid blocking critical threads during tool execution.
 */
internal class EasyAiToolCallback(
    private val toolDefinition: ToolDefinition
) : org.springframework.ai.tool.ToolCallback {

    private val objectMapper = SharedObjectMapper.instance

    private val springAiDefinition = org.springframework.ai.tool.definition.ToolDefinition.builder()
        .name(toolDefinition.name)
        .description(toolDefinition.description)
        .inputSchema(toolDefinition.inputSchema)
        .build()

    override fun getToolDefinition(): org.springframework.ai.tool.definition.ToolDefinition = springAiDefinition

    override fun call(toolInput: String): String {
        val args: Map<String, Any?> = objectMapper.readValue(toolInput)
        // Fallback context for Spring AI compatibility path (non-primary)
        val fallbackContext = AgentContext(agentId = "spring-ai-callback")
        val result = runBlocking(Dispatchers.IO) {
            toolDefinition.execute(
                agentContext = fallbackContext,
                toolCallId = "tc-${UUID.randomUUID()}",
                messageId = null,
                args = args,
                coroutineScope = this,
                onUpdate = {}
            )
        }
        return result.content.filterIsInstance<TextContent>().joinToString(separator = "") { it.text }
    }
}
