package com.easy.easyai.tools.memory

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.memory.MemoryEntry
import com.easy.easyai.core.memory.MemoryOwnerContext
import com.easy.easyai.core.memory.MemoryScope
import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.core.memory.MemoryType
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path

class MemoryWriteToolTest {

    private class FakeMemoryStore : MemoryStore {
        val written = mutableListOf<MemoryEntry>()
        var existsResult = false

        override suspend fun loadAll(
            scope: MemoryScope,
            owner: MemoryOwnerContext,
            totalCharLimit: Int,
            perFileCharLimit: Int
        ): String = ""

        override suspend fun search(
            query: String,
            scope: MemoryScope,
            owner: MemoryOwnerContext,
            limit: Int,
            timeRangeStart: Long?,
            timeRangeEnd: Long?
        ): List<MemoryEntry> = emptyList()

        override suspend fun write(entry: MemoryEntry, scope: MemoryScope, owner: MemoryOwnerContext): Path {
            written.add(entry)
            return Path.of(entry.type.dirName, "${entry.name}.md")
        }

        override suspend fun read(path: String, scope: MemoryScope, owner: MemoryOwnerContext): String? = null

        override suspend fun delete(path: String, scope: MemoryScope, owner: MemoryOwnerContext): Boolean = true

        override suspend fun deleteAll(scope: MemoryScope, owner: MemoryOwnerContext): Int = 0

        override suspend fun list(scope: MemoryScope, owner: MemoryOwnerContext, type: MemoryType?): List<MemoryEntry> = emptyList()

        override suspend fun exists(name: String, scope: MemoryScope, owner: MemoryOwnerContext): Boolean = existsResult

        override suspend fun findByName(name: String, scope: MemoryScope, owner: MemoryOwnerContext): MemoryEntry? = null

        override suspend fun refreshIndex(scope: MemoryScope) {}
    }

    private val store = FakeMemoryStore()

    /** Built directly instead of via builder: [AbstractMemoryToolBuilder.createTool] is protected. */
    private val tool: ToolDefinition = MemoryWriteTool(
        metadata = ToolMetadata(
            name = "memory_write",
            description = "Save durable facts to persistent memory.",
            permissionCategory = "memory",
            tracksFileChanges = true
        ),
        store = store
    )

    private fun execute(args: Map<String, Any?>): ToolResult = runBlocking {
        tool.execute(
            agentContext = AgentContext(agentId = "test", userId = "u", projectPath = Path.of("/proj")),
            toolCallId = "call-test",
            args = args,
            coroutineScope = CoroutineScope(Job())
        )
    }

    private fun addArgs(vararg extra: Pair<String, Any?>): Map<String, Any?> = mapOf(
        "action" to "add",
        "type" to "other",
        "name" to "test_entry",
        "description" to "d",
        "content" to "c"
    ) + extra.toMap()

    private fun output(result: ToolResult): String =
        result.content.filterIsInstance<TextContent>().joinToString("") { it.text }

    @Nested
    inner class `parameter guidance in schema and description` {

        @Test
        fun `schema annotates name rule for bare file names`() {
            assertTrue(tool.inputSchema.contains("Bare entry file name"), tool.inputSchema)
        }
    }

    @Nested
    inner class `scenarios coercion` {

        @Test
        fun `json encoded scenarios string is coerced to a real array`() {
            val result = execute(addArgs("scenarios" to """["s1","s2"]"""))
            assertFalse(result.isError)
            assertEquals(listOf("s1", "s2"), store.written.single().scenarios)
        }

        @Test
        fun `plain text scenario value wraps into single element`() {
            val result = execute(addArgs("scenarios" to "recall perf tuning"))
            assertFalse(result.isError)
            assertEquals(listOf("recall perf tuning"), store.written.single().scenarios)
        }

        @Test
        fun `batch operations coerce nested scenario strings`() {
            val result = execute(
                mapOf(
                    "operations" to listOf(
                        mapOf(
                            "action" to "add",
                            "type" to "other",
                            "name" to "batch_entry",
                            "description" to "d",
                            "content" to "c",
                            "scenarios" to """["b1"]"""
                        )
                    )
                )
            )
            assertFalse(result.isError)
            assertEquals(listOf("b1"), store.written.single().scenarios)
        }
    }

    @Nested
    inner class `add validation aggregation` {

        @Test
        fun `reports all violations at once`() {
            val result = execute(mapOf("action" to "add", "name" to "dir/nested.md"))
            assertTrue(result.isError)
            val message = output(result)
            assertTrue(message.contains("'type'"), message)
            assertTrue(message.contains("'description'"), message)
            assertTrue(message.contains("'content'"), message)
            assertTrue(message.contains("'name'"), message)
        }

        @Test
        fun `valid add succeeds`() {
            val result = execute(addArgs())
            assertFalse(result.isError)
            assertEquals(MemoryType.OTHER, store.written.single().type)
        }
    }
}
