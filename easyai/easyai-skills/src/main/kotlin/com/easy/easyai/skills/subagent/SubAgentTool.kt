package com.easy.easyai.skills.subagent

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.*
import com.easy.easyai.core.event.AgentEndEvent
import com.easy.easyai.core.event.AgentStartEvent
import com.easy.easyai.core.event.MessageListener
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.*
import com.easy.easyai.core.validation.InputSchemaValidator
import com.easy.easyai.core.validation.ValidationResult
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import kotlinx.coroutines.CoroutineScope
import org.slf4j.LoggerFactory
import java.util.*

/**
 * SubAgentTool — created per-session in SessionAgentFactory (like TodoWriteTool).
 *
 * Delegates subtasks to specialized sub-agents. The LLM invokes this tool with a prompt
 * and a sub-agent type. The tool creates a child Agent with derived tools and executes it.
 *
 * Two invocation modes:
 * - Predefined: agentType matches a whitelisted sub-agent (DB record or inline spec).
 * - Dynamic: agentType = "dynamic" + an [AgentSpec] — a one-off sub-agent synthesized at
 *   call time from the parent's own resources (tools/skills/MCP must be subsets; no DB access).
 *   A predefined entry literally named "dynamic" shadows the reserved value.
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
        @param:JsonPropertyDescription("Complete task prompt for the sub-agent. The sub-agent sees ONLY this prompt (plus its agentSpec.systemPrompt), not your conversation history — include all necessary context, file paths, and requirements.")
        val prompt: String,
        @param:JsonPropertyDescription("Sub-agent type to invoke. Use a predefined sub-agent name/id listed in the system prompt, or the reserved value 'dynamic' to create a one-off sub-agent via agentSpec.")
        val agentType: String,
        @param:JsonPropertyDescription("Structured input data matching the sub-agent's inputSchema (only when the sub-agent defines one).")
        val inputData: Map<String, Any?>? = null,
        @param:JsonPropertyDescription("One-off sub-agent specification. Required when agentType='dynamic'; must be omitted for predefined agent types.")
        val agentSpec: AgentSpec? = null,
    )

    /**
     * LLM-provided specification for a dynamic (ad-hoc) sub-agent.
     * Resource selections are hard-constrained to subsets of the parent agent's resources:
     * null/omitted = inherit ALL of the parent's resources of that kind;
     * explicit empty array = grant none; any name outside the parent's set is rejected.
     */
    data class AgentSpec(
        @param:JsonPropertyDescription("Short identifier for the one-off sub-agent (used in logs/UI).")
        val name: String,
        @param:JsonPropertyDescription("Optional one-line purpose of this sub-agent.")
        val description: String? = null,
        @param:JsonPropertyDescription("Role and instructions for the sub-agent — becomes its system prompt. Should describe its expertise, working style, and what its final response must contain.")
        val systemPrompt: String? = null,
        @param:JsonPropertyDescription("Built-in tools to grant, by exact tool name from your own tool list. Omit/null = inherit all your tools; empty array = no built-in tools. 'task' and 'ask_question' are always stripped. Unknown names are rejected.")
        val toolNames: List<String>? = null,
        @param:JsonPropertyDescription("Skills to grant, by name from your authorized skill list. Omit/null = inherit all your skills; empty array = no skills. Unknown names are rejected.")
        val skillNames: List<String>? = null,
        @param:JsonPropertyDescription("MCP servers to grant, by server name (your MCP tools are named '<server>__<tool>'; granting a server includes ALL its tools). Omit/null = inherit all your MCP servers; empty array = no MCP tools. Unknown names are rejected.")
        val mcpServerNames: List<String>? = null,
        @param:JsonPropertyDescription("Optional iteration cap for the sub-agent's work loop. Default 30, maximum 50.")
        val maxIterations: Int? = null,
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
                content = listOf(TextContent("Error: Invalid parameters. Required: prompt (String), agentType (String). For agentType 'dynamic', also provide agentSpec.")),
                isError = true
            )
        }

        val inputVariables = params.inputData ?: emptyMap()
        val allowedIds = agentContext.subAgents.mapNotNull { it["id"] as? String }
        // Predefined whitelist wins over the reserved value: an inline entry literally
        // named "dynamic" is treated as a predefined sub-agent.
        val isDynamic = params.agentType == DYNAMIC_AGENT_TYPE && params.agentType !in allowedIds

        val resolvedDefinition: AgentDefinition
        val resolvedBaseContext: AgentContext
        val derivedTools: List<ToolDefinition>

        if (isDynamic) {
            // 0a. Dynamic branch: LLM-created one-off sub-agent. No DB lookup, no resolver —
            // resources are strictly a subset of the parent agent's already-resolved resources.
            when (val resolution = resolveDynamicSubAgent(params.agentSpec, agentContext)) {
                is DynamicResolution.Rejected -> return ToolResult(
                    content = listOf(TextContent(resolution.message)),
                    isError = true
                )
                is DynamicResolution.Resolved -> {
                    resolvedDefinition = resolution.definition
                    resolvedBaseContext = resolution.baseContext
                    derivedTools = resolution.tools
                    logger.info("Dynamic sub-agent '{}' starting with {} tools",
                        resolution.definition.name, resolution.tools.size)
                }
            }
        } else {
            // 0b. Reject mixing agentSpec with a predefined agentType
            if (params.agentSpec != null && params.agentType in allowedIds) {
                return ToolResult(
                    content = listOf(TextContent(
                        "Error: agentSpec cannot be used with a predefined agentType '${params.agentType}'. " +
                        "Use agentType 'dynamic' or omit agentSpec."
                    )),
                    isError = true
                )
            }

            // 0c. Validate agentType is in the allowed sub-agents whitelist
            if (params.agentType !in allowedIds) {
                return ToolResult(
                    content = listOf(TextContent(
                        "Error: Agent type '${params.agentType}' is not in the allowed sub-agents list. " +
                        "Available: ${allowedIds.joinToString()}. " +
                        "Or pass agentType 'dynamic' with an agentSpec to create a one-off sub-agent."
                    )),
                    isError = true
                )
            }

            // 1. Look up AgentDefinition from store (use userId for user+system scope query)
            val userId = agentContext.userId ?: "system"
            val definition = agentStore.findById(params.agentType, userId)
            resolvedDefinition = definition ?: run {
                // Fallback 1: search by name among sub-agents
                val availableSubAgents = agentStore.findSubAgents(userId)
                val byName = availableSubAgents.find { it.name == params.agentType }
                if (byName != null) {
                    byName
                } else {
                    // Fallback 2: check for inline custom sub-agent in agentContext.subAgents
                    val inlineEntry = agentContext.subAgents.find {
                        (it["inline"] == true) && (it["id"] == params.agentType || it["name"] == params.agentType)
                    }
                    if (inlineEntry != null) {
                        synthesizeInlineDefinition(inlineEntry)
                    } else {
                        return ToolResult(
                            content = listOf(TextContent(
                                "Error: Unknown sub-agent type '${params.agentType}'. " +
                                "Available: ${availableSubAgents.joinToString { it.name }}"
                            )),
                            isError = true
                        )
                    }
                }
            }

            // 2. Validate agent type (PRIMARY-only and TEAM agents cannot be used as subagent)
            if (resolvedDefinition.agentType == AgentType.PRIMARY ||
                resolvedDefinition.agentType == AgentType.TEAM) {
                return ToolResult(
                    content = listOf(TextContent(
                        "Error: Agent '${params.agentType}' (${resolvedDefinition.agentType}) cannot be used as a sub-agent."
                    )),
                    isError = true
                )
            }

            // 2b. Validate inputData against sub-agent's inputSchema (if defined)
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
            // For inline agents, inject explicit MCP bindings and skill whitelist (they have no agent_tool DB rows)
            val inlineEntry = if (resolvedDefinition.id.startsWith("inline:")) {
                agentContext.subAgents.find { it["id"] == resolvedDefinition.id }
            } else null
            val effectiveContext = if (inlineEntry != null) {
                val inlineSkillNames = parseInlineSkillNames(inlineEntry)
                agentContext.copy(
                    mcpConfigs = parseInlineMcpConfigs(inlineEntry),
                    allowedSkillNames = inlineSkillNames
                )
            } else agentContext

            val (baseContext, tools) = if (contextResolver != null) {
                contextResolver.resolve(resolvedDefinition, effectiveContext)
            } else {
                // Fallback: inherit from parent tools (legacy behavior)
                val parentTools = effectiveContext.tools
                val legacyTools = deriveToolPermissions(parentTools, resolvedDefinition, agentStore)
                // Ensure agentId and promptTemplate reflect the sub-agent's own definition, not the parent's
                effectiveContext.copy(
                    agentId = resolvedDefinition.id,
                    promptTemplate = resolvedDefinition.promptTemplate,
                    subAgents = emptyList()
                ) to legacyTools
            }
            resolvedBaseContext = baseContext
            derivedTools = tools
            logger.info("SubAgent '{}' starting with {} tools (resolver={})",
                resolvedDefinition.name, derivedTools.size, if (contextResolver != null) "independent" else "inherited")
        }

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
            sessionVariables = agentContext.sessionVariables, // Share parent's session variables reference
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
                details = mapOf("agentType" to params.agentType, "agentName" to resolvedDefinition.name),
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

    /** Outcome of dynamic sub-agent resolution: a synthesized execution triple or a rejection message. */
    internal sealed interface DynamicResolution {
        data class Resolved(
            val definition: AgentDefinition,
            val baseContext: AgentContext,
            val tools: List<ToolDefinition>
        ) : DynamicResolution

        data class Rejected(val message: String) : DynamicResolution
    }

    /**
     * Validate an LLM-provided [AgentSpec] against the parent context and synthesize a one-off
     * sub-agent. Resource semantics: null/omitted = inherit ALL of the parent's resources of
     * that kind; explicit empty list = grant none; requested names must be subsets of the
     * parent's already-resolved resources (tools / allowedSkillNames / MCP servers derived from
     * resolved MCP tool names) — violations are hard-rejected so the LLM gets an explicit
     * correction signal.
     */
    internal fun resolveDynamicSubAgent(spec: AgentSpec?, agentContext: AgentContext): DynamicResolution {
        if (spec == null) {
            return DynamicResolution.Rejected(
                "Error: agentType 'dynamic' requires an 'agentSpec' object with at least a 'name'. " +
                "Optionally provide systemPrompt, toolNames, skillNames, mcpServerNames."
            )
        }
        if (spec.name.isBlank()) {
            return DynamicResolution.Rejected("Error: agentSpec.name must be a non-blank string.")
        }

        // Built-in tools available to the parent (MCP tools are validated separately by server;
        // FORBIDDEN_TOOLS stay validatable but are stripped later, not rejected)
        val parentToolNames = agentContext.tools
            .filter { it.permissionCategory != MCP_PERMISSION_CATEGORY }
            .map { it.name }
            .toSet()
        val requestedToolNames = spec.toolNames?.toSet() ?: parentToolNames
        val invalidTools = requestedToolNames.filter { it !in parentToolNames }
        if (invalidTools.isNotEmpty()) {
            return DynamicResolution.Rejected(
                "Error: agentSpec.toolNames $invalidTools are not available to this agent. " +
                "Available tool names: ${parentToolNames.sorted()}. " +
                "Dynamic sub-agents may only use a subset of the parent agent's tools."
            )
        }

        // Skill whitelist: empty parent list = no skills authorized (fail-closed)
        val parentSkillNames = agentContext.allowedSkillNames
        val requestedSkillNames = spec.skillNames ?: parentSkillNames
        val invalidSkills = requestedSkillNames.filter { it !in parentSkillNames }
        if (invalidSkills.isNotEmpty()) {
            val hint = if (parentSkillNames.isEmpty()) {
                "This agent has no authorized skills; omit skillNames."
            } else {
                "Authorized skill names: $parentSkillNames."
            }
            return DynamicResolution.Rejected(
                "Error: agentSpec.skillNames $invalidSkills are not authorized for this agent. $hint"
            )
        }

        // MCP servers derived from the parent's resolved MCP tools (named "<server>__<tool>")
        val parentMcpServers = agentContext.tools
            .filter { it.permissionCategory == MCP_PERMISSION_CATEGORY }
            .map { it.name.substringBefore(MCP_NAME_SEPARATOR) }
            .toSet()
        val requestedMcpServers = spec.mcpServerNames?.map { sanitizeMcpName(it) }?.toSet() ?: parentMcpServers
        val invalidServers = requestedMcpServers.filter { it !in parentMcpServers }
        if (invalidServers.isNotEmpty()) {
            val hint = if (parentMcpServers.isEmpty()) {
                "This agent has no MCP servers; omit mcpServerNames."
            } else {
                "Available MCP servers: ${parentMcpServers.sorted()}."
            }
            return DynamicResolution.Rejected(
                "Error: agentSpec.mcpServerNames $invalidServers are not available to this agent. $hint"
            )
        }

        val definition = AgentDefinition.create(
            id = "$DYNAMIC_ID_PREFIX${UUID.randomUUID()}",
            name = spec.name,
            agentType = AgentType.SUBAGENT,
            agentContext = AgentEnv.CHAT,
            description = spec.description ?: "",
            promptTemplate = spec.systemPrompt,
            maxIterations = (spec.maxIterations ?: DEFAULT_DYNAMIC_MAX_ITERATIONS)
                .coerceIn(1, MAX_DYNAMIC_MAX_ITERATIONS),
        )
        // FORBIDDEN_TOOLS are stripped even if requested; a requested MCP server brings all its tools
        val tools = agentContext.tools.filter {
            it.name !in FORBIDDEN_TOOLS && (
                it.name in requestedToolNames ||
                (it.permissionCategory == MCP_PERMISSION_CATEGORY &&
                    it.name.substringBefore(MCP_NAME_SEPARATOR) in requestedMcpServers)
            )
        }
        val baseContext = agentContext.copy(
            agentId = definition.id,
            promptTemplate = definition.promptTemplate,
            customInstructions = null,
            subAgents = emptyList(),
            skills = agentContext.skills.filter { (it["name"] as? String) in requestedSkillNames },
            allowedSkillNames = requestedSkillNames,
            mcpConfigs = emptyList()
        )
        return DynamicResolution.Resolved(definition, baseContext, tools)
    }

    companion object {
        /** Max chars for sub-agent result summary before truncation. */
        const val MAX_RESULT_LENGTH = 10_000

        /** Reserved agentType value that triggers dynamic (LLM-created) sub-agent execution. */
        const val DYNAMIC_AGENT_TYPE = "dynamic"

        /** ID prefix for dynamically synthesized sub-agent definitions. */
        const val DYNAMIC_ID_PREFIX = "dynamic:"

        /** Default iteration cap for dynamic sub-agents when agentSpec.maxIterations is absent. */
        const val DEFAULT_DYNAMIC_MAX_ITERATIONS = 30

        /** Hard upper bound for dynamic sub-agent iterations. */
        const val MAX_DYNAMIC_MAX_ITERATIONS = 50

        /** Permission category of MCP tools; their names follow the "<server>__<tool>" convention. */
        private const val MCP_PERMISSION_CATEGORY = "mcp"
        private const val MCP_NAME_SEPARATOR = "__"

        /** Tools always removed from sub-agent tool sets for safety. */
        private val FORBIDDEN_TOOLS = listOf("task", "ask_question")

        /**
         * Mirror of McpToolDefinition.sanitize (easyai-skills must not depend on easyai-tools).
         * Keeps requested MCP server names comparable with the "<server>__<tool>" naming convention.
         */
        fun sanitizeMcpName(s: String): String = s.replace(Regex("[^a-zA-Z0-9]"), "_")

        /**
         * Synthesize an AgentDefinition from an inline sub-agent map entry.
         * Used when the sub-agent is defined inline (custom mode) rather than as a global DB record.
         */
        fun synthesizeInlineDefinition(entry: Map<String, Any?>): AgentDefinition {
            val name = entry["name"] as? String ?: "inline-agent"
            return AgentDefinition.create(
                id = entry["id"] as? String ?: "inline-$name",
                name = name,
                description = entry["description"] as? String ?: "",
                promptTemplate = entry["systemPrompt"] as? String ?: "",
                toolNames = (entry["toolNames"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                agentType = AgentType.SUBAGENT,
                agentContext = AgentEnv.CHAT,
            )
        }

        /**
         * Parse skill names from an inline sub-agent entry.
         * The "skillNames" field is stored as a JSON string in the inline metadata.
         */
        fun parseInlineSkillNames(entry: Map<String, Any?>): List<String> {
            val rawJson = entry["mcpConfigs"] as? String ?: return emptyList()
            return try {
                val node = SharedObjectMapper.instance.readTree(rawJson)
                val skillNode = node.get("skillNames") ?: return emptyList()
                if (!skillNode.isArray) return emptyList()
                val result = mutableListOf<String>()
                for (el in skillNode) { result.add(el.asString()) }
                result
            } catch (e: Exception) {
                emptyList()
            }
        }

        /**
         * Parse MCP configs from an inline sub-agent entry's metadata.
         * The "mcpConfigs" field contains the raw InlineAgentSpec JSON; extract mcpConfigs array.
         */
        fun parseInlineMcpConfigs(entry: Map<String, Any?>): List<AgentToolConfig> {
            val rawJson = entry["mcpConfigs"] as? String ?: return emptyList()
            return try {
                val node = SharedObjectMapper.instance.readTree(rawJson)
                val mcpNode = node.get("mcpConfigs") ?: return emptyList()
                if (!mcpNode.isArray) return emptyList()
                val result = mutableListOf<AgentToolConfig>()
                for (mcpEl in mcpNode) {
                    val serverName = mcpEl.get("serverName")?.asString() ?: ""
                    val toolNamesNode = mcpEl.get("toolNames")
                    val promptNamesNode = mcpEl.get("promptNames")
                    val toolNames = mutableListOf<String>()
                    val promptNames = mutableListOf<String>()
                    if (toolNamesNode != null && toolNamesNode.isArray) {
                        for (el in toolNamesNode) { toolNames.add(el.asString()) }
                    }
                    if (promptNamesNode != null && promptNamesNode.isArray) {
                        for (el in promptNamesNode) { promptNames.add(el.asString()) }
                    }
                    val metadataObj = mutableMapOf<String, List<String>>()
                    if (toolNames.isNotEmpty()) metadataObj["toolNames"] = toolNames
                    if (promptNames.isNotEmpty()) metadataObj["promptNames"] = promptNames
                    val metadata = metadataObj.takeIf { it.isNotEmpty() }
                        ?.let { SharedObjectMapper.instance.writeValueAsString(it) }
                    result.add(AgentToolConfig(
                        id = "inline_mcp_$serverName",
                        agentId = "inline",
                        targetType = TargetType.MCP,
                        targetName = serverName,
                        metadata = metadata
                    ))
                }
                result
            } catch (_: Exception) {
                emptyList()
            }
        }

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
