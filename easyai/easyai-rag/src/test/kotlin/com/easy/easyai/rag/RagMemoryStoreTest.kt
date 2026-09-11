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
    private val globalBizId = RagBizIdResolver.globalBizId("alice", RagBizIdResolver.MEMORY_TYPE)
    private val projectBizId = RagBizIdResolver.projectBizId("alice", projectPath, RagBizIdResolver.MEMORY_TYPE)!!

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
        coEvery { client.upsert(capture(docSlot), capture(bizSlot), any()) } returns RagUpsertResult(docId = "doc-1", indexed = true)

        store.write(sampleEntry(), MemoryScope.GLOBAL, globalOwner)

        val doc = docSlot.captured
        assertEquals("experience_lessons/frp-remote-access.md", doc.key)
        assertEquals("easyai:experience_lessons/frp-remote-access.md", doc.externalId)
        assertEquals("easyai/experience_lessons/frp-remote-access.md", doc.filePath)
        // biz_id carries isolation + content type; scope metadata is no longer needed
        assertFalse(doc.metadata.containsKey("scope"))
        assertFalse(doc.metadata.containsKey("category"))
        assertEquals("experience_lessons", doc.metadata["type"])
        assertEquals("frp-remote-access", doc.metadata["name"])
        assertEquals("medium", doc.metadata["maturity"])
        // Freshness copy: only the first chunk keeps the frontmatter, so retrieval reads the
        // dates of later hits from this per-chunk metadata
        assertEquals("2025-12-01", doc.metadata["created"])
        assertEquals("2026-01-15", doc.metadata["updated"])
        // Markdown memories: heading-based chunking only — KG and structure index are
        // never read by memory_search (mode=naive), so they must not be built
        assertEquals("structure_aware", doc.options.chunkMethod)
        assertTrue(doc.options.skipKg)
        assertFalse(doc.options.buildStructure)
        assertEquals(globalBizId, bizSlot.captured)
        // createTime is the updated date as epoch seconds (business time)
        val expected = LocalDate.of(2026, 1, 15).atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
        assertEquals(expected, doc.createTime)
    }

    @Test
    fun `write passes PROJECT bizId derived from user and project path`() = runTest {
        val bizSlot = slot<String>()
        coEvery { client.upsert(any(), capture(bizSlot), any()) } returns RagUpsertResult(docId = "doc-1", indexed = true)

        store.write(sampleEntry(), MemoryScope.PROJECT, projectOwner)

        assertEquals(projectBizId, bizSlot.captured)
    }

    @Test
    fun `write to PROJECT scope without project path throws`() = runTest {
        assertFailsWith<MemoryBackendException> {
            store.write(sampleEntry(), MemoryScope.PROJECT, noProjectOwner)
        }
        coVerify(exactly = 0) { client.upsert(any(), any(), any()) }
    }

    // ── search ─────────────────────────────────────────────────────────

    @Test
    fun `frontmatter content round-trips through write and search`() = runTest {
        val docSlot = slot<RagDocument>()
        coEvery { client.upsert(capture(docSlot), any(), any()) } returns RagUpsertResult(docId = "doc-1", indexed = true)
        val entry = sampleEntry().copy(lastAccessed = LocalDate.of(2026, 2, 3))
        store.write(entry, MemoryScope.GLOBAL, globalOwner)
        val storedContent = docSlot.captured.content

        coEvery {
            client.search(
                query = any(),
                filters = any(),
                topK = any(),
                timeRangeStart = any(),
                timeRangeEnd = any(),
                bizId = any()
            )
        } returns listOf(
            RagChunk(
                content = storedContent,
                filePath = "easyai/experience_lessons/frp-remote-access.md",
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
        assertEquals(entry.lastAccessed, parsed.lastAccessed)
    }

    @Test
    fun `search falls back to chunk metadata when the hit chunk lacks frontmatter`() = runTest {
        val updatedEpoch = LocalDate.of(2026, 1, 15).atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
        coEvery {
            client.search(
                query = any(),
                filters = any(),
                topK = any(),
                timeRangeStart = any(),
                timeRangeEnd = any(),
                bizId = any()
            )
        } returns listOf(
            RagChunk(
                content = "## Later heading\n\nBody text with no frontmatter at all.",
                filePath = "easyai/experience_lessons/frp-remote-access.md",
                score = 0.8,
                createTime = updatedEpoch,
                metadata = mapOf("maturity" to "high", "description" to "frp relay setup for remote Mac access")
            )
        )

        val parsed = store.search("frp", MemoryScope.GLOBAL, globalOwner, 5).single()

        assertEquals(sampleEntry().description, parsed.description)
        assertEquals(MemoryMaturity.HIGH, parsed.maturity)
        assertEquals(LocalDate.of(2026, 1, 15), parsed.updated)
        assertNull(parsed.created)
    }

    @Test
    fun `search drops a chunk whose entry cannot be located`() = runTest {
        coEvery {
            client.search(
                query = any(),
                filters = any(),
                topK = any(),
                timeRangeStart = any(),
                timeRangeEnd = any(),
                bizId = any()
            )
        } returns listOf(RagChunk("body without frontmatter", filePath = null, score = 0.8, createTime = null, metadata = emptyMap()))

        assertEquals(0, store.search("anything", MemoryScope.GLOBAL, globalOwner, 5).size)
    }

    @Test
    fun `search passes bizId and time range to client without scope filter`() = runTest {
        coEvery {
            client.search(
                query = any(),
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
        coEvery { client.upsert(capture(docSlot), any(), any()) } returns RagUpsertResult(docId = "doc-1", indexed = true)
        store.write(sampleEntry(), MemoryScope.GLOBAL, globalOwner)
        val storedContent = docSlot.captured.content

        coEvery {
            client.search(
                query = any(),
                filters = any(),
                topK = any(),
                timeRangeStart = any(),
                timeRangeEnd = any(),
                bizId = any()
            )
        } returns listOf(
            RagChunk(storedContent, "easyai/experience_lessons/frp-remote-access.md", 0.9, null, emptyMap()),
            RagChunk(storedContent, "easyai/experience_lessons/frp-remote-access.md", 0.8, null, emptyMap())
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

        coVerify(exactly = 0) { client.search(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { client.list(any(), any()) }
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
    fun `delete reports false when the backend matches no document`() = runTest {
        coEvery { client.delete(any(), any()) } returns false

        val deleted = store.delete("experience_lessons/frp-remote-access.md", MemoryScope.GLOBAL, globalOwner)

        assertFalse(deleted)
        coVerify(exactly = 1) { client.delete("easyai:experience_lessons/frp-remote-access.md", globalBizId) }
    }

    @Test
    fun `delete removes existing document with bizId and no pre-read`() = runTest {
        coEvery { client.delete(any(), any()) } returns true

        val deleted = store.delete("experience_lessons/frp-remote-access.md", MemoryScope.GLOBAL, globalOwner)

        assertTrue(deleted)
        coVerify(exactly = 1) { client.delete("easyai:experience_lessons/frp-remote-access.md", globalBizId) }
        coVerify(exactly = 0) { client.readByExternalId(any(), any()) }
    }

    @Test
    fun `deleteAll lists memory prefix then batch deletes with bizId`() = runTest {
        coEvery { client.list("easyai/", globalBizId) } returns listOf(
            docInfo("doc-1", "easyai/user_preferences/a.md"),
            docInfo("doc-2", "easyai/project_information/b.md")
        )
        coEvery { client.batchDelete(listOf("doc-1", "doc-2"), globalBizId) } returns 2

        val count = store.deleteAll(MemoryScope.GLOBAL, globalOwner)

        assertEquals(2, count)
    }

    // ── findByName / list ──────────────────────────────────────────────

    @Test
    fun `findByName with an explicit type reads the document directly`() = runTest {
        coEvery { client.readByExternalId(EXTERNAL_ID, globalBizId) } returns detail(storedContentOf(sampleEntry()))

        val found = store.findByName("frp-remote-access", MemoryScope.GLOBAL, globalOwner, MemoryType.EXPERIENCE_LESSONS)

        assertEquals(sampleEntry().name, found?.name)
        assertEquals(MemoryType.EXPERIENCE_LESSONS, found?.type)
        coVerify(exactly = 0) { client.list(any(), any()) }
        coVerify(exactly = 1) { client.readByExternalId(any(), any()) }
    }

    @Test
    fun `findByName without a type probes the document list instead of every type`() = runTest {
        val content = storedContentOf(sampleEntry())
        coEvery { client.list("easyai/", globalBizId) } returns listOf(docInfo("doc-1", FILE_PATH, externalId = EXTERNAL_ID))
        coEvery { client.readByExternalId(EXTERNAL_ID, globalBizId) } returns detail(content)

        val found = store.findByName("frp-remote-access", MemoryScope.GLOBAL, globalOwner)

        assertEquals(sampleEntry().name, found?.name)
        coVerify(exactly = 1) { client.list(any(), any()) }
        coVerify(exactly = 1) { client.readByExternalId(any(), any()) }
    }

    @Test
    fun `findByName returns null after one list call when no candidate matches`() = runTest {
        coEvery { client.list(any(), any()) } returns listOf(
            docInfo("doc-9", "easyai/other/unrelated.md", externalId = "easyai:other/unrelated.md")
        )

        assertNull(store.findByName("frp-remote-access", MemoryScope.GLOBAL, globalOwner))

        coVerify(exactly = 1) { client.list(any(), any()) }
        coVerify(exactly = 0) { client.readByExternalId(any(), any()) }
    }

    // ── readEntry / touch ──────────────────────────────────────────────

    @Test
    fun `readEntry resolves one document by path without listing the scope`() = runTest {
        coEvery { client.readByExternalId(EXTERNAL_ID, globalBizId) } returns detail(storedContentOf(sampleEntry()))

        val entry = store.readEntry("experience_lessons/frp-remote-access.md", MemoryScope.GLOBAL, globalOwner)

        assertEquals("frp-remote-access", entry?.name)
        assertEquals(MemoryMaturity.MEDIUM, entry?.maturity)
        coVerify(exactly = 1) { client.readByExternalId(any(), any()) }
        coVerify(exactly = 0) { client.list(any(), any()) }
    }

    @Test
    fun `touch stamps today and rewrites the full stored body`() = runTest {
        val content = storedContentOf(sampleEntry())
        coEvery { client.readByExternalId(EXTERNAL_ID, globalBizId) } returns detail(content)
        val docSlot = slot<RagDocument>()
        coEvery { client.upsert(capture(docSlot), any(), any()) } returns RagUpsertResult(docId = "doc-1", indexed = true)

        val touched = store.touch("experience_lessons/frp-remote-access.md", MemoryScope.GLOBAL, globalOwner)

        assertEquals(LocalDate.now(), touched?.lastAccessed)
        // A retrieval hit can carry only part of the body, so touch must rewrite the complete
        // document it read back, not a chunk fragment.
        assertTrue(docSlot.captured.content.contains("Use frps on the ECS"))
        assertTrue(docSlot.captured.content.contains("last_accessed: ${LocalDate.now()}"))
    }

    @Test
    fun `touch skips the write when lastAccessed is today`() = runTest {
        // The single upsert here comes from seeding the stored content, not from the touch.
        coEvery { client.readByExternalId(EXTERNAL_ID, globalBizId) } returns
            detail(storedContentOf(sampleEntry().copy(lastAccessed = LocalDate.now())))

        val touched = store.touch("experience_lessons/frp-remote-access.md", MemoryScope.GLOBAL, globalOwner)

        assertEquals(LocalDate.now(), touched?.lastAccessed)
        coVerify(exactly = 1) { client.upsert(any(), any(), any()) }
    }

    @Test
    fun `list fetches full content per document with type prefix and bizId`() = runTest {
        val docSlot = slot<RagDocument>()
        coEvery { client.upsert(capture(docSlot), any(), any()) } returns RagUpsertResult(docId = "doc-1", indexed = true)
        store.write(sampleEntry(), MemoryScope.GLOBAL, globalOwner)
        val storedContent = docSlot.captured.content

        coEvery { client.list("easyai/experience_lessons/", globalBizId) } returns listOf(
            docInfo("doc-1", "easyai/experience_lessons/frp-remote-access.md", externalId = "easyai:experience_lessons/frp-remote-access.md")
        )
        coEvery { client.readByExternalId("easyai:experience_lessons/frp-remote-access.md", globalBizId) } returns
            RagDocumentDetail(
                docId = "doc-1",
                externalId = null,
                filePath = "easyai/experience_lessons/frp-remote-access.md",
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
        coEvery { client.upsert(any(), any(), any()) } throws RagException("connection refused", cause = null)

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

    private fun detail(content: String) = RagDocumentDetail(
        docId = "doc-1",
        externalId = EXTERNAL_ID,
        filePath = FILE_PATH,
        content = content,
        status = null,
        createTime = null,
        chunksCount = null
    )

    /** Serialize [entry] the way [RagMemoryStore.write] would, so parsing tests use real input. */
    private suspend fun storedContentOf(entry: MemoryEntry): String {
        val docSlot = slot<RagDocument>()
        coEvery { client.upsert(capture(docSlot), any(), any()) } returns RagUpsertResult(docId = "doc-1", indexed = true)
        store.write(entry, MemoryScope.GLOBAL, globalOwner)
        return docSlot.captured.content
    }

    private companion object {
        const val FILE_PATH = "easyai/experience_lessons/frp-remote-access.md"
        const val EXTERNAL_ID = "easyai:experience_lessons/frp-remote-access.md"
    }
}
