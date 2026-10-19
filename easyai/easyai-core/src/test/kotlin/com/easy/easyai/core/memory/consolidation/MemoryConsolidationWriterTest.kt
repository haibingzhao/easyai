package com.easy.easyai.core.memory.consolidation

import com.easy.easyai.common.util.SharedObjectMapper
import tools.jackson.databind.JsonNode
import com.easy.easyai.core.memory.MemoryArchiveUpdate
import com.easy.easyai.core.memory.MemoryConsolidationSettings
import com.easy.easyai.core.memory.MemoryEntry
import com.easy.easyai.core.memory.MemoryIndexEntry
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
 * Tests the sole write path of a consolidation run: the shape of what lands in the store, the grouping
 * validator that decides what may be asked at all, and the store-backed checks a JSON Schema cannot
 * express (invented docIds, a source two clusters both claimed, a source already archived by an earlier
 * run).
 */
class MemoryConsolidationWriterTest {

    private val store = mockk<MemoryStore>()
    private val settings = MemoryConsolidationSettings()
    private val writer = MemoryConsolidationWriter(store, settings)
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

    /** The index row the grouping pass judges on: identity and stored size, never the body. */
    private fun indexRow(id: String, chars: Int = 500, level: Int = 0) = MemoryIndexEntry(
        docId = id,
        name = "src-$id",
        type = MemoryType.EXPERIENCE_LESSONS,
        level = level,
        contentChars = chars,
        blurb = "blurb $id"
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

    private fun mergeOutput(
        vararg clusters: ConsolidationCluster,
        unmerged: List<String> = emptyList()
    ) = MemoryConsolidationMergeOutput(clusters = clusters.toList(), unmergedDocIds = unmerged)

    private fun groupOutput(
        vararg groups: ConsolidationGroup,
        leftovers: List<String> = emptyList()
    ) = MemoryConsolidationGroupingOutput(groups = groups.toList(), leftoverDocIds = leftovers)

    private fun group(title: String = "FRP setup", sources: List<String> = listOf("doc-a", "doc-b")) =
        ConsolidationGroup(title = title, sourceDocIds = sources)

    private fun rejects(state: MemoryConsolidationRunState): List<String> =
        state.stats.clusters.mapNotNull { it["rejected"] as? String }

    @Nested
    inner class Grouping {

        @Test
        fun `a validated group is queued for the merge pass, not written`() = runTest {
            val runState = state()

            val outcome = writer.planGroups(listOf(indexRow("doc-a"), indexRow("doc-b")), groupOutput(group()), runState)

            assertEquals(emptyList<String>(), outcome.uncoveredDocIds)
            assertEquals(1, runState.stats.clustersProposed.get())
            val proposed = runState.stats.proposed.single()
            assertEquals(listOf("doc-a", "doc-b"), proposed.sourceDocIds)
            assertEquals(1000, proposed.sourceChars, "the queued cluster carries sizes, not bodies")
            coVerify(exactly = 0) { store.writeEntry(any(), any(), any()) }
            coVerify(exactly = 0) { store.archive(any(), any(), any(), any()) }
        }

        @Test
        fun `a docId the model invented leaves the real sources uncovered`() = runTest {
            val runState = state()

            val outcome = writer.planGroups(
                listOf(indexRow("doc-a"), indexRow("doc-b")),
                groupOutput(group(sources = listOf("doc-a", "doc-nonexistent"))),
                runState
            )

            assertEquals(0, runState.stats.clustersProposed.get())
            assertEquals(listOf("doc-a", "doc-b"), outcome.uncoveredDocIds)
        }

        @Test
        fun `one source may be proposed by only one group`() = runTest {
            val runState = state()
            val rows = listOf(indexRow("doc-a"), indexRow("doc-b"), indexRow("doc-c"))

            val outcome = writer.planGroups(
                rows,
                groupOutput(group(title = "first", sources = listOf("doc-a", "doc-b")), group(title = "second", sources = listOf("doc-a", "doc-c"))),
                runState
            )

            assertEquals(1, runState.stats.clustersProposed.get(), "the second cluster lost its shared source")
            assertEquals(1, runState.stats.skippedSingletons.get())
            assertEquals(listOf("doc-c"), outcome.uncoveredDocIds)
        }

        @Test
        fun `a source taken by an earlier batch's cluster is not a gap for this batch`() = runTest {
            val runState = state()
            writer.planGroups(
                listOf(indexRow("doc-a"), indexRow("doc-b")),
                groupOutput(group(sources = listOf("doc-a", "doc-b"))),
                runState
            )

            // Grouping runs over several prompts, and `claimedByModel` only knows about this batch's
            // clusters. Without settling the already-placed source, its docId would be re-asked with the
            // rows the model has just answered — and the lone free source beside it would be asked twice.
            val halfTaken = writer.planGroups(
                listOf(indexRow("doc-a"), indexRow("doc-c")),
                groupOutput(group(title = "second", sources = listOf("doc-a", "doc-c"))),
                runState
            )
            assertEquals(listOf("doc-c"), halfTaken.uncoveredDocIds)

            val allTaken = writer.planGroups(
                listOf(indexRow("doc-a"), indexRow("doc-b")),
                groupOutput(group(title = "third", sources = listOf("doc-a", "doc-b"))),
                runState
            )
            assertEquals(emptyList<String>(), allTaken.uncoveredDocIds, "nothing here is unaccounted for")
        }

        @Test
        fun `a group whose bodies cannot fit one merged entry is dropped`() = runTest {
            val runState = state()
            // Each row alone is under the cap, the pair is not: the tail is trimmed away, and a cluster
            // of one is not a cluster.
            val rows = listOf(indexRow("doc-a", chars = settings.maxGroupSourceChars), indexRow("doc-b", chars = 1_000))

            val outcome = writer.planGroups(rows, groupOutput(group()), runState)

            assertEquals(1, runState.stats.droppedGroups.get())
            assertEquals(0, runState.stats.clustersProposed.get())
            assertEquals(
                emptyList<String>(), outcome.uncoveredDocIds,
                "a refused group is a decision, not a gap — re-asking it would only repeat the refusal"
            )
        }

        @Test
        fun `an oversized group keeps its head and trims the tail`() = runTest {
            val runState = state()
            val rows = (1..settings.maxSourcesPerGroup + 2).map { indexRow("doc-$it", chars = 1_000) }
            val ids = rows.map { it.docId }

            writer.planGroups(rows, groupOutput(group(sources = ids)), runState)

            val proposed = runState.stats.proposed.single()
            assertEquals(settings.maxSourcesPerGroup, proposed.sourceDocIds.size, "the entry-count cap is what bound it")
            assertEquals(ids.take(settings.maxSourcesPerGroup), proposed.sourceDocIds, "the tail is what goes")
        }

        @Test
        fun `a declared leftover is covered work, not a gap`() = runTest {
            val runState = state()

            val outcome = writer.planGroups(
                listOf(indexRow("doc-a"), indexRow("doc-b")),
                groupOutput(leftovers = listOf("doc-a", "doc-b")),
                runState
            )

            assertEquals(emptyList<String>(), outcome.uncoveredDocIds)
            assertEquals(0, runState.stats.clustersProposed.get())
        }

        @Test
        fun `a preview plans the archives the real run would consume and shows their sizes`() = runTest {
            val runState = state(dryRun = true, maxArchives = 2)

            writer.planGroups(
                listOf(indexRow("doc-a"), indexRow("doc-b"), indexRow("doc-c"), indexRow("doc-d")),
                groupOutput(
                    group(title = "first", sources = listOf("doc-a", "doc-b")),
                    group(title = "second", sources = listOf("doc-c", "doc-d"))
                ),
                runState
            )

            assertEquals(2, runState.stats.plannedArchives.get())
            assertEquals(0, runState.remainingArchives())
            assertEquals(2, runState.stats.clusters.size)
            val row = runState.stats.clusters.first()
            assertEquals(1000, row["sourceChars"], "the preview must show how much a cluster has to distill")
            assertEquals(1, row["level"], "a merged entry sits one level above its deepest source")
            assertEquals(true, row["dryRun"])
        }

        @Test
        fun `a preview defers the cluster the real run would defer instead of planning it`() = runTest {
            val runState = state(dryRun = true, maxArchives = 2)

            val outcome = writer.planGroups(
                listOf(indexRow("doc-a"), indexRow("doc-b"), indexRow("doc-c"), indexRow("doc-d")),
                groupOutput(
                    group(title = "first", sources = listOf("doc-a", "doc-b")),
                    group(title = "second", sources = listOf("doc-c", "doc-d"))
                ),
                runState
            )

            assertTrue(
                rejects(runState).any { it.contains("exceed the remaining budget") },
                rejects(runState).toString()
            )
            assertEquals(1, runState.stats.skippedOverCap.get())
            assertEquals(
                emptyList<String>(), outcome.uncoveredDocIds,
                "a cluster the budget ran out on is a decision, not a gap"
            )
        }
    }

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

            val outcome = writer.writeBatch(batchIds, mergeOutput(cluster()), runState)

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

            writer.writeBatch(batchIds, mergeOutput(cluster()), runState)

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
                mergeOutput(cluster(sources = listOf("doc-a", "doc-b", "doc-c"))),
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
                mergeOutput(cluster(refs = listOf(ConsolidationSectionRef("## Setup", listOf("doc-a"))))),
                runState
            )

