package com.easy.easyai.tools.mcp

import com.easy.easyai.skills.command.McpPromptMeta
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Covers the owner-priority shadowing behind [McpClientManager.getVisibleServers] and
 * [McpClientManager.getAllPrompts]: a tool reaches the model as `{server}__{tool}` and a prompt
 * command as `server:prompt`, neither carrying an owner, so two visible buckets holding the same
 * server name must collapse to one, deterministically.
 */
class McpClientManagerTest {

    private fun server(owner: String, name: String) =
        McpServerTools(userId = owner, serverName = name, tools = emptyList())

    private fun prompts(vararg names: String) = names.map { McpPromptMeta(name = it) }

    @Nested
    inner class ShadowByName {

        @Test
        fun `a name held by several buckets is won by the highest priority owner`() {
            val visible = McpClientManager.shadowByName(
                listOf(server("system", "github"), server("grp-1", "github"), server("alice", "github")),
                listOf("alice", "grp-1", "system")
            )
            assertEquals(listOf("alice" to "github"), visible.map { it.userId to it.serverName })
        }

        @Test
        fun `the group bucket wins when the caller has no server of that name`() {
            val visible = McpClientManager.shadowByName(
                listOf(server("system", "github"), server("grp-1", "github"), server("alice", "linear")),
                listOf("alice", "grp-1", "system")
            )
            assertEquals(
                listOf("alice" to "linear", "grp-1" to "github"),
                visible.map { it.userId to it.serverName }
            )
        }

        @Test
        fun `distinct names all survive and follow owner priority, not cache order`() {
            val visible = McpClientManager.shadowByName(
                listOf(server("system", "a"), server("alice", "b"), server("grp-1", "c")),
                listOf("alice", "grp-1", "system")
            )
            assertEquals(listOf("b", "c", "a"), visible.map { it.serverName })
        }

        @Test
        fun `an owner outside the visibility set sorts last instead of winning by iteration order`() {
            val visible = McpClientManager.shadowByName(
                listOf(server("eve", "github"), server("alice", "github")),
                listOf("alice", "system")
            )
            assertEquals(listOf("alice"), visible.map { it.userId })
        }
    }

    @Nested
    inner class ShadowPrompts {

        @Test
        fun `a server name in two buckets keeps the highest priority owner's prompts`() {
            val shadowed = McpClientManager.shadowPrompts(
                mapOf(
                    "system:github" to prompts("system-issue"),
                    "alice:github" to prompts("own-issue"),
                ),
                listOf("alice", "system")
            )
            assertEquals(mapOf("github" to prompts("own-issue")), shadowed)
        }

        @Test
        fun `the owner prefix is stripped and distinct servers all survive`() {
            val shadowed = McpClientManager.shadowPrompts(
                mapOf(
                    "system:github" to prompts("issue"),
                    "grp-1:linear" to prompts("ticket"),
                ),
                listOf("alice", "grp-1", "system")
            )
            assertEquals(setOf("github", "linear"), shadowed.keys)
            assertEquals(prompts("ticket"), shadowed["linear"])
        }
    }
}
