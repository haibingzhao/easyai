package com.easy.easyai.compaction.strategy

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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ChatModel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

/**
 * Agent-based compaction strategy that uses a lightweight Agent loop to generate
 * high-quality semantic summaries AND extract session variables.
 *
 * Key design:
 * - History messages are passed directly as the agent's transcript (preserving structure)
 * - The agent has a single tool: update_variable (for variable extraction)
 * - AgentCompletionCheck ensures the tool is called before the agent finishes
 * - maxIterations=3 for bounded execution (summary + tool call + finish)
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
        private const val MAX_ITERATIONS = 3
        private const val TIMEOUT_MS = 180_000L

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
After generating the summary, you MUST call the update_variable tool:
- Extract key data points (financial figures, analysis results, configuration values) that must persist
- Store intermediate computation results that downstream conversation may reference
- If no variables need updating, call with empty: {"variables": {}}
- You MUST call update_variable as your final action before finishing
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
            withTimeout(TIMEOUT_MS.milliseconds) {
                executeAgentCompaction(messages, context, chatModel)
            }
        } catch (_: TimeoutCancellationException) {
            logger.warn("Agent compaction timed out after {}ms, falling back to simple summary", TIMEOUT_MS)
            StrategyOutput(generateFallbackSummary(messages, context, "Agent compaction timed out"))
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
        val agentContext = AgentContext(
            agentId = "compaction-agent",
            modelConfig = context.modelConfig,
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
        appendLine("After generating the summary, you MUST call the update_variable tool.")
        appendLine("If no variables need updating, call with: {\"variables\": {}}")
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
        description = "Store or update session variables extracted from the conversation. " +
            "Call with variables to persist, or with empty map if nothing needs updating.",
        permissionCategory = "variable",
        isDefaultTool = false
    )
) {
    override val executionMode: ToolExecutionMode = ToolExecutionMode.SEQUENTIAL

    data class Parameters(
        @param:JsonPropertyDescription("Key-value pairs to store. Keys are variable names, values are the data to persist.")
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

        val variables = (args["variables"] as? Map<String, Any?>)
            ?.mapValues { it.value?.toString() ?: "" }
            ?: emptyMap()

        // Store extracted variables
        extractedVariables.set(variables)

        val summary = if (variables.isEmpty()) {
            "No variables to update."
        } else {
            "Updated ${variables.size} variable(s): ${variables.keys.joinToString(", ")}"
        }

        return ToolResult(content = listOf(TextContent(summary)))
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
