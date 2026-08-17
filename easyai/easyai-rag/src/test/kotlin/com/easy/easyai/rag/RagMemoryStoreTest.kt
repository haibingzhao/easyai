package com.easy.easyai.rag

import com.easy.easyai.core.memory.MemoryBackendException
import com.easy.easyai.core.memory.MemoryEntry
import com.easy.easyai.core.memory.MemoryMaturity
import com.easy.easyai.core.memory.MemoryOwnerContext
import com.easy.easyai.core.memory.MemoryScope
import com.easy.easyai.core.memory.MemoryType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [RagMemoryStore]: biz_id derivation from [MemoryOwnerContext],
 * the new `{type}/{name}.md` key layout, frontmatter round-trip, PROJECT-scope
 * degradation without a project path, and error translation.
 */
class RagMemoryStoreTest {

    private val client = mockk<RagClient>(relaxed = true)
    private val store = RagMemoryStore(client)

    private val projectPath = Path.of("/tmp/demo-project")
    private val globalOwner = MemoryOwnerContext(userId = "alice")
    private val projectOwner = MemoryOwnerContext(userId = "alice", projectPath = projectPath)
    private val noProjectOwner = MemoryOwnerContext(userId = "alice")
    private val globalBizId = RagBizIdResolver.globalBizId("alice")
    private val projectBizId = RagBizIdResolver.projectBizId("alice", projectPath)!!

    private fun sampleEntry(): MemoryEntry = MemoryEntry(
        name = "frp-remote-access",
        description = "frp relay setup for remote Mac access",
        type = MemoryType.EXPERIENCE_LESSONS,
        content = "## Steps\n\nUse frps on the ECS and frpc on the Mac.",
        path = "experience_lessons/frp-remote-access.md",
        keywords = listOf("frp", "tunnel"),
        created = LocalDate.of(2025, 12, 1),
        updated = LocalDate.of(2026, 1, 15),
        maturity = MemoryMaturity.MEDIUM,
        scenarios = listOf("Remote access to home Mac", "Secure VNC over SSH")
    )

    // ── write ──────────────────────────────────────────────────────────

    @Test
    fun `write builds RagDocument with new key layout and passes GLOBAL bizId`() = runTest {
        val docSlot = slot<RagDocument>()
        val bizSlot = slot<String>()
        coEvery { client.upsert(capture(docSlot), capture(bizSlot)) } returns RagUpsertResult(docId = "doc-1", indexed = true)

        store.write(sampleEntry(), MemoryScope.GLOBAL, globalOwner)

        val doc = docSlot.captured
        assertEquals("experience_lessons/frp-remote-access.md", doc.key)
        assertEquals("easyai:memory:experience_lessons/frp-remote-access.md", doc.externalId)
        assertEquals("easyai/memory/experience_lessons/frp-remote-access.md", doc.filePath)
        assertEquals(RagCategory.MEMORY, doc.category)
        // biz_id carries isolation; scope metadata is no longer needed
        assertFalse(doc.metadata.containsKey("scope"))
        assertEquals("memory", doc.metadata["category"])
        assertEquals("experience_lessons", doc.metadata["type"])
        assertEquals("frp-remote-access", doc.metadata["name"])
        assertEquals("medium", doc.metadata["maturity"])
        // Markdown memories: heading-based chunking, graph extraction enabled, structure index built
        assertEquals("structure_aware", doc.options.chunkMethod)
        assertFalse(doc.options.skipKg)
        assertTrue(doc.options.buildStructure)
        assertEquals(globalBizId, bizSlot.captured)
        // createTime is the updated date as epoch seconds (business time)
        val expected = LocalDate.of(2026, 1, 15).atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
        assertEquals(expected, doc.createTime)
    }

    @Test
    fun `write passes PROJECT bizId derived from user and project path`() = runTest {
        val bizSlot = slot<String>()
        coEvery { client.upsert(any(), capture(bizSlot)) } returns RagUpsertResult(docId = "doc-1", indexed = true)

        store.write(sampleEntry(), MemoryScope.PROJECT, projectOwner)

        assertEquals(projectBizId, bizSlot.captured)
    }

    @Test
    fun `write to PROJECT scope without project path throws`() = runTest {
        assertFailsWith<MemoryBackendException> {
            store.write(sampleEntry(), MemoryScope.PROJECT, noProjectOwner)
        }
        coVerify(exactly = 0) { client.upsert(any(), any()) }
    }

    // ── search ─────────────────────────────────────────────────────────

