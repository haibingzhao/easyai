package com.easy.easyai.compaction.strategy

import com.easy.easyai.api.model.ModelOptions
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.compaction.estimator.TokenEstimator
import com.easy.easyai.compaction.model.CompactionContext
import com.easy.easyai.core.agent.*
import com.easy.easyai.core.event.ErrorEvent
import com.easy.easyai.core.event.MessageEndEvent
import com.easy.easyai.core.event.MessageUpdateEvent
import com.easy.easyai.core.model.*
import com.easy.easyai.core.tool.*
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import kotlinx.coroutines.CoroutineScope
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ChatModel
import tools.jackson.core.type.TypeReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Agent-based compaction strategy that uses a lightweight Agent loop to generate
 * high-quality semantic summaries AND extract session variables.
 *
 * Key design:
 * - History messages are passed directly as the agent's transcript (preserving structure)
 * - The agent has a single tool: update_variable (for variable extraction)
 * - AgentCompletionCheck ensures the tool is called before the agent finishes
 * - maxIterations=2 for bounded execution (summary + tool call in one turn, nudge as fallback)
 *
 * @param agentServiceProvider Lazy provider for AgentService (avoids circular dependency)
 * @param fallbackChatModel ChatModel to use as fallback when no session-specific model is provided
 */
