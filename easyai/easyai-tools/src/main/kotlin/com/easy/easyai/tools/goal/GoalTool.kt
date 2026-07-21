package com.easy.easyai.tools.goal

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.goal.GoalState
import com.easy.easyai.core.goal.GoalStatus
import com.easy.easyai.core.goal.GoalStatusNotifier
import com.easy.easyai.core.goal.GoalStore
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.*
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Tool for managing goal state during agent execution.
 *
 * Allows the LLM to:
 * - Update goal status (active, completed, blocked, paused)
 * - Modify goal objective text
 * - Add completion evidence
 *
 * This replaces the previous text-marker based protocol ([goal:complete], [goal:blocked])
 * with explicit tool calls, making goal management more reliable and explicit.
 */
class GoalTool(
    metadata: ToolMetadata,
    private val goalStore: GoalStore,
    private val goalStatusNotifier: GoalStatusNotifier?
) : BaseToolDefinition(metadata) {

    companion object {
        private val logger = LoggerFactory.getLogger(GoalTool::class.java)
        private val objectMapper = SharedObjectMapper.instance
    }

    override val executionMode: ToolExecutionMode = ToolExecutionMode.SEQUENTIAL

    override fun parameterType(): Class<*> = GoalToolParameter::class.java

    @Suppress("UNCHECKED_CAST")
    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult = withContext(Dispatchers.IO) {
        try {
            val paramsJson = objectMapper.writeValueAsString(args)
            val params = objectMapper.readValue(paramsJson, GoalToolParameter::class.java)

            val sessionId = agentContext.sessionId
            if (sessionId.isNullOrBlank()) {
                return@withContext ToolResult(
                    content = listOf(TextContent(text = "Error: No active session for goal management")),
                    isError = true
                )
            }

            val userId = agentContext.userId ?: "system"
            val currentGoal = goalStore.getGoal(sessionId, userId) ?: return@withContext ToolResult(
                content = listOf(TextContent(text = "Error: No active goal in this session")),
                isError = true
            )

            val updatedGoal = applyAction(currentGoal, params)
                ?: return@withContext ToolResult(
                    content = listOf(TextContent(text = "Error: Invalid action '${params.action}'")),
                    isError = true
                )

            goalStore.saveGoal(updatedGoal, userId)
            goalStatusNotifier?.notifyGoalChanged(updatedGoal)

            logger.info("Goal updated for session {}: action={}, status={}", sessionId, params.action, updatedGoal.status)

            ToolResult(
                content = listOf(TextContent(text = buildResultMessage(updatedGoal, params.action)))
            )
        } catch (e: Exception) {
            logger.error("Error executing goal tool in tool call {}", toolCallId, e)
            ToolResult(
                content = listOf(TextContent(text = "Failed to update goal: ${e.message}")),
                isError = true
            )
        }
    }

    private fun applyAction(currentGoal: GoalState, params: GoalToolParameter): GoalState? {
        return when (params.action.lowercase()) {
            "update_status" -> {
                val newStatus = params.status?.let { parseStatus(it) } ?: return null
                val reason = params.reason
                var updated = currentGoal.copy(
                    status = newStatus,
                    stopReason = reason ?: when (newStatus) {
                        GoalStatus.COMPLETED -> "completed via goal tool"
                        GoalStatus.BLOCKED -> "blocked"
                        GoalStatus.PAUSED -> "paused via goal tool"
                        else -> null
                    }
                )
                if (newStatus == GoalStatus.COMPLETED && params.evidence != null) {
                    updated = updated.copy(completionEvidence = params.evidence)
                }
                if (newStatus == GoalStatus.BLOCKED && params.reason != null) {
                    updated = updated.copy(blockedReason = params.reason)
                }
                updated.withHistory(params.action, "Status changed to ${newStatus.name.lowercase()}${reason?.let { ": $it" } ?: ""}")
            }
            "update_objective" -> {
                val newObjective = params.objective ?: return null
                currentGoal.copy(objective = newObjective)
                    .withHistory("objective_updated", "Objective updated: ${newObjective.take(100)}")
            }
            "add_evidence" -> {
                val evidence = params.evidence ?: return null
                currentGoal.copy(
                    completionEvidence = evidence,
                    status = GoalStatus.COMPLETED,
                    stopReason = "completed with evidence"
                ).withHistory("evidence_added", "Evidence: ${evidence.take(200)}")
            }
            else -> null
        }
    }

    private fun parseStatus(status: String): GoalStatus? {
        return when (status.lowercase()) {
            "active" -> GoalStatus.ACTIVE
            "completed" -> GoalStatus.COMPLETED
            "blocked" -> GoalStatus.BLOCKED
            "paused" -> GoalStatus.PAUSED
            else -> null
        }
    }

    private fun buildResultMessage(goal: GoalState, action: String): String = buildString {
        when (action.lowercase()) {
            "update_status" -> {
                appendLine("Goal status updated to: ${goal.status.name.lowercase()}")
                goal.stopReason?.let { appendLine("Reason: $it") }
                goal.completionEvidence?.let { appendLine("Evidence: $it") }
                goal.blockedReason?.let { appendLine("Blocker: $it") }
            }
            "update_objective" -> {
                appendLine("Goal objective updated:")
                appendLine(goal.objective)
            }
            "add_evidence" -> {
                appendLine("Evidence added and goal marked as completed:")
                appendLine(goal.completionEvidence)
            }
        }
        appendLine()
        appendLine("Current progress: Turn ${goal.turnCount}/${goal.maxTurns}")
    }
}

/**
 * Parameters for the GoalTool.
 */
data class GoalToolParameter(
    @param:JsonPropertyDescription("Action to perform: 'update_status', 'update_objective', or 'add_evidence'")
    val action: String,
    @param:JsonPropertyDescription("Target status for update_status action: 'active', 'completed', 'blocked', or 'paused'")
    val status: String? = null,
    @param:JsonPropertyDescription("New objective text for update_objective action")
    val objective: String? = null,
    @param:JsonPropertyDescription("Evidence text for add_evidence action or when status is 'completed'")
    val evidence: String? = null,
    @param:JsonPropertyDescription("Reason for status change (e.g., blocker description for 'blocked' status)")
    val reason: String? = null
)
