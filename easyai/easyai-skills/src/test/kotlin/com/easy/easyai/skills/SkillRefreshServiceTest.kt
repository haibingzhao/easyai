package com.easy.easyai.skills

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillOwnerContext
import com.easy.easyai.core.skill.SkillScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [SkillRefreshService] — the one chain that turns files on disk into loadable, searchable
 * skills, run both at startup and on request.
 *
 * Two things are worth defending here. The first is ordering: owners can only be enumerated *after*
 * the backfill claimed what exists on disk, and the prompt view can only be refreshed *after*
 * reconciliation may have disabled rows. The second is ownership: a request-driven pass must claim
 * into the tenant [SkillOwnership.tenantOf] resolves, because that is the row `load_skill` will read
 * back — claim into anything else and the skill is indexed but unusable.
 */
class SkillRefreshServiceTest {

    private val registry = mockk<SkillRegistry>()
    private val catalog = mockk<AsyncSkillCatalogStore>()
    private val syncService = mockk<SkillCatalogSyncService>()
    private val indexer = mockk<SkillIndexer>()
    private val promptSource = mockk<SkillPromptSource>()

    private fun service() = SkillRefreshService(registry, catalog, syncService, indexer, promptSource)

    private fun skill(name: String) = SkillInfo(
        name = name,
        description = "$name description",
        location = Path.of("/server/skills/$name/SKILL.md"),
        content = "body"
    )

    private fun row(name: String, userId: String = "alice", enabled: Boolean = true) = SkillCatalogEntry(
        id = "row-$name-$userId",
        name = name,
        checksum = "a".repeat(64),
        enabled = enabled,
        installPath = "/server/skills/$name",
        userId = userId
    )

    private fun stubStartupPass(
        owners: List<String>,
        summary: ReconcileSummary = ReconcileSummary(owners = owners.size)
    ) {
        coEvery { registry.rescan(any()) } returns RegistryDelta()
        coEvery { registry.all() } returns emptyList()
        every { registry.hasCompletedInitialScan() } returns false
        every { registry.knownProjectRoots() } returns emptySet()
        coEvery { syncService.backfillAll(any(), any()) } returns 0
        coEvery { catalog.listDistinctUserIds() } returns owners
        coEvery { catalog.listByUser(any()) } returns emptyList()
        coEvery { catalog.listAll() } returns emptyList()
        coEvery { indexer.reconcileByDrift(any()) } returns summary
        coEvery { promptSource.refreshVisibility() } returns true
    }

    @Nested
    inner class `the startup pass` {

        @Test
        fun `claim then enumerate owners then reconcile then refresh`() = runTest {
            val summary = ReconcileSummary(owners = 1, reindexed = 3)
            stubStartupPass(owners = listOf("alice"), summary = summary)
            coEvery { syncService.backfillAll(any(), any()) } returns 7

            val result = service().reconcileAllOwners()

            assertEquals(summary, result)
            coVerifyOrder {
                registry.rescan(any())
                syncService.backfillAll(any(), any())
                catalog.listDistinctUserIds()
                indexer.reconcileByDrift(listOf("alice"))
                promptSource.refreshVisibility()
            }
        }

        @Test
        fun `the startup pass hydrates the registry from the project roots the table knows`() = runTest {
            stubStartupPass(owners = listOf("alice"))
            val project = Path.of("/work/repo")
            // M9: projectRootsFromCatalog now uses a single `listAll()` round trip instead of
            // `listDistinctUserIds` + `listByUser` per owner.
            coEvery { catalog.listAll() } returns listOf(
                SkillCatalogEntry(
                    id = "row-in-project",
                    name = "pdf",
                    checksum = "a".repeat(64),
                    installPath = "$project/.easyai/skills/pdf",
                    userId = "alice"
                )
            )
            val roots = slot<Set<Path>>()

            service().reconcileAllOwners()

            coVerify(exactly = 1) { registry.rescan(capture(roots)) }
            assertTrue(project in roots.captured, "a DB-known project must be re-scanned even when this server never served it")
        }

        @Test
        fun `reconciliation is asked for exactly the owners the table knows`() = runTest {
            stubStartupPass(owners = listOf("alice", "bob"))
            val owners = slot<List<String>>()

            service().reconcileAllOwners()

            coVerify(exactly = 1) { indexer.reconcileByDrift(capture(owners)) }
            assertEquals(listOf("alice", "bob"), owners.captured)
        }

        @Test
        fun `an empty table reconciles nobody but still publishes the prompt view`() = runTest {
            stubStartupPass(owners = emptyList())

            val result = service().reconcileAllOwners()

            assertEquals(ReconcileSummary(), result)
            coVerify(exactly = 0) { indexer.reconcileByDrift(any()) }
            coVerify(exactly = 1) { promptSource.refreshVisibility() }
        }

        @Test
        fun `a failed claim still reconciles whatever the table already has`() = runTest {
            stubStartupPass(owners = listOf("alice"))
            coEvery { syncService.backfillAll(any(), any()) } throws IllegalStateException("disk is gone")

            assertEquals(1, service().reconcileAllOwners()?.owners)
            coVerify(exactly = 1) { indexer.reconcileByDrift(listOf("alice")) }
        }

        @Test
        fun `an unreadable table ends the pass without throwing`() = runTest {
            stubStartupPass(owners = listOf("alice"))
            coEvery { catalog.listDistinctUserIds() } throws IllegalStateException("database is down")

            assertNull(service().reconcileAllOwners())
            coVerify(exactly = 0) { indexer.reconcileByDrift(any()) }
        }

        @Test
        fun `an index that cannot be reached leaves the prompt view unrefreshed`() = runTest {
            stubStartupPass(owners = listOf("alice"))
            coEvery { indexer.reconcileByDrift(any()) } throws IllegalStateException("EasyRAG is down")

            assertFailsWith<IllegalStateException> { service().reconcileAllOwners() }
            coVerify(exactly = 0) { promptSource.refreshVisibility() }
        }
    }

