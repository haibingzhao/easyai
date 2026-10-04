package com.easy.easyai.tools

import com.easy.easyai.core.tool.ToolCapability
import com.easy.easyai.tools.question.AskQuestionToolBuilder
import com.easy.easyai.tools.shell.BashToolBuilder
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Locks the capability declarations that agent-loop behaviour switches depend on.
 *
 * [ToolCapability] is opt-in metadata: `AgentLoop` reads SHELL_EXECUTION and USER_INTERACTIVE to
 * decide which recovery hints to steer a repeating agent toward, and the sub-agent / team-member
 * filters in easyai-skills are denylists built from these sets.
 * A dropped declaration degrades silently, so the declarations are asserted here directly. The
 * easyai-skills-side declarations (`task`, `delegate_to_member`, `wait_for_member_events`,
 * `resume_member`, `load_skill`) are locked by `MemberToolFilterTest` in that module.
 */
class ToolCapabilityDeclarationTest {

    @Test
    fun `ask question declares user interaction so sub-agents and members cannot use it`() {        assertEquals(setOf(ToolCapability.USER_INTERACTIVE), AskQuestionToolBuilder().capabilities)
    }

    @Test
    fun `bash declares shell execution so shell steering hints are enabled`() {
        assertEquals(setOf(ToolCapability.SHELL_EXECUTION), BashToolBuilder().capabilities)
    }
}
