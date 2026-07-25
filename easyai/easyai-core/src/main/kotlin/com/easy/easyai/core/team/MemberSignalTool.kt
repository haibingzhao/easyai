package com.easy.easyai.core.team

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.ToolExecutionMode
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import kotlinx.coroutines.CoroutineScope

/**
 * Generic tool injected into member agents for signaling blocked state to the coordinator.
 *
 * Unifies two formerly separate implementations:
 * - Swarm TEAM: name="escalate", onSignal → escalationRef.set(EscalationResult(reason))
 * - Team Agent: name="ask_leader", onSignal → blockedState holder update
 *
 * The [onSignal] callback is invoked synchronously when the member LLM calls this tool.
 * The returned [acknowledgment] message instructs the member LLM to stop execution.
 *
 * Thread-safe: the callback may be invoked from any coroutine context; implementations
 * should use atomic holders (e.g., AtomicReference) if shared across coroutines.
 */
class MemberSignalTool(
    metadata: ToolMetadata,
    private val onSignal: (reason: String, progress: String) -> Unit,
    private val acknowledgment: String = DEFAULT_ACKNOWLEDGMENT,
) : BaseToolDefinition(metadata) {

    override val executionMode = ToolExecutionMode.SEQUENTIAL

    data class Parameters(
        /** Why the member cannot proceed — the blocking issue or question. */
        val reason: String,
        /** Optional description of progress made so far. */
        val progress: String = "",
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
        val params = try {
            SharedObjectMapper.instance.convertValue(args, Parameters::class.java)
        } catch (_: Exception) {
            return errorResult("Invalid parameters. Required: reason (String). Optional: progress (String)")
        }

        onSignal(params.reason, params.progress)
        return ToolResult(content = listOf(TextContent(acknowledgment)))
    }

    companion object {
        /** Default acknowledgment returned to the member LLM, instructing it to stop. */
        const val DEFAULT_ACKNOWLEDGMENT =
            "[BLOCKED] Your request has been sent to the team leader. " +
                "Please end your response now and briefly summarize your progress so far. " +
                "You will be resumed with the answer later."

        /**
         * Shared description template for member signal tools.
         *
         * @param contextLabel Describes who receives the signal (e.g., "team leader").
         */
        @JvmStatic
        fun signalDescription(contextLabel: String = "team leader"): String = """
Call this tool when you are BLOCKED or UNABLE to complete your assigned task.

## When to Use
- You lack critical information or resources needed to proceed
- You encounter an error or dependency that prevents task completion
- The task assignment is outside your area of expertise
- You need clarification or assistance from the $contextLabel

## Parameters
- reason: A clear description of why you cannot proceed or what you need
- progress: (optional) What you have accomplished so far

## Important
You MUST call this tool if you encounter any blocking issue. Do NOT just mention the problem in text — use this tool to formally signal the $contextLabel.
        """.trimIndent()
    }
}
