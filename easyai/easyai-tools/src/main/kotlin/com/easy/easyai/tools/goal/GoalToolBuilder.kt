package com.easy.easyai.tools.goal

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.goal.GoalStatusNotifier
import com.easy.easyai.core.goal.GoalStore
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import org.springframework.stereotype.Component

private const val GOAL_TOOL_DESCRIPTION = """Manage the active goal for this session.

Use this tool to update goal status, modify the objective, or add completion evidence.

Actions:
- "update_status": Change goal status (active, completed, blocked, paused)
  - When marking "completed", provide evidence of completion
  - When marking "blocked", provide the reason/blocker
- "update_objective": Modify the goal's objective text
- "add_evidence": Add evidence and mark goal as completed

IMPORTANT: Call this tool when:
1. You have completed the goal and want to provide evidence
2. You are blocked and need user input (use status="blocked" with a reason, or use ask_question)
3. You want to update the goal objective based on new information
4. You want to pause the goal temporarily

Do NOT call this tool to report progress - only use it for state changes."""

/**
 * Builder for [GoalTool].
 *
 * This tool is conditionally available - it requires a GoalStore to be configured
 * in the system. The tool is only available when goals are active in the session.
 */
@Component
class GoalToolBuilder(
    private val goalStore: GoalStore,
    private val goalStatusNotifier: GoalStatusNotifier?
) : ToolBuilder {
    override val metadata = ToolMetadata(
        name = "goal",
        description = GOAL_TOOL_DESCRIPTION,
        permissionCategory = "goal",
        isDefaultTool = true,
        skipOnResume = false
    )

    override val defaultPermissionRules = listOf(
        PermissionRule("tool.execute.goal", "*", PermissionAction.ALLOW)
    )

    override fun build(context: AgentContext, agentService: AgentService): ToolDefinition {
        return GoalTool(metadata, goalStore, goalStatusNotifier)
    }
}