class CompactionAgentStrategy(
    private val agentServiceProvider: () -> AgentService,
    private val fallbackChatModel: ChatModel? = null
) : CompactionStrategy {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val MAX_ITERATIONS = 1

        private const val COMPACTION_SYSTEM_PROMPT = """
You are an expert conversation summarizer. Your task is to condense a conversation
between a user and an AI assistant into a structured summary that preserves all
critical context needed to continue the conversation seamlessly.

## Rules
1. Be concise but comprehensive - capture all important technical details
2. Preserve file paths, code snippets, and technical specifications exactly
3. Note any decisions made and their rationale
4. Identify blocked items and unresolved issues
5. Extract actionable next steps
6. If the history contains a previous summary, update it incrementally:
   preserve still-true details, remove stale details, and merge in new facts.

## Output Format
Output your summary in this structure:

## Goal
- [Single sentence task summary]

## Constraints & Preferences
- [User constraints, preferences, specifications]

## Progress
### Done
- [Completed work]
### In Progress
- [Current work]
### Blocked
- [Blocked items]

## Key Decisions
- [Decisions made and rationale]

## Next Steps
- [Actionable next steps]

## Critical Context
- [Important technical facts, errors, pending questions]

## Relevant Files
- [Relevant file paths or directories]

## Variable Extraction
After generating the summary, you MUST call the update_variable tool EXACTLY ONCE.

Rules:
- Your output is the COMPLETE variable set — it REPLACES the entire store.
  Include still-valid existing variables, update changed values, add new ones,
  and OMIT variables that are stale or superseded.
- Variables are for NUMERIC/DATA facts ONLY that downstream conversation may reference:
  * Prices, amounts, percentages, ratios, financial figures
  * Dates, IDs, codes, versions, quantities
  * Configuration values, parameters, thresholds
  * Computation results (EPS, PE, revenue, margins, market share)
- DO NOT store in variables (these belong in the summary above):
  * Analysis conclusions, opinions, recommendations, ratings
  * Causal reasoning, risk assessments, impact analysis
  * Customer names, competitor names (unless needed as lookup keys)
  * Tool paths, executable names, environment configs
  * Narrative text, strategies, suggestions
- Values MUST be strings; use concise numeric representations (e.g., "170.69", "25.49%")
- Arrays or objects ARE allowed as values — pass them as JSON (e.g., "customers": ["SMIC", "CXMT"], "share": {"Entegris": "25-30%"}). They will be stored as JSON strings.
- ONLY call with empty {"variables": {}} if NO numeric/data facts exist (neither new nor existing)
- This MUST be your ONLY tool call and your final action before finishing
"""
    }

    override suspend fun compact(
        messages: List<EasyAiMessage>,
        context: CompactionContext,
        chatModel: ChatModel?
    ): String = compactWithUsage(messages, context, chatModel).summary

    override suspend fun compactWithUsage(
        messages: List<EasyAiMessage>,
        context: CompactionContext,
        chatModel: ChatModel?,
        tokenEstimator: TokenEstimator?
    ): StrategyOutput {
        logger.debug(
            "Starting agent-based compaction for {} messages (turn {}, round {})",
            messages.size, context.currentTurnId, context.compactionRound
        )

        return try {
            executeAgentCompaction(messages, context, chatModel)
        } catch (e: Exception) {
            logger.warn("Agent compaction failed, falling back to simple summary", e)
            StrategyOutput(generateFallbackSummary(messages, context, "Agent compaction failed: ${e.message}"))
        }
    }

    private suspend fun executeAgentCompaction(
        messages: List<EasyAiMessage>,
        context: CompactionContext,
        chatModel: ChatModel?
    ): StrategyOutput {
        val agentService = agentServiceProvider()

        // Tracking state
        val toolCalled = AtomicBoolean(false)
        val extractedVariables = AtomicReference<Map<String, String>>(emptyMap())

        // Build lightweight variable extraction tool
        val variableTool = CompactionVariableTool(toolCalled, extractedVariables)

        // Build agent context (dry-run: no persistence, no default system prompt)
        // Disable thinking mode for compaction: summarization is a structured task that
        // doesn't benefit from extended reasoning, and thinking adds significant latency.
        val agentContext = AgentContext(
            agentId = "compaction-agent",
            modelConfig = disableThinking(context.modelConfig),
            sessionId = null,
            tools = listOf(variableTool),
            maxIterations = MAX_ITERATIONS,
            promptTemplate = "",  // Suppress default system prompt; we provide our own
            dryRun = true
        )

        // Create dry-run AgentService (no persistence, no observability)
        // Override defaultChatModel with the session-specific model so the compaction agent
        // uses the same model as the conversation (Agent.chatModel resolves via services.defaultChatModel)
        val resolvedChatModel = chatModel ?: fallbackChatModel
        val dryRunServices = CompactionDryRunAgentService(agentService, resolvedChatModel)

        // Wrap with completion check to enforce variable tool call
        val completionCheck = CompactionVariableCompletionCheck(toolCalled)
        val wrappedServices = wrapServiceWithCompletionChecks(dryRunServices, listOf(completionCheck))

        // Create Agent and AgentRunner
        val agent = Agent(context = agentContext, services = wrappedServices)
        val runner = AgentRunner(agent = agent, messages = mutableListOf())

        // Build initial messages: system prompt + history + instruction
        val initialMessages = buildList {
            add(SystemMessage(text = COMPACTION_SYSTEM_PROMPT))
            // History messages directly as transcript (preserves original structure)
            // May include previous compaction summary (isCompactionSummary=true)
            addAll(messages)
            // Instruction prompt with current variables context
            add(UserMessage(content = listOf(TextContent(buildInstructionPrompt(context)))))
        }

        // Execute agent and collect results
        val summaryBuilder = StringBuilder()
        var totalUsage = Usage()

        val stream = runner.prompt(initialMessages)
        stream.asFlow().collect { event ->
            when (event) {
                is MessageUpdateEvent -> summaryBuilder.append(event.delta)
                is MessageEndEvent -> {
                    event.usage?.let { u ->
                        totalUsage = Usage(
                            inputTokens = totalUsage.inputTokens + u.inputTokens,
                            outputTokens = totalUsage.outputTokens + u.outputTokens,
                            cacheReadTokens = totalUsage.cacheReadTokens + u.cacheReadTokens,
                            cacheWriteTokens = totalUsage.cacheWriteTokens + u.cacheWriteTokens
                        )
                    }
                }
                is ErrorEvent -> {
                    logger.warn("Compaction agent error: {}", event.error.message)
                }
                else -> { /* Ignore other events */ }
            }
        }

        val summary = summaryBuilder.toString().trim()
        val variables = extractedVariables.get()

        if (summary.isBlank()) {
            return StrategyOutput(generateFallbackSummary(messages, context, "Empty agent response"), totalUsage, variables)
        }

        logger.info(
            "Agent compaction complete: summary={}chars, variables={}, usage=in:{}/out:{}",
            summary.length, variables.size, totalUsage.inputTokens, totalUsage.outputTokens
        )

        return StrategyOutput(summary, totalUsage, variables)
    }

    private fun buildInstructionPrompt(context: CompactionContext): String = buildString {
        if (context.previousSummary != null) {
            appendLine("Update the existing summary (visible in history above) with new conversation content.")
            appendLine("Preserve still-true details, remove stale details, merge in new facts.")
        } else {
            appendLine("Create a new structured summary from the conversation history above.")
        }
        appendLine()
        if (context.existingVariables.isNotEmpty()) {
            appendLine("CURRENT SESSION VARIABLES (from previous rounds):")
            context.existingVariables.forEach { (k, v) -> appendLine("- $k: $v") }
            appendLine()
        }
        appendLine("IMPORTANT: After generating the summary, call update_variable ONCE with the COMPLETE updated variable set.")
        appendLine("Your output REPLACES the entire variable store — include still-valid existing variables, update changed values, add new ones, and OMIT stale/superseded ones.")
        appendLine("Only store data facts (prices, figures, percentages, IDs, configs). Analysis/conclusions go in the summary.")
        appendLine("Do NOT split variables across multiple calls.")
    }

    /**
     * Disable thinking mode on the model config for compaction.
     * Thinking adds significant latency with minimal quality benefit for structured summarization.
     */
    private fun disableThinking(config: ModelProviderConfig?): ModelProviderConfig? {
        if (config == null) return null
        val opts = config.options ?: ModelOptions()
        if (opts.thinking != true) return config  // Already disabled or unset
        return config.copy(options = opts.copy(thinking = false))
    }

    private fun generateFallbackSummary(
        messages: List<EasyAiMessage>,
        context: CompactionContext,
        reason: String
    ): String = """
        Context summary (fallback - $reason):

        ## Compacted Range
        - Messages compacted: ${messages.size}
        - Turn: ${context.currentTurnId}

        Note: Agent-based summary generation failed. The conversation contained
        ${messages.size} messages that have been removed from context to save space.
    """.trimIndent()
}

