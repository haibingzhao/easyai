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

        /** Types handed to the duplicate check, so tests can assert it stays type qualified. */
        val existsTypes = mutableListOf<MemoryType?>()

        /** Types handed to every name lookup, for the same assertion on update and remove. */
        val lookups = mutableListOf<MemoryType?>()

        /** Entries the tool resolved by name; batch operations reuse them instead of re-looking up. */
        var entriesByName: Map<String, MemoryEntry> = emptyMap()

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

        override suspend fun readEntry(path: String, scope: MemoryScope, owner: MemoryOwnerContext): MemoryEntry? =
            entriesByName.values.firstOrNull { it.path == path }

        override suspend fun touch(path: String, scope: MemoryScope, owner: MemoryOwnerContext): MemoryEntry? =
            readEntry(path, scope, owner)

        override suspend fun exists(
            name: String,
            scope: MemoryScope,
            owner: MemoryOwnerContext,
            type: MemoryType?
        ): Boolean {
            existsTypes.add(type)
            return existsResult
        }

        override suspend fun findByName(
            name: String,
            scope: MemoryScope,
            owner: MemoryOwnerContext,
            type: MemoryType?
        ): MemoryEntry? {
            lookups.add(type)
            return entriesByName[name]
        }

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

    @Nested
    inner class `type qualified name lookup` {

        private fun existingEntry(): MemoryEntry = MemoryEntry(
            name = "test_entry",
            description = "d",
            type = MemoryType.OTHER,
            content = "old body",
            path = "other/test_entry.md"
        )

        @Test
        fun `add duplicate check is type qualified`() {
            execute(addArgs())

            // The category is validated before the check, so the store can resolve the key in
            // one request instead of probing every type of the active domain.
            assertEquals(listOf(MemoryType.OTHER), store.existsTypes)
        }

        @Test
        fun `update resolves the name through the announced type`() {
            store.entriesByName = mapOf("test_entry" to existingEntry())

            execute(mapOf("action" to "update", "name" to "test_entry", "type" to "other", "content" to "new body"))

            assertEquals(listOf(MemoryType.OTHER), store.lookups)
            assertEquals("new body", store.written.single().content)
        }

        @Test
        fun `an unusable type falls back to the cross-type probe`() {
            store.entriesByName = mapOf("test_entry" to existingEntry())

            execute(mapOf("action" to "update", "name" to "test_entry", "type" to "not_a_type", "content" to "new body"))

            assertEquals(listOf<MemoryType?>(null), store.lookups)
        }

        @Test
        fun `batch operations reuse their pre-read snapshot`() {
            store.entriesByName = mapOf("test_entry" to existingEntry())

            execute(
                mapOf(
                    "operations" to listOf(
                        mapOf("action" to "update", "name" to "test_entry", "type" to "other", "content" to "new body")
                    )
                )
            )

            // The batch pre-reads the entry once and passes it down; the handler must not look
            // the same name up a second time.
            assertEquals(1, store.lookups.size)
        }
    }
}
