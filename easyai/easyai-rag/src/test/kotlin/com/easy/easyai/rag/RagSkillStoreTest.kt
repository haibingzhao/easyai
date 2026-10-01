package com.easy.easyai.rag

import com.easy.easyai.core.skill.SkillDeleteResult
import com.easy.easyai.core.skill.SkillDocumentState
import com.easy.easyai.core.skill.SkillEntry
import com.easy.easyai.core.skill.SkillOwnerContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [RagSkillStore].
 *
 * Two properties carry the design: slice addressing (one slice per owner — a wrong biz_id is a
 * tenant-isolation bug, not a cosmetic issue) and degradation (skill discovery is a non-critical
 * path, so no RAG failure may escape into the agent loop or startup).
 */
class RagSkillStoreTest {

    private val client = mockk<RagClient>(relaxed = true)
    private val store = RagSkillStore(client)

    private val aliceOwner = SkillOwnerContext(userId = "alice")
    private val aliceBizId = RagBizIdResolver.skillBizId("alice")
    private val systemBizId = RagBizIdResolver.skillBizId("system")

    private fun entry(name: String = "pdf-report", description: String = "Generate PDF reports from data"): SkillEntry =
        SkillEntry(
            key = SkillEntry.keyFor(name),
            name = name,
            description = description,
            tags = listOf("pdf", "report"),
            examples = listOf("把季度数据导出成 PDF"),
            content = "## Workflow\n\nRun the render script.",
            origin = "handwritten",
            checksum = "source-checksum"
        )

    // ── index ──────────────────────────────────────────────────────────

    @Nested
    inner class `index writes` {

        @Test
        fun `entries are upserted into their owner skill slice`() = runTest {
            val docSlot = slot<RagDocument>()
            val bizSlot = slot<String>()
            val awaitSlot = slot<Boolean>()
            coEvery { client.upsert(capture(docSlot), capture(bizSlot), capture(awaitSlot)) } returns
                RagUpsertResult(docId = "doc-1", indexed = false)

            val indexed = store.submit(listOf(entry()), aliceOwner)

            assertIs<SkillDocumentState.Submitted>(indexed.single().state)
            assertEquals("source-checksum", docSlot.captured.metadata["checksum"])
            assertEquals(aliceBizId, bizSlot.captured)
            // Bulk paths are fire-and-forget; install and create pass awaitIndexing = true explicitly.
            assertFalse(awaitSlot.captured)

            val doc = docSlot.captured
            assertEquals("skills/pdf-report.md", doc.key)
            assertEquals("structure_aware", doc.options.chunkMethod)
            assertTrue(doc.options.skipKg)
            assertFalse(doc.options.buildStructure)
            assertEquals("pdf-report", doc.metadata["name"])
            assertEquals("Generate PDF reports from data", doc.metadata["description"])
            assertEquals("pdf,report", doc.metadata["tags"])
            assertEquals("handwritten", doc.metadata["origin"])
            // biz_id is the security boundary; this copy only exists to make server logs readable
            assertEquals("alice", doc.metadata["userId"])
            // The body is indexed on purpose: descriptions alone recall far worse
            assertTrue(doc.content.contains("Run the render script."), doc.content)
        }

        @Test
        fun `the shared layer writes to the system slice, never into a user slice`() = runTest {
            val bizSlot = slot<String>()
            coEvery { client.upsert(any(), capture(bizSlot), any()) } returns
                RagUpsertResult(docId = "doc-1", indexed = false)

            store.submit(listOf(entry()), SkillOwnerContext(userId = "system"))

            assertEquals(systemBizId, bizSlot.captured)
            assertEquals("u_system_s", bizSlot.captured)
        }

        @Test
        fun `an owner-less context addresses the shared slice instead of writing nothing`() = runTest {
            coEvery { client.upsert(any(), any(), any()) } returns RagUpsertResult(docId = "doc-1", indexed = false)

            val indexed = store.submit(listOf(entry()), SkillOwnerContext(userId = null))

            assertIs<SkillDocumentState.Submitted>(indexed.single().state)
            coVerify(exactly = 1) { client.upsert(any(), "u_system_s", false) }
        }

        @Test
        fun `awaitIndexing is forwarded so a freshly installed skill is immediately searchable`() = runTest {
            val awaitSlot = slot<Boolean>()
            coEvery { client.upsert(any(), any(), capture(awaitSlot)) } returns RagUpsertResult(docId = "doc-1", indexed = true)

            store.submit(listOf(entry()), aliceOwner, awaitIndexing = true)

            assertTrue(awaitSlot.captured)
        }

        @Test
        fun `an empty entry list costs no request`() = runTest {
            assertTrue(store.submit(emptyList(), aliceOwner).isEmpty())
            coVerify(exactly = 0) { client.upsert(any(), any(), any()) }
        }

        @Test
        fun `one failing document does not abort the batch`() = runTest {
            coEvery { client.upsert(any(), any(), any()) } returns RagUpsertResult(docId = "doc-1", indexed = false)
            coEvery {
                client.upsert(match { doc -> doc.key == "skills/broken.md" }, any(), any())
            } throws RagException("pipeline busy", statusCode = 409)

            val indexed = store.submit(listOf(entry("good"), entry("broken")), aliceOwner)

            assertEquals(2, indexed.size)
            assertIs<SkillDocumentState.Submitted>(indexed.first().state)
            assertIs<SkillDocumentState.Failed>(indexed.last().state)
        }

        @Test
        fun `business time is the on-disk mtime so stable content keeps a stable date`() = runTest {
            val skillFile = tempDir.resolve("SKILL.md")
            Files.writeString(skillFile, "---\nname: pdf-report\n---\n\nbody\n")
            val stamped = System.currentTimeMillis() - 8_640_000L
            assertTrue(skillFile.toFile().setLastModified(stamped))

            val docSlot = slot<RagDocument>()
            coEvery { client.upsert(capture(docSlot), any(), any()) } returns RagUpsertResult(docId = "doc-1", indexed = false)

            store.submit(listOf(entry().copy(location = skillFile.toString())), aliceOwner)

            assertEquals(stamped / 1000, docSlot.captured.createTime)
        }

        @TempDir
        lateinit var tempDir: Path
    }

