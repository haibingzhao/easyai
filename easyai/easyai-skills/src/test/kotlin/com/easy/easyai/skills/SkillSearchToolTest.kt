package com.easy.easyai.skills

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillEntry
import com.easy.easyai.core.skill.SkillOwnerContext
import com.easy.easyai.core.skill.SkillScope
import com.easy.easyai.core.skill.SkillStore
import com.easy.easyai.core.tool.ToolMetadata
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [SkillSearchTool] — the read half of on-demand loading, and the place where the first
 * two authorization gates meet: which slices are queried, and which hits the catalog vouches for.
 *
 * The negative assertions matter as much as the positive ones: a discovery tool that reports an
 * error result can stall an agent loop, so every degraded path must read as "nothing matched".
 */
class SkillSearchToolTest {

    private val store = mockk<SkillStore>(relaxed = true)
    private val catalog = mockk<AsyncSkillCatalogStore>(relaxed = true)
    private val project = Path.of("/work/repo")

    private val metadata = ToolMetadata(
        name = "skill_search",
        description = "Discover skills",
        permissionCategory = "skill",
        isDefaultTool = false
    )

    private fun tool(
        catalogStore: AsyncSkillCatalogStore? = catalog,
        searchTopK: Int = SkillSearchTool.DEFAULT_SEARCH_TOP_K
    ) = SkillSearchTool(metadata, store, catalogStore, searchTopK)

    private fun hit(name: String, scope: SkillScope = SkillScope.GLOBAL, description: String = "does things") = SkillEntry(
        key = SkillEntry.keyFor(name),
        name = name,
        description = description,
        tags = listOf("reports"),
        content = "body",
        location = "/skills/$name/SKILL.md",
        scope = scope,
        score = 0.9
    )

    private fun row(name: String, userId: String = "alice", enabled: Boolean = true) = SkillCatalogEntry(
        id = "row-$name-$userId",
        name = name,
        checksum = "a".repeat(64),
        enabled = enabled,
        installPath = "/home/$userId/.easyai/skills/$name",
        userId = userId
    )

    /** Extension on [CoroutineScope] so the tool can be handed a real scope, as the agent loop does. */
    private suspend fun CoroutineScope.search(
        tool: SkillSearchTool,
        args: Map<String, Any?>,
        context: AgentContext = AgentContext(agentId = "a", userId = "alice", projectPath = project)
    ): Pair<String, Boolean> {
        val result = tool.execute(
            agentContext = context,
            toolCallId = "tc-1",
            args = args,
            coroutineScope = this,
            onUpdate = {}
        )
        val text = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
        return text to result.isError
    }

    @Nested
    inner class `slice selection` {

        @Test
        fun `both slices of one owner are queried in a single request`() = runTest {
            val scopes = slot<List<SkillScope>>()
            val owner = slot<SkillOwnerContext>()
            coEvery { store.search(any(), capture(scopes), capture(owner), any()) } returns emptyList()
            coEvery { catalog.listByUser("alice") } returns listOf(row("pdf"))

            val (text, isError) = search(tool(), mapOf("query" to "pdf"))

            assertEquals(listOf(SkillScope.GLOBAL, SkillScope.PROJECT), scopes.captured)
            assertEquals("alice", owner.captured.userId)
            assertEquals(project, owner.captured.projectPath)
            assertFalse(isError, "an empty result must not look like a failure: $text")
            assertTrue(text.contains("No skills found"), "got: $text")
        }

        @Test
        fun `without a project there is only the global slice to query`() = runTest {
            val scopes = slot<List<SkillScope>>()
            coEvery { store.search(any(), capture(scopes), any(), any()) } returns emptyList()

            search(tool(), mapOf("query" to "pdf"), AgentContext(agentId = "a", userId = "alice"))

            assertEquals(listOf(SkillScope.GLOBAL), scopes.captured)
        }

        @Test
        fun `an explicit scope narrows the slice set`() = runTest {
            val scopes = mutableListOf<List<SkillScope>>()
            coEvery { store.search(any(), capture(scopes), any(), any()) } returns emptyList()

            search(tool(), mapOf("query" to "pdf", "scope" to "GLOBAL"))
            search(tool(), mapOf("query" to "pdf", "scope" to "project"))

            assertEquals(listOf(listOf(SkillScope.GLOBAL), listOf(SkillScope.PROJECT)), scopes)
        }

        @Test
        fun `an unrecognised scope keeps the default of querying both`() = runTest {
            val scopes = slot<List<SkillScope>>()
            coEvery { store.search(any(), capture(scopes), any(), any()) } returns emptyList()

            search(tool(), mapOf("query" to "pdf", "scope" to "everywhere"))

            assertEquals(listOf(SkillScope.GLOBAL, SkillScope.PROJECT), scopes.captured)
        }

        @Test
        fun `the caller may ask for more results than the default quota`() = runTest {
            coEvery { store.search(any(), any(), any(), any()) } returns emptyList()

            search(tool(), mapOf("query" to "pdf", "topK" to 12))

            coVerify(exactly = 1) { store.search("pdf", any(), any(), 12) }
        }

        @Test
        fun `a request with no identity reads the default tenant slice`() = runTest {
            val owner = slot<SkillOwnerContext>()
            coEvery { store.search(any(), any(), capture(owner), any()) } returns emptyList()

            search(tool(), mapOf("query" to "pdf"), AgentContext(agentId = "a"))

            assertEquals(SkillCatalogEntry.DEFAULT_USER_ID, owner.captured.userId)
        }
    }

