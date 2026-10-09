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
    private val context = AgentContext(agentId = "test", userId = "alice")

    private fun tool(allowed: List<String> = listOf("review"), catalog: AsyncSkillCatalogStore? = f.catalog) = SkillTool(
        ToolMetadata("load_skill", "Load skill", permissionCategory = "skill"), f.registry, allowed, catalog
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
            verify(exactly = 0) { f.registry.visibleForOwners(any()) }
            coVerify(exactly = 0) { f.catalog.listByOwners(any()) }
        }

        @Test
        fun `blank name requires a parameter`() = runTest {
            assertTrue(text(load(this, name = " ")).contains("required"))
        }

        @Test
        fun `own skill shadows the shared namesake and a disabled own row cannot resurrect it`() = runTest {
            val shared = f.register("review", SkillModelFixture.SYSTEM)
            val own = f.register("review")
            f.rows = listOf(f.row(shared), f.row(own))
            val tool = tool()
            val first = load(this, tool)
            assertFalse(first.isError)
            assertTrue(text(first).contains(own.content))
            assertFalse(text(first).contains(shared.content))
            f.rows = listOf(f.row(shared), f.row(own, enabled = false))
            assertTrue(load(this, tool).isError)
        }

        @Test
        fun `shared disabled state is honored per identity`() = runTest {
            val shared = f.register("review", SkillModelFixture.SYSTEM)
            val unrelated = f.register("other")
            f.rows = listOf(f.row(shared), f.row(unrelated))
            val tool = tool()
            assertFalse(load(this, tool).isError)
            f.rows = listOf(f.row(shared, enabled = false), f.row(unrelated))
            assertTrue(load(this, tool).isError)
        }

        @Test
        fun `other tenants and mismatched install paths cannot supply content`() = runTest {
            val shared = f.register("review", SkillModelFixture.SYSTEM)
            f.rows = listOf(f.row(shared, user = "bob"))
            assertTrue(load(this).isError)
            f.rows = listOf(f.row(shared).copy(installPath = "/.easyai-fixture-skills/system/other"))
            assertTrue(load(this).isError)
            val queried = mutableListOf<List<String>>()
            coVerify(atLeast = 1) { f.catalog.listByOwners(capture(queried)) }
            assertTrue(queried.none { "bob" in it }, "another tenant's bucket must never be queried: $queried")
        }

        @Test
        fun `without catalog the registry snapshot loads unbound`() = runTest {
            // Single-machine dev mode has no rows at all: the scanned snapshot is the authorization.
            val own = f.register("own")
            val unbound = tool(allowed = listOf("own"), catalog = null)
            assertFalse(load(this, unbound, name = "own").isError)
            assertTrue(text(load(this, unbound, name = "own")).contains(own.content))
        }

        @Test
        fun `shared binding is announced in the loaded content`() = runTest {
            val shared = f.register("review", SkillModelFixture.SYSTEM)
            f.rows = listOf(f.row(shared))
            assertTrue(text(load(this)).contains("read-only"))
        }

        @Test
        fun `catalog outage does not reuse a previously authorized view`() = runTest {
            val own = f.register("review")
            f.rows = listOf(f.row(own))
            val tool = tool()
            assertFalse(load(this, tool).isError)
            coEvery { f.catalog.listByOwners(any()) } throws IllegalStateException("db down")
            val failure = load(this, tool)
            assertTrue(failure.isError)
            assertTrue(text(failure).contains("catalog is unavailable"))
            assertFalse(text(failure).contains(own.content))
        }

        @Test
        fun `catalog cancellation propagates`() = runTest {
            f.register("review")
            coEvery { f.catalog.listByOwners(any()) } throws CancellationException("cancelled")
            assertFailsWith<CancellationException> { load(this) }
        }
    }

    @Nested
    inner class Content {
        @Test
        fun `load includes authoritative content description and tags`() = runTest {
            val own = f.register("review", tags = setOf("coding", "review"))
            f.rows = listOf(f.row(own))
            val result = load(this)
            assertFalse(result.isError)
            assertTrue(text(result).contains(own.content))
            assertTrue(text(result).contains("coding, review"))
            assertTrue(text(result).contains("Base directory"))
            assertEquals(ToolExecutionMode.SEQUENTIAL, tool().executionMode)
        }
    }
}
