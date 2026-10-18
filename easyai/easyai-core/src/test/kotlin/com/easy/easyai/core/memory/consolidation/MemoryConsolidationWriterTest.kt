package com.easy.easyai.core.memory.consolidation

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.memory.MemoryArchiveUpdate
import com.easy.easyai.core.memory.MemoryEntry
import com.easy.easyai.core.memory.MemoryOwnerContext
import com.easy.easyai.core.memory.MemoryScope
import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.core.memory.MemoryType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests the sole write path of a consolidation run: the shape of what lands in the store, and the
 * store-backed checks a JSON Schema cannot express (invented docIds, a source two clusters both
 * claimed, a source already archived by an earlier run).
 */
class MemoryConsolidationWriterTest {

    private val store = mockk<MemoryStore>()
    private val writer = MemoryConsolidationWriter(store)
    private val scope = MemoryScope.PROJECT
    private val owner = MemoryOwnerContext("alice", Path.of("/tmp/demo"))
    private val batchIds = listOf("doc-a", "doc-b")

    private fun source(id: String, level: Int = 0, archived: Boolean = false) = MemoryEntry(
        id = id,
        name = "src-$id",
        description = "desc $id",
        type = MemoryType.EXPERIENCE_LESSONS,
        content = "body $id",
        path = "experience_lessons/src-$id.md",
        created = LocalDate.of(2026, 1, 1),
        updated = LocalDate.of(2026, 1, 1),
        level = level,
        archived = archived
    )

    /** Resolves only the docIds a cluster actually asks for, so overlap reads like the real store. */
    private fun state(
        dryRun: Boolean = false,
        maxArchives: Int = 20,
        pool: List<MemoryEntry> = listOf(source("doc-a"), source("doc-b"))
    ): MemoryConsolidationRunState {
        coEvery { store.findByIds(any(), scope, owner) } answers {
            val ids = firstArg<List<String>>()
            pool.filter { it.id in ids }
        }
        return MemoryConsolidationRunState(
            type = MemoryType.EXPERIENCE_LESSONS,
            scope = scope,
            owner = owner,
            dryRun = dryRun,
            maxArchives = maxArchives,
            stats = MemoryConsolidationRunStats()
        )
    }

    private fun cluster(
        title: String = "FRP setup",
        sources: List<String> = listOf("doc-a", "doc-b"),
        description: String = "merged desc",
        content: String = "## Setup\nuse frp",
        refs: List<ConsolidationSectionRef> = emptyList()
    ) = ConsolidationCluster(
        mergedTitle = title,
        mergedDescription = description,
        mergedContent = content,
        sectionRefs = refs,
        sourceDocIds = sources
    )

    private fun output(vararg clusters: ConsolidationCluster, leftovers: List<String> = emptyList()) =
        MemoryConsolidationOutput(clusters = clusters.toList(), leftoverDocIds = leftovers)

    private fun rejects(state: MemoryConsolidationRunState): List<String> =
        state.stats.clusters.mapNotNull { it["rejected"] as? String }

