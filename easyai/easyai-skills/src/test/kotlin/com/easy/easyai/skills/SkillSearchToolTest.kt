package com.easy.easyai.skills

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillOwnerContext
import com.easy.easyai.core.skill.SkillScope
import com.easy.easyai.core.skill.SkillStore
import com.easy.easyai.core.skill.SkillSyncState
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillSearchToolTest {
    private val f = SkillModelFixture()
    private val store = mockk<SkillStore>(relaxed = true)
    private val global = f.skill("global")
    private val local = f.skill("local", f.project)
    private val context = AgentContext(agentId = "a", userId = "alice", projectPath = f.project)

    private fun tool(
        allowed: List<String> = listOf("global", "local"),
        index: SkillStore? = store,
        catalog: AsyncSkillCatalogStore? = f.catalog
    ) = SkillSearchTool(
        ToolMetadata("skill_search", "Search skills", permissionCategory = "skill"),
        index, catalog, registry = f.registry, config = f.config, allowedSkillNames = allowed
    )

    private suspend fun search(
        scope: CoroutineScope,
        tool: SkillSearchTool = tool(),
        query: String = "semantic query",
        filter: String? = null,
        topK: Int = 5,
        context: AgentContext = this.context
    ): ToolResult = tool.execute(
        context, "tc-1", args = mapOf("query" to query, "scope" to filter, "topK" to topK),
        coroutineScope = scope, onUpdate = {}
    )

    private fun text(result: ToolResult) = result.content.filterIsInstance<TextContent>().joinToString { it.text }

    @Nested
    inner class Isolation {
        @Test
        fun `prompt search and load bind a name to the same enabled project instance`() = runTest {
            val override = f.skill("global", f.project, "Project-specific instructions")
            f.skills = listOf(global, override)
            f.rows = listOf(f.row(global, "system"), f.row(override))
            val allowed = listOf("global")
            val prompt = SkillPromptSource(f.registry, f.catalog, true, false, config = f.config)
            assertEquals(override.description, prompt.skillsForPrompt("alice", f.project, allowed).single()["description"])
            coEvery { store.search(any(), any(), any(), any()) } returns listOf(f.hit(override))
            val found = search(this, tool(allowed))
            assertTrue(text(found).contains(override.location.toString()))
            assertFalse(text(found).contains(global.location.toString()))
            val loaded = SkillTool(
                ToolMetadata("load_skill", "Load", permissionCategory = "skill"), f.registry, allowed, f.catalog, f.config
            ).execute(context, "load", args = mapOf("name" to "global"), coroutineScope = this, onUpdate = {})
            assertFalse(loaded.isError)
            assertTrue(text(loaded).contains(override.content))
            assertFalse(text(loaded).contains(global.content))
        }

        @Test
        fun `owner and project slices are queried separately including system fallback`() = runTest {
            f.skills = listOf(global, local)
            f.rows = listOf(f.row(global, "system"), f.row(local))
            coEvery { store.search(any(), listOf(SkillScope.GLOBAL), SkillOwnerContext("system", null), any()) } returns listOf(f.hit(global))
            coEvery { store.search(any(), listOf(SkillScope.PROJECT), SkillOwnerContext("alice", f.project), any()) } returns listOf(f.hit(local))
            val result = search(this)
            assertFalse(result.isError)
            assertTrue(text(result).contains("[global] global:"))
            assertTrue(text(result).contains("[project] local:"))
            assertFalse(text(result).contains("untrusted"))
            coVerify(exactly = 1) { store.search(any(), listOf(SkillScope.GLOBAL), SkillOwnerContext("system", null), any()) }
            coVerify(exactly = 1) { store.search(any(), listOf(SkillScope.PROJECT), SkillOwnerContext("alice", f.project), any()) }
            coVerify(exactly = 2) { store.search(any(), any(), any(), any()) }
        }

        @Test
        fun `wrong owner scope key or source hits never pass by name alone`() = runTest {
            f.skills = listOf(global, local)
            f.rows = listOf(f.row(global, "system"), f.row(local))
            coEvery { store.search(any(), listOf(SkillScope.GLOBAL), any(), any()) } returns listOf(
                f.hit(local), f.hit(global).copy(scope = SkillScope.PROJECT),
                f.hit(global).copy(location = "/shared/skills/foreign/SKILL.md"),
                f.hit(global).copy(key = "skills/other.md"), f.hit(global).copy(checksum = "old")
            )
            coEvery { store.search(any(), listOf(SkillScope.PROJECT), any(), any()) } returns listOf(f.hit(global))
            assertTrue(text(search(this)).contains("No skills found"))
        }

        @Test
        fun `same name global hit cannot advertise the overridden instance even with global filter`() = runTest {
            val override = f.skill("global", f.project)
            f.skills = listOf(global, override)
            f.rows = listOf(f.row(global, "system"), f.row(override))
            coEvery { store.search(any(), any(), any(), any()) } returns listOf(f.hit(global))
            val searchTool = tool()
            assertTrue(text(search(this, searchTool)).contains("No skills found"))
            assertTrue(text(search(this, searchTool, query = "global", filter = "global")).contains("No skills found"))
            f.rows = listOf(f.row(global, "system"), f.row(override, enabled = false))
            assertTrue(text(search(this, searchTool, query = "global")).contains("No skills found"))
        }

        @Test
        fun `empty whitelist rejects before catalog and index access`() = runTest {
            assertTrue(search(this, tool(emptyList())).isError)
            coVerify(exactly = 0) { f.catalog.listByUser(any()) }
            coVerify(exactly = 0) { store.search(any(), any(), any(), any()) }
        }

        @Test
        fun `current project is exact and no project request never queries project slices`() = runTest {
            val ancestor = f.skill("parent", f.project.parent)
            f.skills = listOf(global, local, ancestor)
            f.rows = f.skills.map { f.row(it) }
            search(this, tool(listOf("global", "local", "parent")))
            coVerify(exactly = 0) { store.search(any(), any(), SkillOwnerContext("alice", f.project.parent), any()) }
            search(this, context = context.copy(projectPath = null), filter = "project")
            coVerify(exactly = 1) { store.search(any(), listOf(SkillScope.PROJECT), any(), any()) }
        }
    }

    @Nested
    inner class FreshnessAndFallback {
        @Test
        fun `pending index and absent store use bounded authorized name and description matching`() = runTest {
            f.skills = listOf(global, local, f.skill("hidden"))
            f.rows = f.skills.map { f.row(it, state = SkillSyncState.PENDING_INDEX) }
            val result = search(this, query = "tasks", topK = 1)
            assertFalse(result.isError)
            assertTrue(text(result).contains("Found 1 skill"))
            assertFalse(text(result).contains("hidden"))
            coVerify(exactly = 0) { store.search(any(), any(), any(), any()) }
            assertTrue(text(search(this, tool(index = null), query = "local")).contains("[project] local:"))
        }

        @Test
        fun `project index failure does not swallow independent global results`() = runTest {
            f.skills = listOf(global, local)
            f.rows = listOf(f.row(global, "system"), f.row(local))
            coEvery { store.search(any(), listOf(SkillScope.PROJECT), any(), any()) } throws IllegalStateException("slice unavailable")
            coEvery { store.search(any(), listOf(SkillScope.GLOBAL), any(), any()) } returns listOf(f.hit(global))
            assertTrue(text(search(this)).contains("[global] global:"))
            assertTrue(text(search(this, query = "local")).contains("[project] local:"))
        }

        @Test
        fun `a reused search tool rereads disablement and catalog failure is explicitly unavailable`() = runTest {
            f.skills = listOf(global)
            f.rows = listOf(f.row(global))
            val searchTool = tool()
            assertTrue(text(search(this, searchTool, query = "global")).contains("[global] global:"))
            f.rows = listOf(f.row(global, enabled = false))
            assertTrue(text(search(this, searchTool, query = "global")).contains("No skills found"))
            coEvery { f.catalog.listByUser("alice") } throws IllegalStateException("db down")
            val failed = search(this, searchTool, query = "global")
            assertTrue(failed.isError)
            assertTrue(text(failed).contains("catalog is unavailable"))
            coVerify(exactly = 1) { store.search(any(), any(), any(), any()) }
        }

        @Test
        fun `no catalog means local matching for explicit global sources only`() = runTest {
            f.skills = listOf(global, local)
            val result = search(this, tool(catalog = null), query = "tasks")
            assertTrue(text(result).contains("[global] global:"))
            assertFalse(text(result).contains("[project] local:"))
            coVerify(exactly = 0) { store.search(any(), any(), any(), any()) }
        }

        @Test
        fun `cancellation propagates from both catalog and retrieval`() = runTest {
            f.skills = listOf(global)
            f.rows = listOf(f.row(global))
            coEvery { store.search(any(), any(), any(), any()) } throws CancellationException("cancelled")
            assertFailsWith<CancellationException> { search(this) }
            coEvery { f.catalog.listByUser(any()) } throws CancellationException("cancelled")
            assertFailsWith<CancellationException> { search(this) }
        }

        @Test
        fun `topK is capped and blank queries are rejected`() = runTest {
            f.skills = listOf(global)
            f.rows = listOf(f.row(global))
            search(this, topK = 10000)
            coVerify(exactly = 1) { store.search(any(), any(), any(), 20) }
            assertTrue(search(this, query = " ").isError)
            coVerify(exactly = 1) { store.search(any(), any(), any(), any()) }
        }
    }
}
