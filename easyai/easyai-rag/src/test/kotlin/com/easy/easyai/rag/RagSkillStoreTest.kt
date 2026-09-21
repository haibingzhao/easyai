package com.easy.easyai.rag

import com.easy.easyai.core.skill.SkillEntry
import com.easy.easyai.core.skill.SkillOwnerContext
import com.easy.easyai.core.skill.SkillScope
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [RagSkillStore].
 *
 * Two properties carry the design: slice addressing (a wrong biz_id is a tenant-isolation bug,
 * not a cosmetic issue) and degradation (skill discovery is a non-critical path, so no RAG
 * failure may escape into the agent loop or startup).
 */
class RagSkillStoreTest {

    private val client = mockk<RagClient>(relaxed = true)
    private val store = RagSkillStore(client)

    private val projectPath = Path.of("/tmp/demo-project")
    private val globalOwner = SkillOwnerContext(userId = "alice")
    private val projectOwner = SkillOwnerContext(userId = "alice", projectPath = projectPath)
    private val globalBizId = RagBizIdResolver.globalBizId("alice", RagBizIdResolver.SKILL_TYPE)
    private val projectBizId = RagBizIdResolver.projectBizId("alice", projectPath, RagBizIdResolver.SKILL_TYPE)!!

    private fun entry(name: String = "pdf-report", description: String = "Generate PDF reports from data"): SkillEntry =
        SkillEntry(
            key = SkillEntry.keyFor(name),
            name = name,
            description = description,
            tags = listOf("pdf", "report"),
            examples = listOf("把季度数据导出成 PDF"),
            content = "## Workflow\n\nRun the render script.",
            origin = "handwritten"
        )

    // ── index ──────────────────────────────────────────────────────────