    @Nested
    inner class Commit {

        @Test
        fun `writes one merged entry and archives every source`() = runTest {
            val runState = state(pool = listOf(source("doc-a"), source("doc-b", level = 1)))
            val written = slot<MemoryEntry>()
            coEvery { store.writeEntry(capture(written), scope, owner) } answers {
                written.captured.copy(id = "merged-1")
            }
            val archiveUpdate = slot<MemoryArchiveUpdate>()
            coEvery { store.archive(any(), capture(archiveUpdate), scope, owner) } returns 2

            val outcome = writer.writeBatch(batchIds, output(cluster()), runState)

            val entry = written.captured
            assertEquals(2, entry.level, "merged level must be max(source levels) + 1")
            assertEquals(listOf("doc-a", "doc-b"), entry.refs)
            assertEquals(MemoryType.EXPERIENCE_LESSONS, entry.type)
            assertEquals(true, archiveUpdate.captured.archived)
            assertEquals("merged-1", archiveUpdate.captured.mergedInto)
            assertEquals(1, runState.stats.mergedWritten.get())
            assertEquals(2, runState.stats.archived.get())
            assertEquals(18, runState.remainingArchives())
            assertEquals(emptyList<String>(), outcome.uncoveredDocIds)
        }

        @Test
        fun `the merged entry keeps the union of its sources' keywords and scenarios`() = runTest {
            // The merged body claims to preserve every distinct fact, and retrieval filters plus the
            // Memories page read these tags — dropping them would make the merged entry harder to find
            // than the two entries it absorbed.
            val runState = state(
                pool = listOf(
                    source("doc-a").copy(keywords = listOf("frp", "shared"), scenarios = listOf("remote access")),
                    source("doc-b").copy(keywords = listOf("shared", "tunnel"), scenarios = listOf("deploy, then verify"))
                )
            )
            val written = slot<MemoryEntry>()
            coEvery { store.writeEntry(capture(written), scope, owner) } answers {
                written.captured.copy(id = "merged-1")
            }
            coEvery { store.archive(any(), any(), scope, owner) } returns 2

            writer.writeBatch(batchIds, output(cluster()), runState)

            assertEquals(listOf("frp", "shared", "tunnel"), written.captured.keywords)
            assertEquals(listOf("remote access", "deploy, then verify"), written.captured.scenarios)
        }

        @Test
        fun `a partial archive is reported as a live duplicate, not just a smaller count`() = runTest {
            // The merged document is already stored when the archive call answers, so every source it
            // did not flag is now searchable twice. The per-id reasons only reach the backend's log —
            // the run's own report has to carry the gap or the UI shows a clean smaller number.
            val runState = state(pool = listOf(source("doc-a"), source("doc-b"), source("doc-c")))
            coEvery { store.writeEntry(any(), scope, owner) } answers { firstArg<MemoryEntry>().copy(id = "merged-1") }
            coEvery { store.archive(any(), any(), scope, owner) } returns 1

            writer.writeBatch(
                listOf("doc-a", "doc-b", "doc-c"),
                output(cluster(sources = listOf("doc-a", "doc-b", "doc-c"))),
                runState
            )

            assertEquals(1, runState.stats.archived.get())
            assertEquals(2, runState.stats.archiveShortfall.get())
            val row = runState.stats.clusters.single()
            assertEquals(3, row["expectedArchives"])
            assertEquals(1, row["archived"])
        }

        @Test
        fun `section refs insert inline source markers at their anchors`() = runTest {
            val runState = state()
            val written = slot<MemoryEntry>()
            coEvery { store.writeEntry(capture(written), scope, owner) } answers {
                written.captured.copy(id = "merged-1")
            }
            coEvery { store.archive(any(), any(), scope, owner) } returns 2

            writer.writeBatch(
                batchIds,
                output(cluster(refs = listOf(ConsolidationSectionRef("## Setup", listOf("doc-a"))))),
                runState
            )

            assertTrue(written.captured.content.contains("〔src:doc-a〕"), written.captured.content)
        }

        @Test
        fun `dry run records the plan without writing or archiving`() = runTest {
            val runState = state(dryRun = true)

            val outcome = writer.writeBatch(batchIds, output(cluster()), runState)

            assertEquals(0, runState.stats.mergedWritten.get())
            assertEquals(0, runState.stats.archived.get())
            assertEquals(1, runState.stats.clusters.size)
            assertEquals(2, runState.stats.plannedArchives.get(), "the preview plans both sources")
            assertEquals(emptyList<String>(), outcome.uncoveredDocIds)
            coVerify(exactly = 0) { store.writeEntry(any(), any(), any()) }
            coVerify(exactly = 0) { store.archive(any(), any(), any(), any()) }
        }
    }

