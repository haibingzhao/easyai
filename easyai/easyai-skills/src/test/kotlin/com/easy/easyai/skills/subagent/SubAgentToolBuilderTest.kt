package com.easy.easyai.skills.subagent

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.agent.AsyncAgentStore
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubAgentToolBuilderTest {

    private val agentService = mockk<AgentService>(relaxed = true)

    private fun builder(store: AsyncAgentStore? = mockk(relaxed = true)) = SubAgentToolBuilder(
        agentStore = store,
        subAgentContextResolver = null,
        subAgentMessageListenerFactory = null
    )

    @Nested
    inner class `Registration guards` {

        @Test
        fun `registers task tool for primary agent without predefined sub-agents`() {
            val context = AgentContext(agentId = "primary", subAgents = emptyList())
            assertIs<SubAgentTool>(builder().build(context, agentService))
        }

        @Test
        fun `registers task tool when predefined sub-agents exist`() {
            val context = AgentContext(
                agentId = "primary",
                subAgents = listOf(mapOf("id" to "pre1", "name" to "coder"))
            )
            assertIs<SubAgentTool>(builder().build(context, agentService))
        }

        @Test
        fun `does not register for sub-agents (recursion guard)`() {
            val context = AgentContext(agentId = "child", parentAgentId = "primary")
            assertNull(builder().build(context, agentService))
        }

        @Test
        fun `does not register without agent store`() {
            val context = AgentContext(agentId = "primary")
            assertNull(builder(store = null).build(context, agentService))
        }
    }

    @Nested
    inner class `Metadata` {

        @Test
        fun `description documents the dynamic agentSpec mode`() {
            val metadata = builder().metadata
            assertTrue(metadata.description.contains("agentType='dynamic'"), metadata.description)
            assertTrue(metadata.description.contains("agentSpec"), metadata.description)
        }
    }
}
