package com.easy.easyai.web.controller

import com.easy.easyai.core.memory.MemoryEntry
import com.easy.easyai.core.memory.MemoryMaturity
import com.easy.easyai.core.memory.MemoryOwnerContext
import com.easy.easyai.core.memory.MemoryScope
import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.core.memory.MemoryType
import com.easy.easyai.web.model.CreateMemoryRequest
import com.easy.easyai.web.model.UpdateMemoryConfigRequest
import com.easy.easyai.web.model.UpdateMemoryRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests [MemoryController] behavior when no MemoryStore bean is wired
 * (RAG not configured): reads degrade to empty, config reports disabled,
 * and mutations fail with 503 SERVICE_UNAVAILABLE.
 */
class MemoryControllerTest {

    private val controller = MemoryController(null)

    @Test
    fun `list returns empty when memory not enabled`() {
        val result = controller.listMemories(scope = "global", type = null, maturity = null).block()
        assertTrue(result != null && result.isEmpty())
    }

    @Test
    fun `config reports disabled when memory not enabled`() {
        val config = controller.getConfig().block()
        assertFalse(config!!.enabled)
    }

    @Test
    fun `get memory fails with 503 when memory not enabled`() {
        val e = assertFailsWith<ResponseStatusException> {
            controller.getMemory("any", "global").block()
        }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.statusCode)
    }

    @Test
    fun `update memory fails with 503 when memory not enabled`() {
        val e = assertFailsWith<ResponseStatusException> {
            controller.updateMemory("any", "global", request = UpdateMemoryRequest(content = "new")).block()
        }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.statusCode)
    }

    @Test
    fun `create memory fails with 503 when memory not enabled`() {
        val request = CreateMemoryRequest(
            name = "test",
            description = "d",
            type = "experience_lessons",
            scope = "global",
            content = "body"
        )
        val e = assertFailsWith<ResponseStatusException> {
            controller.createOrUpdateMemory(request).block()
        }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.statusCode)
    }

    @Test
    fun `delete memory fails with 503 when memory not enabled`() {
        val e = assertFailsWith<ResponseStatusException> {
            controller.deleteMemory("any", "global").block()
        }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.statusCode)
    }

    @Test
    fun `delete all fails with 503 when memory not enabled`() {
        val e = assertFailsWith<ResponseStatusException> {
            controller.deleteAllMemories("global").block()
        }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.statusCode)
    }

    @Test
    fun `update config rejects runtime enabled change`() {
        assertFailsWith<IllegalArgumentException> {
            controller.updateConfig(UpdateMemoryConfigRequest(enabled = true)).block()
        }
    }

    @Test
    fun `update config without changes reports disabled`() {
        val config = controller.updateConfig(UpdateMemoryConfigRequest()).block()
        assertFalse(config!!.enabled)
    }

    // ── enabled store: new fields and filters ───────────────────────────

    @Test
    fun `create parses maturity and scenarios into the entry`() = runTest {
        val store = mockk<MemoryStore>()
        val controller = MemoryController(store)
        coEvery { store.findByName(any(), any(), any()) } returns null
        coEvery { store.write(any(), any(), any()) } returns Path.of("experience_lessons/frp.md")

        val dto = controller.createOrUpdateMemory(
            CreateMemoryRequest(
                name = "frp",
                description = "d",
                type = "experience_lessons",
                scope = "global",
                content = "body",
                maturity = "medium",
                scenarios = listOf("remote access", "  ", "tunnel")
            )
        ).block()

        assertEquals("medium", dto?.maturity)
        assertEquals(listOf("remote access", "tunnel"), dto?.scenarios)
        coVerify { store.write(match { it.maturity == MemoryMaturity.MEDIUM && it.scenarios == listOf("remote access", "tunnel") }, any(), any()) }
    }

    @Test
    fun `create ignores invalid maturity`() = runTest {
        val store = mockk<MemoryStore>()
        val controller = MemoryController(store)
        coEvery { store.findByName(any(), any(), any()) } returns null
        coEvery { store.write(any(), any(), any()) } returns Path.of("x")

        val dto = controller.createOrUpdateMemory(
            CreateMemoryRequest(
                name = "x",
                description = "d",
                type = "other",
                scope = "global",
                content = "body",
                maturity = "bogus"
            )
        ).block()

        assertNull(dto?.maturity)
    }

    @Test
    fun `update applies partial fields and preserves others`() = runTest {
        val store = mockk<MemoryStore>()
        val controller = MemoryController(store)
        val existing = MemoryEntry(
            name = "frp",
            description = "old desc",
            type = MemoryType.EXPERIENCE_LESSONS,
            content = "old body",
            path = "experience_lessons/frp.md",
            keywords = listOf("a"),
            created = java.time.LocalDate.of(2026, 1, 1),
            updated = java.time.LocalDate.of(2026, 1, 1),
            maturity = MemoryMaturity.LOW,
            scenarios = listOf("s1")
        )
        coEvery { store.findByName("frp", any(), any()) } returns existing
        coEvery { store.write(any(), any(), any()) } returns Path.of("experience_lessons/frp.md")

        val dto = controller.updateMemory(
            name = "frp",
            scope = "global",
            request = UpdateMemoryRequest(content = "new body", maturity = "high")
        ).block()

        assertEquals("new body", dto?.content)
        assertEquals("old desc", dto?.description)
        assertEquals("high", dto?.maturity)
        assertEquals(listOf("s1"), dto?.scenarios)
        coVerify {
            store.write(
                match {
                    it.content == "new body" && it.description == "old desc" &&
                        it.maturity == MemoryMaturity.HIGH && it.scenarios == listOf("s1")
                },
                any(),
                any()
            )
        }
    }

    @Test
    fun `list filters by maturity`() = runTest {
        val store = mockk<MemoryStore>()
        val controller = MemoryController(store)
        fun entry(name: String, maturity: MemoryMaturity?) = MemoryEntry(
            name = name,
            description = "d",
            type = MemoryType.OTHER,
            content = "c",
            path = "other/$name.md",
            maturity = maturity
        )
        coEvery { store.list(MemoryScope.GLOBAL, any<MemoryOwnerContext>(), any()) } returns listOf(
            entry("a", MemoryMaturity.LOW),
            entry("b", MemoryMaturity.HIGH),
            entry("c", null)
        )

        val dtos = controller.listMemories(scope = "global", type = null, maturity = "high").block()

        assertEquals(1, dtos?.size)
        assertEquals("b", dtos?.first()?.name)
    }
}
