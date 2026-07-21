package com.easy.easyai.swarm.tool

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.*
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicReference

/**
 * Result captured when a team member calls the [EscalationTool].
 */
data class EscalationResult(val reason: String)

/**
 * LLM-callable tool for a team member to explicitly signal escalation.
 *
 * When a member agent is blocked, lacks information, or cannot complete its assigned task,
 * it calls this tool with a reason. The escalation is recorded in a shared [AtomicReference]
 * that [com.easy.easyai.swarm.runtime.TeamTaskExecutor] reads after execution completes.
 *
 * Thread-safe: uses [AtomicReference] to support parallel member execution within a coroutine scope.
 */
class EscalationTool(
    metadata: ToolMetadata,
    private val escalationRef: AtomicReference<EscalationResult?>,
) : BaseToolDefinition(metadata) {

    override val executionMode = ToolExecutionMode.SEQUENTIAL

    data class Parameters(
        /** Reason why the member cannot complete the assigned task. */
        val reason: String
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
            return errorResult("Invalid parameters. Required: reason (String)")
        }

        escalationRef.set(EscalationResult(params.reason))
        return ToolResult(content = listOf(TextContent("Escalation recorded: ${params.reason}")))
    }

    companion object {
        val DESCRIPTION = """
Call this tool when you are BLOCKED or UNABLE to complete your assigned task.

## When to Use
- You lack critical information or resources needed to proceed
- You encounter an error or dependency that prevents task completion
- The task assignment is outside your area of expertise
- You need clarification or reassignment from the team leader

## Parameters
- reason: A clear description of why you cannot proceed

## Important
You MUST call this tool if you encounter any blocking issue. Do NOT just mention the problem in text — use this tool to formally escalate.
        """.trimIndent()
    }
}