    @Nested
    inner class Guards {

        @Test
        fun `a cluster with fewer than two sources is skipped`() = runTest {
            val runState = state()

            writer.writeBatch(batchIds, output(cluster(sources = listOf("doc-a"))), runState)

            assertTrue(rejects(runState).any { it.contains("needs at least 2 sourceDocIds") }, rejects(runState).toString())
            assertEquals(1, runState.stats.skippedSingletons.get())
            coVerify(exactly = 0) { store.writeEntry(any(), any(), any()) }
        }

        @Test
        fun `a cluster larger than the remaining archive budget is deferred`() = runTest {
            val runState = state(maxArchives = 1)

            writer.writeBatch(batchIds, output(cluster()), runState)

            assertTrue(rejects(runState).any { it.contains("exceed the remaining budget") }, rejects(runState).toString())
            assertEquals(1, runState.stats.skippedOverCap.get())
            coVerify(exactly = 0) { store.writeEntry(any(), any(), any()) }
        }

        @Test
        fun `a cluster naming docIds the store does not have is rejected`() = runTest {
            val runState = state(pool = listOf(source("doc-a")))

            writer.writeBatch(batchIds, output(cluster()), runState)

            assertTrue(rejects(runState).any { it.contains("fewer than 2 source documents were found") }, rejects(runState).toString())
            coVerify(exactly = 0) { store.writeEntry(any(), any(), any()) }
        }

        @Test
        fun `blank merged fields are rejected`() = runTest {
            val runState = state()

            writer.writeBatch(batchIds, output(cluster(description = "   ")), runState)

            assertTrue(rejects(runState).any { it.contains("are required") }, rejects(runState).toString())
            coVerify(exactly = 0) { store.writeEntry(any(), any(), any()) }
        }

        @Test
        fun `a source already absorbed by this run cannot be absorbed twice`() = runTest {
            val runState = state(pool = listOf(source("doc-a"), source("doc-b"), source("doc-c")))
            val written = slot<MemoryEntry>()
            coEvery { store.writeEntry(capture(written), scope, owner) } answers {
                written.captured.copy(id = "merged-${written.captured.name}")
            }
            coEvery { store.archive(any(), any(), scope, owner) } answers { firstArg<List<String>>().size }

            writer.writeBatch(
                listOf("doc-a", "doc-b", "doc-c"),
                output(
                    cluster(title = "first", sources = listOf("doc-a", "doc-b")),
                    cluster(title = "second", sources = listOf("doc-a", "doc-c"))
                ),
                runState
            )

            assertTrue(
                rejects(runState).any { it.contains("already absorbed by an earlier cluster") },
                rejects(runState).toString()
            )
            assertEquals(1, runState.stats.mergedWritten.get(), "only the first cluster may land")
            assertEquals(2, runState.stats.archived.get(), "doc-a must not be archived a second time")
            assertEquals(1, runState.stats.skippedSingletons.get())
        }

        @Test
        fun `a source archived by an earlier run is not absorbed again`() = runTest {
            val runState = state(pool = listOf(source("doc-a"), source("doc-b", archived = true)))

            writer.writeBatch(batchIds, output(cluster()), runState)

            assertTrue(
                rejects(runState).any { it.contains("fewer than 2 source documents were found") },
                rejects(runState).toString()
            )
            coVerify(exactly = 0) { store.writeEntry(any(), any(), any()) }
        }

        @Test
        fun `a preview spends the same archive budget the real run would`() = runTest {
            val runState = state(
                dryRun = true,
                maxArchives = 2,
                pool = listOf(source("doc-a"), source("doc-b"), source("doc-c"), source("doc-d"))
            )

            writer.writeBatch(
                listOf("doc-a", "doc-b", "doc-c", "doc-d"),
                output(
                    cluster(title = "first", sources = listOf("doc-a", "doc-b")),
                    cluster(title = "second", sources = listOf("doc-c", "doc-d"))
                ),
                runState
            )

            assertTrue(rejects(runState).any { it.contains("exceed the remaining budget") }, rejects(runState).toString())
            assertEquals(2, runState.stats.clusters.size, "the second cluster is over budget, not planned")
            assertEquals(0, runState.stats.archived.get(), "a preview archives nothing")
            assertEquals(0, runState.remainingArchives())
        }
    }

    /**
     * The whole "validate the answer before writing it" design rests on the generated schema demanding
     * every field: an optional property would let a half-filled answer pass the shape check and reach
     * the writer as blank text.
     */
    @Nested
    inner class SchemaContract {

        @Test
        fun `the schema requires every output field`() {
            val root = SharedObjectMapper.instance.readTree(CONSOLIDATION_OUTPUT_SCHEMA)
            assertEquals(
                setOf("clusters", "leftoverDocIds"),
                (0 until root.path("required").size()).map { root.path("required")[it].asText() }.toSet()
            )
            val cluster = root.path("properties")["clusters"].path("items")
            assertEquals(
                setOf("mergedContent", "mergedDescription", "mergedTitle", "sectionRefs", "sourceDocIds"),
                (0 until cluster.path("required").size()).map { cluster.path("required")[it].asText() }.toSet()
            )
            assertFalse(cluster.path("additionalProperties").asBoolean())
        }
    }

    @Nested
    inner class Coverage {

        @Test
        fun `a source neither clustered nor left over is reported uncovered`() = runTest {
            val runState = state(pool = listOf(source("doc-a"), source("doc-b"), source("doc-c")))
            coEvery { store.writeEntry(any(), scope, owner) } answers { firstArg<MemoryEntry>().copy(id = "merged-1") }
            coEvery { store.archive(any(), any(), scope, owner) } returns 2

            val outcome = writer.writeBatch(
                listOf("doc-a", "doc-b", "doc-c"),
                output(cluster(sources = listOf("doc-a", "doc-b"))),
                runState
            )

            assertEquals(listOf("doc-c"), outcome.uncoveredDocIds)
        }

        @Test
        fun `an unmergeable entry declared as leftover is not a gap`() = runTest {
            val runState = state(pool = listOf(source("doc-a"), source("doc-b"), source("doc-c")))
            coEvery { store.writeEntry(any(), scope, owner) } answers { firstArg<MemoryEntry>().copy(id = "merged-1") }
            coEvery { store.archive(any(), any(), scope, owner) } returns 2

            val outcome = writer.writeBatch(
                listOf("doc-a", "doc-b", "doc-c"),
                output(cluster(sources = listOf("doc-a", "doc-b")), leftovers = listOf("doc-c")),
                runState
            )

            assertEquals(emptyList<String>(), outcome.uncoveredDocIds)
        }

        @Test
        fun `a batch with no cluster at all reports every source as covered once declared`() = runTest {
            val runState = state(dryRun = true)

            val outcome = writer.writeBatch(batchIds, output(leftovers = batchIds), runState)

            assertEquals(emptyList<String>(), outcome.uncoveredDocIds)
            assertEquals(0, runState.stats.clusters.size)
        }
    }
}
