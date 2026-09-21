package com.easy.easyai.skills

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillEntry
import com.easy.easyai.core.skill.SkillOwnerContext
import com.easy.easyai.core.skill.SkillScope
import com.easy.easyai.core.skill.SkillStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [SkillIndexer], whose whole purpose is the reconciliation economics: a stable
 * installation must produce **zero** backend traffic at startup, and only real content drift may
 * cost an upsert. The `unchanged` case is therefore the most important assertion in this file.
 */
class SkillIndexerTest {

    @TempDir
    lateinit var tempDir: Path

    private val store = mockk<SkillStore>(relaxed = true)
    private val catalog = mockk<AsyncSkillCatalogStore>(relaxed = true)
    private val config = SkillConfig()
    private val sync = SkillCatalogSyncService(catalog)

    private fun dir(name: String, body: String = "---\nname: $name\ndescription: d\n---\n\nbody\n"): Path {
        val skillDir = tempDir.resolve(name)
        Files.createDirectories(skillDir)
        Files.writeString(skillDir.resolve("SKILL.md"), body)
        return skillDir
    }

    private fun row(
        skillDir: Path,
        name: String = skillDir.fileName.toString(),
        userId: String = "alice",
        enabled: Boolean = true,
        source: String = SkillCatalogEntry.SOURCE_LOCAL,
        origin: String? = null,
        checksum: String = SkillChecksums.sha256Hex(Files.readAllBytes(skillDir.resolve("SKILL.md")))
    ) = SkillCatalogEntry(
        id = "$userId-$name",
        name = name,
        source = source,
        checksum = checksum,
        enabled = enabled,
        installPath = skillDir.toString(),
        origin = origin,
        userId = userId
    )

    private fun indexer(
        indexConcurrency: Int = SkillIndexer.DEFAULT_INDEX_CONCURRENCY
    ) = SkillIndexer(store, catalog, sync, config, indexConcurrency)

    @Nested
    inner class `driven reconciliation` {

        @Test
        fun `unchanged skills cost zero backend writes`() = runTest {
            val stable = row(dir("stable"))
            val other = row(dir("other"))
            coEvery { catalog.listByUser("alice") } returns listOf(stable, other)

            val summary = indexer().reconcileByDrift(listOf("alice"))

            coVerify(exactly = 0) { store.index(any(), any(), any(), any()) }
            coVerify(exactly = 0) { store.delete(any(), any(), any()) }
            coVerify(exactly = 0) { catalog.updateChecksum(any(), any(), any()) }
            assertEquals(2, summary.unchanged)
            assertEquals(0, summary.backendWrites, "a steady-state boot must not talk to EasyRAG at all")
        }

        @Test
        fun `content drift re-indexes and persists the new fingerprint`() = runTest {
            val skillDir = dir("edited")
            val stale = row(skillDir, checksum = "0".repeat(64))
            val expected = SkillChecksums.sha256Hex(Files.readAllBytes(skillDir.resolve("SKILL.md")))
            coEvery { catalog.listByUser("alice") } returns listOf(stale)
            coEvery { store.index(any(), any(), any(), any()) } returns 1

            val summary = indexer().reconcileByDrift(listOf("alice"))

            coVerify(exactly = 1) { catalog.updateChecksum(stale.id, expected, "0.0.0") }
            val documents = slot<List<SkillEntry>>()
            coVerify(exactly = 1) {
                store.index(capture(documents), SkillScope.GLOBAL, SkillOwnerContext("alice", null), false)
            }
            assertEquals(listOf("edited"), documents.captured.map { it.name })
            assertEquals(1, summary.reindexed)
        }

        @Test
        fun `a vanished skill tree is delisted, keeping the row for provenance`() = runTest {
            val skillDir = dir("deleted")
            val entry = row(skillDir)
            Files.delete(skillDir.resolve("SKILL.md"))
            coEvery { catalog.listByUser("alice") } returns listOf(entry)
            coEvery { store.delete(any(), any(), any()) } returns true

            val summary = indexer().reconcileByDrift(listOf("alice"))

            coVerify(exactly = 1) { store.delete("deleted", SkillScope.GLOBAL, SkillOwnerContext("alice", null)) }
            coVerify(exactly = 1) { catalog.setEnabled(entry.id, false) }
            coVerify(exactly = 0) { catalog.delete(any()) }
            assertEquals(1, summary.delisted)
        }

        @Test
        fun `a disabled skill is not pushed back into the index because its file changed`() = runTest {
            val skillDir = dir("switched-off")
            val entry = row(skillDir, enabled = false, checksum = "0".repeat(64))
            coEvery { catalog.listByUser("alice") } returns listOf(entry)

            indexer().reconcileByDrift(listOf("alice"))

            coVerify(exactly = 0) { store.index(any(), any(), any(), any()) }
            // M11: a disabled row's checksum must NOT be updated on drift, otherwise the next
            // enable would see `driftOf == None` and skip the reindex, leaving the index serving
            // the pre-disable content. The row stays stale until it is enabled again.
            coVerify(exactly = 0) { catalog.updateChecksum(any(), any(), any()) }
        }

        @Test
        fun `one owner whose rows cannot be listed does not block the others`() = runTest {
            val skillDir = dir("reachable")
            coEvery { catalog.listByUser("broken") } throws IllegalStateException("db down")
            coEvery { catalog.listByUser("alice") } returns listOf(row(skillDir))

            val summary = indexer().reconcileByDrift(listOf("broken", "alice"))

            assertEquals(1, summary.failed)
            assertEquals(1, summary.unchanged)
        }

        @Test
        fun `reconciliation stays within the configured concurrency window`() = runTest {
            val rows = (1..6).map { row(dir("burst-$it", body = "---\nname: burst-$it\n---\n\nb\n"), checksum = "0".repeat(64)) }
            coEvery { catalog.listByUser("alice") } returns rows
            val inFlight = AtomicInteger()
            val peak = AtomicInteger()
            coEvery { store.index(any(), any(), any(), any()) } coAnswers {
                val now = inFlight.incrementAndGet()
                peak.updateAndGet { if (it < now) now else it }
                delay(5)
                inFlight.decrementAndGet()
                1
            }

            indexer(indexConcurrency = 2).reconcileByDrift(listOf("alice"))

            assertTrue(peak.get() <= 2, "observed concurrency ${peak.get()} exceeded the limit of 2")
            coVerify(exactly = 6) { store.index(any(), any(), any(), any()) }
        }
    }

