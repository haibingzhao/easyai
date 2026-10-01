package com.easy.easyai.skills

import com.easy.easyai.core.skill.SkillOwnerContext
import com.easy.easyai.core.skill.SkillSyncState
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillCatalogServiceTest {
    @TempDir lateinit var temp: Path

    @Nested
    inner class Enablement {
        @Test
        fun `enabling publishes disabled edits before submitting identical content`() = runTest {
            val chain = SkillSyncFixture(temp)
            val file = chain.write("draft", owner = "alice", body = "old body")
            chain.refresher.refreshFor("alice")
            val owner = SkillOwnerContext("alice")
            chain.management.setEnabled("draft", owner, false)
            val stamp = Files.getLastModifiedTime(file)
            file.writeText("---\nname: draft\ndescription: new description\nversion: 2.0.0\n---\nnew body")
            Files.setLastModifiedTime(file, stamp)
            chain.remote.onSubmit = {
                assertEquals("new body", chain.registry.get("alice", "draft")?.content)
                assertEquals("new description", chain.registry.get("alice", "draft")?.description)
            }
            val result = chain.management.setEnabled("draft", owner, true)
            assertIs<SkillToggleResult.Applied>(result)
            assertTrue(result.enabled)
            assertTrue(result.indexSynced)
            val row = chain.catalog.allRows().single()
            assertEquals("2.0.0", row.version)
            assertEquals(SkillChecksums.dirDigest(file.parent), row.checksum)
            assertEquals(row.checksum, row.indexedChecksum)
            assertEquals("new body", chain.remote.submitted.last().content)
        }

        @Test
        fun `missing or invalid source cannot be enabled`() = runTest {
            val chain = SkillSyncFixture(temp)
            val file = chain.write("draft", owner = "alice")
            chain.refresher.refreshFor("alice")
            val owner = SkillOwnerContext("alice")
            chain.management.setEnabled("draft", owner, false)
            file.writeText("---\ndescription: no name\n---\nbroken")
            assertIs<SkillToggleResult.Rejected>(chain.management.setEnabled("draft", owner, true))
            Files.delete(file)
            assertIs<SkillToggleResult.Rejected>(chain.management.setEnabled("draft", owner, true))
            assertFalse(chain.catalog.allRows().single().enabled)
        }

        @Test
        fun `disable without rag is immediate but does not claim remote absence`() = runTest {
            val chain = SkillSyncFixture(temp, withRemote = false)
            chain.write("draft", owner = "alice")
            chain.refresher.refreshFor("alice")
            val result = chain.management.setEnabled("draft", SkillOwnerContext("alice"), false)
            assertIs<SkillToggleResult.Applied>(result)
            assertFalse(result.indexSynced)
            val row = chain.catalog.allRows().single()
            assertFalse(row.enabled)
            assertEquals(SkillSyncState.PENDING_DELETE, row.syncState)
        }

        @Test
        fun `async enable does not report synced on acceptance`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("draft", owner = "alice")
            chain.refresher.refreshFor("alice")
            val owner = SkillOwnerContext("alice")
            chain.management.setEnabled("draft", owner, false)
            chain.remote.asynchronous = true
            val result = chain.management.setEnabled("draft", owner, true)
            assertIs<SkillToggleResult.Applied>(result)
            assertFalse(result.indexSynced)
            assertEquals(SkillSyncState.SUBMITTED, chain.catalog.allRows().single().syncState)
        }

        @Test
        fun `regular users cannot toggle shared rows but the system owner can`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("shared", owner = SkillModelFixture.SYSTEM)
            chain.refresher.refreshFor("system")

            val rejected = chain.management.setEnabled("shared", SkillOwnerContext("alice"), false)
            assertIs<SkillToggleResult.Rejected>(rejected)
            assertEquals("Shared skills are read-only", rejected.reason)
            // Admins act as the system owner explicitly; anonymous requests collapse to it too.
            assertIs<SkillToggleResult.Applied>(chain.management.setEnabled("shared", SkillOwnerContext("system"), false))
            assertTrue(chain.catalog.allRows().none { it.enabled })
        }

        @Test
        fun `other users cannot toggle a private row`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("private", owner = "alice")
            chain.refresher.refreshFor("alice")

            assertIs<SkillToggleResult.Rejected>(chain.management.setEnabled("private", SkillOwnerContext("bob"), false))
            assertTrue(chain.catalog.allRows().all { it.enabled })
        }
    }

    @Nested
    inner class Views {
        @Test
        fun `management view reports disk presence and never another owners rows`() = runTest {
            val chain = SkillSyncFixture(temp)
            val file = chain.write("draft", owner = "alice")
            chain.refresher.refreshFor("alice")
            val owner = SkillOwnerContext("alice")
            val view = chain.management.find("draft", owner)!!
            assertTrue(view.installedOnDisk)
            assertFalse(view.shared)
            assertEquals("alice", view.entry.userId)
            assertNull(chain.management.find("draft", SkillOwnerContext("bob")))
            Files.delete(file)
            assertFalse(chain.management.list(owner).single().installedOnDisk)
        }

        @Test
        fun `own rows shadow shared names and mark shared rows in the list`() = runTest {
            val chain = SkillSyncFixture(temp)
            chain.write("pdf", owner = SkillModelFixture.SYSTEM)
            chain.write("pdf", owner = "alice")
            chain.write("notes", owner = "alice")
            chain.write("template", owner = SkillModelFixture.SYSTEM)
            chain.refresher.refreshFor("alice")

            val views = chain.management.list(SkillOwnerContext("alice"))
            assertEquals(listOf("notes", "pdf", "template"), views.map { it.entry.name }.sorted())
            assertEquals(listOf("template"), views.filter { it.shared }.map { it.entry.name })
            assertEquals(listOf("notes", "pdf"), views.filter { !it.shared }.map { it.entry.name }.sorted())
            // The shared pdf is shadowed by alice's own row of the same name.
            assertNull(views.singleOrNull { it.entry.name == "pdf" && it.shared })
        }
    }
}
