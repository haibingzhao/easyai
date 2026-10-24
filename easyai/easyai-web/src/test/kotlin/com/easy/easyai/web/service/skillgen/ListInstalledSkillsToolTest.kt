package com.easy.easyai.web.service.skillgen

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.skills.ScopedSkill
import com.easy.easyai.skills.SkillAccessResolver
import com.easy.easyai.skills.SkillInfo
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * The name list the installer agent reads before choosing a slug. What a bucket contains comes from
 * the catalog row alone: an unbound skill has no recorded owner, so counting it as another layer's
 * name would send the agent to warn about a shadow nobody resolved.
 */
class ListInstalledSkillsToolTest {

    private val userId = "joe"
    private val owners = listOf("joe", "group-1", "system")

    private fun scoped(name: String, rowOwner: String?): ScopedSkill = ScopedSkill(
        SkillInfo(
            name = name,
            description = "Use when demonstrating $name",
            location = Path.of("/tmp/skills/${rowOwner ?: userId}/$name/SKILL.md"),
            content = "body"
        ),
        rowOwner?.let { owner ->
            SkillCatalogEntry(
                name = name,
                checksum = "x",
                rootPath = "/tmp/skills/$owner",
                installPath = "/tmp/skills/$owner/$name",
                userId = owner
            )
        }
    )

    private suspend fun CoroutineScope.list(vararg skills: ScopedSkill): String {
        val resolver = mockk<SkillAccessResolver>()
        coEvery { resolver.listScopedSkillsForOwners(owners) } returns skills.toList()
        val result = ListInstalledSkillsTool(resolver, writeOwner = userId, userId = userId, owners = owners)
            .execute(
                agentContext = AgentContext(agentId = "skill-installer"),
                toolCallId = "tc-1",
                args = emptyMap(),
                coroutineScope = this,
                onUpdate = {}
            )
        return result.content.filterIsInstance<TextContent>().joinToString("") { it.text }
    }

    @Test
    fun `a row of another layer is reported as shadowable`() = runTest {
        val output = list(scoped("demo", "system"))

        assertContains(output, "[shared] demo")
        assertContains(output, "Names already installed in the target bucket: none")
        assertContains(output, "other layers")
    }

    @Test
    fun `a row of the target bucket is reported as taken`() = runTest {
        val output = list(scoped("demo", userId))

        assertContains(output, "[personal] demo")
        assertContains(output, "Names already installed in the target bucket: demo")
        assertFalse(output.contains("other layers"))
    }

    @Test
    fun `a skill with no catalog row claims no layer`() = runTest {
        val output = list(scoped("demo", null))

        assertContains(output, "[disk] demo")
        assertContains(output, "no catalog row")
        assertFalse(output.contains("other layers"))
        assertFalse(output.contains("Names already installed in the target bucket: demo"))
    }

    @Test
    fun `a deployment without a resolver says the name check is unavailable`() = runTest {
        val result = ListInstalledSkillsTool(null, writeOwner = userId, userId = userId, owners = owners)
            .execute(
                agentContext = AgentContext(agentId = "skill-installer"),
                toolCallId = "tc-1",
                args = emptyMap(),
                coroutineScope = this,
                onUpdate = {}
            )

        assertContains(
            result.content.filterIsInstance<TextContent>().joinToString("") { it.text },
            "installed-name checks are unavailable"
        )
    }
}