/**
 * Lightweight variable tool for compaction agent.
 * Collects variables into an AtomicReference without any persistence.
 */
internal class CompactionVariableTool(
    private val toolCalled: AtomicBoolean,
    private val extractedVariables: AtomicReference<Map<String, String>>
) : BaseToolDefinition(
    ToolMetadata(
        name = "update_variable",
        description = "Store numeric/data variables extracted from the conversation. " +
            "Only store data facts: prices, figures, percentages, IDs, configs, computation results. " +
            "Arrays/objects are allowed as values (stored as JSON strings). " +
            "Do NOT store analysis, conclusions, or narrative text. " +
            "Call with empty map only if no numeric data exists.",
        permissionCategory = "variable",
        isDefaultTool = false
    )
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val executionMode: ToolExecutionMode = ToolExecutionMode.SEQUENTIAL

    data class Parameters(
        @param:JsonPropertyDescription("Numeric/data key-value pairs to store. Keys are variable names, values are data strings (prices, percentages, IDs). Arrays/objects are allowed as values (passed as JSON, stored as JSON string). Do NOT include analysis or narrative text.")
        val variables: Map<String, String>? = null,
        @param:JsonPropertyDescription("List of variable keys to delete.")
        val deleteKeys: List<String>? = null
    )

    override fun parameterType(): Class<*> = Parameters::class.java

    @Suppress("UNCHECKED_CAST")
    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        toolCalled.set(true)

        logger.debug("update_variable called with args: {}", args)

        val variables = coerceToMap(args["variables"])
        val deleteKeys = coerceToStringList(args["deleteKeys"])

        // Merge extracted variables (accumulate across calls, apply deletions)
        extractedVariables.updateAndGet { existing ->
            (existing + variables) - deleteKeys.toSet()
        }

        val summary = if (variables.isEmpty() && deleteKeys.isEmpty()) {
            logger.warn("update_variable called with empty variables — conversation data will NOT be persisted")
            "No variables to update."
        } else {
            "Updated ${variables.size} variable(s): ${variables.keys.joinToString(", ")}"
        }

        return ToolResult(content = listOf(TextContent(summary)))
    }

    /**
     * Coerces the raw value into a Map<String, String>.
     * Handles: Map (normal), String (LLM double-encoded JSON), null.
     * Nested Map/List values are serialized to JSON strings.
     */
    @Suppress("UNCHECKED_CAST")
    private fun coerceToMap(value: Any?): Map<String, String> = when (value) {
        is Map<*, *> -> (value as Map<String, Any?>).mapValues { stringifyValue(it.value) }
        is String -> try {
            val parsed = SharedObjectMapper.instance.readValue(value, object : TypeReference<Map<String, Any?>>() {})
            parsed.mapValues { stringifyValue(it.value) }
        } catch (e: Exception) {
            logger.warn("update_variable: failed to parse string-encoded variables: {}", e.message)
            emptyMap()
        }
        null -> emptyMap()
        else -> {
            logger.warn("update_variable: unexpected variables type: {}", value::class.qualifiedName)
            emptyMap()
        }
    }

    /**
     * Converts a variable value to its string representation.
     * Primitives use toString(); nested Map/List (arrays, object arrays) are serialized as JSON.
     */
    private fun stringifyValue(v: Any?): String = when (v) {
        null -> ""
        is Map<*, *>, is List<*> -> try {
            SharedObjectMapper.instance.writeValueAsString(v)
        } catch (e: Exception) {
            logger.warn("update_variable: failed to serialize nested value: {}", e.message)
            v.toString()
        }
        else -> v.toString()
    }

    /**
     * Coerces the raw value into a List<String>.
     * Handles: List (normal), String (LLM double-encoded JSON array), null.
     */
    private fun coerceToStringList(value: Any?): List<String> = when (value) {
        is List<*> -> value.filterIsInstance<String>()
        is String -> try {
            SharedObjectMapper.instance.readValue(value, object : TypeReference<List<String>>() {})
        } catch (e: Exception) {
            logger.warn("update_variable: failed to parse string-encoded deleteKeys: {}", e.message)
            emptyList()
        }
        null -> emptyList()
        else -> emptyList()
    }
}

/**
 * Delegating AgentService that disables all persistence and observability for compaction agent.
 * Overrides defaultChatModel to use the session-specific model for the compaction agent.
 */
internal class CompactionDryRunAgentService(
    private val delegate: AgentService,
    private val sessionChatModel: ChatModel? = null
) : AgentService by delegate {

    /** Use session-specific model so compaction agent uses the same model as the conversation. */
    override val defaultChatModel: ChatModel
        get() = sessionChatModel ?: delegate.defaultChatModel

    /** Disable message persistence. */
    override val messageListener: com.easy.easyai.core.event.MessageListener? = null

    /** Disable observability events. */
    override val eventListeners: List<AgentEventListener> = emptyList()

    /** Disable completion checks from parent (we inject our own). */
    override val completionChecks: List<AgentCompletionCheck> = emptyList()

    /** Disable wait-for-user tracking. */
    override val waitForUserListener: WaitForUserListener? = null

    /** Disable memory loading. */
    override val memoryStore: com.easy.easyai.core.memory.MemoryStore? = null

    /**
     * Override to return a no-op TransformContextService to prevent recursive compaction.
     */
    override val transformContextService: TransformContextService = DefaultTransformContextService()
}
