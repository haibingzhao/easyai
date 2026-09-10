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
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests [MemoryListTool]: the listing is the governance view, so it has to report how long an
 * entry went unused and surface review candidates — while keeping the "(N entries)" header the
 * console parses with /\((\d+) entries\)/g.
 */
class MemoryListToolTest {

    private val store = mockk<MemoryStore>(relaxed = true)

    private val tool: ToolDefinition = MemoryListTool(
        metadata = ToolMetadata(name = "memory_list", description = "List memories.", permissionCategory = "memory"),
        store = store
    )

    private fun entry(
        name: String,
        created: LocalDate?,
        updated: LocalDate?,
        lastAccessed: LocalDate?,
        maturity: MemoryMaturity?
    ): MemoryEntry = MemoryEntry(
        name = name,
        description = "description of $name",
        type = MemoryType.EXPERIENCE_LESSONS,
        content = "body",
        path = "experience_lessons/$name.md",
        created = created,
        updated = updated,
        maturity = maturity,
        lastAccessed = lastAccessed
    )

    private fun stubProjectEntries(vararg entries: MemoryEntry) {
        val list = entries.toList()
        coEvery { store.list(MemoryScope.PROJECT, any(), any()) } returns list
        coEvery { store.list(MemoryScope.GLOBAL, any(), any()) } returns emptyList()
    }

    private fun execute(): String = runBlocking {
        val result: ToolResult = tool.execute(
            agentContext = AgentContext(agentId = "test", userId = "u", projectPath = Path.of("/proj")),
            toolCallId = "call-test",
            args = emptyMap(),
            coroutineScope = CoroutineScope(Job())
        )
        assertFalse(result.isError)
        result.content.filterIsInstance<TextContent>().joinToString("") { it.text }
    }

    @Nested
    inner class `staleness review` {

        @Test
        fun `low maturity untouched entries appear as review candidates`() {
            stubProjectEntries(
                entry(
                    name = "old-convention",
                    created = LocalDate.of(2025, 1, 1),
                    updated = LocalDate.now().minusDays(200),
                    lastAccessed = null,
                    maturity = MemoryMaturity.LOW
                )
            )

            val text = execute()

            assertTrue(text.contains("unused for 200 days"), text)
            assertTrue(text.contains("### Review candidates"), text)
            assertTrue(text.contains("old-convention"), text)
        }

        @Test
        fun `recently accessed entry is not flagged for review`() {
            stubProjectEntries(
                entry(
                    name = "live-convention",
                    created = LocalDate.of(2025, 1, 1),
                    updated = LocalDate.now().minusDays(200),
                    lastAccessed = LocalDate.now(),
                    maturity = MemoryMaturity.LOW
                )
            )

            val text = execute()

            assertTrue(text.contains("unused for 0 days"), text)
            assertFalse(text.contains("### Review candidates"), text)
        }

        @Test
        fun `fresh entry with low maturity is not flagged for review`() {
            stubProjectEntries(
                entry(
                    name = "new-convention",
                    created = LocalDate.now().minusDays(3),
                    updated = LocalDate.now().minusDays(3),
                    lastAccessed = null,
                    maturity = MemoryMaturity.LOW
                )
            )

            assertFalse(execute().contains("### Review candidates"))
        }

        @Test
        fun `unknown dates render as unused unknown`() {
            stubProjectEntries(
                entry(name = "undated", created = null, updated = null, lastAccessed = null, maturity = null)
            )

            val text = execute()

            assertTrue(text.contains("unused for unknown"), text)
            assertTrue(text.contains("maturity: unset"), text)
        }
    }

    @Nested
    inner class `console compatible header` {

        @Test
        fun `scope header keeps the entries count format`() {
            stubProjectEntries(
                entry("a", LocalDate.now(), LocalDate.now(), null, MemoryMaturity.HIGH),
                entry("b", LocalDate.now(), LocalDate.now(), null, MemoryMaturity.HIGH)
            )

            val text = execute()

            assertTrue(Regex("\\((\\d+) entries\\)").containsMatchIn(text), text)
        }
    }
}
