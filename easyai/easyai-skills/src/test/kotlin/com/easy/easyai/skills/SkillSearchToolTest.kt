package com.easy.easyai.skills

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.skill.AsyncSkillCatalogStore
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
    private val context = AgentContext(agentId = "a", userId = "alice")

    private fun tool(
        allowed: List<String> = listOf("review", "notes"),
        index: SkillStore? = store,
        catalog: AsyncSkillCatalogStore? = f.catalog
    ) = SkillSearchTool(
        ToolMetadata("skill_search", "Search skills", permissionCategory = "skill"),
        index, catalog, registry = f.registry, allowedSkillNames = allowed
    )

    private suspend fun search(
        scope: CoroutineScope,
        tool: SkillSearchTool = tool(),
        query: String = "semantic query",
        topK: Int? = null,
        context: AgentContext = this.context
    ): ToolResult = tool.execute(
        context, "tc-1",
        args = if (topK == null) mapOf("query" to query) else mapOf("query" to query, "topK" to topK),
        coroutineScope = scope, onUpdate = {}
    )

    private fun text(result: ToolResult) = result.content.filterIsInstance<TextContent>().joinToString { it.text }

    @Nested
    inner class Isolation {
        @Test
        fun `prompt search and load bind a name to the same enabled instance`() = runTest {
            val shared = f.register("review", SkillModelFixture.SYSTEM)
            val own = f.register("review", "alice")
            val notes = f.register("notes", "alice")
            f.rows = listOf(f.row(shared), f.row(own), f.row(notes))
            val allowed = listOf("review", "notes")
            val prompt = SkillPromptSource(f.registry, f.catalog, true, false)
            assertEquals(own.description, prompt.skillsForPrompt("alice", allowed).first { it["name"] == "review" }["description"])
            coEvery { store.search(any(), any(), any()) } returns listOf(f.hit(own), f.hit(notes))
            val found = search(this, tool(allowed), query = "semantic")
            assertTrue(text(found).contains(own.location.toString()))
            assertFalse(text(found).contains(shared.location.toString()))
            val loaded = SkillTool(
                ToolMetadata("load_skill", "Load", permissionCategory = "skill"), f.registry, allowed, f.catalog
            ).execute(context, "load", args = mapOf("name" to "review"), coroutineScope = this, onUpdate = {})
            assertFalse(loaded.isError)
            assertTrue(text(loaded).contains(own.content))
            assertFalse(text(loaded).contains(shared.content))
        }

        @Test
        fun `one query covers the requester slice and the shared slice`() = runTest {
            val shared = f.register("pdf", SkillModelFixture.SYSTEM)
            val own = f.register("notes", "alice")
            f.rows = listOf(f.row(shared), f.row(own))
            coEvery { store.search(any(), any(), any()) } returns listOf(f.hit(shared), f.hit(own))
            val result = search(this, tool(listOf("pdf", "notes")))
            assertFalse(result.isError)
            assertTrue(text(result).contains("[shared] pdf:"))
            assertTrue(text(result).contains("[mine] notes:"))
            assertFalse(text(result).contains("untrusted"))
            coVerify(exactly = 1) {
                store.search("semantic query", listOf("alice", SkillModelFixture.SYSTEM), any())
            }
        }

        @Test
        fun `requester slice leads the owner list even when a shared name sorts first`() = runTest {
            val shared = f.register("aaa-guide", SkillModelFixture.SYSTEM)
            val own = f.register("zzz-report", "alice")
            f.rows = listOf(f.row(shared), f.row(own))
            coEvery { store.search(any(), any(), any()) } returns listOf(f.hit(own), f.hit(shared))
            search(this, tool(listOf("aaa-guide", "zzz-report")))
            // The backend dedupes by name in the order owners are given, so a name-sorted list would
            // let the shared slice shadow the requester's own skill.
            coVerify(exactly = 1) {
                store.search("semantic query", listOf("alice", SkillModelFixture.SYSTEM), any())
            }
        }

        @Test
        fun `wrong owner key location or checksum hits never pass by name alone`() = runTest {
            val shared = f.register("review", SkillModelFixture.SYSTEM)
            val own = f.register("review", "alice")
            f.rows = listOf(f.row(shared), f.row(own))
            coEvery { store.search(any(), any(), any()) } returns listOf(
                // The shared install of the same name: alice's effective view has no such instance.
                f.hit(shared),
                f.hit(own).copy(key = "skills/other.md"),
                f.hit(own).copy(location = "/other/root/alice/review/SKILL.md"),
                f.hit(own).copy(checksum = "old"),
                f.hit(own),
            )
            val result = search(this, tool(listOf("review")))
            assertTrue(text(result).contains("[mine] review:"))
            assertFalse(text(result).contains(shared.location.toString()))
        }

        @Test
        fun `shared slice names a hit only when it matches the shared install`() = runTest {
            val shared = f.register("review", SkillModelFixture.SYSTEM)
            f.rows = listOf(f.row(shared))
            coEvery { store.search(any(), any(), any()) } returns listOf(f.hit(shared))
            val result = search(this, tool(listOf("review")))
            assertTrue(text(result).contains("[shared] review:"))
            coVerify(exactly = 1) { store.search(any(), listOf(SkillModelFixture.SYSTEM), any()) }
        }

        @Test
        fun `empty whitelist rejects before catalog and index access`() = runTest {
            assertTrue(search(this, tool(emptyList())).isError)
            coVerify(exactly = 0) { f.catalog.listByUser(any()) }
            coVerify(exactly = 0) { store.search(any(), any(), any()) }
        }
    }

    @Nested
    inner class FreshnessAndFallback {
        @Test
        fun `pending index and absent store use bounded authorized name and description matching`() = runTest {
            val review = f.register("review", "alice")
            val obscure = f.register("obscure", "alice", description = "Nothing to match here")
            f.rows = listOf(
                f.row(review, state = SkillSyncState.PENDING_INDEX),
                f.row(obscure, state = SkillSyncState.PENDING_INDEX),
            )
            val result = search(this, tool(listOf("review", "obscure")), query = "review", topK = 1)
            assertFalse(result.isError)
            assertTrue(text(result).contains("Found 1 skill"))
            assertFalse(text(result).contains("obscure"))
            coVerify(exactly = 0) { store.search(any(), any(), any()) }
            assertTrue(text(search(this, tool(listOf("review", "obscure"), index = null), query = "review")).contains("[mine] review:"))
        }

        @Test
        fun `a reused search tool rereads disablement and catalog failure is explicitly unavailable`() = runTest {
            val review = f.register("review", "alice")
            f.rows = listOf(f.row(review))
            coEvery { store.search(any(), any(), any()) } returns listOf(f.hit(review))
            val searchTool = tool(listOf("review"))
            assertTrue(text(search(this, searchTool, query = "review")).contains("[mine] review:"))
            f.rows = listOf(f.row(review, enabled = false))
            assertTrue(text(search(this, searchTool, query = "review")).contains("No skills found"))
            coEvery { f.catalog.listByUser("alice") } throws IllegalStateException("db down")
            val failed = search(this, searchTool, query = "review")
            assertTrue(failed.isError)
            assertTrue(text(failed).contains("catalog is unavailable"))
        }

        @Test
        fun `no catalog exposes registry skills unbound and never indexes`() = runTest {
            val review = f.register("review", "alice")
            val result = search(this, tool(listOf("review"), catalog = null), query = "tasks")
            assertTrue(text(result).contains("[mine] review:"))
            coVerify(exactly = 0) { store.search(any(), any(), any()) }
        }

        @Test
        fun `cancellation propagates from both catalog and retrieval`() = runTest {
            val review = f.register("review", "alice")
            f.rows = listOf(f.row(review))
            coEvery { store.search(any(), any(), any()) } throws CancellationException("cancelled")
            assertFailsWith<CancellationException> { search(this, tool(listOf("review"))) }
            coEvery { f.catalog.listByUser(any()) } throws CancellationException("cancelled")
            assertFailsWith<CancellationException> { search(this, tool(listOf("review"))) }
        }

        @Test
        fun `topK is capped and blank queries are rejected`() = runTest {
            val review = f.register("review", "alice")
            f.rows = listOf(f.row(review))
            coEvery { store.search(any(), any(), any()) } returns emptyList()
            search(this, tool(listOf("review")), topK = 10000)
            coVerify(exactly = 1) { store.search(any(), any(), 20 * 2) }
            assertTrue(search(this, tool(listOf("review")), query = " ").isError)
            coVerify(exactly = 1) { store.search(any(), any(), any()) }
        }
    }
}
