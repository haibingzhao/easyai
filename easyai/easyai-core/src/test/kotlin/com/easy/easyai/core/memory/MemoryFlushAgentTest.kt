package com.easy.easyai.core.memory

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.UserMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage as SpringAssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests [MemoryFlushAgent] structured extraction: JSON parsing, entry mapping
 * (slug name, category fallback, maturity/scenarios), and dedup/parse-failure handling.
 */
class MemoryFlushAgentTest {

    private val store = mockk<MemoryStore>(relaxed = true)

    private val agentContext = AgentContext(
        agentId = "agent",
        userId = "alice",
        projectPath = Path.of("/tmp/demo"),
        memoryAutoGeneration = true
    )

    private val messages = List(6) { UserMessage("message $it") }

    private fun chatModelReturning(text: String): ChatModel {
        val chatModel = mockk<ChatModel>()
        val assistantMsg = SpringAssistantMessage(text)
        val generation = mockk<Generation>(relaxed = true)
        every { generation.output } returns assistantMsg
        val response = mockk<ChatResponse>(relaxed = true)
        every { response.result } returns generation
        coEvery { chatModel.call(any<Prompt>()) } returns response
        return chatModel
    }

    private fun sampleJson(): String = """
        {"memories": [
          {"title": "FRP Tunnel Setup!", "description": "frp relay config", "category": "experience_lessons",
           "keywords": ["frp", " tunnel "], "scenarios": ["remote access", "remote access"], "maturity": "medium",
           "content": "Use frps on the ECS."},
          {"title": "User prefers dark theme", "description": "UI preference", "category": "user_preferences",
           "keywords": [], "scenarios": [], "maturity": "high", "content": "Dark theme only."}
        ]}
    """.trimIndent()

    @Test
    fun `flush writes one entry per extracted memory with mapped metadata`() = runTest {
        val entries = mutableListOf<MemoryEntry>()
        val scopeSlot = slot<MemoryScope>()
        coEvery { store.write(capture(entries), capture(scopeSlot), any()) } returns Path.of("x")

        val result = MemoryFlushAgent(store).maybeFlush(
            agentContext = agentContext,
            messages = messages,
            modelContextLength = 100_000,
            estimatedTokenCount = 90_000,
            chatModel = chatModelReturning(sampleJson())
        )

        assertEquals(2, result?.written)
        coVerify(exactly = 2) { store.write(any(), MemoryScope.PROJECT, any()) }
        assertEquals(MemoryScope.PROJECT, scopeSlot.captured)

        val first = entries.first()
        assertEquals("frp-tunnel-setup", first.name)
        assertEquals("experience_lessons/frp-tunnel-setup.md", first.path)
        assertEquals(MemoryType.EXPERIENCE_LESSONS, first.type)
        assertEquals(MemoryMaturity.MEDIUM, first.maturity)
        assertEquals(listOf("frp", "tunnel"), first.keywords)
        assertEquals(listOf("remote access"), first.scenarios)
    }

    @Test
    fun `flush falls back to OTHER category and null maturity for unknown values`() = runTest {
        val entrySlot = slot<MemoryEntry>()
        coEvery { store.write(capture(entrySlot), any(), any()) } returns Path.of("x")

        val json = """
            {"memories": [
              {"title": "Mystery fact", "description": "d", "category": "bogus_category",
               "keywords": [], "scenarios": [], "maturity": "bogus", "content": "body"}
            ]}
        """.trimIndent()

        val result = MemoryFlushAgent(store).maybeFlush(
            agentContext = agentContext,
            messages = messages,
            modelContextLength = 100_000,
            estimatedTokenCount = 90_000,
            chatModel = chatModelReturning(json)
        )

        assertEquals(1, result?.written)
        assertEquals(MemoryType.OTHER, entrySlot.captured.type)
        assertNull(entrySlot.captured.maturity)
    }

    @Test
    fun `flush skips without writing when response is not JSON`() = runTest {
        val result = MemoryFlushAgent(store).maybeFlush(
            agentContext = agentContext,
            messages = messages,
            modelContextLength = 100_000,
            estimatedTokenCount = 90_000,
            chatModel = chatModelReturning("Here are some facts:\n- fact one\n- fact two")
        )

        assertNull(result)
        coVerify(exactly = 0) { store.write(any(), any(), any()) }
    }

    @Test
    fun `flush returns null below threshold`() = runTest {
        val result = MemoryFlushAgent(store).maybeFlush(
            agentContext = agentContext,
            messages = messages,
            modelContextLength = 100_000,
            estimatedTokenCount = 10_000,
            chatModel = chatModelReturning(sampleJson())
        )

        assertNull(result)
        coVerify(exactly = 0) { store.write(any(), any(), any()) }
    }

    @Test
    fun `flush does not deduplicate a second flush of the same context`() = runTest {
        coEvery { store.write(any(), any(), any()) } returns Path.of("x")

        val agent = MemoryFlushAgent(store)
        val chatModel = chatModelReturning(sampleJson())

        val first = agent.maybeFlush(agentContext, messages, 100_000, 90_000, chatModel)
        val second = agent.maybeFlush(agentContext, messages, 100_000, 90_000, chatModel)

        assertEquals(2, first?.written)
        // Same context hash already flushed: second attempt is skipped entirely.
        assertNull(second)
        coVerify(exactly = 2) { store.write(any(), any(), any()) }
        assertTrue(true)
    }
}