    // ── search ─────────────────────────────────────────────────────────

    @Nested
    inner class `search reads` {

        @Test
        fun `every owner slice is queried in one round trip with an over-fetched topK`() = runTest {
            coEvery { client.search(query = any(), topK = any(), bizIds = any()) } returns emptyList()

            store.search("pdf", listOf("alice", "system"), topK = 5)

            coVerify(exactly = 1) {
                client.search(
                    query = "pdf",
                    filters = emptyMap(),
                    topK = 10,
                    timeRangeStart = null,
                    timeRangeEnd = null,
                    bizId = null,
                    bizIds = listOf(aliceBizId, systemBizId)
                )
            }
        }

        @Test
        fun `a single owner collapses the biz set to its own slice`() = runTest {
            coEvery { client.search(query = any(), topK = any(), bizIds = any()) } returns emptyList()

            store.search("pdf", listOf("system"), topK = 5)

            coVerify(exactly = 1) {
                client.search(
                    query = "pdf",
                    filters = emptyMap(),
                    topK = 5,
                    timeRangeStart = null,
                    timeRangeEnd = null,
                    bizId = null,
                    bizIds = listOf(systemBizId)
                )
            }
        }

        @Test
        fun `a repeated owner contributes one slice and keeps its first position`() = runTest {
            coEvery { client.search(query = any(), topK = any(), bizIds = any()) } returns emptyList()

            store.search("pdf", listOf("alice", "alice", "system"), topK = 5)

            coVerify(exactly = 1) {
                client.search(
                    query = "pdf",
                    filters = emptyMap(),
                    topK = 10,
                    timeRangeStart = null,
                    timeRangeEnd = null,
                    bizId = null,
                    bizIds = listOf(aliceBizId, systemBizId)
                )
            }
        }

        @Test
        fun `an own slice shadows the same name of the shared layer`() = runTest {
            stubSearch(
                aliceBizId to entry("shared-name", "own description"),
                systemBizId to entry("shared-name", "shared description")
            )

            val results = store.search("pdf", listOf("alice", "system"), topK = 5)

            assertEquals(listOf("own description"), results.map { it.description })
            assertTrue(results.all { it.score != null })
        }

        @Test
        fun `chunks of the same document collapse into one entry`() = runTest {
            stubSearch(aliceBizId to entry(), aliceBizId to entry())

            assertEquals(1, store.search("pdf", listOf("alice"), topK = 5).size)
        }

        @Test
        fun `the per-owner quota keeps a crowded shared slice from evicting the own hit`() = runTest {
            stubSearch(
                systemBizId to entry("shared-one"),
                systemBizId to entry("shared-two"),
                systemBizId to entry("shared-three"),
                aliceBizId to entry("own-one")
            )

            val results = store.search("pdf", listOf("alice", "system"), topK = 2)

            assertEquals(setOf("own-one", "shared-one"), results.map { it.name }.toSet())
        }

        @Test
        fun `a chunk from a foreign slice is dropped rather than returned`() = runTest {
            stubSearch("u_bob_s" to entry("leaked"))

            assertTrue(store.search("pdf", listOf("alice"), topK = 5).isEmpty())
        }
    }

