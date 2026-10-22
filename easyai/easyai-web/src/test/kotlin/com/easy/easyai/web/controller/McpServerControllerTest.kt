package com.easy.easyai.web.controller

import com.easy.easyai.auth.group.GroupClaims
import com.easy.easyai.tools.mcp.AsyncMcpServerStore
import com.easy.easyai.tools.mcp.McpClientManager
import com.easy.easyai.tools.mcp.McpServerConfig
import com.easy.easyai.tools.mcp.McpServerStatus
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import reactor.util.context.Context
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [McpServerController] bucket targeting.
 *
 * A server name is unique only within its bucket, so the same name can be listed twice — once under
 * the caller and once under the group. These tests pin that every row-addressed call (toggle,
 * reconnect, delete, tool/prompt read) hits the bucket named by `scope` instead of falling back to a
 * self-first name lookup, and that a member cannot toggle a shared row.
 */
class McpServerControllerTest {

    private class InMemoryMcpServerStore : AsyncMcpServerStore {
        /** Owner to its persisted config. The real table also keys by name; these tests use one row per owner. */
        val rows = mutableMapOf<String, McpServerConfig>()

        override suspend fun findAll(userId: String): List<McpServerConfig> =
            rows.filterKeys { it == userId }.values.toList()

        override suspend fun findByName(name: String, userId: String): McpServerConfig? =
            rows[userId]?.takeIf { it.name == name }

        override suspend fun save(config: McpServerConfig, userId: String) {
            rows[userId] = config
        }

        override suspend fun update(config: McpServerConfig, userId: String) {
            if (rows[userId]?.name == config.name) rows[userId] = config
        }

        override suspend fun delete(name: String, userId: String) {
            if (rows[userId]?.name == name) rows.remove(userId)
        }

        override suspend fun findAllEnabled(): List<McpServerConfig> = rows.values.filter { it.enabled }

        override suspend fun findAllEnabled(userId: String): List<McpServerConfig> =
            findAll(userId).filter { it.enabled }
    }

    private val store = InMemoryMcpServerStore()
    private val manager = mockk<McpClientManager>()

    private val personal = config("alice", name = "shared-name")
    private val group = config("grp-1", name = "shared-name")

    private fun config(userId: String, name: String) = McpServerConfig(
        id = "$userId-$name",
        name = name,
        type = "remote",
        url = "https://example.com/$userId",
        userId = userId,
    )

    private fun controller() = McpServerController(store, manager)

    /** Seed a persisted row without a coroutine; the store's suspend surface is not under test here. */
    private fun seed(owner: String, config: McpServerConfig) {
        store.rows[owner] = config
    }

    @BeforeEach
    fun stubManager() {
        coEvery { manager.ensureOwnersConnected(any()) } just Runs
        every { manager.getConnectedServers(any<Collection<String>>()) } returns emptyList()
        every { manager.getStatus(any(), any()) } returns McpServerStatus.Disabled
        coEvery { manager.connect(any(), any()) } returns McpServerStatus.Connected
        every { manager.disconnect(any(), any()) } just Runs
        every { manager.getToolDefs(any(), any()) } returns emptyList()
        every { manager.getServerPrompts(any(), any()) } returns emptyList()
    }

    private fun asAlice(claims: GroupClaims? = null): Context =
        ReactiveSecurityContextHolder.withAuthentication(
            UsernamePasswordAuthenticationToken("alice", "", emptyList<GrantedAuthority>()).apply {
                details = claims
            }
        )

    private fun asGroupOwner(): Context = asAlice(
        GroupClaims(owners = listOf("grp-1"), groupId = "g1", groupUserId = "grp-1", isGroupOwner = true)
    )

    private fun asGroupMember(): Context = asAlice(
        GroupClaims(owners = listOf("grp-1"), groupId = "g1", groupUserId = "grp-1", isGroupOwner = false)
    )

    @Nested
    inner class `listing` {

        @Test
        fun `same-named servers from both buckets stay distinct rows`() {
            seed("alice", personal)
            seed("grp-1", group)

            val rows = controller().listAll().contextWrite(asGroupMember()).block()!!

            assertEquals(listOf("alice", "grp-1"), rows.map { it.ownerId })
            assertEquals(listOf("shared-name", "shared-name"), rows.map { it.name })
        }
    }

    @Nested
    inner class `write targeting` {

        @Test
        fun `a group-scoped reconnect targets the group row, not the personal twin`() {
            seed("alice", personal)
            seed("grp-1", group)

            val dto = controller().reconnect("shared-name", "group").contextWrite(asGroupOwner()).block()!!

            assertEquals("grp-1", dto.ownerId)
            coVerify(exactly = 1) { manager.connect(match { it.userId == "grp-1" }, "grp-1") }
            coVerify(exactly = 0) { manager.connect(match { it.userId == "alice" }, any()) }
        }

        @Test
        fun `an unscoped reconnect targets the caller's personal row`() {
            seed("alice", personal)
            seed("grp-1", group)

            val dto = controller().reconnect("shared-name", null).contextWrite(asGroupOwner()).block()!!

            assertEquals("alice", dto.ownerId)
            coVerify(exactly = 1) { manager.connect(match { it.userId == "alice" }, "alice") }
        }

        @Test
        fun `a member cannot toggle the shared row`() {
            seed("alice", personal)
            seed("grp-1", group)

            val ex = assertFailsWith<ResponseStatusException> {
                controller().reconnect("shared-name", "group").contextWrite(asGroupMember()).block()
            }

            assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
            coVerify(exactly = 0) { manager.connect(any(), any()) }
        }

        @Test
        fun `a group-scoped update edits only the group row`() {
            seed("alice", personal)
            seed("grp-1", group)

            val dto = controller().update(
                "shared-name",
                McpServerCreateRequest(
                    name = "shared-name",
                    type = "remote",
                    url = "https://changed.example.com",
                    enabled = false,
                ),
                "group",
            ).contextWrite(asGroupOwner()).block()!!

            assertEquals("grp-1", dto.ownerId)
            assertEquals("https://changed.example.com", store.rows.getValue("grp-1").url)
            assertEquals("https://example.com/alice", store.rows.getValue("alice").url)
            verify(exactly = 1) { manager.disconnect("shared-name", "grp-1") }
        }

        @Test
        fun `a group-scoped delete removes only the group row`() {
            seed("alice", personal)
            seed("grp-1", group)

            controller().delete("shared-name", "group").contextWrite(asGroupOwner()).block()

            assertEquals(setOf("alice"), store.rows.keys)
        }
    }

    @Nested
    inner class `read targeting` {

        @Test
        fun `tools are read from the scoped bucket`() {
            seed("alice", personal)
            seed("grp-1", group)

            controller().getTools("shared-name", "group").contextWrite(asGroupMember()).block()

            verify(exactly = 1) { manager.getToolDefs("grp-1", "shared-name") }
            verify(exactly = 0) { manager.getToolDefs("alice", "shared-name") }
        }

        @Test
        fun `a member may read the shared server's prompts`() {
            seed("alice", personal)
            seed("grp-1", group)

            controller().getPrompts("shared-name", "group").contextWrite(asGroupMember()).block()

            verify(exactly = 1) { manager.getServerPrompts("shared-name", "grp-1") }
        }

        @Test
        fun `a group-scoped read without an active group is refused`() {
            val ex = assertFailsWith<ResponseStatusException> {
                controller().getTools("shared-name", "group").contextWrite(asAlice()).block()
            }

            assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        }
    }
}
