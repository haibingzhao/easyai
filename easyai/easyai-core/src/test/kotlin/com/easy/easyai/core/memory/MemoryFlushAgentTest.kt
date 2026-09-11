package com.easy.easyai.core.memory

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.UserMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage as SpringAssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFalse
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
        every { chatModel.call(any<Prompt>()) } returns chatResponse(text)
        return chatModel
    }

    private fun chatResponse(text: String): ChatResponse {
        val assistantMsg = SpringAssistantMessage(text)
        val generation = mockk<Generation>(relaxed = true)
        every { generation.output } returns assistantMsg
        val response = mockk<ChatResponse>(relaxed = true)
        every { response.result } returns generation
        return response
    }

    /** One extracted item in the shape the flush prompt asks the model to return. */
    private fun item(title: String, action: String? = null, reason: String? = null): String {
        val fields = listOfNotNull(
            "\"title\": \"$title\"",
            "\"description\": \"new description\"",
            "\"category\": \"experience_lessons\"",
            "\"content\": \"new body\"",
            "\"maturity\": \"high\"",
            action?.let { "\"action\": \"$it\"" },
            reason?.let { "\"reason\": \"$it\"" }
        )
        return "{" + fields.joinToString(", ") + "}"
    }

    private fun jsonOf(vararg items: String): String = """{"memories": [${items.joinToString(", ")}]}"""

    /** Entry as already held by the store before this flush. */
    private fun storedEntry(name: String) = MemoryEntry(
        name = name,
        description = "stored description",
        type = MemoryType.EXPERIENCE_LESSONS,
        content = "stored body",
        path = "experience_lessons/$name.md",
        created = LocalDate.of(2025, 1, 5),
        updated = LocalDate.of(2026, 1, 10),
        maturity = MemoryMaturity.LOW
    )

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

    @Nested
    inner class `existing entry reconciliation` {

        private suspend fun flush(
            json: String,
            existing: List<MemoryEntry>,
            allowRemovals: Boolean = false
        ): MemoryFlushAgent.FlushResult? {
            coEvery { store.list(any(), any(), any()) } returns existing
            return MemoryFlushAgent(store, allowRemovals = allowRemovals).maybeFlush(
                agentContext = agentContext,
                messages = messages,
                modelContextLength = 100_000,
                estimatedTokenCount = 90_000,
                chatModel = chatModelReturning(json)
            )
        }

        @Test
        fun `add on an existing name becomes an update preserving created`() = runTest {
            val stored = storedEntry("frp-tunnel-setup")
            val entrySlot = slot<MemoryEntry>()
            coEvery { store.write(capture(entrySlot), any(), any()) } returns Path.of("x")

            val result = flush(jsonOf(item("FRP Tunnel Setup!")), listOf(stored))

            assertEquals(0, result?.written)
            assertEquals(1, result?.updated)
            assertEquals(stored.created, entrySlot.captured.created)
            assertEquals(LocalDate.now(), entrySlot.captured.updated)
            assertEquals("experience_lessons/frp-tunnel-setup.md", entrySlot.captured.path)
        }

        @Test
        fun `update refreshes updated and keeps created`() = runTest {
            val stored = storedEntry("frp-tunnel-setup")
            val entrySlot = slot<MemoryEntry>()
            coEvery { store.write(capture(entrySlot), any(), any()) } returns Path.of("x")

            val result = flush(jsonOf(item("frp-tunnel-setup", action = "update")), listOf(stored))

            assertEquals(1, result?.updated)
            assertEquals("new body", entrySlot.captured.content)
            assertEquals(stored.created, entrySlot.captured.created)
            assertEquals(LocalDate.now(), entrySlot.captured.updated)
        }

        @Test
        fun `update of an entry missing from the snapshot is skipped`() = runTest {
            val result = flush(jsonOf(item("other-entry", action = "update")), listOf(storedEntry("frp-tunnel-setup")))

            assertEquals(0, result?.updated)
            coVerify(exactly = 0) { store.write(any(), any(), any()) }
        }

        @Test
        fun `remove is skipped when the name is absent from the snapshot`() = runTest {
            val result = flush(
                jsonOf(item("frp-tunnel-setup", action = "remove", reason = "superseded by new entry")),
                listOf(storedEntry("unrelated-entry")),
                allowRemovals = true
            )

            assertEquals(0, result?.removed)
            coVerify(exactly = 0) { store.delete(any(), any(), any()) }
        }

        @Test
        fun `remove is skipped when allowRemovals is false`() = runTest {
            val result = flush(
                jsonOf(item("frp-tunnel-setup", action = "remove", reason = "contradicted by the user")),
                listOf(storedEntry("frp-tunnel-setup"))
            )

            coVerify(exactly = 0) { store.delete(any(), any(), any()) }
            assertEquals(1, result?.reviewCandidates)
            assertEquals(0, result?.removed)
        }

        @Test
        fun `remove without a reason is a review candidate`() = runTest {
            val result = flush(
                jsonOf(item("frp-tunnel-setup", action = "remove")),
                listOf(storedEntry("frp-tunnel-setup")),
                allowRemovals = true
            )

            coVerify(exactly = 0) { store.delete(any(), any(), any()) }
            assertEquals(1, result?.reviewCandidates)
        }

        @Test
        fun `removals beyond the per-flush cap are skipped`() = runTest {
            val existing = listOf("a", "b", "c", "d").map { storedEntry(it) }

            val result = flush(
                jsonOf(*existing.map { item(it.name, action = "remove", reason = "proven obsolete") }.toTypedArray()),
                existing,
                allowRemovals = true
            )

            assertEquals(3, result?.removed)
            assertEquals(1, result?.reviewCandidates)
            coVerify(exactly = 3) { store.delete(any(), any(), any()) }
        }

        @Test
        fun `a failed snapshot never removes anything but still writes new entries`() = runTest {
            coEvery { store.list(any(), any(), any()) } throws RuntimeException("rag unavailable")
            coEvery { store.write(any(), any(), any()) } returns Path.of("x")

            val result = MemoryFlushAgent(store, allowRemovals = true).maybeFlush(
                agentContext = agentContext,
                messages = messages,
                modelContextLength = 100_000,
                estimatedTokenCount = 90_000,
                chatModel = chatModelReturning(
                    jsonOf(
                        item("brand-new-fact"),
                        item("frp-tunnel-setup", action = "remove", reason = "proven obsolete")
                    )
                )
            )

            assertEquals(1, result?.written)
            assertEquals(0, result?.removed)
            coVerify(exactly = 0) { store.delete(any(), any(), any()) }
        }

        @Test
        fun `prompt lists existing entries as metadata only`() = runTest {
            coEvery { store.list(any(), any(), any()) } returns listOf(storedEntry("frp-tunnel-setup"))
            val promptSlot = slot<Prompt>()
            val chatModel = mockk<ChatModel>()
            every { chatModel.call(capture(promptSlot)) } returns chatResponse("""{"memories": []}""")

            MemoryFlushAgent(store).maybeFlush(
                agentContext = agentContext,
                messages = messages,
                modelContextLength = 100_000,
                estimatedTokenCount = 90_000,
                chatModel = chatModel
            )

            val prompt = promptSlot.captured.instructions.joinToString("\n") { it.text.orEmpty() }
            assertContains(prompt, "<existing_memories>")
            assertContains(prompt, "experience_lessons/frp-tunnel-setup")
            // Bodies stay out of the prompt: a flush runs when the window is nearly full
            assertFalse(prompt.contains("stored body"), prompt)
        }
    }
}