    // ── delete ─────────────────────────────────────────────────────────

    @Nested
    inner class `index removal` {

        @Test
        fun `delete removes the document from the addressed slice only`() = runTest {
            coEvery { client.delete(any(), any()) } returns true
            coEvery { client.inspectByExternalId(any(), any()) } returns null

            assertIs<SkillDeleteResult.Absent>(store.ensureAbsent("pdf-report", aliceOwner))
            coVerify(exactly = 1) { client.delete("easyai:skills/pdf-report.md", aliceBizId) }
        }

        @Test
        fun `delete never touches another owner's slice`() = runTest {
            coEvery { client.delete(any(), any()) } returns true
            coEvery { client.inspectByExternalId(any(), any()) } returns null

            store.ensureAbsent("pdf-report", aliceOwner)

            coVerify(exactly = 0) { client.delete(any(), systemBizId) }
        }
    }

    // ── degradation ────────────────────────────────────────────────────

    @Nested
    inner class `failure never propagates` {

        @Test
        fun `a search outage yields empty results instead of an exception`() = runTest {
            coEvery { client.search(query = any(), topK = any(), bizIds = any()) } throws RagException("connection refused")
            coEvery { client.search(query = any(), topK = any(), bizId = any()) } throws RagException("connection refused")

            assertTrue(store.search("pdf", listOf("alice", "system"), topK = 5).isEmpty())
        }

        @Test
        fun `a bizIds set the server rejects degrades to one retry on the requesting owner`() = runTest {
            val chunks = stubSearch(aliceBizId to entry("own-one"))
            stubDegradedSearch(chunks)
            coEvery {
                client.search(query = any(), topK = any(), bizIds = any())
            } throws RagException("invalid biz_id", statusCode = 400)

            val results = store.search("pdf", listOf("alice", "system"), topK = 5)

            assertEquals(listOf("own-one"), results.map { it.name })
            coVerify(exactly = 1) {
                client.search(
                    query = "pdf",
                    filters = emptyMap(),
                    topK = 5,
                    timeRangeStart = null,
                    timeRangeEnd = null,
                    bizId = aliceBizId,
                    bizIds = null
                )
            }
        }

        @Test
        fun `an empty owner list never reaches the client`() = runTest {
            assertTrue(store.search("pdf", emptyList(), topK = 5).isEmpty())
            coVerify(exactly = 0) { client.search(query = any(), topK = any(), bizIds = any()) }
        }

        @Test
        fun `an index outage returns zero and does not throw`() = runTest {
            coEvery { client.upsert(any(), any(), any()) } throws RagException("connection refused")

            assertIs<SkillDocumentState.Failed>(store.submit(listOf(entry()), aliceOwner).single().state)
        }

        @Test
        fun `a delete outage reports failure`() = runTest {
            coEvery { client.delete(any(), any()) } throws RagException("connection refused")

            assertIs<SkillDeleteResult.Failed>(store.ensureAbsent("pdf-report", aliceOwner))
        }
    }

