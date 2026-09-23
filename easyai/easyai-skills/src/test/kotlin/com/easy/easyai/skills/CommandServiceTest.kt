package com.easy.easyai.skills

import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.UserMessage
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
    fun `source round trips unicode percent plus parentheses and hash exactly once`() = runTest {
        val skill = skill("项目 space+(x)#%20")
        coEvery { access.listScopedSkills("alice", root) } returns listOf(ScopedSkill(skill, null))
        val reference = CommandUtils.skillReference("display-name", skill.location.toString())
        val result = service.resolveAndExpand("$reference inspect", "alice", "session", root)
        assertEquals(skill.location.toString(), result?.commandSource)
        assertEquals("fresh body\n\ninspect", result?.expandedPrompt)
        assertEquals("code-review", result?.commandName)
    }

    @Test
    fun `bare full skill name preserves hyphens and refuses ambiguous sources`() = runTest {
        val first = skill("project")
        val second = skill("global")
        coEvery { access.listScopedSkills("alice", root) } returns listOf(ScopedSkill(first, null))
        assertEquals("fresh body\n\ncheck", service.resolveAndExpand("/code-review check", "alice", "s", root)?.expandedPrompt)
        coEvery { access.listScopedSkills("alice", root) } returns listOf(ScopedSkill(first, null), ScopedSkill(second, null))
        assertFailsWith<CommandReferenceException> { service.resolveAndExpand("/code-review", "alice", "s", root) }
    }

    @Test
    fun `explicit source must be scoped even when file exists`() = runTest {
        val other = skill("other-user")
        coEvery { access.listScopedSkills("alice", root) } returns emptyList()
        assertFailsWith<CommandReferenceException> {
            service.resolveAndExpand(CommandUtils.skillReference(other.name, other.location.toString()), "alice", "s", root)
        }
    }

    @Test
    fun `missing source never falls back to same name`() = runTest {
        val missing = skill("missing")
        Files.delete(missing.location)
        coEvery { access.listScopedSkills("alice", root) } returns listOf(ScopedSkill(missing, null))
        assertFailsWith<CommandReferenceException> {
            service.resolveAndExpand(CommandUtils.skillReference(missing.name, missing.location.toString()), "alice", "s", root)
        }
    }

    @Test
    fun `relative malformed and unregistered references are rejected`() = runTest {
        coEvery { access.listScopedSkills(any(), any()) } returns emptyList()
        for (text in listOf("[/x](skill:relative%2FSKILL.md)", "[/x](skill:%ZZ)", "[/x](skill:%2Ftmp%2FSKILL.md)")) {
            assertFailsWith<CommandReferenceException> { service.resolveAndExpand(text, "alice", "s", root) }
        }
        assertNull(service.resolveAndExpand("Example [/x](skill:%2Ftmp%2FSKILL.md)", "alice", "s", root))
    }

    @Test
    fun `builtin null terminates dispatch and goal chinese special case is retained`() = runTest {
        val builtin = mockk<BuiltinCommandHandler> {
            every { name } returns "goal"
            coEvery { execute("s", any(), "alice") } returns null
        }
        val commands = CommandService(registry, null, builtinHandlers = listOf(builtin), skillAccessResolver = access)
        assertNull(commands.resolveAndExpand("/goal中文目标", "alice", "s", root))
        coVerify(exactly = 1) { builtin.execute("s", "中文目标", "alice") }
        coVerify(exactly = 0) { access.listScopedSkills(any(), any()) }
    }

    @Test
    fun `explicit skill bypasses same named builtin without executing it`() = runTest {
        val selected = skill("selected", "goal")
        val builtin = mockk<BuiltinCommandHandler> { every { name } returns "goal" }
        val commands = CommandService(registry, null, builtinHandlers = listOf(builtin), skillAccessResolver = access)
        coEvery { access.listScopedSkills("alice", root) } returns listOf(ScopedSkill(selected, null))
        assertEquals(CommandCategory.SKILL, commands.resolveAndExpand(CommandUtils.skillReference("goal", selected.location.toString()), "alice", "s", root)?.commandCategory)
        coVerify(exactly = 0) { builtin.execute(any(), any(), any()) }
    }

    @Test
    fun `MCP full token including colon reaches prompt provider`() = runTest {
        val provider = mockk<McpPromptProvider>()
        val command = CommandInfo(name = "server:prompt-name", category = CommandCategory.MCP, mcpServer = "server", mcpPromptName = "prompt-name")
        every { registry.resolve("server:prompt-name") } returns command
        coEvery { access.listScopedSkills("alice", root) } returns emptyList()
        coEvery { provider.getPrompt("server", "prompt-name", emptyMap()) } returns "MCP body"
        val commands = CommandService(registry, provider, skillAccessResolver = access)
        assertEquals("MCP body", commands.resolveAndExpand("/server:prompt-name", "alice", "s", root)?.expandedPrompt)
    }

    @Test
    fun `replay checks identity but reuses saved body without rereading disk`() = runTest {
        val selected = skill("selected")
        coEvery { access.listScopedSkills("alice", root) } returns listOf(ScopedSkill(selected, null))
        val snapshot = CommandExpansion(selected.name, "saved body", CommandCategory.SKILL, selected.location.toString())
        val message = UserMessage(content = listOf(TextContent("/code-review")), metadata = service.metadata(snapshot, "alice", root))
        Files.delete(selected.location)
        service.validateReplay(listOf(message), "alice", root)
        coEvery { access.listScopedSkills("alice", root) } returns emptyList()
        assertFailsWith<CommandReferenceException> { service.validateReplay(listOf(message), "alice", root) }
    }
}
