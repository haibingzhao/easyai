package com.easy.easyai.tools.memory

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.memory.MemoryEntry
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests [MemoryReadTool]: the access record must come from the single authoritative entry
 * the store touches, never from a whole-scope listing.
 */
class MemoryReadToolTest {

    private val store = mockk<MemoryStore>(relaxed = true)

    private val path = "experience_lessons/frp-setup.md"

    private val tool: ToolDefinition = MemoryReadTool(
        metadata = ToolMetadata(name = "memory_read", description = "Read a memory.", permissionCategory = "memory"),
        store = store
    )

    private fun execute(): String = runBlocking {
        val result = tool.execute(
            agentContext = AgentContext(agentId = "test", userId = "u", projectPath = Path.of("/proj")),
            toolCallId = "call-test",
            args = mapOf("path" to path),
            coroutineScope = CoroutineScope(Job())
        )
        assertFalse(result.isError)
        result.content.filterIsInstance<TextContent>().joinToString("") { it.text }
    }

    @Test
    fun `read records access without listing the whole scope`() {
        coEvery { store.read(path, MemoryScope.PROJECT, any()) } returns "frontmatter + body"
        coEvery { store.touch(path, MemoryScope.PROJECT, any()) } returns MemoryEntry(
            name = "frp-setup",
            description = "frp relay setup",
            type = MemoryType.EXPERIENCE_LESSONS,
            content = "Use frps on the ECS.",
            path = path,
            updated = LocalDate.now()
        )

        assertEquals("frontmatter + body", execute())

        coVerify(exactly = 0) { store.list(any(), any(), any()) }
        coVerify(exactly = 1) { store.touch(path, MemoryScope.PROJECT, any()) }
    }

    @Test
    fun `read still returns content when the access record fails`() {
        coEvery { store.read(path, MemoryScope.PROJECT, any()) } returns "frontmatter + body"
        coEvery { store.touch(any(), any(), any()) } throws RuntimeException("rag unavailable")

        assertEquals("frontmatter + body", execute())
    }

    /**
     * Both read modes must be visible in the schema: a model that sees only `path` has no way to
     * ask for the archived bodies a merged entry refers to.
     */
    @Nested
    inner class `schema offers both read modes` {

        @Test
        fun `path and docIds are both optional and both described`() {
            val schema = tool.inputSchema

            assertTrue(schema.contains("\"path\""), schema)
            assertTrue(schema.contains("\"docIds\""), schema)
            assertTrue(schema.contains("source entries a merged entry absorbed"), schema)
            // Neither mode may be required: a call shape that forces one of them would leave the model
            // no way to say "just the path". The tool itself rejects a call that gives neither.
            assertFalse(schema.contains("\"required\""), schema)
        }
    }

    /**
     * Drill-down by docId is read-only by design: touching a history entry would rewrite it, and a
     * rewrite is how its archive columns get flattened.
     */
    @Nested
    inner class `drill down into merged sources` {

        private fun archived(name: String, docId: String, refs: List<String> = emptyList()): MemoryEntry =
            MemoryEntry(
                name = name,
                description = "history of $name",
                type = MemoryType.EXPERIENCE_LESSONS,
                content = "full body of $name",
                path = "experience_lessons/$name.md",
                id = docId,
                archived = true,
                level = 0,
                refs = refs,
                mergedInto = "merged-doc"
            )

        private fun executeIds(vararg extra: Pair<String, Any?>): ToolResult = runBlocking {
            tool.execute(
                agentContext = AgentContext(agentId = "test", userId = "u", projectPath = Path.of("/proj")),
                toolCallId = "call-test",
                args = mapOf(*extra),
                coroutineScope = CoroutineScope(Job())
            )
        }

        private fun text(result: ToolResult): String =
            result.content.filterIsInstance<TextContent>().joinToString("") { it.text }

        @Test
        fun `docIds come back in full without any access recording`() {
            coEvery { store.findByIds(any(), MemoryScope.PROJECT, any()) } returns
                listOf(archived("frp-setup", "doc-a"), archived("relay-log", "doc-b"))

            val result = executeIds("docIds" to listOf("doc-a", "doc-b"))

            assertFalse(result.isError)
            val body = text(result)
            assertTrue(body.contains("full body of frp-setup"), body)
            assertTrue(body.contains("full body of relay-log"), body)
            assertTrue(body.contains("archived: true"), body)
            assertTrue(body.contains("merged into: merged-doc"), body)
            coVerify(exactly = 0) { store.touch(any(), any(), any()) }
            coVerify(exactly = 0) { store.list(any(), any(), any()) }
            coVerify(exactly = 0) { store.findByIds(any(), MemoryScope.GLOBAL, any()) }
        }

        @Test
        fun `a docId list sent as a JSON string is still a list`() {
            coEvery { store.findByIds(any(), MemoryScope.PROJECT, any()) } returns listOf(archived("frp-setup", "doc-a"))

            val result = executeIds("docIds" to """["doc-a"]""")

            assertFalse(result.isError)
            coVerify(exactly = 1) { store.findByIds(listOf("doc-a"), MemoryScope.PROJECT, any()) }
        }

        @Test
        fun `an id the project slice does not hold is asked of the global slice`() {
            coEvery { store.findByIds(any(), MemoryScope.PROJECT, any()) } returns emptyList()
            coEvery { store.findByIds(any(), MemoryScope.GLOBAL, any()) } returns listOf(archived("relay-log", "doc-b"))

            val body = text(executeIds("docIds" to listOf("doc-a", "doc-b")))

            assertTrue(body.contains("full body of relay-log"), body)
            assertTrue(body.contains("Not returned by either scope: doc-a"), body)
        }

        @Test
        fun `an archived entry that was itself merged names the level below it`() {
            coEvery { store.findByIds(any(), MemoryScope.PROJECT, any()) } returns
                listOf(archived("frp-setup", "doc-a", refs = listOf("doc-x")))

            val body = text(executeIds("docIds" to listOf("doc-a")))

            assertTrue(body.contains("deeper sources: doc-x"), body)
        }

        @Test
        fun `over-many ids are refused before any read`() {
            val result = executeIds("docIds" to (1..11).map { "doc-$it" })

            assertTrue(result.isError)
            coVerify(exactly = 0) { store.findByIds(any(), any(), any()) }
        }

        @Test
        fun `neither argument answers nothing`() {
            assertTrue(executeIds().isError)
        }
    }
}
