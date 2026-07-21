package com.easy.easyai.web.service

import com.easy.easyai.core.goal.GoalState
import com.easy.easyai.core.goal.GoalStatusNotifier
import com.easy.easyai.core.goal.GoalStore
import com.easy.easyai.skills.command.BuiltinCommandHandler
import com.easy.easyai.skills.command.CommandExpansion
import org.slf4j.LoggerFactory

/**
 * Handles `/goal` slash command as a built-in command.
 *
 * This handler is responsible for creating new goals. Goal lifecycle management
 * (pause, resume, edit, delete) is handled via REST API endpoints and the GoalTool.
 *
 * When a goal is set:
 * 1. Creates and persists a [GoalState] to the session
 * 2. Returns a [CommandExpansion] containing a system prompt that:
 *    - Informs the agent about the active goal
 *    - Explains how to use the GoalTool to update goal status
 *    - Instructs to use AskQuestion if clarification is needed
 */
class GoalCommandHandler(
    private val goalStore: GoalStore,
    private val goalStatusNotifier: GoalStatusNotifier? = null
) : BuiltinCommandHandler {

    override val name: String = "goal"
    override val description: String = "Set a goal for the agent to work toward autonomously"
    override val hints: List<String> = listOf($$"$1")

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun execute(sessionId: String, args: String, userId: String): CommandExpansion? {
        val trimmedArgs = args.trim()

        // No args → usage hint
        if (trimmedArgs.isEmpty()) {
            return null
        }

        // Create new goal
        val goal = GoalState(
            sessionId = sessionId,
            objective = trimmedArgs
        )
        goalStore.saveGoal(goal.withHistory("set", "Goal set: $trimmedArgs"), userId)
        goalStatusNotifier?.notifyGoalChanged(goal)
        logger.info("Goal set for session {}: {}", sessionId, trimmedArgs)

        return CommandExpansion(
            commandName = name,
            expandedPrompt = buildGoalFrameworkPrompt(goal),
        )
    }

    private fun buildGoalFrameworkPrompt(goal: GoalState): String = buildString {
        appendLine("<goal_framework>")
        appendLine("A goal has been set for this session. You must work toward completing it autonomously.")
        appendLine()
        appendLine("<goal_objective>")
        appendLine(escapeXml(goal.objective))
        appendLine("</goal_objective>")
        appendLine()
        appendLine("## Goal Management")
        appendLine()
        appendLine("You have access to the `goal` tool to manage the goal state:")
        appendLine()
        appendLine("### When to use the goal tool:")
        appendLine("1. **Goal Completed**: When you believe the goal is satisfied, call `goal` with:")
        appendLine("   - action: \"update_status\"")
        appendLine("   - status: \"completed\"")
        appendLine("   - evidence: A summary of what you verified to confirm completion")
        appendLine()
        appendLine("2. **Goal Blocked**: When you cannot proceed without user input, call `goal` with:")
        appendLine("   - action: \"update_status\"")
        appendLine("   - status: \"blocked\"")
        appendLine("   - reason: A clear description of what's blocking you")
        appendLine("   - OR use the `ask_question` tool to ask the user directly")
        appendLine()
        appendLine("3. **Update Objective**: If the goal needs refinement based on new information:")
        appendLine("   - action: \"update_objective\"")
        appendLine("   - objective: The refined goal text")
        appendLine()
        appendLine("### Important Guidelines:")
        appendLine("- Do NOT just output text markers like [goal:complete] - use the goal tool instead")
        appendLine("- Provide concrete evidence when marking a goal as completed")
        appendLine("- Be specific about blockers when marking a goal as blocked")
        appendLine("- If the goal is unclear, use `ask_question` to clarify before proceeding")
        appendLine()
        appendLine("## Budget")
        appendLine("- Maximum auto-continue turns: ${goal.maxTurns}")
        appendLine("- Maximum duration: ${goal.maxDurationMs / 1000} seconds")
        appendLine("- Current turn: 0/${goal.maxTurns}")
        appendLine("</goal_framework>")
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