    @Nested
    inner class `tenant resolution` {

        @Test
        fun `a user with their own rows never searches another tenant slice`() = runTest {
            val owner = slot<SkillOwnerContext>()
            coEvery { catalog.listByUser("alice") } returns listOf(row("pdf"))
            coEvery { store.search(any(), any(), capture(owner), any()) } returns emptyList()

            search(tool(), mapOf("query" to "pdf"))

            assertEquals("alice", owner.captured.userId)
        }

        @Test
        fun `a user with nothing of their own falls back to the shared scanned rows`() = runTest {
            val owner = slot<SkillOwnerContext>()
            coEvery { catalog.listByUser("bob") } returns emptyList()
            coEvery { catalog.listByUser(SkillCatalogEntry.DEFAULT_USER_ID) } returns listOf(row("pdf", userId = "system"))
            coEvery { store.search(any(), any(), capture(owner), any()) } returns emptyList()

            search(tool(), mapOf("query" to "pdf"), AgentContext(agentId = "a", userId = "bob", projectPath = project))

            assertEquals(SkillCatalogEntry.DEFAULT_USER_ID, owner.captured.userId)
        }

        @Test
        fun `an unreadable catalog keeps the requesting user rather than widening access`() = runTest {
            val owner = slot<SkillOwnerContext>()
            coEvery { catalog.listByUser("alice") } throws IllegalStateException("db down")
            coEvery { store.search(any(), any(), capture(owner), any()) } returns emptyList()

            search(tool(), mapOf("query" to "pdf"))

            assertEquals("alice", owner.captured.userId)
        }
    }

    @Nested
    inner class `the enablement gate` {

        @Test
        fun `a hit the catalog does not vouch for is dropped`() = runTest {
            coEvery { catalog.listByUser("alice") } returns listOf(row("pdf"))
            coEvery { store.search(any(), any(), any(), any()) } returns listOf(hit("pdf"), hit("ghost"))

            val (text, isError) = search(tool(), mapOf("query" to "pdf"))

            assertFalse(isError)
            assertTrue(text.contains("pdf"), "got: $text")
            assertFalse(text.contains("ghost"), "an unclaimed skill must not be advertised: $text")
        }

        @Test
        fun `a disabled skill is hidden even while its document is still indexed`() = runTest {
            coEvery { catalog.listByUser("alice") } returns listOf(row("pdf", enabled = false))
            coEvery { store.search(any(), any(), any(), any()) } returns listOf(hit("pdf"))

            val (text, _) = search(tool(), mapOf("query" to "pdf"))

            assertTrue(text.contains("No skills found"), "got: $text")
        }

        @Test
        fun `a broken catalog read filters nothing, so skills do not vanish behind an outage`() = runTest {
            coEvery { catalog.listByUser("alice") } throws IllegalStateException("db down")
            coEvery { store.search(any(), any(), any(), any()) } returns listOf(hit("pdf"))

            val (text, _) = search(tool(), mapOf("query" to "pdf"))

            assertTrue(text.contains("pdf"), "got: $text")
        }

        @Test
        fun `without a catalog layer the previous search behaviour is kept`() = runTest {
            coEvery { store.search(any(), any(), any(), any()) } returns listOf(hit("pdf"))

            val (text, _) = search(tool(catalogStore = null), mapOf("query" to "pdf"))

            assertTrue(text.contains("pdf"), "got: $text")
        }

        @Test
        fun `one exchange reads the table once, not once per search`() = runTest {
            coEvery { catalog.listByUser("alice") } returns listOf(row("pdf"))
            coEvery { store.search(any(), any(), any(), any()) } returns listOf(hit("pdf"))
            val tool = tool()

            search(tool, mapOf("query" to "pdf"))
            search(tool, mapOf("query" to "xlsx"))

            coVerify(exactly = 1) { catalog.listByUser("alice") }
        }
    }