    @Nested
    inner class `remote confirmation` {
        @Test
        fun `unchanged but unprocessed remains submitted even when awaiting`() = runTest {
            coEvery { client.upsert(any(), any(), any()) } returns RagUpsertResult("doc", indexed = false, unchanged = true)
            val result = store.submit(listOf(entry()), aliceOwner, awaitIndexing = true)
            assertIs<SkillDocumentState.Submitted>(result.single().state)
        }

        @Test
        fun `processed result carries observed remote metadata checksum not submitted checksum`() = runTest {
            coEvery { client.upsert(any(), any(), any()) } returns RagUpsertResult("doc", indexed = true)
            coEvery { client.inspectByExternalId("easyai:skills/pdf-report.md", aliceBizId) } returns
                RagDocumentDetail("doc", null, null, null, "processed", null, null, mapOf("checksum" to "remote-version"))
            val result = store.submit(listOf(entry()), aliceOwner).single().state
            assertIs<SkillDocumentState.Processed>(result)
            assertEquals("remote-version", result.checksum)
        }

        @Test
        fun `missing is idempotent success but disabled backend is not missing`() = runTest {
            coEvery { client.delete(any(), any()) } returns false
            coEvery { client.inspectByExternalId(any(), any()) } returns null
            assertIs<SkillDeleteResult.Absent>(store.ensureAbsent("pdf-report", aliceOwner))
            coEvery { client.inspectByExternalId(any(), any()) } throws RagException("disabled")
            assertIs<SkillDeleteResult.Failed>(store.ensureAbsent("pdf-report", aliceOwner))
            assertIs<SkillDocumentState.Failed>(store.inspect("pdf-report", aliceOwner))
        }

        @Test
        fun `delete acceptance is not absence while the remote document still exists`() = runTest {
            coEvery { client.delete(any(), any()) } returns true
            coEvery { client.inspectByExternalId(any(), any()) } returns
                RagDocumentDetail("doc", null, null, null, "pending", null, null)
            assertIs<SkillDeleteResult.Failed>(store.ensureAbsent("pdf-report", aliceOwner))
        }
    }

    // ── helpers ────────────────────────────────────────────────────────

    /**
     * Stub the multi-slice search path with one chunk per (bizId, entry) pair, so parsing runs on
     * input serialized exactly the way [RagSkillStore.submit] stores it.
     */
    private suspend fun stubSearch(vararg hits: Pair<String, SkillEntry>): List<RagChunk> {
        val chunks = hits.map { (bizId, skillEntry) ->
            RagChunk(
                content = storedContentOf(skillEntry),
                filePath = "easyai/${skillEntry.key}",
                score = 0.9,
                createTime = null,
                metadata = emptyMap(),
                bizId = bizId
            )
        }
        coEvery { client.search(query = any(), topK = any(), bizIds = any()) } returns chunks
        return chunks
    }

    /** Stub the single-slice path used when the server rejects the `bizIds` set. */
    private fun stubDegradedSearch(chunks: List<RagChunk>) {
        coEvery { client.search(query = any(), topK = any(), bizId = any()) } returns chunks
    }

    private suspend fun storedContentOf(skillEntry: SkillEntry): String {
        val docSlot = slot<RagDocument>()
        coEvery { client.upsert(capture(docSlot), any(), any()) } returns RagUpsertResult(docId = "doc-1", indexed = false)
        store.submit(listOf(skillEntry), aliceOwner)
        return docSlot.captured.content
    }
}
