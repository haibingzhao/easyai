package com.easy.easyai.skills

import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillOwnerContext
import com.easy.easyai.core.skill.SkillDocumentState
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillIndexerTest {
    @TempDir lateinit var temp: Path

    @Nested
    inner class Recovery {
        @Test
        fun `same observed checksum retries after failed submission`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("draft", owner = "alice")
            chain.remote.failSubmit = true
            val first = chain.refresher.refreshFor("alice")
            val failedRow = chain.catalog.allRows().single()
            assertEquals(1, first.summary?.failed)
            assertEquals(SkillSyncState.PENDING_INDEX, failedRow.syncState)
            assertNull(failedRow.indexedChecksum)
            assertNotNull(failedRow.nextAttemptAt)
            assertNotNull(failedRow.lastError)
            chain.remote.failSubmit = false
            val second = chain.refresher.refreshFor("alice")
            val final = chain.catalog.allRows().single()
            assertEquals(failedRow.checksum, final.checksum)
            assertEquals(final.checksum, final.indexedChecksum)
            assertEquals(1, second.summary?.confirmed)
            assertEquals(2, chain.remote.submitted.size)
        }

        @Test
        fun `asynchronous acceptance stays submitted until the exact checksum is processed`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("draft", owner = "alice")
            chain.remote.asynchronous = true
            val first = chain.refresher.refreshFor("alice")
            val row = chain.catalog.allRows().single()
            assertEquals(1, first.summary?.submitted)
            assertEquals(0, first.summary?.confirmed)
            assertEquals(SkillSyncState.SUBMITTED, row.syncState)
            assertNull(row.indexedChecksum)
            chain.remote.states.keys.single().let { key ->
                chain.remote.states[key] = SkillDocumentState.Processed("wrong-checksum")
                chain.indexer.reconcileByDrift(listOf("alice"))
                assertEquals(SkillSyncState.SUBMITTED, chain.catalog.allRows().single().syncState)
                chain.remote.states[key] = SkillDocumentState.Processed(row.checksum)
                val final = chain.indexer.reconcileByDrift(listOf("alice"))
                assertEquals(1, final.confirmed)
                assertEquals(row.checksum, chain.catalog.allRows().single().indexedChecksum)
            }
        }

        @Test
        fun `due synced rows are inspected and remote loss is repaired without disk drift`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("draft", owner = "alice")
            chain.refresher.refreshFor("alice")
            val row = chain.catalog.allRows().single()
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
            chain.write("draft", owner = "alice")
            chain.refresher.refreshFor("alice")
            chain.remote.failDelete = true
            // Disabling is what takes a document away; a deleted local file would only be restored
            // from the package. The remote must confirm absence before the row may say ABSENT.
            assertIs<SkillToggleResult.Applied>(chain.management.setEnabled("draft", SkillOwnerContext("alice"), false))
            chain.remote.failDelete = true
            val first = chain.refresher.refreshFor("alice")
            assertEquals(0, first.summary?.delisted)
            assertEquals(1, first.summary?.failed)
            assertFalse(chain.catalog.allRows().single().enabled)
            assertEquals(SkillSyncState.PENDING_DELETE, chain.catalog.allRows().single().syncState)
            chain.remote.failDelete = false
            val second = chain.refresher.refreshFor("alice")
            assertEquals(1, second.summary?.delisted)
            assertEquals(SkillSyncState.ABSENT, chain.catalog.allRows().single().syncState)
            assertTrue(chain.remote.documents.isEmpty())
            assertEquals(0, chain.refresher.refreshFor("alice").summary?.delisted)
        }

        @Test
        fun `same mtime content edit updates registry package row and index from identical bytes`() = runTest {
            val chain = SkillSyncFixture(temp)
            val file = chain.write("draft", owner = "alice", body = "old")
            chain.refresher.refreshFor("alice")
            val rowBefore = chain.catalog.allRows().single()
            val stamp = Files.getLastModifiedTime(file)
            file.writeText("---\nname: draft\ndescription: d\nversion: 1.0.0\n---\nnew body")
            Files.setLastModifiedTime(file, stamp)
            // The indexer runs alone here: mtime says nothing moved, so only the content digest can
            // reveal the drift — and the indexer must push the package itself, not wait for a sync.
            val outcome = chain.indexer.reconcileByDrift(listOf("alice"))
            val row = chain.catalog.allRows().single()
            assertEquals(1, outcome.updated)
            assertEquals("new body", chain.registry.get("alice", "draft")?.content)
            assertEquals("new body", chain.remote.submitted.last().content)
            assertEquals(row.checksum, chain.remote.submitted.last().checksum)
            assertEquals(SkillChecksums.dirDigest(file.parent), row.checksum)
            assertEquals(rowBefore.objectKey, row.objectKey)
            assertEquals(SkillSyncState.SYNCED, row.syncState)
        }
    }

    @Nested
    inner class Concurrency {
        @Test
        fun `disable is local immediately and late indexing is compensated`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("draft", owner = "alice")
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            chain.remote.onSubmit = { started.complete(Unit); release.await() }
            val refreshing = async { chain.refresher.refreshFor("alice") }
            started.await()
            val row = chain.catalog.allRows().single()
            val disabling = async { chain.management.setEnabled("draft", SkillOwnerContext("alice"), false) }
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
            chain.write("draft", owner = "alice")
            chain.remote.onSubmit = { throw CancellationException("closing") }
            assertFailsWith<CancellationException> { chain.refresher.refreshFor("alice") }
            assertEquals(SkillSyncState.PENDING_INDEX, chain.catalog.allRows().single().syncState)
        }
    }

    @Nested
    inner class Enablement {
        @Test
        fun `prepareEnable parses and publishes before atomically enabling the exact bytes`() = runTest {
            val chain = SkillSyncFixture(temp)
            val file = chain.write("draft", owner = "alice")
            val snapshot = assertNotNull(chain.sync.snapshotOf(file.parent))
            val row = chain.catalog.claim(
                SkillCatalogEntry(
                    name = "draft", checksum = snapshot.checksum, version = snapshot.version,
                    rootPath = SkillPaths.canonicalize(SkillPaths.ownerRoot(chain.config, "alice")),
                    installPath = SkillPaths.canonicalize(file.parent),
                    objectKey = chain.packages.keyFor("alice", "draft"),
                    userId = "alice", enabled = false
                )
            )

            assertTrue(chain.indexer.prepareEnable(row))
            val enabled = chain.catalog.findById(row.id)!!
            assertTrue(enabled.enabled)
            assertEquals(snapshot.checksum, enabled.checksum)
            assertNotNull(chain.registry.get("alice", "draft"))
        }

        @Test
        fun `prepareEnable rejects a stale revision without publishing`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("draft", owner = "alice")
            chain.refresher.refreshFor("alice")
            val row = chain.catalog.allRows().single()
            chain.catalog.setEnabled(row.id, false)
            val stale = chain.catalog.findById(row.id)!!.copy(revision = row.revision - 1)

            assertFalse(chain.indexer.prepareEnable(stale))
            assertFalse(chain.catalog.findById(row.id)!!.enabled)
        }

        @Test
        fun `prepareEnable repairs frontmatter drift instead of rejecting it`() = runTest {
            val chain = SkillSyncFixture(temp)
            val file = chain.write("draft", owner = "alice")
            chain.refresher.refreshFor("alice")
            val row = chain.catalog.allRows().single()
            chain.catalog.setEnabled(row.id, false)
            val disabled = chain.catalog.findById(row.id)!!
            file.writeText("---\nname: renamed\ndescription: d\n---\nbody")

            assertTrue(chain.indexer.prepareEnable(disabled))

            val enabled = chain.catalog.findById(row.id)!!
            assertTrue(enabled.enabled)
            assertEquals("draft", SkillLoader.parse(file).name, "the directory name is the identity")
        }

        @Test
        fun `prepareEnable rejects a row whose directory carries another name`() = runTest {
            val chain = SkillSyncFixture(temp)
            val dir = chain.write("draft", owner = "alice").parent!!
            chain.refresher.refreshFor("alice")
            val row = chain.catalog.allRows().single()
            val moved = dir.resolveSibling("renamed")
            Files.move(dir, moved)
            chain.catalog.replace(row.copy(installPath = SkillPaths.canonicalize(moved)))
            chain.catalog.setEnabled(row.id, false)

            assertFailsWith<IllegalArgumentException> { chain.indexer.prepareEnable(chain.catalog.findById(row.id)!!) }
            assertFalse(chain.catalog.findById(row.id)!!.enabled)
        }
    }
}