    @Nested
    inner class `single-skill writes` {

        @Test
        fun `indexOne persists the catalog row before touching the index`() = runTest {
            val skillDir = dir("ordered")
            val entry = row(skillDir)
            val order = mutableListOf<String>()
            coEvery { catalog.upsert(any()) } answers { order.add("catalog"); firstArg() }
            val awaitSlot = slot<Boolean>()
            coEvery { store.index(any(), any(), any(), capture(awaitSlot)) } answers { order.add("index"); 1 }

            assertTrue(indexer().indexOne(entry, SkillScope.GLOBAL, SkillOwnerContext("alice"), await = true))

            assertEquals(listOf("catalog", "index"), order)
            assertTrue(awaitSlot.captured, "an install must be searchable the instant it returns")
        }

        @Test
        fun `indexOne refreshes the fingerprint from disk rather than trusting the caller`() = runTest {
            val skillDir = dir("refreshed")
            val expected = SkillChecksums.sha256Hex(Files.readAllBytes(skillDir.resolve("SKILL.md")))
            val captured = slot<SkillCatalogEntry>()
            coEvery { catalog.upsert(capture(captured)) } answers { firstArg() }

            indexer().indexOne(row(skillDir, checksum = "0".repeat(64)), SkillScope.GLOBAL, SkillOwnerContext("alice"))

            assertEquals(expected, captured.captured.checksum)
        }

        @Test
        fun `a catalog write failure propagates so the caller can roll the files back`() = runTest {
            val skillDir = dir("no-row")
            coEvery { catalog.upsert(any()) } throws IllegalStateException("db is read-only")

            assertFailsWith<IllegalStateException> {
                indexer().indexOne(row(skillDir), SkillScope.GLOBAL, SkillOwnerContext("alice"))
            }
            coVerify(exactly = 0) { store.index(any(), any(), any(), any()) }
        }

        @Test
        fun `an index failure alone is tolerated and retried on the next pass`() = runTest {
            val skillDir = dir("index-off")
            coEvery { store.index(any(), any(), any(), any()) } throws IllegalStateException("rag down")

            assertFalse(indexer().indexOne(row(skillDir), SkillScope.GLOBAL, SkillOwnerContext("alice")))
            coVerify(exactly = 1) { catalog.upsert(any()) }
        }

        @Test
        fun `a skill with no file on disk is never indexed`() = runTest {
            val missing = row(tempDir.resolve("absent"), checksum = "0".repeat(64))

            assertFalse(indexer().indexOne(missing, SkillScope.GLOBAL, SkillOwnerContext("alice")))
            coVerify(exactly = 0) { catalog.upsert(any()) }
            coVerify(exactly = 0) { store.index(any(), any(), any(), any()) }
        }

        @Test
        fun `disable removes the document but keeps the row`() = runTest {
            val entry = row(dir("pdf-report"))
            coEvery { store.delete(any(), any(), any()) } returns true
            coEvery { catalog.setEnabled(any(), any()) } returns true

            assertTrue(indexer().removeOne(entry, removeCatalogRow = false))

            coVerify(exactly = 1) { catalog.setEnabled(entry.id, false) }
            coVerify(exactly = 0) { catalog.delete(any()) }
        }

        @Test
        fun `uninstall drops both the row and the document`() = runTest {
            val entry = row(dir("pdf-report"))
            coEvery { store.delete(any(), any(), any()) } returns true
            coEvery { catalog.delete(any()) } returns true

            assertTrue(indexer().removeOne(entry))

            coVerify(exactly = 1) { catalog.delete(entry.id) }
        }
    }

    @Nested
    inner class `slice addressing` {

        @Test
        fun `a project-installed row is addressed to the project slice`() = runTest {
            val project = tempDir.resolve("repo")
            val skillDir = project.resolve(".easyai/skills/in-project")
            Files.createDirectories(skillDir)
            Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: in-project\n---\n\nbody\n")
            val entry = row(skillDir)

            val indexer = indexer()

            assertEquals(SkillScope.PROJECT, indexer.scopeOf(entry))
            assertEquals(project.toAbsolutePath().normalize(), indexer.ownerOf(entry).projectPath)
        }

        @Test
        fun `the index document is read from disk, not invented from the row`() = runTest {
            val skillDir = dir("document")

            val document = indexer().entryOf(row(skillDir))

            assertEquals("document", document?.name)
            assertEquals(skillDir.resolve("SKILL.md").toAbsolutePath().toString(), document?.location)
        }
    }
}