            assertTrue(written.captured.content.contains("〔src:doc-a〕"), written.captured.content)
        }

        @Test
        fun `a body past the key-points target is stored and counted, not refused`() = runTest {
            // The 2K figure is what the prompt asks for; 6K is what the store can hold. Refusing at 2K
            // would throw away a merge the model did correctly and leave the sources as duplicates.
            val runState = state()
            coEvery { store.writeEntry(any(), scope, owner) } answers { firstArg<MemoryEntry>().copy(id = "merged-1") }
            coEvery { store.archive(any(), any(), scope, owner) } returns 2
            val long = "x".repeat(com.easy.easyai.core.memory.MemoryContentLimits.CONTENT_TARGET_CHARS + 100)

            writer.writeBatch(batchIds, mergeOutput(cluster(content = long)), runState)

            assertEquals(1, runState.stats.mergedWritten.get())
            assertEquals(1, runState.stats.overTarget.get())
            assertTrue(rejects(runState).isEmpty(), rejects(runState).toString())
        }

        @Test
        fun `dry run records the plan without writing or archiving`() = runTest {
            val runState = state(dryRun = true)

            val outcome = writer.writeBatch(batchIds, mergeOutput(cluster()), runState)

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

            writer.writeBatch(batchIds, mergeOutput(cluster(sources = listOf("doc-a"))), runState)

            assertTrue(rejects(runState).any { it.contains("needs at least 2 sourceDocIds") }, rejects(runState).toString())
            assertEquals(1, runState.stats.skippedSingletons.get())
            coVerify(exactly = 0) { store.writeEntry(any(), any(), any()) }
        }

        @Test
        fun `a cluster larger than the remaining archive budget is deferred`() = runTest {
            val runState = state(maxArchives = 1)

            writer.writeBatch(batchIds, mergeOutput(cluster()), runState)

            assertTrue(rejects(runState).any { it.contains("exceed the remaining budget") }, rejects(runState).toString())
            assertEquals(1, runState.stats.skippedOverCap.get())
            coVerify(exactly = 0) { store.writeEntry(any(), any(), any()) }
        }

        @Test
        fun `a cluster naming docIds the store does not have is rejected`() = runTest {
            val runState = state(pool = listOf(source("doc-a")))

            writer.writeBatch(batchIds, mergeOutput(cluster()), runState)

            assertTrue(rejects(runState).any { it.contains("fewer than 2 source documents were found") }, rejects(runState).toString())
            coVerify(exactly = 0) { store.writeEntry(any(), any(), any()) }
        }

        @Test
        fun `blank merged fields are rejected`() = runTest {
            val runState = state()

            writer.writeBatch(batchIds, mergeOutput(cluster(description = "   ")), runState)

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
                mergeOutput(
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

            writer.writeBatch(batchIds, mergeOutput(cluster()), runState)

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
                mergeOutput(
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
     * the writer as blank text. Both passes are covered because each one's silence means something the
     * writer has to tell apart from a lost answer.
     */
    @Nested
    inner class SchemaContract {

        @Test
        fun `the grouping schema requires every output field`() {
            val root = SharedObjectMapper.instance.readTree(CONSOLIDATION_GROUPING_SCHEMA)
            assertEquals(requiredOf(root), setOf("groups", "leftoverDocIds"))
            val group = root.path("properties")["groups"].path("items")
            assertEquals(requiredOf(group), setOf("sourceDocIds", "title"))
            assertFalse(group.path("additionalProperties").asBoolean())
        }

        @Test
        fun `the merge schema requires every output field`() {
            val root = SharedObjectMapper.instance.readTree(CONSOLIDATION_MERGE_SCHEMA)
            assertEquals(requiredOf(root), setOf("clusters", "unmergedDocIds"))
            val cluster = root.path("properties")["clusters"].path("items")
            assertEquals(
                setOf("mergedContent", "mergedDescription", "mergedTitle", "sectionRefs", "sourceDocIds"),
                requiredOf(cluster)
            )
            assertFalse(cluster.path("additionalProperties").asBoolean())
        }

        private fun requiredOf(node: JsonNode): Set<String> =
            (0 until node.path("required").size()).map { node.path("required")[it].asText() }.toSet()
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
                mergeOutput(cluster(sources = listOf("doc-a", "doc-b"))),
                runState
            )

            assertEquals(listOf("doc-c"), outcome.uncoveredDocIds)
        }

        @Test
        fun `an unmergeable entry declared as such is not a gap`() = runTest {
            val runState = state(pool = listOf(source("doc-a"), source("doc-b"), source("doc-c")))
            coEvery { store.writeEntry(any(), scope, owner) } answers { firstArg<MemoryEntry>().copy(id = "merged-1") }
            coEvery { store.archive(any(), any(), scope, owner) } returns 2

            val outcome = writer.writeBatch(
                listOf("doc-a", "doc-b", "doc-c"),
                mergeOutput(cluster(sources = listOf("doc-a", "doc-b")), unmerged = listOf("doc-c")),
                runState
            )

            assertEquals(emptyList<String>(), outcome.uncoveredDocIds)
        }

        @Test
        fun `a batch with no cluster at all reports every source as covered once declared`() = runTest {
            val runState = state(dryRun = true)

            val outcome = writer.writeBatch(batchIds, mergeOutput(unmerged = batchIds), runState)

            assertEquals(emptyList<String>(), outcome.uncoveredDocIds)
            assertEquals(0, runState.stats.clusters.size)
        }
    }
}
