package com.easy.easyai.tools.memory

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.memory.MemoryEntry
import com.easy.easyai.core.memory.MemoryScope
import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.core.memory.MemoryType
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
}
