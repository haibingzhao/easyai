package com.easy.easyai.skills

import com.easy.easyai.skills.command.CommandCategory
import com.easy.easyai.skills.command.DefaultCommandRegistry
import com.easy.easyai.skills.command.McpPromptMeta
import com.easy.easyai.skills.command.McpPromptProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * MCP prompt commands are the one registry category that is not owner-scoped upstream, so the
 * registry has to hand the caller's visibility set straight to the provider: an unscoped listing
 * would advertise every other user's connected servers in `/api/commands`.
 */
class DefaultCommandRegistryTest {

    private val provider = mockk<McpPromptProvider>()

    @Test
    fun `the listing is scoped to the owners handed in`() {
        val owners = listOf("alice", "grp-1", "system")
        val captured = slot<Collection<String>>()
        every { provider.getAllPrompts(capture(captured)) } returns
            mapOf("github" to listOf(McpPromptMeta(name = "issue")))

        val commands = DefaultCommandRegistry(provider).all(owners)

        assertEquals(owners, captured.captured.toList())
        assertEquals(listOf("github:issue"), commands.map { it.name })
        assertEquals(listOf(CommandCategory.MCP), commands.map { it.category })
    }

    @Test
    fun `a colon token resolves through the same owner set`() {
        val owners = listOf("alice", "grp-1", "system")
        val captured = slot<Collection<String>>()
        every { provider.getAllPrompts(capture(captured)) } returns
            mapOf("github" to listOf(McpPromptMeta(name = "issue")))

        val command = DefaultCommandRegistry(provider).resolve("github:issue", owners)

        assertEquals(owners, captured.captured.toList())
        assertEquals("github", command?.mcpServer)
        assertEquals("issue", command?.mcpPromptName)
    }

    @Test
    fun `a server nobody in the visibility set serves is not a command`() {
        every { provider.getAllPrompts(any()) } returns emptyMap()
        assertNull(DefaultCommandRegistry(provider).resolve("github:issue", listOf("alice", "system")))
    }

    @Test
    fun `builtin handlers survive an absent prompt provider`() {
        assertNull(DefaultCommandRegistry(null).resolve("github:issue", listOf("alice")))
        assertEquals(emptyList(), DefaultCommandRegistry(null).all(listOf("alice")))
    }
}
