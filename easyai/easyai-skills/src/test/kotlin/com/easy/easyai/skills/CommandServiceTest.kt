package com.easy.easyai.skills

import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.UserMessage
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.skills.command.BuiltinCommandHandler
import com.easy.easyai.skills.command.CommandCategory
import com.easy.easyai.skills.command.CommandExpansion
import com.easy.easyai.skills.command.CommandInfo
import com.easy.easyai.skills.command.CommandReferenceException
import com.easy.easyai.skills.command.CommandRegistry
import com.easy.easyai.skills.command.CommandService
import com.easy.easyai.skills.command.CommandUtils
import com.easy.easyai.skills.command.McpPromptProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CommandServiceTest {
    @TempDir
    lateinit var root: Path

    private val registry = mockk<CommandRegistry> {
        every { resolve(any()) } returns null
    }
    private val access = mockk<SkillAccessResolver>()
    private val service = CommandService(registry, null, skillAccessResolver = access)

    private fun skill(dir: String, name: String = "code-review", body: String = "fresh body"): SkillInfo {
        val location = root.resolve(dir).resolve("SKILL.md")
        Files.createDirectories(location.parent)
        Files.writeString(location, "---\nname: $name\n---\n$body")
        return SkillInfo(name = name, location = location, content = "stale registry body")
    }

    @Test
    fun `skill references round trip the name token exactly once`() = runTest {
        val skill = skill("project space+(x)")
        coEvery { access.listScopedSkills("alice") } returns listOf(ScopedSkill(skill, null))
        val reference = CommandUtils.skillReference("display-name", skill.name)
        val result = service.resolveAndExpand("$reference inspect", "alice", "session")
        assertEquals(skill.name, result?.commandSource)
        assertEquals("fresh body\n\ninspect", result?.expandedPrompt)
        assertEquals("code-review", result?.commandName)
    }

    @Test
    fun `bare full skill name keeps hyphens and an unknown name is not a command`() = runTest {
        val first = skill("project")
        coEvery { access.listScopedSkills("alice") } returns listOf(ScopedSkill(first, null))
        assertEquals("fresh body\n\ncheck", service.resolveAndExpand("/code-review check", "alice", "s")?.expandedPrompt)
        coEvery { access.listScopedSkills("alice") } returns emptyList()
        assertNull(service.resolveAndExpand("/code-review", "alice", "s"), "a name nobody installed is ordinary text")
    }

    @Test
    fun `a disabled skill leaves the menu and refuses expansion but a saved replay still resolves`() = runTest {
        val skill = skill("project")
        val row = SkillCatalogEntry(
            id = "row-1", name = skill.name, checksum = "c", enabled = false,
            rootPath = root.toString(), installPath = skill.location.parent.toString(), userId = "alice"
        )
        coEvery { access.listScopedSkills("alice") } returns listOf(ScopedSkill(skill, row))

        assertEquals(emptyList<CommandInfo>(), service.listSkillCommands("alice"))
        assertFailsWith<CommandReferenceException> {
            service.resolveAndExpand(CommandUtils.skillReference(skill.name, skill.name), "alice", "s")
        }

        val saved = UserMessage(
            content = listOf(TextContent("/code-review")),
            metadata = mapOf(
                UserMessage.COMMAND_NAME to skill.name,
                UserMessage.COMMAND_EXPANSION to "fresh body",
                UserMessage.COMMAND_CATEGORY to CommandCategory.SKILL.name,
                UserMessage.COMMAND_SOURCE to skill.name,
                UserMessage.COMMAND_USER_ID to "alice"
            )
        )
        service.validateReplay(listOf(saved), "alice")
    }

    @Test
    fun `explicit name outside the visible view is refused even when the file exists`() = runTest {
        val other = skill("other-user")
        coEvery { access.listScopedSkills("alice") } returns emptyList()
        assertFailsWith<CommandReferenceException> {
            service.resolveAndExpand(CommandUtils.skillReference(other.name, other.name), "alice", "s")
        }
    }

    @Test
    fun `unreadable source never falls back to the registry body`() = runTest {
        val missing = skill("missing")
        Files.delete(missing.location)
        coEvery { access.listScopedSkills("alice") } returns listOf(ScopedSkill(missing, null))
        assertFailsWith<CommandReferenceException> {
            service.resolveAndExpand(CommandUtils.skillReference(missing.name, missing.name), "alice", "s")
        }
    }

    @Test
    fun `legacy path tokens and malformed references are rejected`() = runTest {
        coEvery { access.listScopedSkills(any()) } returns emptyList()
        val legacyPathToken = CommandUtils.skillReference("x", root.resolve("SKILL.md").toString())
        for (text in listOf(legacyPathToken, "[/x](skill:%ZZ)", "[/x](skill:relative)")) {
            assertFailsWith<CommandReferenceException> { service.resolveAndExpand(text, "alice", "s") }
        }
        assertNull(service.resolveAndExpand("Example [/x](skill:notes)", "alice", "s"))
    }

    @Test
    fun `builtin null terminates dispatch and goal chinese special case is retained`() = runTest {
        val builtin = mockk<BuiltinCommandHandler> {
            every { name } returns "goal"
            coEvery { execute("s", any(), "alice") } returns null
        }
        val commands = CommandService(registry, null, builtinHandlers = listOf(builtin), skillAccessResolver = access)
        assertNull(commands.resolveAndExpand("/goal中文目标", "alice", "s"))
        coVerify(exactly = 1) { builtin.execute("s", "中文目标", "alice") }
        coVerify(exactly = 0) { access.listScopedSkills(any()) }
    }

    @Test
    fun `explicit skill bypasses same named builtin without executing it`() = runTest {
        val selected = skill("selected", "goal")
        val builtin = mockk<BuiltinCommandHandler> { every { name } returns "goal" }
        val commands = CommandService(registry, null, builtinHandlers = listOf(builtin), skillAccessResolver = access)
        coEvery { access.listScopedSkills("alice") } returns listOf(ScopedSkill(selected, null))
        assertEquals(
            CommandCategory.SKILL,
            commands.resolveAndExpand(CommandUtils.skillReference("goal", "goal"), "alice", "s")?.commandCategory
        )
        coVerify(exactly = 0) { builtin.execute(any(), any(), any()) }
    }

    @Test
    fun `MCP full token including colon reaches prompt provider`() = runTest {
        val provider = mockk<McpPromptProvider>()
        val command = CommandInfo(name = "server:prompt-name", category = CommandCategory.MCP, mcpServer = "server", mcpPromptName = "prompt-name")
        every { registry.resolve("server:prompt-name") } returns command
        coEvery { access.listScopedSkills("alice") } returns emptyList()
        coEvery { provider.getPrompt("server", "prompt-name", emptyMap()) } returns "MCP body"
        val commands = CommandService(registry, provider, skillAccessResolver = access)
        assertEquals("MCP body", commands.resolveAndExpand("/server:prompt-name", "alice", "s")?.expandedPrompt)
    }

    @Test
    fun `skill command list carries the shared marker and name source`() = runTest {
        val shared = skill("shared-dir", "pdf")
        val own = skill("own-dir", "notes")
        coEvery { access.listScopedSkills("alice") } returns listOf(
            ScopedSkill(shared, row(shared, SkillCatalogEntry.DEFAULT_USER_ID)),
            ScopedSkill(own, row(own, "alice")),
        )
        val commands = service.listSkillCommands("alice")
        assertEquals(listOf("pdf", "notes"), commands.map { it.source })
        assertEquals(listOf(true, false), commands.map { it.shared })
    }

    private fun row(skill: SkillInfo, owner: String) = SkillCatalogEntry(
        name = skill.name,
        checksum = "checksum",
        rootPath = skill.location.parent.parent.toString(),
        installPath = skill.location.parent.toString(),
        userId = owner,
    )

    @Test
    fun `replay checks identity but reuses saved body without rereading disk`() = runTest {
        val selected = skill("selected")
        coEvery { access.listScopedSkills("alice") } returns listOf(ScopedSkill(selected, null))
        val snapshot = CommandExpansion(selected.name, "saved body", CommandCategory.SKILL, selected.name)
        val message = UserMessage(content = listOf(TextContent("/code-review")), metadata = service.metadata(snapshot, "alice"))
        Files.delete(selected.location)
        service.validateReplay(listOf(message), "alice")
        coEvery { access.listScopedSkills("alice") } returns emptyList()
        assertFailsWith<CommandReferenceException> { service.validateReplay(listOf(message), "alice") }
    }
}
