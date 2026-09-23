package com.easy.easyai.skills

import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillDocumentState
import com.easy.easyai.core.skill.SkillOwnerContext
import com.easy.easyai.core.skill.SkillScope
import com.easy.easyai.core.skill.SkillSyncState
import com.easy.easyai.core.skill.SkillSyncUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillIndexerTest {
    @TempDir lateinit var temp: Path

    @Nested
    inner class Recovery {
        @Test
        fun `same observed checksum retries after failed submission`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("draft")
            chain.remote.failSubmit = true
            val first = chain.refresher.refreshFor("alice", chain.project)
            val failedRow = chain.catalog.listAll().single()
            assertEquals(1, first.summary?.failed)
            assertEquals(SkillSyncState.PENDING_INDEX, failedRow.syncState)
            assertNull(failedRow.indexedChecksum)
            assertNotNull(failedRow.nextAttemptAt)
            assertNotNull(failedRow.lastError)
            chain.remote.failSubmit = false
            val second = chain.refresher.refreshFor("alice", chain.project)
            val final = chain.catalog.listAll().single()
            assertEquals(failedRow.checksum, final.checksum)
            assertEquals(final.checksum, final.indexedChecksum)
            assertEquals(1, second.summary?.confirmed)
            assertEquals(2, chain.remote.submitted.size)
        }

        @Test
        fun `asynchronous acceptance stays submitted through restart until exact checksum is processed`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("draft")
            chain.remote.asynchronous = true
            val first = chain.refresher.refreshFor("alice", chain.project)
            val row = chain.catalog.listAll().single()
            assertEquals(1, first.summary?.submitted)
            assertEquals(0, first.summary?.confirmed)
            assertEquals(SkillSyncState.SUBMITTED, row.syncState)
            assertNull(row.indexedChecksum)
            val indexer = SkillIndexer(chain.remote, chain.catalog, SkillCatalogSyncService(chain.catalog, chain.config, chain.registry), chain.config)
            val address = chain.remote.states.keys.single()
            chain.remote.states[address] = SkillDocumentState.Processed("wrong-checksum")
            indexer.reconcileByDrift(listOf("alice"))
            assertEquals(SkillSyncState.SUBMITTED, chain.catalog.listAll().single().syncState)
            chain.remote.states[address] = SkillDocumentState.Processed(row.checksum)
            val final = indexer.reconcileByDrift(listOf("alice"))
            assertEquals(1, final.confirmed)
            assertEquals(row.checksum, chain.catalog.listAll().single().indexedChecksum)
        }

        @Test
        fun `due synced rows are inspected and remote loss is repaired without disk drift`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("draft")
            chain.refresher.refreshFor("alice", chain.project)
            val row = chain.catalog.listAll().single()
            chain.catalog.updateSync(row.id, row.revision, SkillSyncUpdate(SkillSyncState.SYNCED, row.checksum, 0L))
            chain.remote.states.clear()
            chain.remote.documents.clear()
            val result = chain.indexer.reconcilePending(limit = 1)
            assertEquals(1, result.rows)
            assertEquals(1, result.submitted)
            assertEquals(1, result.confirmed)
        }

        @Test
        fun `delete failures remain pending and only confirmed absence counts`() = runTest {
            val chain = SkillSyncFixture(temp)
            val file = chain.write("draft")
            chain.refresher.refreshFor("alice", chain.project)
            Files.delete(file)
            chain.remote.failDelete = true
            val first = chain.refresher.refreshFor("alice", chain.project)
            assertEquals(0, first.summary?.delisted)
            assertEquals(1, first.summary?.failed)
            assertFalse(chain.catalog.listAll().single().enabled)
            assertEquals(SkillSyncState.PENDING_DELETE, chain.catalog.listAll().single().syncState)
            chain.remote.failDelete = false
            val second = chain.refresher.refreshFor("alice", chain.project)
            assertEquals(1, second.summary?.delisted)
            assertEquals(SkillSyncState.ABSENT, chain.catalog.listAll().single().syncState)
            assertTrue(chain.remote.documents.isEmpty())
            assertEquals(0, chain.refresher.refreshFor("alice", chain.project).summary?.delisted)
        }

        @Test
        fun `same mtime content edit updates registry and indexed checksum from identical bytes`() = runTest {
            val chain = SkillSyncFixture(temp)
            val file = chain.write("draft", "old")
            chain.refresher.refreshFor("alice", chain.project)
            val stamp = Files.getLastModifiedTime(file)
            file.writeText("---\nname: draft\ndescription: d\n---\nnew body")
            Files.setLastModifiedTime(file, stamp)
            val outcome = chain.refresher.refreshFor("alice", chain.project)
            val row = chain.catalog.listAll().single()
            assertEquals(1, outcome.summary?.updated)
            assertEquals("new body", chain.registry.get("draft", chain.project)?.content)
            assertEquals("new body", chain.remote.submitted.last().content)
            assertEquals(row.checksum, chain.remote.submitted.last().checksum)
            assertEquals(SkillChecksums.sha256Hex(Files.readAllBytes(file)), row.checksum)
        }
    }

    @Nested
    inner class Concurrency {
        @Test
        fun `disable is local immediately and late indexing is compensated`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("draft")
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            chain.remote.onSubmit = { started.complete(Unit); release.await() }
            val refreshing = async { chain.refresher.refreshFor("alice", chain.project) }
            started.await()
            val row = chain.catalog.listAll().single()
            val disabling = async { chain.management.setEnabled("draft", SkillOwnerContext("alice", chain.project), false) }
            runCurrent()
            assertFalse(chain.catalog.findById(row.id)!!.enabled)
            release.complete(Unit)
            refreshing.await()
            disabling.await()
            val final = chain.catalog.findById(row.id)!!
            assertFalse(final.enabled)
            assertEquals("alice", final.userId)
            assertEquals(SkillSyncState.ABSENT, final.syncState)
            assertTrue(chain.remote.documents.isEmpty())
        }

        @Test
        fun `cancellation is not converted into a successful or failed synchronization`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("draft")
            chain.remote.onSubmit = { throw CancellationException("closing") }
            assertFailsWith<CancellationException> { chain.refresher.refreshFor("alice", chain.project) }
            assertEquals(SkillSyncState.PENDING_INDEX, chain.catalog.listAll().single().syncState)
        }
    }

    @Nested
    inner class CurrentIdentity {
        @Test
        fun `project source with global identity only cleans its persisted slice and is never moved`() = runTest {
            val chain = SkillSyncFixture(temp)
            val file = chain.write("draft")
            val snapshot = chain.sync.snapshotOf(file.parent)!!
            val invalid = chain.catalog.claim(SkillCatalogEntry(name = "draft", checksum = "unchanged",
                installPath = file.parent.toString(), userId = "alice"))
            val targetRow = chain.catalog.claim(SkillCatalogEntry(name = "draft", checksum = "reserved",
                installPath = file.parent.resolveSibling("reserved").toString(), userId = "alice", enabled = false,
                projectHash = SkillScopeResolver.projectHashOf(chain.project),
                indexProjectPath = SkillPaths.canonicalize(chain.project)))
            val oldAddress = chain.remote.address("draft", SkillScope.GLOBAL, SkillOwnerContext("alice"))
            val target = chain.remote.address("draft", SkillScope.PROJECT, SkillOwnerContext("alice", chain.project))
            val reserved = SkillDocumentState.Processed("reserved")
            chain.remote.states[oldAddress] = SkillDocumentState.Processed(snapshot.checksum)
            chain.remote.states[target] = reserved

            val result = chain.indexer.synchronize(invalid)
            val row = chain.catalog.findById(invalid.id)!!
            assertEquals(1, result.failed)
            assertEquals("", row.projectHash)
            assertNull(row.indexProjectPath)
            assertEquals(invalid.checksum, row.checksum)
            assertEquals(targetRow, chain.catalog.findById(targetRow.id))
            assertEquals(listOf(oldAddress), chain.remote.deleted)
            assertEquals(reserved, chain.remote.states[target])
            assertTrue(chain.remote.submitted.isEmpty())
            assertTrue(chain.registry.all().isEmpty(), "invalid identity must not publish even local content")
            assertNotNull(row.nextAttemptAt)
            assertNotNull(row.lastError)
        }

        @Test
        fun `shared source with project identity never writes into the global slice`() = runTest {
            val chain = SkillSyncFixture(temp)
            val file = chain.write("draft", global = true)
            val invalid = chain.catalog.claim(SkillCatalogEntry(name = "draft", checksum = "unchanged",
                installPath = file.parent.toString(), userId = "alice",
                projectHash = SkillScopeResolver.projectHashOf(chain.project),
                indexProjectPath = SkillPaths.canonicalize(chain.project)))
            val oldAddress = chain.remote.address("draft", SkillScope.PROJECT, SkillOwnerContext("alice", chain.project))
            val target = chain.remote.address("draft", SkillScope.GLOBAL, SkillOwnerContext("alice"))
            val reserved = SkillDocumentState.Processed("reserved")
            chain.remote.states[oldAddress] = SkillDocumentState.Processed("old")
            chain.remote.states[target] = reserved

            assertEquals(1, chain.indexer.synchronize(invalid).failed)
            assertEquals(listOf(oldAddress), chain.remote.deleted)
            assertEquals(reserved, chain.remote.states[target])
            assertTrue(chain.remote.submitted.isEmpty())
            assertEquals(invalid.projectHash, chain.catalog.findById(invalid.id)?.projectHash)
        }

        @Test
        fun `missing project address is rejected rather than filled from the install path`() = runTest {
            val chain = SkillSyncFixture(temp)
            val file = chain.write("draft")
            val invalid = chain.catalog.claim(SkillCatalogEntry(name = "draft", checksum = "unchanged",
                installPath = file.parent.toString(), userId = "alice",
                projectHash = SkillScopeResolver.projectHashOf(chain.project)))

            assertEquals(1, chain.indexer.synchronize(invalid).failed)
            assertNull(chain.catalog.findById(invalid.id)?.indexProjectPath)
            assertTrue(chain.remote.submitted.isEmpty())
            assertTrue(chain.remote.deleted.isEmpty(), "a hash is not a recoverable slice address")
        }

        @Test
        fun `mismatched stored project address cannot be used even for cleanup`() = runTest {
            val chain = SkillSyncFixture(temp)
            val file = chain.write("draft")
            val invalid = chain.catalog.claim(SkillCatalogEntry(name = "draft", checksum = "unchanged",
                installPath = file.parent.toString(), userId = "alice",
                projectHash = SkillScopeResolver.projectHashOf(chain.project),
                indexProjectPath = temp.resolve("other").toString()))

            assertEquals(1, chain.indexer.synchronize(invalid).failed)
            assertTrue(chain.remote.submitted.isEmpty())
            assertTrue(chain.remote.deleted.isEmpty())
            assertEquals(invalid.indexProjectPath, chain.catalog.findById(invalid.id)?.indexProjectPath)
        }

        @Test
        fun `unknown source after configuration change cleans only the verifiable persisted address`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("draft")
            chain.refresher.refreshFor("alice", chain.project)
            val row = chain.catalog.listAll().single()
            val address = chain.remote.states.keys.single()
            val changedConfig = chain.config.copy(homeSkillDirs = emptyList(), paths = emptyList())
            val sync = SkillCatalogSyncService(chain.catalog, changedConfig, chain.registry)
            val indexer = SkillIndexer(chain.remote, chain.catalog, sync, changedConfig)
            chain.remote.submitted.clear()
            chain.remote.failDelete = true
            assertEquals(1, indexer.synchronize(row).failed)
            assertTrue(address in chain.remote.states)
            chain.remote.failDelete = false

            assertEquals(1, indexer.synchronize(row).failed)
            assertEquals(listOf(address), chain.remote.deleted)
            assertTrue(chain.remote.submitted.isEmpty())
            assertEquals(row.projectHash, chain.catalog.findById(row.id)?.projectHash)
            assertEquals(row.indexProjectPath, chain.catalog.findById(row.id)?.indexProjectPath)
        }

        @Test
        fun `prepareEnable rejects inconsistent identity without publishing or changing the row`() = runTest {
            val chain = SkillSyncFixture(temp)
            val file = chain.write("draft")
            val invalid = chain.catalog.claim(SkillCatalogEntry(name = "draft", checksum = "unchanged",
                installPath = file.parent.toString(), userId = "alice", enabled = false))

            assertFailsWith<IllegalArgumentException> { chain.indexer.prepareEnable(invalid) }
            assertEquals(invalid, chain.catalog.findById(invalid.id))
            assertTrue(chain.registry.all().isEmpty())
            assertTrue(chain.remote.submitted.isEmpty())
            assertTrue(chain.remote.deleted.isEmpty())
        }
    }
}