    @Nested
    inner class `the request-driven pass` {

        private val project = Path.of("/work/repo")

        private fun stubRefresh(added: List<String>, registered: List<SkillInfo>, owner: String = "alice") {
            coEvery { registry.rescan(any()) } returns RegistryDelta(
                added = added,
                removed = emptyList(),
                total = registered.size,
                addedKeys = added.map { SkillKey(it, null) }
            )
            coEvery { registry.all() } returns registered
            every { registry.get(any(), any()) } answers {
                val name = arg<String>(0)
                registered.firstOrNull { it.name == name }
            }
            coEvery { catalog.listDistinctUserIds() } returns listOf(owner)
            coEvery { catalog.listByUser(owner) } returns listOf(row("other", userId = owner))
            coEvery { catalog.listAll() } returns emptyList()
        }

        private fun stubIndexing(owner: String = "alice") {
            coEvery { catalog.listByName(any(), owner) } returns listOf(row("pdf", userId = owner))
            every { indexer.scopeOf(any()) } returns SkillScope.GLOBAL
            every { indexer.ownerOf(any()) } returns SkillOwnerContext(owner, null)
            coEvery { indexer.indexOne(any(), any(), any(), await = false) } returns true
            coEvery { indexer.reconcileByDrift(any()) } returns ReconcileSummary(owners = 1)
            coEvery { promptSource.refreshVisibility() } returns true
        }

        @Test
        fun `the project path is the extra root the re-scan gets`() = runTest {
            stubRefresh(added = emptyList(), registered = emptyList())
            stubIndexing()

            service().refreshFor("alice", project)

            coVerify(exactly = 1) { registry.rescan(match { it.contains(project) }) }
        }

        @Test
        fun `a requester with its own rows claims into its own tenant`() = runTest {
            stubRefresh(added = listOf("pdf"), registered = listOf(skill("pdf")))
            stubIndexing()

            val outcome = service().refreshFor("alice", project)

            assertEquals("alice", outcome.owner)
            coVerify(exactly = 1) { syncService.backfillAll(any(), "alice") }
        }

        @Test
        fun `a requester with no rows of its own claims into the shared tenant it will be resolved to`() = runTest {
            stubRefresh(added = listOf("pdf"), registered = listOf(skill("pdf")), owner = "bob")
            coEvery { catalog.listByUser("bob") } returns emptyList()
            coEvery { catalog.listByUser(SkillCatalogEntry.DEFAULT_USER_ID) } returns listOf(row("other", userId = "system"))
            coEvery { catalog.listDistinctUserIds() } returns listOf("bob")
            stubIndexing(owner = SkillCatalogEntry.DEFAULT_USER_ID)

            val outcome = service().refreshFor("bob", project)

            assertEquals(
                SkillCatalogEntry.DEFAULT_USER_ID, outcome.owner,
                "claiming for 'bob' would strand the row where checkLoad cannot find it"
            )
            coVerify(exactly = 1) { syncService.backfillAll(any(), SkillCatalogEntry.DEFAULT_USER_ID) }
            coVerify(exactly = 0) { syncService.backfillAll(any(), "bob") }
        }

        @Test
        fun `only what this pass discovered is offered for a claim`() = runTest {
            stubRefresh(added = listOf("pdf"), registered = listOf(skill("legacy"), skill("pdf")))
            stubIndexing()
            val discovered = slot<List<SkillInfo>>()

            service().refreshFor("alice", project)

            coVerify(exactly = 1) { syncService.backfillAll(capture(discovered), any()) }
            assertEquals(listOf("pdf"), discovered.captured.map { it.name })
        }

        @Test
        fun `nothing new means no catalog traffic at all`() = runTest {
            stubRefresh(added = emptyList(), registered = listOf(skill("legacy")))
            stubIndexing()

            val outcome = service().refreshFor("alice", project)

            assertEquals(0, outcome.claimed)
            coVerify(exactly = 0) { syncService.backfillAll(any(), any()) }
            coVerify(exactly = 0) { indexer.indexOne(any(), any(), any(), await = any()) }
        }

        @Test
        fun `a fresh row is pushed to the index even though drift would call it unchanged`() = runTest {
            stubRefresh(added = listOf("pdf"), registered = listOf(skill("pdf")))
            stubIndexing()
            val awaited = slot<Boolean>()
            val entry = slot<SkillCatalogEntry>()

            val outcome = service().refreshFor("alice", project)

            assertEquals(1, outcome.submitted)
            coVerify(exactly = 1) { indexer.indexOne(capture(entry), SkillScope.GLOBAL, any(), await = capture(awaited)) }
            assertEquals("pdf", entry.captured.name)
            // The plan's hard rule: waiting for the backend is install semantics, and this runs inside a
            // conversation turn while holding the refresh mutex.
            assertFalse(awaited.captured, "a refresh must never wait on the index backend")
        }

        @Test
        fun `reconciliation is limited to the tenant that was just refreshed`() = runTest {
            stubRefresh(added = listOf("pdf"), registered = listOf(skill("pdf")))
            stubIndexing()

            service().refreshFor("alice", project)

            // H1: startup claims every on-disk skill under `system`, so a request-driven pass must
            // reconcile both the caller's tenant and the shared one; otherwise a user editing a
            // GLOBAL skill would leave the system-owned row stale.
            coVerify(exactly = 1) {
                indexer.reconcileByDrift(listOf("alice", SkillCatalogEntry.DEFAULT_USER_ID))
            }
        }

        @Test
        fun `a refresh hands documents over and never waits for the backend`() = runTest {
            stubRefresh(added = listOf("pdf"), registered = listOf(skill("pdf")))
            stubIndexing()

            val outcome = service().refreshFor("alice", project)

            coVerify(exactly = 1) { indexer.indexOne(any(), SkillScope.GLOBAL, any(), await = false) }
            coVerify(exactly = 0) { indexer.indexOne(any(), any(), any(), await = true) }
            assertEquals(1, outcome.submitted, "submitted is all this pass can honestly claim")
        }

        @Test
        fun `a write the backend refuses is not counted as submitted`() = runTest {
            stubRefresh(added = listOf("pdf"), registered = listOf(skill("pdf")))
            stubIndexing()
            coEvery { indexer.indexOne(any(), any(), any(), await = false) } returns false

            val outcome = service().refreshFor("alice", project)

            assertEquals(0, outcome.submitted, "a refused write must not be reported as one that landed")
        }

        @Test
        fun `an index write that throws does not abort the pass`() = runTest {
            stubRefresh(added = listOf("pdf"), registered = listOf(skill("pdf")))
            stubIndexing()
            coEvery { indexer.indexOne(any(), any(), any(), await = false) } throws IllegalStateException("EasyRAG is down")

            val outcome = service().refreshFor("alice", project)

            assertEquals(0, outcome.submitted)
            assertEquals(1, outcome.summary?.owners, "reconciliation still runs for the tenant")
            coVerify(exactly = 1) { promptSource.refreshVisibility() }
        }

        @Test
        fun `a catalog row that vanished mid-pass is not indexed`() = runTest {
            stubRefresh(added = listOf("pdf"), registered = listOf(skill("pdf")))
            stubIndexing()
            coEvery { catalog.listByName("pdf", "alice") } returns emptyList()

            val outcome = service().refreshFor("alice", project)

            assertEquals(0, outcome.submitted)
            coVerify(exactly = 0) { indexer.indexOne(any(), any(), any(), await = any()) }
        }

        @Test
        fun `a failed claim still reports the re-scan result`() = runTest {
            stubRefresh(added = listOf("pdf"), registered = listOf(skill("pdf")))
            stubIndexing()
            coEvery { syncService.backfillAll(any(), any()) } throws IllegalStateException("database is down")

            val outcome = service().refreshFor("alice", project)

            assertEquals(0, outcome.claimed)
            assertEquals(listOf("pdf"), outcome.delta.added)
        }

        @Test
        fun `an unreachable index degrades to a null summary instead of throwing`() = runTest {
            stubRefresh(added = listOf("pdf"), registered = listOf(skill("pdf")))
            stubIndexing()
            coEvery { indexer.reconcileByDrift(any()) } throws IllegalStateException("EasyRAG is down")

            val outcome = service().refreshFor("alice", project)

            assertNull(outcome.summary, "the tool must still be able to say what was registered")
            coVerify(exactly = 1) { promptSource.refreshVisibility() }
        }
    }
}