    @Test
    fun `frontmatter content round-trips through write and search`() = runTest {
        val docSlot = slot<RagDocument>()
        coEvery { client.upsert(capture(docSlot), any()) } returns RagUpsertResult(docId = "doc-1", indexed = true)
        store.write(sampleEntry(), MemoryScope.GLOBAL, globalOwner)
        val storedContent = docSlot.captured.content

        coEvery {
            client.search(
                query = any(),
                category = any(),
                filters = any(),
                topK = any(),
                timeRangeStart = any(),
                timeRangeEnd = any(),
                bizId = any()
            )
        } returns listOf(
            RagChunk(
                content = storedContent,
                filePath = "easyai/memory/experience_lessons/frp-remote-access.md",
                score = 0.9,
                createTime = null,
                metadata = emptyMap()
            )
        )

        val results = store.search("logging rules", MemoryScope.GLOBAL, globalOwner, 5)
        assertEquals(1, results.size)
        val parsed = results[0]
        assertEquals(sampleEntry().name, parsed.name)
        assertEquals(sampleEntry().description, parsed.description)
        assertEquals(MemoryType.EXPERIENCE_LESSONS, parsed.type)
        assertEquals(sampleEntry().content, parsed.content)
        assertEquals(sampleEntry().keywords, parsed.keywords)
        assertEquals(sampleEntry().created, parsed.created)
        assertEquals(sampleEntry().updated, parsed.updated)
        assertEquals(MemoryMaturity.MEDIUM, parsed.maturity)
        assertEquals(sampleEntry().scenarios, parsed.scenarios)
    }

    @Test
    fun `search passes bizId and time range to client without scope filter`() = runTest {
        coEvery {
            client.search(
                query = any(),
                category = any(),
                filters = any(),
                topK = any(),
                timeRangeStart = any(),
                timeRangeEnd = any(),
                bizId = any()
            )
        } returns emptyList()

        store.search("query", MemoryScope.PROJECT, projectOwner, limit = 7, timeRangeStart = 100L, timeRangeEnd = 200L)

        coVerify {
            client.search(
                query = "query",
                category = RagCategory.MEMORY,
                filters = emptyMap(),
                topK = 7,
                timeRangeStart = 100L,
                timeRangeEnd = 200L,
                bizId = projectBizId
            )
        }
    }

    @Test
    fun `search deduplicates chunks of the same entry`() = runTest {
        val docSlot = slot<RagDocument>()
        coEvery { client.upsert(capture(docSlot), any()) } returns RagUpsertResult(docId = "doc-1", indexed = true)
        store.write(sampleEntry(), MemoryScope.GLOBAL, globalOwner)
        val storedContent = docSlot.captured.content

        coEvery {
            client.search(
                query = any(),
                category = any(),
                filters = any(),
                topK = any(),
                timeRangeStart = any(),
                timeRangeEnd = any(),
                bizId = any()
            )
        } returns listOf(
            RagChunk(storedContent, "easyai/memory/experience_lessons/frp-remote-access.md", 0.9, null, emptyMap()),
            RagChunk(storedContent, "easyai/memory/experience_lessons/frp-remote-access.md", 0.8, null, emptyMap())
        )

        val results = store.search("query", MemoryScope.GLOBAL, globalOwner, 5)
        assertEquals(1, results.size)
    }

    // ── PROJECT degradation without project path ───────────────────────

