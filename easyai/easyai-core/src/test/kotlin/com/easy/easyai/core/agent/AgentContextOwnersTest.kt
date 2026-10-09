package com.easy.easyai.core.agent

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * [AgentContext.effectiveOwners] is what every runtime resolver reads to decide group visibility.
 * The contract: use the boundary-supplied [AgentContext.owners] when present, else fall back to the
 * pre-group `{userId, system}` so sub-agents / swarm workers / ephemeral contexts that never set it
 * resolve exactly as before.
 */
class AgentContextOwnersTest {

    @Test
    fun `uses the supplied owners when set`() {
        val ctx = AgentContext(agentId = "a", userId = "alice", owners = setOf("alice", "grp_1", "system"))
        assertEquals(setOf("alice", "grp_1", "system"), ctx.effectiveOwners)
    }

    @Test
    fun `falls back to userId plus system when owners unset`() {
        val ctx = AgentContext(agentId = "a", userId = "alice")
        assertEquals(setOf("alice", "system"), ctx.effectiveOwners)
    }

    @Test
    fun `falls back to system alone when there is no userId either`() {
        val ctx = AgentContext(agentId = "a")
        assertEquals(setOf("system"), ctx.effectiveOwners)
    }
}
