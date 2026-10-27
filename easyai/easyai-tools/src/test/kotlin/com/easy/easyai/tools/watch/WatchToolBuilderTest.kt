package com.easy.easyai.tools.watch

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.watch.WatchService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The builder decides whether `watch` reaches the model at all. A host without the watch subsystem
 * must keep booting without the tool; an interactive main agent with the service must get it —
 * that second case silently failed when the dependency was a nullable constructor default, because
 * Spring could fall back to Kotlin's generated no-arg constructor and leave the field null.
 */
class WatchToolBuilderTest {

    private val service = mockk<WatchService>(relaxed = true)
    private val provider = mockk<ObjectProvider<WatchService>>()
    private val agentService = mockk<AgentService>(relaxed = true)

    private fun builder(available: WatchService? = service): WatchToolBuilder {
        every { provider.ifAvailable } returns available
        return WatchToolBuilder(provider)
    }

    private fun context(
        unattended: Boolean = false,
        parentAgentId: String? = null,
        swarmRunId: String? = null,
        sessionId: String? = "session-1",
        userId: String? = "alice"
    ) = AgentContext(
        agentId = "investment-analyst",
        sessionId = sessionId,
        userId = userId,
        parentAgentId = parentAgentId,
        swarmRunId = swarmRunId,
        unattended = unattended
    )

    private fun build(ctx: AgentContext, watchBuilder: WatchToolBuilder = builder()) = runBlocking {
        watchBuilder.build(ctx, agentService)
    }

    @Nested
    inner class `Interactive main agent` {
        @Test
        fun `builds the watch tool when the host wires a watch service`() {
            assertEquals("watch", build(context())?.name)
        }

        @Test
        fun `stays hidden when the host wires no watch service`() {
            assertNull(build(context(), builder(available = null)))
        }
    }

    @Nested
    inner class `Contexts with no user to confirm a watch` {
        @Test
        fun `hidden for unattended runs`() {
            assertNull(build(context(unattended = true)))
        }

        @Test
        fun `hidden for sub-agents`() {
            assertNull(build(context(parentAgentId = "parent-agent")))
        }

        @Test
        fun `hidden inside swarm runs`() {
            assertNull(build(context(swarmRunId = "swarm-run-1")))
        }

        @Test
        fun `hidden without a session`() {
            assertNull(build(context(sessionId = null)))
        }

        @Test
        fun `hidden without an authenticated user`() {
            assertNull(build(context(userId = null)))
        }
    }
}