    @Test
    fun `PROJECT reads without project path degrade to empty and skip the client`() = runTest {
        assertEquals(emptyList(), store.search("query", MemoryScope.PROJECT, noProjectOwner))
        assertEquals(emptyList(), store.list(MemoryScope.PROJECT, noProjectOwner))
        assertEquals("", store.loadAll(MemoryScope.PROJECT, noProjectOwner))
        assertNull(store.read("experience_lessons/frp-remote-access.md", MemoryScope.PROJECT, noProjectOwner))
        assertNull(store.findByName("frp-remote-access", MemoryScope.PROJECT, noProjectOwner))
        assertFalse(store.exists("frp-remote-access", MemoryScope.PROJECT, noProjectOwner))
        assertEquals(0, store.deleteAll(MemoryScope.PROJECT, noProjectOwner))

        coVerify(exactly = 0) { client.search(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { client.list(any(), any(), any()) }
        coVerify(exactly = 0) { client.readByExternalId(any(), any()) }
    }

    @Test
    fun `PROJECT delete without project path throws`() = runTest {
        assertFailsWith<MemoryBackendException> {
            store.delete("experience_lessons/frp-remote-access.md", MemoryScope.PROJECT, noProjectOwner)
        }
    }

    // ── delete / deleteAll ─────────────────────────────────────────────

    @Test
    fun `delete returns false and skips client delete when document is absent`() = runTest {
        coEvery { client.readByExternalId(any(), any()) } returns null

        val deleted = store.delete("experience_lessons/frp-remote-access.md", MemoryScope.GLOBAL, globalOwner)

        assertFalse(deleted)
        coVerify(exactly = 0) { client.delete(any(), any()) }
    }

    @Test
    fun `delete removes existing document with bizId`() = runTest {
        val externalId = "easyai:memory:experience_lessons/frp-remote-access.md"
        coEvery { client.readByExternalId(externalId, globalBizId) } returns
            RagDocumentDetail(
                docId = "doc-1",
                externalId = externalId,
                filePath = null,
                content = "x",
                status = null,
                createTime = null,
                chunksCount = null
            )

        val deleted = store.delete("experience_lessons/frp-remote-access.md", MemoryScope.GLOBAL, globalOwner)

        assertTrue(deleted)
        coVerify { client.delete(externalId, globalBizId) }
    }

    @Test
    fun `deleteAll lists memory prefix then batch deletes with bizId`() = runTest {
        coEvery { client.list(RagCategory.MEMORY, "easyai/memory/", globalBizId) } returns listOf(
            docInfo("doc-1", "easyai/memory/user_preferences/a.md"),
            docInfo("doc-2", "easyai/memory/project_information/b.md")
        )
        coEvery { client.batchDelete(listOf("doc-1", "doc-2"), globalBizId) } returns 2

        val count = store.deleteAll(MemoryScope.GLOBAL, globalOwner)

        assertEquals(2, count)
    }

    // ── findByName / list ──────────────────────────────────────────────

    @Test
    fun `findByName derives externalId per type and reads directly with bizId`() = runTest {
        val docSlot = slot<RagDocument>()
        coEvery { client.upsert(capture(docSlot), any()) } returns RagUpsertResult(docId = "doc-1", indexed = true)
        store.write(sampleEntry(), MemoryScope.GLOBAL, globalOwner)
        val storedContent = docSlot.captured.content

        coEvery { client.readByExternalId(any(), any()) } returns null
        coEvery { client.readByExternalId("easyai:memory:experience_lessons/frp-remote-access.md", globalBizId) } returns
            RagDocumentDetail(
                docId = "doc-1",
                externalId = "easyai:memory:experience_lessons/frp-remote-access.md",
                filePath = "easyai/memory/experience_lessons/frp-remote-access.md",
                content = storedContent,
                status = null,
                createTime = null,
                chunksCount = null
            )

        val found = store.findByName("frp-remote-access", MemoryScope.GLOBAL, globalOwner)
        assertEquals(sampleEntry().name, found?.name)
        assertEquals(MemoryType.EXPERIENCE_LESSONS, found?.type)
    }

    @Test
    fun `list fetches full content per document with type prefix and bizId`() = runTest {
        val docSlot = slot<RagDocument>()
        coEvery { client.upsert(capture(docSlot), any()) } returns RagUpsertResult(docId = "doc-1", indexed = true)
        store.write(sampleEntry(), MemoryScope.GLOBAL, globalOwner)
        val storedContent = docSlot.captured.content

        coEvery { client.list(RagCategory.MEMORY, "easyai/memory/experience_lessons/", globalBizId) } returns listOf(
            docInfo("doc-1", "easyai/memory/experience_lessons/frp-remote-access.md", externalId = "easyai:memory:experience_lessons/frp-remote-access.md")
        )
        coEvery { client.readByExternalId("easyai:memory:experience_lessons/frp-remote-access.md", globalBizId) } returns
            RagDocumentDetail(
                docId = "doc-1",
                externalId = null,
                filePath = "easyai/memory/experience_lessons/frp-remote-access.md",
                content = storedContent,
                status = null,
                createTime = null,
                chunksCount = null
            )

        val entries = store.list(MemoryScope.GLOBAL, globalOwner, MemoryType.EXPERIENCE_LESSONS)
        assertEquals(1, entries.size)
        assertEquals("frp-remote-access", entries[0].name)
    }

    // ── error translation ──────────────────────────────────────────────

    @Test
    fun `RagException is translated to MemoryBackendException`() = runTest {
        coEvery { client.upsert(any(), any()) } throws RagException("connection refused", cause = null)

        assertFailsWith<MemoryBackendException> {
            store.write(sampleEntry(), MemoryScope.GLOBAL, globalOwner)
        }
    }

    private fun docInfo(docId: String, filePath: String, externalId: String? = null) = RagDocInfo(
        docId = docId,
        filePath = filePath,
        status = null,
        externalId = externalId,
        contentSummary = null,
        contentLength = null,
        chunksCount = null,
        createdAt = null,
        updatedAt = null
    )
}
