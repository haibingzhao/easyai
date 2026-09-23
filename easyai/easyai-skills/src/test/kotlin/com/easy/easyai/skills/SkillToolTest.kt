package com.easy.easyai.skills

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.tool.ToolExecutionMode
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillToolTest {
    private val f = SkillModelFixture()
    private val global = f.skill("review")
    private val local = f.skill("review", f.project)
    private val context = AgentContext(agentId = "test", userId = "alice", projectPath = f.project)

    private fun tool(allowed: List<String> = listOf("review"), catalog: AsyncSkillCatalogStore? = f.catalog) = SkillTool(
        ToolMetadata("load_skill", "Load skill", permissionCategory = "skill"), f.registry, allowed, catalog, f.config
    )

    private suspend fun load(scope: CoroutineScope, tool: SkillTool = tool(), name: String = "review"): ToolResult =
        tool.execute(context, "tc-1", args = mapOf("name" to name), coroutineScope = scope, onUpdate = {})

    private fun text(result: ToolResult): String = result.content.filterIsInstance<TextContent>().joinToString { it.text }

    @Nested
    inner class Gates {
        @Test
        fun `empty whitelist and unknown names are rejected before registry or catalog reads`() = runTest {
            assertTrue(load(this, tool(emptyList())).isError)
            assertTrue(load(this, name = "secret").isError)
            verify(exactly = 0) { f.registry.all() }
            coVerify(exactly = 0) { f.catalog.listByUser(any()) }
        }

        @Test
        fun `blank name requires a parameter`() = runTest {
            assertTrue(text(load(this, name = " ")).contains("required"))
        }

        @Test
        fun `project overrides global and disabled override cannot resurrect global`() = runTest {
            f.skills = listOf(global, local)
            f.rows = listOf(f.row(global, "system"), f.row(local))
            val tool = tool()
            val first = load(this, tool)
            assertFalse(first.isError)
            assertTrue(text(first).contains(local.content))
            assertFalse(text(first).contains(global.content))
            f.rows = listOf(f.row(global, "system"), f.row(local, enabled = false))
            assertTrue(load(this, tool).isError)
        }

        @Test
        fun `fallback is per identity and honors shared disabled state`() = runTest {
            val unrelated = f.skill("other")
            f.skills = listOf(global, unrelated)
            f.rows = listOf(f.row(global, "system"), f.row(unrelated))
            val tool = tool()
            assertFalse(load(this, tool).isError)
            f.rows = listOf(f.row(global, "system", enabled = false), f.row(unrelated))
            assertTrue(load(this, tool).isError)
        }

        @Test
        fun `other tenants and mismatched install paths cannot supply content`() = runTest {
            f.skills = listOf(global)
            f.rows = listOf(f.row(global, "bob"))
            assertTrue(load(this).isError)
            f.rows = listOf(f.row(global).copy(installPath = "/shared/skills/other"))
            assertTrue(load(this).isError)
            coVerify(exactly = 0) { f.catalog.listByUser("bob") }
        }

        @Test
        fun `without catalog only explicitly shared globals load`() = runTest {
            f.skills = listOf(local)
            assertTrue(load(this, tool(catalog = null)).isError)
            f.skills = listOf(global)
            assertFalse(load(this, tool(catalog = null)).isError)
            f.skills = listOf(global.copy(location = global.location.resolveSibling("../../unknown/SKILL.md")))
            assertTrue(load(this, tool(catalog = null)).isError)
        }

        @Test
        fun `catalog outage does not reuse a previously authorized view`() = runTest {
            f.skills = listOf(global)
            f.rows = listOf(f.row(global))
            val tool = tool()
            assertFalse(load(this, tool).isError)
            coEvery { f.catalog.listByUser("alice") } throws IllegalStateException("db down")
            val failure = load(this, tool)
            assertTrue(failure.isError)
            assertTrue(text(failure).contains("catalog is unavailable"))
            assertFalse(text(failure).contains(global.content))
        }

        @Test
        fun `catalog cancellation propagates`() = runTest {
            coEvery { f.catalog.listByUser(any()) } throws CancellationException("cancelled")
            assertFailsWith<CancellationException> { load(this) }
        }
    }

    @Nested
    inner class Content {
        @Test
        fun `load includes authoritative content description and tags`() = runTest {
            f.skills = listOf(global.copy(tags = setOf("coding", "review")))
            f.rows = listOf(f.row(global))
            val result = load(this)
            assertFalse(result.isError)
            assertTrue(text(result).contains(global.content))
            assertTrue(text(result).contains("coding, review"))
            assertTrue(text(result).contains("Base directory"))
            assertEquals(ToolExecutionMode.SEQUENTIAL, tool().executionMode)
        }
    }
}
