package com.easy.easyai.core.agent

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * [AgentContext.effectiveOwners] is what every runtime resolver reads to decide group visibility.
 * The contract: use the boundary-supplied [AgentContext.owners] when present, else fall back to the
 * pre-group `{userId, system}` so sub-agents / swarm workers / ephemeral contexts that never set it
 * resolve exactly as before. [AgentContext.auxOwners] is the aux-model variant: the same set minus
 * the `system` fold-in (kept only when `system` is the caller), matching the web `currentGroupOwners()`.
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

    @Test
    fun `auxOwners drops the system fold-in but keeps self and group`() {
        val ctx = AgentContext(agentId = "a", userId = "alice", owners = setOf("alice", "grp_1", "system"))
        assertEquals(setOf("alice", "grp_1"), ctx.auxOwners)
    }

    @Test
    fun `auxOwners drops system from the userId-plus-system fallback`() {
        val ctx = AgentContext(agentId = "a", userId = "alice")
        assertEquals(setOf("alice"), ctx.auxOwners)
    }

    @Test
    fun `auxOwners keeps system when it is the caller (auth-disabled)`() {
        val ctx = AgentContext(agentId = "a", userId = "system", owners = setOf("system"))
        assertEquals(setOf("system"), ctx.auxOwners)
    }
}