    @Nested
    inner class `result rendering` {

        @Test
        fun `hits are labelled with the granularity they came from and point at load_skill`() = runTest {
            coEvery { catalog.listByUser("alice") } returns listOf(row("pdf"), row("xlsx"))
            coEvery { store.search(any(), any(), any(), any()) } returns
                listOf(hit("pdf", SkillScope.GLOBAL), hit("xlsx", SkillScope.PROJECT))

            val (text, isError) = search(tool(), mapOf("query" to "pdf"))

            assertFalse(isError)
            assertTrue(text.contains("- [global] pdf: does things [reports]"), "got: $text")
            assertTrue(text.contains("- [project] xlsx: does things [reports]"), "got: $text")
            assertTrue(text.contains("load_skill"), "the agent needs to know what to do next: $text")
            assertTrue(text.contains("user 'alice'"), "the tenant in play must be visible: $text")
        }

        @Test
        fun `a backend outage reads as no matches, never as an error`() = runTest {
            coEvery { store.search(any(), any(), any(), any()) } throws IllegalStateException("rag down")

            val (text, isError) = search(tool(), mapOf("query" to "pdf"))

            assertFalse(isError, "discovery is optional; it must never break the loop")
            assertTrue(text.contains("No skills found"), "got: $text")
        }

        // H6: `runCatchingSearch` must not swallow coroutine cancellation. Agent-loop timeouts and SSE
        // disconnects propagate as CancellationException; if a discovery tool absorbs it, `withTimeout`
        // loses its semantics and the coroutine can hang past its deadline.
        @Test
        fun `a cancelled backend call propagates CancellationException instead of returning empty`() = runTest {
            coEvery { store.search(any(), any(), any(), any()) } throws kotlinx.coroutines.CancellationException("scope cancelled")

            val ex = kotlin.runCatching { search(tool(), mapOf("query" to "pdf")) }.exceptionOrNull()

            assertTrue(ex is kotlinx.coroutines.CancellationException,
                "cancellation must survive the degraded-backend catch, otherwise withTimeout is a lie: got $ex")
        }
    }

    @Nested
    inner class `argument handling` {

        @Test
        fun `a blank query asks for a real query instead of searching everything`() = runTest {
            val (text, isError) = search(tool(), mapOf("query" to "   "))

            assertTrue(isError, "a missing required argument is a tool-call error, matching load_skill's contract")
            assertTrue(text.contains("'query' parameter is required"), "got: $text")
            coVerify(exactly = 0) { store.search(any(), any(), any(), any()) }
        }

        @Test
        fun `a missing query is treated the same way`() = runTest {
            val (text, _) = search(tool(), emptyMap())

            assertTrue(text.contains("'query' parameter is required"), "got: $text")
        }

        @Test
        fun `a non-positive topK falls back to the configured quota`() = runTest {
            coEvery { store.search(any(), any(), any(), any()) } returns emptyList()

            search(tool(searchTopK = 7), mapOf("query" to "pdf", "topK" to 0))

            coVerify(exactly = 1) { store.search("pdf", any(), any(), 7) }
        }
    }
}
