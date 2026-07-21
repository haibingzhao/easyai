package com.easy.easyai.skills.subagent

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.*
import com.easy.easyai.core.event.*
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.*
import com.easy.easyai.core.validation.InputSchemaValidator
import com.easy.easyai.core.validation.ValidationResult
import kotlinx.coroutines.CoroutineScope
import org.slf4j.LoggerFactory

/**
 * SubAgentTool — created per-session in SessionAgentFactory (like TodoWriteTool).
 *
 * Delegates subtasks to specialized sub-agents. The LLM invokes this tool with a prompt
 * and a sub-agent type. The tool creates a child Agent with derived tools and executes it.
 *
 * V1: Black-box execution — the parent agent only sees the final result.
 * V2: Transparent execution — sub-agent events are forwarded to the parent event stream.
 *
 * @param agentStore AsyncAgentStore for looking up sub-agent definitions and tool configs.
 * @param agentService Shared AgentService infrastructure.
 * @param contextResolver Resolves sub-agent tools, skills, and MCP configs independently from the agent_tool table.
 * @param subAgentMessageListenerFactory Factory to create MessageListener with parentMessageId and parentToolCallId for sub-agent persistence.
 */
class SubAgentTool(
    metadata: ToolMetadata,
    private val agentStore: AsyncAgentStore,
    private val agentService: AgentService,
    private val contextResolver: SubAgentContextResolver? = null,
    private val subAgentMessageListenerFactory: ((sessionId: String, context: AgentContext, parentMessageId: String, parentToolCallId: String) -> MessageListener?)? = null,
) : BaseToolDefinition(metadata) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val inputSchemaValidator = InputSchemaValidator()

    override val executionMode = ToolExecutionMode.PARALLEL

    data class Parameters(
        val prompt: String,
        val agentType: String,
        /** Structured input data matching the sub-agent's inputSchema (when defined). */
        val inputData: Map<String, Any?>? = null,
    )

    override fun parameterType(): Class<*> = Parameters::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        // Normalize inputData: LLMs may pass it as a JSON string instead of a Map
        val normalizedArgs = args.toMutableMap()
        val rawInputData = normalizedArgs["inputData"]
        if (rawInputData is String) {
            try {
                normalizedArgs["inputData"] = SharedObjectMapper.instance.readValue(
                    rawInputData, Map::class.java
                ) as Map<*, *>
            } catch (_: Exception) { /* leave as-is, will fail in convertValue */ }
        }

        val params = try {
            SharedObjectMapper.instance.convertValue(normalizedArgs, Parameters::class.java)
        } catch (_: Exception) {
            return ToolResult(
                content = listOf(TextContent("Error: Invalid parameters. Required: prompt (String), agentType (String)")),
                isError = true
            )
        }

        // 0. Validate agentType is in the allowed sub-agents whitelist
        val allowedIds = agentContext.subAgents.mapNotNull { it["id"] as? String }
        if (params.agentType !in allowedIds) {
            return ToolResult(
                content = listOf(TextContent(
                    "Error: Agent type '${params.agentType}' is not in the allowed sub-agents list. " +
                    "Available: ${allowedIds.joinToString()}"
                )),
                isError = true
            )
        }

        // 1. Look up AgentDefinition from store (use userId for user+system scope query)
        val userId = agentContext.userId ?: "system"
        val definition = agentStore.findById(params.agentType, userId)
        val resolvedDefinition = definition ?: run {
            // Fallback: search by name among sub-agents
            val availableSubAgents = agentStore.findSubAgents(userId)
            val byName = availableSubAgents.find { it.name == params.agentType }
            if (byName == null) {
                return ToolResult(
                    content = listOf(TextContent(
                        "Error: Unknown sub-agent type '${params.agentType}'. " +
                        "Available: ${availableSubAgents.joinToString { it.name }}"
                    )),
                    isError = true
                )
            }
            byName
        }

        // 2. Validate agent type (PRIMARY-only agents cannot be used as subagent)
        if (resolvedDefinition.agentType == AgentType.PRIMARY) {
            return ToolResult(
                content = listOf(TextContent(
                    "Error: Agent '${params.agentType}' is primary-only and cannot be used as a sub-agent."
                )),
                isError = true
            )
        }

        // 2b. Validate inputData against sub-agent's inputSchema (if defined)
        val inputVariables = params.inputData ?: emptyMap()
        val schema = resolvedDefinition.inputSchema
        if (schema != null) {
            if (inputVariables.isEmpty()) {
                return ToolResult(
                    content = listOf(TextContent(
                        "Error: Sub-agent '${params.agentType}' requires inputData matching its inputSchema, but none was provided."
                    )),
                    isError = true
                )
            }
            val validationResult = inputSchemaValidator.validateInput(schema, inputVariables)
            if (validationResult is ValidationResult.Invalid) {
                return ToolResult(
                    content = listOf(TextContent(
                        "Error: inputData does not match sub-agent '${params.agentType}' inputSchema:\n" +
                        validationResult.errors.joinToString("\n")
                    )),
                    isError = true
                )
            }
        }

        // 3. Resolve sub-agent context and tools independently (same as primary agent)
        val (resolvedBaseContext, derivedTools) = if (contextResolver != null) {
            contextResolver.resolve(resolvedDefinition, agentContext)
        } else {
            // Fallback: inherit from parent tools (legacy behavior)
            val parentTools = agentContext.tools
            val legacyTools = deriveToolPermissions(parentTools, resolvedDefinition, agentStore)
            // Ensure agentId and promptTemplate reflect the sub-agent's own definition, not the parent's
            agentContext.copy(
                agentId = resolvedDefinition.id,
                promptTemplate = resolvedDefinition.promptTemplate,
                subAgents = emptyList()
            ) to legacyTools
        }
        logger.info("SubAgent '{}' starting with {} tools (resolver={})",
            resolvedDefinition.name, derivedTools.size, if (contextResolver != null) "independent" else "inherited")

        // 4. Build sub-agent system prompt
        val subAgentSystemPrompt = buildSubAgentSystemPrompt(resolvedDefinition, params.prompt, inputVariables)

        // 5. Build sub-agent context (inherit parent's model/project/mode, set parentAgentId for recursion prevention)
        // agentRunId is the parent task toolCallId so each sub-agent invocation has its own todo scope.
        val subAgentContext = resolvedBaseContext.copy(
            modelConfig = agentContext.modelConfig,
            sessionId = agentContext.sessionId,
            projectId = agentContext.projectId,
            projectPath = agentContext.projectPath,
            memoryAutoGeneration = agentContext.memoryAutoGeneration,
            customInstructions = subAgentSystemPrompt,
            tools = derivedTools,
            maxIterations = resolvedDefinition.maxIterations,
            parentAgentId = agentContext.parentAgentId ?: agentContext.agentId, // Non-null prevents recursive SubAgentTool
            agentRunId = toolCallId,
            inputVariables = inputVariables,
            abortSignal = agentContext.abortSignal, // Inherit parent's abort signal for graceful cancel
        )

        onUpdate(ToolUpdate.Progress("Running ${resolvedDefinition.name} sub-agent..."))

        // 6. Execute sub-agent with timeout protection
        // Wrap AgentService with a sub-agent-specific MessageListener for persistence
        val subAgentService = wrapServiceWithListener(
            agentService,
            subAgentMessageListenerFactory?.let { factory ->
                agentContext.sessionId?.let { sid ->
                    messageId?.let { parentMsgId ->
                        factory(sid, subAgentContext, parentMsgId, toolCallId)
                    }
                }
            }
        )

        val output = executeAgentWithProtection(
            agent = Agent(subAgentContext, subAgentService),
            prompt = params.prompt,
            timeoutMs = resolvedDefinition.maxIterations * 20_000L,
            abortSignal = agentContext.abortSignal,
            onEvent = { event ->
                // Skip sub-agent lifecycle events — they should not leak into the parent stream.
                // AgentStartEvent/AgentEndEvent are parent-agent-level concepts; the sub-agent's
                // endReason must not overwrite the parent's session.lastEndReason.
                if (event !is AgentStartEvent && event !is AgentEndEvent) {
                    onUpdate(ToolUpdate.SubAgentEvent(
                        agentName = resolvedDefinition.name,
                        event = event
                    ))
                }
            },
            maxSummaryLength = MAX_RESULT_LENGTH,
            truncateLabel = "Result",
            label = "SubAgent '${resolvedDefinition.name}'",
        )

        // 7. Map execution output to ToolResult
        // Diagnostic logging: record usage for all outcomes (timeout/failure/success)
        if (output.status != ExecutionStatus.COMPLETED) {
            logger.warn("SubAgent '{}' {} (usage: input={}, output={}, cacheRead={}, cacheWrite={}, duration={}ms)",
                resolvedDefinition.name, output.status,
                output.usage.inputTokens, output.usage.outputTokens,
                output.usage.cacheReadTokens, output.usage.cacheWriteTokens, output.usage.durationMs)
        }
        logger.info("SubAgent '{}' completed: {} chars result (usage: input={}, output={}, cacheRead={}, cacheWrite={}, duration={}ms)",
            resolvedDefinition.name, output.summary.length,
            output.usage.inputTokens, output.usage.outputTokens,
            output.usage.cacheReadTokens, output.usage.cacheWriteTokens, output.usage.durationMs)

        return when (output.status) {
            ExecutionStatus.COMPLETED -> ToolResult(
                content = listOf(TextContent(output.summary)),
                details = mapOf("agentType" to params.agentType),
                usage = output.usage
            )
            ExecutionStatus.TIMEOUT -> ToolResult(
                content = listOf(TextContent(
                    "Sub-agent '${params.agentType}' timed out after ${resolvedDefinition.maxIterations} iterations. " +
                    "Consider using a more focused prompt or a different sub-agent type."
                )),
                isError = true,
                usage = output.usage.takeIf { output.hasUsage }
            )
            ExecutionStatus.FAILED -> ToolResult(
                content = listOf(TextContent("Sub-agent '${params.agentType}' failed: ${output.error?.substringAfterLast("failed: ") ?: "unknown error"}")),
                isError = true,
                usage = output.usage.takeIf { output.hasUsage }
            )
        }
    }

    companion object {
        /** Max chars for sub-agent result summary before truncation. */
        const val MAX_RESULT_LENGTH = 10_000

        /** Tools always removed from sub-agent tool sets for safety. */
        private val FORBIDDEN_TOOLS = listOf("task", "ask_question")

        /**
         * Derive the tool set for a sub-agent from the parent's tools.
         * Uses whitelist from agent_tool table. Empty whitelist = inherit all parent tools.
         * Always removes subagent and ask_question tools (prevent recursion).
         */
        suspend fun deriveToolPermissions(
            parentTools: List<ToolDefinition>,
            definition: AgentDefinition,
            agentStore: AsyncAgentStore
        ): List<ToolDefinition> {
            val whitelist = agentStore.getAgentToolConfigs(definition.id, TargetType.TOOL)
            var tools = parentTools
            // Whitelist filter (empty = inherit all)
            if (whitelist.isNotEmpty()) {
                val allowedNames = whitelist.map { it.targetName }.toSet()
                tools = tools.filter { it.name in allowedNames }
            }
            // Always remove subagent itself (prevent recursion) + ask_question (not suitable for sub-agents)
            tools = tools.filter { it.name !in FORBIDDEN_TOOLS }
            return tools
        }

        /**
         * Build the system prompt for a sub-agent.
         * Includes role definition, task description, and behavioral constraints.
         */
        fun buildSubAgentSystemPrompt(
            definition: AgentDefinition,
            taskPrompt: String,
            inputVariables: Map<String, Any?> = emptyMap()
        ): String {
            val sb = StringBuilder()
            // When promptTemplate is provided, PromptTemplateService handles rendering in AgentLoopRunner.
            // We only need to add the role definition for customInstructions or default fallback.
            if (definition.promptTemplate.isNullOrBlank()) {
                if (!definition.customInstructions.isNullOrBlank()) {
                    sb.appendLine(definition.customInstructions)
                } else {
                    sb.appendLine("You are a sub-agent named '${definition.name}'.")
                    if (!definition.description.isNullOrBlank()) {
                        sb.appendLine(definition.description)
                    }
                    sb.appendLine()
                    sb.appendLine("## Constraints")
                    sb.appendLine("- Focus ONLY on the task described below.")
                    sb.appendLine("- Do NOT attempt tasks outside the scope of this prompt.")
                    sb.appendLine("- Do NOT ask the user questions — work autonomously with available tools.")
                }
            }
            // This instruction is critical: the parent agent ONLY sees the final message,
            // not intermediate tool calls or analysis. The subagent must include all
            // important details (findings, file changes, relevant code snippets, etc.)
            // in its final response.
            sb.appendLine()
            sb.appendLine("## Final Response Requirements")
            sb.appendLine("Your final message is the ONLY content returned to the parent agent.")
            sb.appendLine("Intermediate work (tool calls, file reads, analysis) is NOT visible to the parent.")
            sb.appendLine("Therefore, your final response MUST be comprehensive and self-contained:")
            sb.appendLine("- List all files created, modified, or deleted with their paths")
            sb.appendLine("- Summarize key findings, decisions made, and their rationale")
            sb.appendLine("- Include relevant code snippets or command outputs if important")
            sb.appendLine("- Report any errors encountered and how they were resolved")
            sb.appendLine("- Do NOT write a brief one-liner — include enough detail for the parent to continue work")
            sb.appendLine("- If you used todo_write to track sub-tasks, mark all items completed or cancelled before your final response")
            if (inputVariables.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("## Structured Input Data")
                sb.appendLine("The following structured data was provided by the parent agent:")
                sb.appendLine("```json")
                sb.appendLine(SharedObjectMapper.instance.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(inputVariables))
                sb.appendLine("```")
            }
            sb.appendLine()
            sb.appendLine("## Your Task")
            sb.appendLine(taskPrompt)
            return sb.toString()
        }
    }
}
