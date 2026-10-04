package com.easy.easyai.skills.team

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolCapability
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import com.easy.easyai.skills.SkillToolBuilder
import com.easy.easyai.skills.subagent.SubAgentTool
import com.easy.easyai.skills.subagent.SubAgentToolBuilder
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Minimal [ToolDefinition] carrying a capability set, so the filters can be exercised against
 * what the real builders declare without building the tools themselves.
 */
private class CapabilityProbe(
    override val name: String,
    override val capabilities: Set<ToolCapability>
) : ToolDefinition {
    override val description: String = "probe"
    override val inputSchema: String = "{}"
    override suspend fun execute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult = ToolResult(content = listOf(TextContent("ok")))
}

/**
 * Locks the capability declarations that the member / sub-agent tool filters depend on.
 *
 * Those filters are denylists over opt-in metadata: a tool that declares nothing is handed to team
 * members and sub-agents silently. `ask_question` (USER_INTERACTIVE) lives in easyai-tools, which
 * this module must not depend on, so its declaration is asserted by `ToolCapabilityDeclarationTest`.
 */
class MemberToolFilterTest {

    private val stateRegistry = mockk<TeamCoordinationStateRegistry>(relaxed = true)

    private val builders: List<ToolBuilder> = listOf(
        SubAgentToolBuilder(agentStore = null),
        DelegateToMemberToolBuilder(agentStore = null, stateRegistry = stateRegistry),
        WaitForMemberEventsToolBuilder(agentStore = null, stateRegistry = stateRegistry),
        ResumeMemberToolBuilder(agentStore = null, stateRegistry = stateRegistry),
        SkillToolBuilder(
            registry = null,
            catalogProvider = mockk(relaxed = true),
            refresherProvider = mockk(relaxed = true),
            ragEnabled = false
        )
    )

    private fun probe(toolName: String): ToolDefinition =
        builders.single { it.name == toolName }.let { CapabilityProbe(it.name, it.capabilities) }

    @Test
    fun `builders declare the capabilities the filters match on`() {
        assertEquals(setOf(ToolCapability.SPAWNS_SUBAGENTS), probe("task").capabilities)
        assertEquals(setOf(ToolCapability.SKILL_LOADING), probe("load_skill").capabilities)
        for (name in listOf("delegate_to_member", "wait_for_member_events", "resume_member")) {
            assertEquals(setOf(ToolCapability.TEAM_COORDINATION), probe(name).capabilities, name)
        }
    }

    @Test
    fun `member filter blocks sub-agent spawning and team coordination`() {
        for (name in listOf("task", "delegate_to_member", "wait_for_member_events", "resume_member")) {
            assertTrue(DelegateToMemberTool.isForbiddenForMember(probe(name)), "$name must not reach a team member")
        }
    }

    @Test
    fun `member filter still passes skill loading and undeclared tools`() {
        assertFalse(DelegateToMemberTool.isForbiddenForMember(probe("load_skill")))
        // ask_leader (MemberSignalTool) declares nothing and is appended after filtering.
        assertFalse(DelegateToMemberTool.isForbiddenForMember(CapabilityProbe("ask_leader", emptySet())))
    }

    @Test
    fun `sub-agent filter blocks recursion and user interaction but not team coordination`() {
        assertTrue(SubAgentTool.isForbiddenForSubAgent(probe("task")))
        assertFalse(SubAgentTool.isForbiddenForSubAgent(probe("load_skill")))
        assertFalse(SubAgentTool.isForbiddenForSubAgent(probe("delegate_to_member")))
    }
}
