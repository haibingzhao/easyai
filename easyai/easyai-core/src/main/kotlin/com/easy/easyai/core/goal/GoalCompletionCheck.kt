package com.easy.easyai.core.goal

import com.easy.easyai.core.agent.AgentCompletionCheck
import com.easy.easyai.core.agent.CompletionCheckInput
import com.easy.easyai.core.agent.CompletionCheckResult
import org.slf4j.LoggerFactory

/**
 * Simplified completion check that keeps the agent loop running while a goal is active.
 *
 * Unlike the previous marker-based implementation, this check relies on the [GoalTool]
 * for explicit state updates. The LLM calls the goal tool to mark completion or blocked status,
 * and this check simply verifies whether the goal is still active and within limits.
 *
 * When the agent loop is about to stop:
 * 1. Looks up the active goal for the session
 * 2. If goal doesn't exist or is not ACTIVE → Done
 * 3. If goal is ACTIVE and within limits → Continue with a simple prompt
 * 4. If limits exceeded → Done (stop the loop)
 */
class GoalCompletionCheck(
    private val goalStore: GoalStore,
    private val goalStatusNotifier: GoalStatusNotifier? = null
) : AgentCompletionCheck {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun check(input: CompletionCheckInput): CompletionCheckResult {
        val sessionId = input.agentContext.sessionId
        if (sessionId.isNullOrBlank()) {
            return CompletionCheckResult.Done
        }

        // Sub-agents should not drive goal completion
        if (input.agentContext.parentAgentId != null) {
            return CompletionCheckResult.Done
        }

        val userId = input.agentContext.userId ?: "system"
        val goal = try {
            goalStore.getGoal(sessionId, userId)
        } catch (e: Exception) {
            logger.warn("Failed to get goal for session {}: {}", sessionId, e.message)
            return CompletionCheckResult.Done
        }

        // No goal or not active → done
        if (goal == null || !goal.isActive) {
            return CompletionCheckResult.Done
        }

        // Check safety limits
        val limitReason = checkLimits(goal)
        if (limitReason != null) {
            logger.info("Goal limit reached for session {}: {}", sessionId, limitReason)

            // Update goal status to LIMIT_REACHED
            val finalGoal = goal.copy(
                status = GoalStatus.LIMIT_REACHED,
                stopReason = limitReason
            ).withHistory("limit", limitReason)

            saveAndNotify(finalGoal, userId)
            return CompletionCheckResult.Done
        }

        // Goal is still active and within limits → continue
        val updatedGoal = goal.copy(turnCount = goal.turnCount + 1)
            .withHistory("auto-continue", "Turn ${goal.turnCount + 1}/${goal.maxTurns}")
        saveAndNotify(updatedGoal, userId)

        logger.info("Goal auto-continue for session {}: turn {}/{}", sessionId, updatedGoal.turnCount, updatedGoal.maxTurns)
        return CompletionCheckResult.Continue(buildContinuePrompt(updatedGoal))
    }

    private suspend fun saveAndNotify(goal: GoalState, userId: String) {
        try {
            goalStore.saveGoal(goal, userId)
        } catch (e: Exception) {
            logger.warn("Failed to save goal state for session {}: {}", goal.sessionId, e.message)
        }
        goalStatusNotifier?.notifyGoalChanged(goal)
    }

    private fun checkLimits(goal: GoalState): String? {
        if (goal.turnCount >= goal.maxTurns) {
            return "Auto-continue turn limit reached (${goal.turnCount}/${goal.maxTurns})"
        }
        if (goal.elapsedMs >= goal.maxDurationMs) {
            val seconds = goal.elapsedMs / 1000
            return "Duration limit reached (${seconds}s / ${goal.maxDurationMs / 1000}s)"
        }
        return null
    }

    private fun buildContinuePrompt(goal: GoalState): String = buildString {
        appendLine("<goal_continuation>")
        appendLine("The goal below is still active. Continue working toward it.")
        appendLine()
        appendLine("<goal_objective>")
        appendLine(escapeXml(goal.objective))
        appendLine("</goal_objective>")
        appendLine()
        appendLine("## Progress")
        appendLine("- Turns used: ${goal.turnCount}/${goal.maxTurns}")
        appendLine("- Time elapsed: ${goal.elapsedMs / 1000}s/${goal.maxDurationMs / 1000}s")
        appendLine()
        appendLine("## Next Steps")
        appendLine("1. Take the next concrete step toward the goal")
        appendLine("2. Verify your progress against the goal objective")
        appendLine("3. When the goal is complete, use the `goal` tool with action=\"update_status\", status=\"completed\", and provide evidence")
        appendLine("4. If you're blocked, use the `goal` tool with status=\"blocked\" or ask the user for clarification")
        appendLine("</goal_continuation>")
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
