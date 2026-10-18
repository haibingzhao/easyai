package com.easy.easyai.tools.memory

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.memory.MemoryEntry
import com.easy.easyai.core.memory.MemoryMaturity
import com.easy.easyai.core.memory.MemoryScope
import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.core.memory.MemoryType
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests [MemorySearchTool] output: every hit must carry the freshness tail the memory governance
 * rules in the system prompt rely on, and the same facts as a structured payload the console renders
 * from. The prose has no contract — the UI never parses it — so the payload is what the tests pin.
 */
class MemorySearchToolTest {

    private val store = mockk<MemoryStore>(relaxed = true)

    /** Built directly instead of via builder: [AbstractMemoryToolBuilder.createTool] is protected. */
    private val tool: ToolDefinition = MemorySearchTool(
        metadata = ToolMetadata(name = "memory_search", description = "Search memories.", permissionCategory = "memory"),
        store = store
    )

    private fun entry(name: String, updated: LocalDate?, maturity: MemoryMaturity?): MemoryEntry = MemoryEntry(
        name = name,
        description = "description of $name",
        type = MemoryType.EXPERIENCE_LESSONS,
        content = "body of $name",
        path = "experience_lessons/$name.md",
        created = LocalDate.of(2025, 12, 1),
        updated = updated,
        maturity = maturity
    )

    /** Hits are served from the project scope only, so counts stay attributable to one scope. */
    private fun stubProjectHits(entries: List<MemoryEntry>) {
        coEvery { store.search(any(), MemoryScope.PROJECT, any(), any(), any(), any()) } returns entries
        coEvery { store.search(any(), MemoryScope.GLOBAL, any(), any(), any(), any()) } returns emptyList()
    }

    private fun output(result: ToolResult): String =
        result.content.filterIsInstance<TextContent>().joinToString("") { it.text }

    private fun execute(): ToolResult = runBlocking {
        tool.execute(
            agentContext = AgentContext(agentId = "test", userId = "u", projectPath = Path.of("/proj")),
            toolCallId = "call-test",
            args = mapOf("query" to "frp"),
            coroutineScope = CoroutineScope(Job())
        )
    }

    private fun executeSearch(): String = output(execute())

    @Suppress("UNCHECKED_CAST")
    private fun detailHits(result: ToolResult): List<Map<String, Any?>> =
        result.details["hits"] as List<Map<String, Any?>>

    @Nested
    inner class `freshness on every hit` {

        @Test
        fun `search results surface updated date and maturity`() {
            stubProjectHits(listOf(entry("frp-setup", LocalDate.now().minusDays(119), MemoryMaturity.MEDIUM)))

            val text = executeSearch()

            assertTrue(text.contains("updated: ${LocalDate.now().minusDays(119)} (119 days ago)"), text)
            assertTrue(text.contains("maturity: medium"), text)
        }

        @Test
        fun `missing timestamps render as unknown rather than being omitted`() {
            stubProjectHits(listOf(entry("mystery", updated = null, maturity = null)))

            val text = executeSearch()

            assertTrue(text.contains("updated: unknown"), text)
            assertTrue(text.contains("maturity: unset"), text)
        }

        @Test
        fun `result header keeps the Found N memories format`() {
            stubProjectHits(listOf(entry("frp-setup", LocalDate.now(), MemoryMaturity.HIGH)))

            val text = executeSearch()

            assertTrue(Regex("Found (\\d+) memories").containsMatchIn(text), text)
        }
    }

    @Nested
    inner class `structured render payload` {

        @Test
        fun `hits carry every field the card renders`() {
            val updated = LocalDate.now().minusDays(3)
            stubProjectHits(listOf(entry("frp-setup", updated, MemoryMaturity.MEDIUM)))

            val hit = detailHits(execute()).single()

            assertEquals("project", hit["scope"])
            assertEquals("experience_lessons/frp-setup.md", hit["path"])
            assertEquals("frp-setup", hit["name"])
            assertEquals("description of frp-setup", hit["description"])
            assertEquals(updated.toString(), hit["updated"])
            assertEquals(3L, hit["daysSinceUpdate"])
            assertEquals("medium", hit["maturity"])
            assertEquals(0, hit["level"])
            assertEquals("body of frp-setup", hit["contentPreview"])
        }

        @Test
        fun `an unknown timestamp stays null`() {
            stubProjectHits(listOf(entry("mystery", updated = null, maturity = null)))

            val hit = detailHits(execute()).single()

            assertNull(hit["updated"])
            assertNull(hit["daysSinceUpdate"])
            assertNull(hit["maturity"])
        }

        @Test
        fun `no hits leaves the payload empty`() {
            stubProjectHits(emptyList())

            assertTrue(execute().details.isEmpty())
        }
    }

    @Nested
    inner class `access recording` {

        @Test
        fun `only the leading hits of a scope record an access date`() {
            stubProjectHits(List(5) { entry("entry-$it", LocalDate.now(), MemoryMaturity.HIGH) })

            executeSearch()

            coVerify(exactly = 3) { store.touch(any(), MemoryScope.PROJECT, any()) }
        }

        @Test
        fun `a failing access record does not break the result`() {
            stubProjectHits(listOf(entry("frp-setup", LocalDate.now(), MemoryMaturity.HIGH)))
            coEvery { store.touch(any(), any(), any()) } throws RuntimeException("rag unavailable")

            val text = executeSearch()

            assertTrue(text.contains("description of frp-setup"), text)
        }
    }
}
