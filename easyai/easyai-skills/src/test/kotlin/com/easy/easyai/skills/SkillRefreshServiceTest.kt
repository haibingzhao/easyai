package com.easy.easyai.skills

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillRefreshServiceTest {
    @TempDir lateinit var temp: Path

    @Nested
    inner class Coordination {
        @Test
        fun `catalog inventory precedes scanning and claims precede owner enumeration`() = runTest {
            val registry = mockk<SkillRegistry>(relaxed = true)
            val catalog = mockk<AsyncSkillCatalogStore>(relaxed = true)
            val sync = mockk<SkillCatalogSyncService>(relaxed = true)
            val indexer = mockk<SkillIndexer>(relaxed = true)
            every { registry.all() } returns emptyList()
            every { registry.rescan(any()) } returns RegistryDelta()
            coEvery { sync.claimUnclaimed(any(), any(), any()) } returns SkillClaimSummary()
            coEvery { catalog.listDistinctUserIds() } returns listOf("alice")
            coEvery { indexer.reconcileByDrift(any()) } returns ReconcileSummary(owners = 1)
            val service = SkillRefreshService(registry, catalog, sync, indexer)
            assertEquals(1, service.reconcileAllOwners()?.owners)
            coVerifyOrder {
                catalog.listAll()
                registry.rescan(any())
                sync.claimUnclaimed(any(), null, null)
                catalog.listDistinctUserIds()
                indexer.reconcileByDrift(listOf("alice"))
            }
        }

        @Test
        fun `failed owner inventory cannot trigger a scan claim or index synchronization`() = runTest {
            val registry = mockk<SkillRegistry>(relaxed = true)
            val catalog = mockk<AsyncSkillCatalogStore>(relaxed = true)
            val sync = mockk<SkillCatalogSyncService>(relaxed = true)
            val indexer = mockk<SkillIndexer>(relaxed = true)
            coEvery { catalog.listAll() } throws IllegalStateException("database down")
            val service = SkillRefreshService(registry, catalog, sync, indexer)
            assertNull(service.reconcileAllOwners())
            assertFailsWith<IllegalStateException> { service.refreshFor("alice", temp) }
            verify(exactly = 0) { registry.rescan(any()) }
            coVerify(exactly = 0) { sync.claimUnclaimed(any(), any(), any()) }
            coVerify(exactly = 0) { indexer.reconcileByDrift(any()) }
        }

        @Test
        fun `second refresh after catalog outage preserves private disable and does not create system row`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("draft", global = true)
            chain.refresher.refreshFor("alice", chain.project)
            val row = chain.catalog.listAll().single()
            chain.catalog.setEnabled(row.id, false)
            chain.catalog.failLists = true
            assertNull(chain.refresher.reconcileAllOwners())
            chain.catalog.failLists = false
            chain.refresher.reconcileAllOwners()
            assertEquals("alice", chain.catalog.listAll().single().userId)
            assertFalse(chain.catalog.listAll().single().enabled)
            assertTrue(chain.catalog.listByUser("system").isEmpty())
        }

        @Test
        fun `unclaimed preexisting registered source is claimed by authenticated refresh`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("draft")
            chain.registry.rescan(setOf(chain.project))
            val outcome = chain.refresher.refreshFor("first-user", chain.project)
            assertTrue(outcome.delta.addedKeys.isEmpty())
            assertEquals(1, outcome.claimed)
            assertEquals("first-user", outcome.owner)
            assertEquals("first-user", chain.catalog.listAll().single().userId)
        }
    }
}