    @Nested
    inner class `index writes` {

        @Test
        fun `GLOBAL entries are upserted into the per-user skill slice`() = runTest {
            val docSlot = slot<RagDocument>()
            val bizSlot = slot<String>()
            val awaitSlot = slot<Boolean>()
            coEvery { client.upsert(capture(docSlot), capture(bizSlot), capture(awaitSlot)) } returns
                RagUpsertResult(docId = "doc-1", indexed = false)

            val indexed = store.index(listOf(entry()), SkillScope.GLOBAL, globalOwner)

            assertEquals(1, indexed)
            assertEquals(globalBizId, bizSlot.captured)
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
        fun `PROJECT entries land in the project slice, never the global one`() = runTest {
            val bizSlot = slot<String>()
            coEvery { client.upsert(any(), capture(bizSlot), any()) } returns RagUpsertResult(docId = "doc-1", indexed = false)

            store.index(listOf(entry()), SkillScope.PROJECT, projectOwner)

            assertEquals(projectBizId, bizSlot.captured)
            assertTrue(bizSlot.captured.startsWith("u_alice-demo-project-"), bizSlot.captured)
            assertTrue(bizSlot.captured.endsWith("_s"), bizSlot.captured)
        }

        @Test
        fun `PROJECT without a project path degrades to zero writes`() = runTest {
            val indexed = store.index(listOf(entry()), SkillScope.PROJECT, globalOwner)

            assertEquals(0, indexed)
            coVerify(exactly = 0) { client.upsert(any(), any(), any()) }
        }

        @Test
        fun `awaitIndexing is forwarded so a freshly installed skill is immediately searchable`() = runTest {
            val awaitSlot = slot<Boolean>()
            coEvery { client.upsert(any(), any(), capture(awaitSlot)) } returns RagUpsertResult(docId = "doc-1", indexed = true)

            store.index(listOf(entry()), SkillScope.GLOBAL, globalOwner, awaitIndexing = true)

            assertTrue(awaitSlot.captured)
        }

        @Test
        fun `an empty entry list costs no request`() = runTest {
            assertEquals(0, store.index(emptyList(), SkillScope.GLOBAL, globalOwner))
            coVerify(exactly = 0) { client.upsert(any(), any(), any()) }
        }

        @Test
        fun `one failing document does not abort the batch`() = runTest {
            coEvery { client.upsert(any(), any(), any()) } returns RagUpsertResult(docId = "doc-1", indexed = false)
            coEvery {
                client.upsert(match { doc -> doc.key == "skills/broken.md" }, any(), any())
            } throws RagException("pipeline busy", statusCode = 409)

            val indexed = store.index(listOf(entry("good"), entry("broken")), SkillScope.GLOBAL, globalOwner)

            assertEquals(1, indexed, "log-and-continue drops only the failing entry")
        }

        @Test
        fun `business time is the on-disk mtime so stable content keeps a stable date`() = runTest {
            val skillFile = tempDir.resolve("SKILL.md")
            Files.writeString(skillFile, "---\nname: pdf-report\n---\n\nbody\n")
            val stamped = System.currentTimeMillis() - 8_640_000L
            assertTrue(skillFile.toFile().setLastModified(stamped))

            val docSlot = slot<RagDocument>()
            coEvery { client.upsert(capture(docSlot), any(), any()) } returns RagUpsertResult(docId = "doc-1", indexed = false)

            store.index(listOf(entry().copy(location = skillFile.toString())), SkillScope.GLOBAL, globalOwner)

            assertEquals(stamped / 1000, docSlot.captured.createTime)
        }

        @TempDir
        lateinit var tempDir: Path
    }

    // ── search ─────────────────────────────────────────────────────────

    @Nested
    inner class `search reads` {

        @Test
        fun `both slices are queried in one round trip with an over-fetched topK`() = runTest {
            coEvery { client.search(query = any(), topK = any(), bizIds = any()) } returns emptyList()

            store.search("pdf", listOf(SkillScope.GLOBAL, SkillScope.PROJECT), projectOwner, topK = 5)

            coVerify(exactly = 1) {
                client.search(
                    query = "pdf",
                    filters = emptyMap(),
                    topK = 10,
                    timeRangeStart = null,
                    timeRangeEnd = null,
                    bizId = null,
                    bizIds = listOf(globalBizId, projectBizId)
                )
            }
        }

        @Test
        fun `without a project path the biz set collapses to the global slice alone`() = runTest {
            coEvery { client.search(query = any(), topK = any(), bizIds = any()) } returns emptyList()

            store.search("pdf", listOf(SkillScope.GLOBAL, SkillScope.PROJECT), globalOwner, topK = 5)

            coVerify(exactly = 1) {
                client.search(
                    query = "pdf",
                    filters = emptyMap(),
                    topK = 5,
                    timeRangeStart = null,
                    timeRangeEnd = null,
                    bizId = null,
                    bizIds = listOf(globalBizId)
                )
            }
        }

        @Test
        fun `hits are labelled with the granularity their slice implies`() = runTest {
            stubSearch(
                globalBizId to entry("shared-name", "global description"),
                projectBizId to entry("shared-name", "project description")
            )

            val results = store.search("pdf", listOf(SkillScope.GLOBAL, SkillScope.PROJECT), projectOwner, topK = 5)

            assertEquals(2, results.size, "the same name in two slices must not swallow each other")
            assertEquals(
                setOf(SkillScope.GLOBAL to "global description", SkillScope.PROJECT to "project description"),
                results.map { it.scope to it.description }.toSet()
            )
            assertTrue(results.all { it.score != null })
        }

        @Test
        fun `chunks of the same document collapse into one entry`() = runTest {
            stubSearch(globalBizId to entry(), globalBizId to entry())

            assertEquals(1, store.search("pdf", listOf(SkillScope.GLOBAL), globalOwner, topK = 5).size)
        }

        @Test
        fun `the per-slice quota keeps a crowded project slice from evicting the global hit`() = runTest {
            stubSearch(
                projectBizId to entry("project-one"),
                projectBizId to entry("project-two"),
                projectBizId to entry("project-three"),
                globalBizId to entry("global-one")
            )

            val results = store.search("pdf", listOf(SkillScope.GLOBAL, SkillScope.PROJECT), projectOwner, topK = 2)

            assertEquals(setOf("global-one", "project-one"), results.map { it.name }.toSet())
        }

        @Test
        fun `a chunk from a foreign slice is dropped rather than mislabelled`() = runTest {
            stubSearch("u_bob_s" to entry("leaked"))

            assertTrue(store.search("pdf", listOf(SkillScope.GLOBAL), globalOwner, topK = 5).isEmpty())
        }
    }

    // ── delete ─────────────────────────────────────────────────────────

    @Nested
    inner class `index removal` {

        @Test
        fun `delete removes the document from the addressed slice only`() = runTest {
            coEvery { client.delete(any(), any()) } returns true

            assertTrue(store.delete("pdf-report", SkillScope.GLOBAL, globalOwner))
            coVerify(exactly = 1) { client.delete("easyai:skills/pdf-report.md", globalBizId) }
        }

        @Test
        fun `delete on an unaddressable PROJECT slice never reaches the client`() = runTest {
            assertFalse(store.delete("pdf-report", SkillScope.PROJECT, globalOwner))
            coVerify(exactly = 0) { client.delete(any(), any()) }
        }
    }

    // ── degradation ────────────────────────────────────────────────────

    @Nested
    inner class `failure never propagates` {

        @Test
        fun `a search outage yields empty results instead of an exception`() = runTest {
            coEvery { client.search(query = any(), topK = any(), bizIds = any()) } throws RagException("connection refused")
            coEvery { client.search(query = any(), topK = any(), bizId = any()) } throws RagException("connection refused")

            assertTrue(store.search("pdf", listOf(SkillScope.GLOBAL, SkillScope.PROJECT), projectOwner).isEmpty())
        }

        @Test
        fun `a bizIds set the server rejects degrades to one retry on the global slice`() = runTest {
            val chunks = stubSearch(globalBizId to entry("global-one"))
            stubDegradedSearch(chunks)
            coEvery {
                client.search(query = any(), topK = any(), bizIds = any())
            } throws RagException("invalid biz_id", statusCode = 400)

            val results = store.search("pdf", listOf(SkillScope.GLOBAL, SkillScope.PROJECT), projectOwner, topK = 5)

            assertEquals(listOf("global-one"), results.map { it.name })
            coVerify(exactly = 1) {
                client.search(
                    query = "pdf",
                    filters = emptyMap(),
                    topK = 5,
                    timeRangeStart = null,
                    timeRangeEnd = null,
                    bizId = globalBizId,
                    bizIds = null
                )
            }
        }

        @Test
        fun `an index outage returns zero and does not throw`() = runTest {
            coEvery { client.upsert(any(), any(), any()) } throws RagException("connection refused")

            assertEquals(0, store.index(listOf(entry()), SkillScope.GLOBAL, globalOwner))
        }

        @Test
        fun `a delete outage reports false`() = runTest {
            coEvery { client.delete(any(), any()) } throws RagException("connection refused")

            assertFalse(store.delete("pdf-report", SkillScope.GLOBAL, globalOwner))
        }
    }

    // ── helpers ────────────────────────────────────────────────────────

    /**
     * Stub the multi-slice search path with one chunk per (bizId, entry) pair, so parsing runs on
     * input serialized exactly the way [RagSkillStore.index] stores it.
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
        store.index(listOf(skillEntry), SkillScope.GLOBAL, globalOwner)
        return docSlot.captured.content
    }
}
