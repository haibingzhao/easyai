package com.easy.easyai.tools.question

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import org.springframework.stereotype.Component

/**
 * Builder for [AskQuestionTool].
 */
@Component
class AskQuestionToolBuilder : ToolBuilder {
    override val metadata = ToolMetadata(
        name = "ask_question",
        description = """
        Use this tool when you need to ask the user questions during execution. This allows you to:
        1. Gather user preferences or requirements
        2. Clarify ambiguous instructions
        3. Get decisions on implementation choices as you work
        4. Offer choices to the user about what direction to take.

        Usage notes:
        - Set allowOther: true to add an "Other" option with text input when selected
        - Answers are returned as arrays of labels; set multiple: true to allow selecting more than one
        - If you recommend a specific option, make that the first option in the list and add "(Recommended)" at the end of the label
        - IMPORTANT: When the user's request is unclear, ambiguous, or you need more information to proceed, use this tool to clarify before taking action
        - Don't make assumptions about user intent - ask first
    """,
        permissionCategory = "interaction",
        uiRenderer = "ask_question",
        skipOnResume = true
    )
    override val defaultPermissionRules = listOf(
        PermissionRule("tool.execute.interaction", "*", PermissionAction.ALLOW)
    )

    override fun build(context: AgentContext, agentService: AgentService): ToolDefinition {
        return AskQuestionTool(metadata)
    }
}
