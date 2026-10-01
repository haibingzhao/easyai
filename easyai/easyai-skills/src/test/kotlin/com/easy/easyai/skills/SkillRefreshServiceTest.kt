package com.easy.easyai.skills

import com.easy.easyai.core.skill.SkillCatalogEntry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillRefreshServiceTest {

    private val sync = mockk<SkillSyncService>(relaxed = true)
    private val indexer = mockk<SkillIndexer>(relaxed = true)

    private fun service() = SkillRefreshService(sync, indexer)

    init {
        coEvery { sync.ownerOf(any()) } answers {
            firstArg<String?>()?.takeIf { it.isNotBlank() } ?: SkillCatalogEntry.DEFAULT_USER_ID
        }
    }

    @Nested
    inner class Coordination {
        @Test
        fun `sync runs before the index pass and covers the shared layer first`() = runTest {
            val owners = listOf(SkillCatalogEntry.DEFAULT_USER_ID, "alice")
            coEvery { sync.syncFor("alice") } returns SkillSyncOutcome(owners)
            coEvery { indexer.reconcileByDrift(owners) } returns ReconcileSummary(owners = 2)

            val outcome = service().refreshFor("alice")

            assertEquals(owners, outcome.owners)
            coVerifyOrder {
                sync.syncFor("alice")
                indexer.reconcileByDrift(owners)
            }
        }

        @Test
        fun `a sync failure still indexes what the catalog already holds`() = runTest {
            coEvery { sync.syncFor("alice") } throws IllegalStateException("database down")
            coEvery { indexer.reconcileByDrift(any()) } returns ReconcileSummary(owners = 2)

            val outcome = service().refreshFor("alice")

            assertNull(outcome.sync)
            assertNull(outcome.delta)
            assertEquals(2, outcome.summary?.owners)
        }

        @Test
        fun `an index failure keeps the sync counters`() = runTest {
            coEvery { sync.syncFor("alice") } returns SkillSyncOutcome(listOf("alice"), claimed = 1)
            coEvery { indexer.reconcileByDrift(any()) } throws IllegalStateException("rag down")

            val outcome = service().refreshFor("alice")

            assertEquals(1, outcome.sync?.claimed)
            assertNull(outcome.summary)
        }

        @Test
        fun `cancellation propagates instead of reporting a partial refresh`() = runTest {
            coEvery { sync.syncFor("alice") } throws CancellationException("closing")
            assertFailsWith<CancellationException> { service().refreshFor("alice") }
            coVerify(exactly = 0) { indexer.reconcileByDrift(any()) }
        }

        @Test
        fun `system requests touch only the shared layer`() = runTest {
            for (userId in listOf(null, " ", "system")) {
                assertEquals(listOf(SkillCatalogEntry.DEFAULT_USER_ID), service().ownersFor(userId))
            }
            assertEquals(listOf(SkillCatalogEntry.DEFAULT_USER_ID, "alice"), service().ownersFor("alice"))
        }
    }

    @Nested
    inner class LazyFirstAccess {
        @Test
        fun `the first access reconciles once and later accesses are free`() = runTest {
            val service = service()
            coEvery { sync.syncFor("alice") } returns SkillSyncOutcome(listOf("alice"))

            service.ensureSynced("alice")
            service.ensureSynced("alice")

            coVerify(exactly = 1) { sync.syncFor("alice") }
            coVerify(exactly = 1) { indexer.reconcileByDrift(any()) }
        }

        @Test
        fun `a failed pass is retried on the next access`() = runTest {
            val service = service()
            coEvery { sync.syncFor("alice") } throws IllegalStateException("db down") andThen SkillSyncOutcome()

            service.ensureSynced("alice")
            service.ensureSynced("alice")

            coVerify(exactly = 2) { sync.syncFor("alice") }
        }

        @Test
        fun `different users sync independently`() = runTest {
            val service = service()
            service.ensureSynced("alice")
            service.ensureSynced("bob")

            coVerify(exactly = 1) { sync.syncFor("alice") }
            coVerify(exactly = 1) { sync.syncFor("bob") }
        }
    }

    @Nested
    inner class ManagementPaths {
        @Test
        fun `a successful add submits the new row to the index`() = runTest {
            val row = SkillCatalogEntry(name = "draft", checksum = "c", rootPath = "/r", installPath = "/r/draft", userId = "alice")
            val service = service()
            coEvery { sync.addSkill("alice", "draft", Path.of("/src")) } returns SkillAddResult.Added(row)

            val result = service.addSkill("alice", "draft", Path.of("/src"))

            assertEquals(row, (result as SkillAddResult.Added).row)
            coVerify(exactly = 1) { indexer.synchronize(row, await = false) }
        }

        @Test
        fun `a rejected add never touches the index`() = runTest {
            coEvery { sync.addSkill(any(), any(), any()) } returns SkillAddResult.NameConflict("taken")

            val result = service().addSkill("alice", "draft", Path.of("/src"))

            assertTrue(result is SkillAddResult.NameConflict)
            coVerify(exactly = 0) { indexer.synchronize(any(), any()) }
        }

        @Test
        fun `delete delists the document only when the row was removed`() = runTest {
            val row = SkillCatalogEntry(name = "draft", checksum = "c", rootPath = "/r", installPath = "/r/draft", userId = "alice")
            coEvery { sync.deleteSkill("alice", "draft") } returns row
            coEvery { indexer.delist("draft", "alice") } returns true

            assertEquals(row, service().deleteSkill("alice", "draft"))

            val captured = slot<String>()
            coVerify(exactly = 1) { indexer.delist(capture(captured), "alice") }
            assertEquals("draft", captured.captured)
            coVerify(exactly = 0) { indexer.delist("draft", "bob") }
        }

        @Test
        fun `a missing row skips delisting`() = runTest {
            coEvery { sync.deleteSkill("alice", "draft") } returns null

            assertNull(service().deleteSkill("alice", "draft"))
            coVerify(exactly = 0) { indexer.delist(any(), any()) }
        }
    }
}
